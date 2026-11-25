#!/usr/bin/env bash
# 示例：服务器端发布脚本，由 GitHub Actions 调用；也可手动运行。主机与密钥一律来自环境或仓库 Secrets，不要把地址写进仓库。
#   部署:  bash /opt/marketing-platform/deploy.sh deploy <commit-sha>
#   回滚:  bash /opt/marketing-platform/deploy.sh rollback
# 只替换 app.jar 和前端 dist，补跑未执行过的 db/V*.sql；不动 .env、MySQL/Redis 数据卷和 backups。
# 若已运行过 nginx/enable-https.sh，顺带刷新 HTTPS 的 nginx 配置；证书和 .env 仍不改。
# 本版本额外在迁移后预热 offer:* Redis 键，并在新版本健康后删除旧 coupon:*；详见 deploy/redis-cutover.sh。
set -euo pipefail
MODE=${1:-deploy}
SHA=${2:-manual}
BASE=/opt/marketing-platform
IN=$BASE/releases/incoming
PREV=$BASE/releases/previous
cd "$BASE"
# HTTPS 启用后，compose 还必须带上 docker-compose.https.yml，否则重建容器会丢掉 443 和证书挂载。
run_compose() {
  if [ -f "$BASE/docker-compose.https.yml" ]; then
    command docker compose -f docker-compose.yml -f docker-compose.https.yml "$@"
  else
    command docker compose "$@"
  fi
}
exec 9>/tmp/marketing-deploy.lock
flock -n 9 || { echo "另一个部署正在进行"; exit 1; }
set -a; . ./.env; set +a
mysqlq() { run_compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql mysql -uroot --default-character-set=utf8mb4 marketing "$@"; }

wait_healthy() {
  local id status
  for _ in $(seq 1 60); do
    id=$(run_compose ps -q app)
    status=$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || echo unknown)
    [ "$status" = healthy ] && return 0
    [ "$status" = unhealthy ] && return 1
    sleep 5
  done
  return 1
}

restart_app() {
  run_compose build app
  run_compose up -d --no-deps app
}

activate() { # $1 = 发布目录（含 app.jar 和 dist/）
  cp "$1/app.jar" app/app.jar.new && mv app/app.jar.new app/app.jar
  # dist 是 nginx 的绑定挂载目录，只能原地同步内容，不能整目录替换
  rsync -a --delete "$1/dist/" dist/
  restart_app
}

if [ "$MODE" = rollback ]; then
  test -s "$PREV/app.jar" || { echo "没有可回滚的上一版本"; exit 1; }
  echo "回滚到上一版本: $(cat "$PREV/sha" 2>/dev/null || echo unknown)"
  activate "$PREV"
  wait_healthy || { echo "回滚后应用仍不健康"; run_compose logs --tail 80 app; exit 1; }
  run_compose exec -T nginx nginx -s reload
  cp "$PREV/sha" .deployed-sha 2>/dev/null || true
  echo "回滚完成"; exit 0
fi

test -s "$IN/app.jar" && test -f "$IN/dist/index.html" || { echo "发布包不完整"; exit 1; }

# 1. 保存当前版本，供回滚
rm -rf "$PREV.tmp"; mkdir -p "$PREV.tmp"
cp -a app/app.jar "$PREV.tmp/app.jar"
cp -a dist "$PREV.tmp/dist"
cat .deployed-sha > "$PREV.tmp/sha" 2>/dev/null || echo manual-initial > "$PREV.tmp/sha"
rm -rf "$PREV"; mv "$PREV.tmp" "$PREV"

# 2. 数据库迁移：记录在 deploy_schema_history；首次运行时把服务器初始化时已执行的脚本记为基线
mysqlq -e "CREATE TABLE IF NOT EXISTS deploy_schema_history (version VARCHAR(128) NOT NULL PRIMARY KEY, applied_at DATETIME NOT NULL, release_sha VARCHAR(64) NULL)"
if [ "$(mysqlq -N -e 'SELECT COUNT(*) FROM deploy_schema_history')" = 0 ]; then
  for f in db/V*.sql; do
    mysqlq -e "INSERT INTO deploy_schema_history VALUES ('$(basename "$f")', NOW(), 'baseline')"
  done
fi
applied=$(mysqlq -N -e 'SELECT version FROM deploy_schema_history')
pending=()
for f in $(ls "$IN"/db/V*.sql | sort); do
  grep -qxF "$(basename "$f")" <<<"$applied" || pending+=("$f")
