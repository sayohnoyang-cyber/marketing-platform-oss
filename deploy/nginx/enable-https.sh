#!/usr/bin/env bash
# 在生产服务器上一次性启用 https://sayohno.cn 。
# 不读取、不打印 .env。合并到 main 只会把脚本拷到服务器，不会自动执行本脚本。
#
#   bash /opt/marketing-platform/nginx/enable-https.sh you@example.com
#
# 运行前：sayohno.cn 与 www.sayohno.cn 的 A 记录指向本机，安全组放行 TCP 80 和 443。
# Compose 文件自动识别 compose.yaml / docker-compose.yml。docker-compose.yml 若是指向 compose.yaml 的符号链接，使用真实文件。
# 证书镜像 certbot/certbot:v2.11.0 已在本机时不再拉取。没有镜像且 Docker Hub 超时时，改拉
# docker.m.daocloud.io/certbot/certbot:v2.11.0 并打成同样的标签。可用 CERTBOT_IMAGE / CERTBOT_IMAGE_MIRROR 覆盖。
set -euo pipefail

BASE=${MARKETING_BASE:-/opt/marketing-platform}
EMAIL=${1:-${CERTBOT_EMAIL:-}}
KIT=$(cd "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
. "$KIT/compose-project.sh"

if [ "$(id -u)" -ne 0 ]; then
  echo "请用 root 运行：bash $KIT/enable-https.sh <邮箱>"
  exit 1
fi
if [ -z "$EMAIL" ] || ! [[ "$EMAIL" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]]; then
  echo "用法: bash $KIT/enable-https.sh <Let's Encrypt 通知邮箱>"
  exit 1
fi
command -v docker >/dev/null
command -v python3 >/dev/null
COMPOSE_FILE=$(resolve_compose_file "$BASE") || { echo "找不到 $BASE/compose.yaml 或 docker-compose.yml"; exit 1; }
echo "使用 Compose 文件 $COMPOSE_FILE"
cd "$BASE"
exec 9>/tmp/marketing-deploy.lock
flock -n 9 || { echo "另一个部署正在进行，请稍后再运行"; exit 1; }
kv() { awk -F= -v k="$1" '$1==k { print substr($0, index($0, "=") + 1); exit }' "$2"; }
http_home() { curl -fsS --max-time 10 http://127.0.0.1/ | grep -q '<div id="app"'; }
http_api() { curl -fsS --max-time 10 http://127.0.0.1/api/health | grep -q '"status":"UP"'; }
http_nocache() { curl -fsSI --max-time 10 http://127.0.0.1/ | grep -qi '^Cache-Control: no-cache'; }
redirect_apex() { curl -fsSI --max-time 10 -H 'Host: sayohno.cn' http://127.0.0.1/ | tr -d '\r' | grep -qi '^Location: https://sayohno.cn/'; }
redirect_www() { curl -fsSI --max-time 10 -H 'Host: www.sayohno.cn' http://127.0.0.1/ | tr -d '\r' | grep -qi '^Location: https://sayohno.cn/'; }
https_home() { curl -fsS --max-time 10 --resolve sayohno.cn:443:127.0.0.1 https://sayohno.cn/ | grep -q '<div id="app"'; }
https_api() { curl -fsS --max-time 10 --resolve sayohno.cn:443:127.0.0.1 https://sayohno.cn/api/health | grep -q '"status":"UP"'; }
https_www() { curl -fsSI --max-time 10 --resolve www.sayohno.cn:443:127.0.0.1 https://www.sayohno.cn/ | tr -d '\r' | grep -qi '^Location: https://sayohno.cn/'; }
asset_immutable() { curl -fsSI --max-time 10 "http://127.0.0.1$asset" | grep -qi 'public, immutable'; }
asset_max_age() { curl -fsSI --max-time 10 "http://127.0.0.1$asset" | grep -qi 'max-age=2592000'; }
asset_missing() { [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1/assets/missing-not-real.js)" = "404" ]; }
short_proxied() {
  local body
  body=$(curl -sS --max-time 10 http://127.0.0.1/s/not-a-real-link || true)
  [ -n "$body" ] && ! printf '%s' "$body" | grep -q '<div id="app"'
}

resolve_v4() { getent ahostsv4 "$1" 2>/dev/null | awk '{ print $1; exit }'; }
apex=$(resolve_v4 sayohno.cn || true)
www=$(resolve_v4 www.sayohno.cn || true)
expected=$(curl -fsS --max-time 3 http://100.100.100.200/latest/meta-data/eipv4 || true)
if [ -z "$expected" ]; then
  expected=47.105.124.241
fi
if [ -z "$apex" ] || [ "$apex" != "$www" ] || [ "$apex" != "$expected" ]; then
  echo "DNS 还未指向这台机器。sayohno.cn=${apex:-无} www.sayohno.cn=${www:-无} 期望=$expected"
  echo "请把 @ 和 www 的 A 记录都设为 $expected，等解析生效后再运行。"
  exit 1
fi

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow 443/tcp || true
fi
if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
  firewall-cmd --permanent --add-port=443/tcp || true
  firewall-cmd --reload || true
fi

mkdir -p "$BASE/nginx/conf.d" "$BASE/nginx/backup" "$BASE/certbot/www/.well-known/acme-challenge" "$BASE/certbot/conf"
chmod 755 "$BASE/nginx" "$BASE/certbot" "$BASE/certbot/www" "$BASE/certbot/conf"

echo "读取当前 nginx 配置"
run_compose exec -T nginx nginx -T > "$BASE/nginx/backup/nginx-T.latest.txt"
python3 "$KIT/render_config.py" \
  --mode bootstrap \
  --nginx-t "$BASE/nginx/backup/nginx-T.latest.txt" \
  --out "$BASE/nginx/backup/rendered-bootstrap.conf" \
  --write-upstream "$BASE/nginx/upstream.env" \
  --write-server-body "$BASE/nginx/server-body.inc"
CONFIG_FILE=$(kv CONFIG_FILE "$BASE/nginx/upstream.env")
[ -n "$CONFIG_FILE" ] || { echo "没有解析出 nginx 配置路径"; exit 1; }

tmp_json=$(mktemp)
chmod 600 "$tmp_json"
trap 'rm -f "$tmp_json"' EXIT
docker compose -f "$COMPOSE_FILE" config --format json > "$tmp_json"
python3 "$KIT/render_config.py" --plan-override \
  --compose-json "$tmp_json" \
  --base "$BASE" \
  --config-file "$CONFIG_FILE" \
  --override-out "$BASE/docker-compose.https.yml" \
  --install-env "$BASE/nginx/install.env"
rm -f "$tmp_json"
trap - EXIT

HOST_CONFIG=$(kv HOST_CONFIG "$BASE/nginx/install.env")
[ -n "$HOST_CONFIG" ] || { echo "没有规划出配置文件路径"; exit 1; }
mkdir -p "$(dirname "$HOST_CONFIG")"
if [ -f "$HOST_CONFIG" ]; then
  cp -a "$HOST_CONFIG" "$BASE/nginx/backup/default.conf.before-https"
fi

render_mode() {
  python3 "$KIT/render_config.py" \
    --mode "$1" \
    --server-body "$BASE/nginx/server-body.inc" \
    --upstream-env "$BASE/nginx/upstream.env" \
    --out "$HOST_CONFIG"
}

rollback_nginx() {
  echo "回滚 nginx"
  if [ -f "$BASE/nginx/backup/default.conf.before-https" ]; then
    cp -a "$BASE/nginx/backup/default.conf.before-https" "$HOST_CONFIG"
  fi
  if [ ! -f "$BASE/nginx/https.enabled" ]; then
    rm -f "$BASE/docker-compose.https.yml"
    command docker compose -f "$COMPOSE_FILE" up -d --no-deps nginx || true
  else
    run_compose up -d --no-deps nginx || true
  fi
}

certs_ready=0
if [ -s "$BASE/certbot/conf/live/sayohno.cn/fullchain.pem" ] && [ -s "$BASE/certbot/conf/live/sayohno.cn/privkey.pem" ]; then
  certs_ready=1
fi
if [ "$certs_ready" = 1 ]; then
  render_mode https
else
  render_mode bootstrap
fi

echo "重新加载 nginx 容器"
if ! run_compose up -d --no-deps nginx; then
  rollback_nginx
  exit 1
fi

cid=""
ready=0
for _ in $(seq 1 30); do
  cid=$(run_compose ps -q nginx || true)
  if [ -n "$cid" ] && docker exec "$cid" nginx -t >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" != 1 ]; then
  echo "nginx 配置检查失败"
  run_compose logs --tail 80 nginx || true
  rollback_nginx
  exit 1
fi
docker exec "$cid" nginx -s reload

# reload 后旧 worker 还会再应答一小段时间，这里重试而不是立刻判失败。
if ! retry_check 12 http_home || ! retry_check 12 http_api; then
  echo "HTTP 站点或 /api/health 没有恢复"
  rollback_nginx
  exit 1
fi

# shellcheck disable=SC1091
. "$KIT/cert-perms.sh"
install_helper() {
  local name=$1
  if [ "$KIT/$name" != "$BASE/nginx/$name" ]; then
    cp "$KIT/$name" "$BASE/nginx/$name"
  fi
  chmod 755 "$BASE/nginx/$name"
}
install_helper cert-perms.sh
install_helper compose-project.sh
install_helper renew-cert.sh
install_helper enable-https.sh

if [ "$certs_ready" = 0 ]; then
  printf 'ok' > "$BASE/certbot/www/.well-known/acme-challenge/ping"
  chmod -R a+rX "$BASE/certbot/www"
  probe=$(curl -fsS --max-time 10 -H 'Host: sayohno.cn' http://127.0.0.1/.well-known/acme-challenge/ping || true)
  rm -f "$BASE/certbot/www/.well-known/acme-challenge/ping"
  if [ "$probe" != "ok" ]; then
    echo "ACME 目录没有从 /var/www/certbot 提供（得到: ${probe:-空}）。站点仍是 HTTP。请检查 nginx 挂载后重跑。"
    exit 1
  fi
  echo "申请 Let's Encrypt 证书（sayohno.cn, www.sayohno.cn）"
  if ! run_certbot --name sayohno-certbot \
    -v "$BASE/certbot/www:/var/www/certbot" \
    -v "$BASE/certbot/conf:/etc/letsencrypt" \
    certonly --webroot -w /var/www/certbot \
    --cert-name sayohno.cn \
    -d sayohno.cn -d www.sayohno.cn \
    --email "$EMAIL" --agree-tos --no-eff-email --non-interactive --keep-until-expiring; then
    echo "证书申请失败。站点仍通过 HTTP 提供。看完 certbot 的报错后可以重新运行本脚本。"
    exit 1
  fi
  gid=$(docker exec "$cid" id -g nginx | tr -d '\r')
  fix_cert_perms "$BASE/certbot/conf" "$gid"
  render_mode https
  if ! docker exec "$cid" nginx -t; then
    echo "HTTPS 配置未通过检查，恢复为仅 HTTP"
    render_mode bootstrap
    docker exec "$cid" nginx -s reload || true
    exit 1
  fi
  docker exec "$cid" nginx -s reload
fi

fail=0
note() { echo "通过: $1"; }
bad() { echo "检查失败: $1"; fail=1; }
check() {
  if retry_check 12 "$1"; then note "$2"; else bad "$2"; fi
}

check http_home "HTTP IP 仍打开首页"
check http_api "HTTP IP 的 /api/health"
check http_nocache "index.html 仍是 no-cache"
check redirect_apex "http://sayohno.cn 跳到 https"
check redirect_www "http://www 跳到 apex"
check https_home "https://sayohno.cn"
check https_api "https /api/health"
check https_www "https://www 跳到 apex"
asset=""
for _ in $(seq 1 12); do
  asset=$(curl -fsS --max-time 10 http://127.0.0.1/ | grep -oE '/assets/[^" ]+' | head -1 || true)
  [ -n "$asset" ] && break
  sleep 1
done
if [ -n "$asset" ]; then
  check asset_immutable "哈希资源 immutable"
  check asset_max_age "哈希资源 30 天缓存"
fi
check asset_missing "缺失的静态资源仍是 404"
check short_proxied "短链仍交给后端"

if [ "$fail" != 0 ]; then
  echo "自检未通过，恢复运行前的 nginx 配置。证书文件会保留。"
  if [ -f "$BASE/nginx/backup/default.conf.before-https" ]; then
    cp -a "$BASE/nginx/backup/default.conf.before-https" "$HOST_CONFIG"
  else
    render_mode bootstrap
  fi
  docker exec "$cid" nginx -s reload || true
  exit 1
fi

cat > /etc/cron.d/sayohno-certbot <<EOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
15 3 * * * root $BASE/nginx/renew-cert.sh >> /var/log/sayohno-certbot.log 2>&1
EOF
chmod 644 /etc/cron.d/sayohno-certbot
touch /var/log/sayohno-certbot.log
date -Is > "$BASE/nginx/https.enabled"
printf 'HOST_CONFIG=%s\n' "$HOST_CONFIG" >> "$BASE/nginx/https.enabled"

echo
echo "HTTPS 已启用。"
echo "请确认阿里云安全组入方向放行了 TCP 443，并且 TCP 80 仍然放行。"
echo "浏览器打开 https://sayohno.cn 。www 和 http://域名 会跳到这个地址。"
echo "http://$expected/ 仍然直接打开站点，供现有访问和发布健康检查使用。"
echo
echo "然后编辑 $BASE/.env（不要提交这个文件）。线上只改这一项："
echo "  PUBLIC_ORIGIN=https://sayohno.cn"
echo "compose 会把它映射成 CONSOLE_BASE_URL、SHORTLINK_PUBLIC_BASE_URL 和 MARKETING_WEB_CORSALLOWEDORIGINS_0。"
echo "再执行："
echo "  docker compose -f $COMPOSE_FILE -f $BASE/docker-compose.https.yml up -d --no-deps app"