done
if [ ${#pending[@]} -gt 0 ]; then
  mkdir -p backups && chmod 700 backups
  bk="backups/pre-deploy-$(date +%Y%m%d-%H%M%S)-${SHA:0:7}.sql"
  run_compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql mysqldump -uroot --single-transaction --routines --triggers --default-character-set=utf8mb4 marketing > "$bk"
  chmod 600 "$bk"; echo "迁移前已备份数据库: $bk"
  for f in "${pending[@]}"; do
    echo "执行迁移 $(basename "$f")"
    mysqlq < "$f"
    mysqlq -e "INSERT INTO deploy_schema_history VALUES ('$(basename "$f")', NOW(), '$SHA')"
  done
fi
cp "$IN"/db/V*.sql db/
if [ -f "$IN/redis-cutover.sh" ]; then
  mkdir -p "$BASE/deploy"
  cp "$IN/redis-cutover.sh" "$BASE/deploy/redis-cutover.sh"
  chmod +x "$BASE/deploy/redis-cutover.sh"
fi

# 2b. Redis 预热：写 offer:* 库存与限领。必须在切换 jar 之前，避免新版本按空计数放行超领。
# 失败则不切换应用。若本次已跑完 V012+，旧应用可能已无法访问新表结构，需要尽快修好后重跑预热并完成发布。
if [ -x "$BASE/deploy/redis-cutover.sh" ]; then
  echo "执行 Redis 键切换预热"
  RELEASE_SHA="$SHA" bash "$BASE/deploy/redis-cutover.sh" --base "$BASE" --warm || {
    echo "Redis 预热失败，中止发布（应用尚未切换）"
    echo "若本次已执行 V012+ 迁移，请排查后手动执行: bash $BASE/deploy/redis-cutover.sh --warm"
    echo "再重新发布以切换应用。"
    exit 1
  }
fi

# 3. 切换版本并等待健康；失败自动回滚（回滚 jar，不会回滚已执行的迁移或 Redis 预热）
activate "$IN"
if ! wait_healthy; then
  echo "新版本健康检查失败，自动回滚"; run_compose logs --tail 120 app || true
  activate "$PREV"; wait_healthy || true
  exit 1
fi
run_compose exec -T nginx nginx -s reload
echo "$SHA" > .deployed-sha
cp "$IN/remote-deploy.sh" "$BASE/deploy.sh" 2>/dev/null || true

# 3b. 新版本已健康后再删旧 coupon:*；失败不回滚（新应用不再读取这些键）
if [ -x "$BASE/deploy/redis-cutover.sh" ]; then
  echo "清理旧 coupon:* 键"
  RELEASE_SHA="$SHA" bash "$BASE/deploy/redis-cutover.sh" --base "$BASE" --delete-old || {
    echo "警告：未能删除旧 coupon:* 键，新版本已上线。请稍后手动执行: bash $BASE/deploy/redis-cutover.sh --delete-old"
  }
fi

# 4. 清理：只删悬空镜像和较早的迁移备份
docker image prune -f >/dev/null || true
ls -1t backups/pre-deploy-*.sql 2>/dev/null | tail -n +6 | xargs -r rm -f

# 5. 同步 HTTPS 工具。未执行 enable-https.sh 之前只拷文件，不改线上 nginx。
if [ -d "$IN/nginx" ]; then
  mkdir -p "$BASE/nginx"
  for f in render_config.py enable-https.sh renew-cert.sh cert-perms.sh \
    sayohno.bootstrap.conf.template sayohno.https.conf.template server-body.fallback.inc test_render.py; do
    if [ -f "$IN/nginx/$f" ]; then
      cp "$IN/nginx/$f" "$BASE/nginx/$f"
    fi
  done
  chmod 755 "$BASE/nginx/enable-https.sh" "$BASE/nginx/renew-cert.sh" "$BASE/nginx/cert-perms.sh" 2>/dev/null || true
  if [ -f "$BASE/nginx/https.enabled" ] && [ -f "$BASE/nginx/server-body.inc" ] && [ -f "$BASE/nginx/install.env" ] && command -v python3 >/dev/null; then
    # shellcheck disable=SC1091
    . "$BASE/nginx/install.env"
    host_config=${HOST_CONFIG:-}
    if [ -n "$host_config" ] && [ -f "$host_config" ]; then
      cp -a "$host_config" "$host_config.bak"
      if python3 "$BASE/nginx/render_config.py" --mode https \
        --server-body "$BASE/nginx/server-body.inc" \
        --upstream-env "$BASE/nginx/upstream.env" \
        --out "$host_config" \
        && run_compose exec -T nginx nginx -t; then
        run_compose exec -T nginx nginx -s reload
        rm -f "$host_config.bak"
        echo "已刷新 HTTPS nginx 配置"
      else
        mv "$host_config.bak" "$host_config"
        run_compose exec -T nginx nginx -s reload || true
        echo "警告：新的 nginx 配置未生效，已恢复上一份配置。应用版本已切换。"
      fi
    fi
  fi
fi
echo "部署完成: $SHA"
