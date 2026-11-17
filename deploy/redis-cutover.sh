#!/usr/bin/env bash
# Redis 键切换（一次性）：备份并清理旧 coupon:*，按数据库预热 offer:* 库存与限领计数。
#
# 生产环境（与 remote-deploy.sh 相同，读取 /opt/marketing-platform/.env，通过 docker compose exec 访问 mysql/redis）：
#   bash /opt/marketing-platform/deploy/redis-cutover.sh --dry-run
#   bash /opt/marketing-platform/deploy/redis-cutover.sh --warm
#   bash /opt/marketing-platform/deploy/redis-cutover.sh --delete-old
# 不带阶段参数时，依次执行 --warm 与 --delete-old（适合应用已切换到新版本后的维护窗口）。
#
# 部署脚本会自动调用：迁移完成后、切换 jar 之前执行 --warm；新版本健康后再执行 --delete-old。
# 预热与删旧键都只自动跑一次（记录在 deploy_cutover_history）。需要重跑时加 --force，并先停领券流量。
#
# 不迁移 coupon:hold:* / coupon:hold:a:* 到 offer:ticket:*：旧 hold 与新 ticket 值格式不同，且属于进行中请求的短生命周期幂等键。
# 请在维护窗口执行，避免切换瞬间有未落库的预扣。
set -euo pipefail

usage() {
  cat <<'EOF'
用法: redis-cutover.sh [--dry-run] [--warm] [--delete-old] [--force]
                       [--base DIR] [--compose-file FILE]
                       [--mysql-container NAME] [--redis-container NAME]
                       [--mysql-host HOST --mysql-port PORT]
                       [--redis-host HOST --redis-port PORT]

  --warm         备份 coupon:*（如有）并按数据库预热 offer:* 库存/限领
  --delete-old   再次备份后删除全部 coupon:*
  --dry-run      只打印将要执行的操作，不写 Redis、不写备份、不写历史表
  --force        忽略 deploy_cutover_history 中的已执行标记并重跑

连接优先级：
  1) --mysql-host / --redis-host（宿主机 redis-cli / mysql）
  2) --mysql-container / --redis-container（docker exec）
  3) 默认在 --base（缺省 /opt/marketing-platform）下 docker compose exec
EOF
}

DRY=0
WARM=0
DELETE_OLD=0
FORCE=0
BASE="${CUTOVER_BASE:-/opt/marketing-platform}"
COMPOSE_FILE=""
MYSQL_CONTAINER_OPT=""
REDIS_CONTAINER_OPT=""
MYSQL_HOST_OPT=""
MYSQL_PORT_OPT=""
REDIS_HOST_OPT=""
REDIS_PORT_OPT=""
SHA="${RELEASE_SHA:-manual}"

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --warm) WARM=1 ;;
    --delete-old) DELETE_OLD=1 ;;
    --force) FORCE=1 ;;
    --base) BASE="${2:?}"; shift ;;
    --compose-file) COMPOSE_FILE="${2:?}"; shift ;;
    --mysql-container) MYSQL_CONTAINER_OPT="${2:?}"; shift ;;
    --redis-container) REDIS_CONTAINER_OPT="${2:?}"; shift ;;
    --mysql-host) MYSQL_HOST_OPT="${2:?}"; shift ;;
    --mysql-port) MYSQL_PORT_OPT="${2:?}"; shift ;;
    --redis-host) REDIS_HOST_OPT="${2:?}"; shift ;;
    --redis-port) REDIS_PORT_OPT="${2:?}"; shift ;;
    --sha) SHA="${2:?}"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; usage >&2; exit 1 ;;
  esac
  shift
done

if [ "$WARM" -eq 0 ] && [ "$DELETE_OLD" -eq 0 ]; then
  WARM=1
  DELETE_OLD=1
fi

if [ -d "$BASE" ]; then
  BASE="$(cd "$BASE" && pwd)"
fi
if [ -f "$BASE/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$BASE/.env"
  set +a
fi

MYSQL_PWD_VALUE="${MYSQL_ROOT_PASSWORD:-${MYSQL_PASSWORD:-}}"
MYSQL_USER_VALUE="${MYSQL_USER:-root}"
MYSQL_DB_VALUE="${MYSQL_DATABASE:-marketing}"
MYSQL_PORT_VALUE="${MYSQL_PORT_OPT:-${MYSQL_PORT:-3306}}"
REDIS_PORT_VALUE="${REDIS_PORT_OPT:-${REDIS_PORT:-6379}}"

compose() {
  if [ -n "$COMPOSE_FILE" ]; then
    docker compose --project-directory "$BASE" -f "$COMPOSE_FILE" "$@"
  else
    docker compose --project-directory "$BASE" "$@"
  fi
}

# 命令不从管道读入数据。务必把 stdin 接到 /dev/null，避免 docker exec / compose exec
# 在 while-read 循环里把后续键名或 SQL 行吞掉。
redis_do() {
  if [ -n "$REDIS_HOST_OPT" ]; then
    if [ -n "${REDIS_PASSWORD:-}" ]; then
      redis-cli -h "$REDIS_HOST_OPT" -p "$REDIS_PORT_VALUE" -a "$REDIS_PASSWORD" --no-auth-warning "$@" </dev/null
    else
      redis-cli -h "$REDIS_HOST_OPT" -p "$REDIS_PORT_VALUE" "$@" </dev/null
    fi
  elif [ -n "$REDIS_CONTAINER_OPT" ]; then
    if [ -n "${REDIS_PASSWORD:-}" ]; then
      docker exec -e REDISCLI_AUTH="$REDIS_PASSWORD" "$REDIS_CONTAINER_OPT" redis-cli --no-auth-warning "$@" </dev/null
    else
      docker exec "$REDIS_CONTAINER_OPT" redis-cli "$@" </dev/null
    fi
  else
    if [ -n "${REDIS_PASSWORD:-}" ]; then
      compose exec -T redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning "$@" </dev/null
    else
      compose exec -T redis redis-cli "$@" </dev/null
    fi
  fi
}

mysql_do() {
  if [ -n "$MYSQL_HOST_OPT" ]; then
    MYSQL_PWD="$MYSQL_PWD_VALUE" mysql -h "$MYSQL_HOST_OPT" -P "$MYSQL_PORT_VALUE" -u "$MYSQL_USER_VALUE" --default-character-set=utf8mb4 "$MYSQL_DB_VALUE" "$@" </dev/null
  elif [ -n "$MYSQL_CONTAINER_OPT" ]; then
    docker exec -e MYSQL_PWD="$MYSQL_PWD_VALUE" "$MYSQL_CONTAINER_OPT" mysql -u "$MYSQL_USER_VALUE" --default-character-set=utf8mb4 "$MYSQL_DB_VALUE" "$@" </dev/null
  else
    compose exec -T -e MYSQL_PWD="$MYSQL_PWD_VALUE" mysql mysql -u "$MYSQL_USER_VALUE" --default-character-set=utf8mb4 "$MYSQL_DB_VALUE" "$@" </dev/null
  fi
}

# 去掉 docker/redis-cli 可能带出的 CR，并丢弃空行
clean_lines() {
  tr -d '\r' | sed '/^$/d'
}

# 通过 stdin 向 redis-cli 一次写入多条命令；仅在明确喂文件时使用 -i/-T。
redis_pipe() {
  if [ -n "$REDIS_HOST_OPT" ]; then
    if [ -n "${REDIS_PASSWORD:-}" ]; then
      redis-cli -h "$REDIS_HOST_OPT" -p "$REDIS_PORT_VALUE" -a "$REDIS_PASSWORD" --no-auth-warning
    else
      redis-cli -h "$REDIS_HOST_OPT" -p "$REDIS_PORT_VALUE"
    fi
  elif [ -n "$REDIS_CONTAINER_OPT" ]; then
    if [ -n "${REDIS_PASSWORD:-}" ]; then
      docker exec -i -e REDISCLI_AUTH="$REDIS_PASSWORD" "$REDIS_CONTAINER_OPT" redis-cli --no-auth-warning
    else
      docker exec -i "$REDIS_CONTAINER_OPT" redis-cli
    fi
  else
    if [ -n "${REDIS_PASSWORD:-}" ]; then
      compose exec -T redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning
    else
      compose exec -T redis redis-cli
    fi
  fi
}

# 把查询写到临时文件；失败必须退出（进程替换会吞掉 mysql 的非零状态）
mysql_query() {
  local out="$1"
  shift
  if ! mysql_do "$@" > "$out"; then
    echo "MySQL 查询失败" >&2
    cat "$out" >&2 || true
    exit 1
  fi
  # 去掉 SET 语句可能产生的警告，保留结果行
  local cleaned
  cleaned="$(clean_lines < "$out")"
  printf '%s
' "$cleaned" > "$out"
}

scan_keys() {
  local pattern="$1"
  redis_do --scan --pattern "$pattern" | clean_lines
}

require_offer_schema() {
  local n
  n="$(mysql_do -N -e "SET time_zone = '+08:00'; SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'offer_plan';" | clean_lines | tail -n 1)"
  if [ "${n:-0}" != "1" ]; then
    echo "未找到 offer_plan 表。请先执行 V012–V014 迁移，再跑本脚本。" >&2
    exit 1
  fi
}

ensure_history_table() {
  [ "$DRY" -eq 1 ] && return 0
  mysql_do -e "
    CREATE TABLE IF NOT EXISTS deploy_cutover_history (
      step VARCHAR(64) NOT NULL PRIMARY KEY,
      applied_at DATETIME NOT NULL,
      release_sha VARCHAR(64) NULL,
      detail TEXT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
  "
}

history_done() {
  local step="$1"
  local n
  n="$(mysql_do -N -e "SET time_zone = '+08:00';
    SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'deploy_cutover_history';" | clean_lines | tail -n 1)"
  if [ "${n:-0}" != "1" ]; then
    echo 0
    return 0
  fi
  mysql_do -N -e "SELECT COUNT(*) FROM deploy_cutover_history WHERE step='$(sql_escape "$step")';" | clean_lines | tail -n 1
}

record_history() {
  local step="$1"
  local detail="$2"
  [ "$DRY" -eq 1 ] && return 0
  mysql_do -e "INSERT INTO deploy_cutover_history (step, applied_at, release_sha, detail)
    VALUES ('$(sql_escape "$step")', NOW(), '$(sql_escape "$SHA")', '$(sql_escape "$detail")')
    ON DUPLICATE KEY UPDATE applied_at = NOW(), release_sha = VALUES(release_sha), detail = VALUES(detail);"
}

sql_escape() {
  printf '%s' "$1" | sed "s/'/''/g"
}

# 与 VoucherClaimService 中 Math.max(60, Duration.between(now, usableUntil).getSeconds()) 一致
TTL_SQL="GREATEST(60, UNIX_TIMESTAMP(usable_until) - UNIX_TIMESTAMP(NOW()))"
PLAN_TTL_SQL="GREATEST(60, UNIX_TIMESTAMP(p.usable_until) - UNIX_TIMESTAMP(NOW()))"

count_keys() {
  local pattern="$1"
  local n=0
  while IFS= read -r _; do
    n=$((n + 1))
  done < <(scan_keys "$pattern")
  echo "$n"
}

backup_coupon_keys() {
  local dest="$1"
  local key typ pttl payload tmp n=0
  if [ "$DRY" -eq 1 ]; then
    echo "[dry-run] 将备份 coupon:* 到 $dest"
    return 0
  fi
  mkdir -p "$(dirname "$dest")"
  tmp="$(mktemp)"
  {
    echo "# marketing-platform redis cutover backup"
    echo "# format: TYPE<TAB>PTTL_MS<TAB>KEY<TAB>PAYLOAD"
    echo "# hash payload: field=value;field=value"
    echo "# generated_at=$(date '+%Y-%m-%d %H:%M:%S %z')"
    while IFS= read -r key; do
      typ="$(redis_do TYPE "$key" | clean_lines | tail -n 1)"
      pttl="$(redis_do PTTL "$key" | clean_lines | tail -n 1)"
      payload=""
      case "$typ" in
        string)
          payload="$(redis_do GET "$key" | clean_lines | tail -n 1)"
          ;;
        hash)
          payload="$(redis_do HGETALL "$key" | clean_lines | awk 'NR%2==1{f=$0; next} {printf "%s%s=%s", (n++?",":""), f, $0}')"
          ;;
        none)
          continue
          ;;
        *)
          payload="UNSUPPORTED_TYPE"
          echo "警告: 键 $key 类型为 $typ，备份仅记录类型，无法按值还原" >&2
          ;;
      esac
      printf '%s\t%s\t%s\t%s\n' "$typ" "$pttl" "$key" "$payload"
      n=$((n + 1))
    done < <(scan_keys 'coupon:*')
  } > "$tmp"
  mv "$tmp" "$dest"
  chmod 600 "$dest" 2>/dev/null || true
  echo "已备份 $n 个 coupon:* 键 -> $dest"
  BACKUP_COUNT="$n"
}

SET_QUEUE=""
expect_file=""

begin_sets() {
  SET_QUEUE="$(mktemp)"
  expect_file="$(mktemp)"
  : > "$SET_QUEUE"
  : > "$expect_file"
}

queue_set() {
  local key="$1" value="$2" ttl="$3"
  printf 'SET %s %s EX %s\n' "$key" "$value" "$ttl" >> "$SET_QUEUE"
  printf '%s\t%s\n' "$key" "$value" >> "$expect_file"
}

flush_sets() {
  local n
  n="$(wc -l < "$SET_QUEUE" | tr -d ' ')"
  if [ "$DRY" -eq 1 ]; then
    cat "$SET_QUEUE"
    rm -f "$SET_QUEUE" "$expect_file"
    SET_QUEUE=""; expect_file=""
    return 0
  fi
  if [ "${n:-0}" -gt 0 ]; then
    redis_pipe < "$SET_QUEUE" >/dev/null
    local getq key expected actual
    getq="$(mktemp)"
    while IFS=$'\t' read -r key expected; do
      [ -z "${key:-}" ] && continue
      printf 'GET %s\n' "$key" >> "$getq"
    done < "$expect_file"
    # paste 把期望值与 GET 结果按行对齐（避免双 stdin 互相覆盖，且兼容 bash 3.2）
    while IFS=$'\t' read -r key expected actual; do
      actual="$(printf '%s' "$actual" | tr -d '\r')"
      if [ "$actual" != "$expected" ]; then
        echo "校验失败: $key 期望 $expected，实际 ${actual:-<空>}" >&2
        rm -f "$SET_QUEUE" "$expect_file" "$getq"
        exit 1
      fi
    done < <(paste "$expect_file" <(redis_pipe < "$getq" | clean_lines))
    rm -f "$getq"
  fi
  rm -f "$SET_QUEUE" "$expect_file"
  SET_QUEUE=""; expect_file=""
}

warm_offer_keys() {
  local plans=0 invs=0 plan_quotas=0 inv_quotas=0
  local id qty ttl user_id plan_id claimed inventory_id remaining

  local qfile
  qfile="$(mktemp)"
  echo "---- 预热 offer:plan:{id}:inventory（剩余未分配/直接领取库存 = offer_plan.inventory_qty）----"
  begin_sets
  mysql_query "$qfile" -N -B -e "SET time_zone = '+08:00';
    SELECT id, inventory_qty, $TTL_SQL AS ttl
    FROM offer_plan
    WHERE del_flag = 0 AND status = 'open' AND usable_until > NOW();"
  while IFS=$'\t' read -r id qty ttl; do
    [ -z "${id:-}" ] && continue
    queue_set "offer:plan:${id}:inventory" "$qty" "$ttl"
    plans=$((plans + 1))
  done < "$qfile"
  flush_sets

  echo "---- 预热 offer:inventory:{id}（剩余库存 = quantity - claimed_count）----"
  begin_sets
  mysql_query "$qfile" -N -B -e "SET time_zone = '+08:00';
    SELECT i.id, GREATEST(0, i.quantity - i.claimed_count) AS remaining, $PLAN_TTL_SQL AS ttl
    FROM offer_inventory i
    INNER JOIN offer_plan p ON p.id = i.plan_id
    WHERE i.status = 'open' AND p.del_flag = 0 AND p.status = 'open' AND p.usable_until > NOW();"
  while IFS=$'\t' read -r id remaining ttl; do
    [ -z "${id:-}" ] && continue
    queue_set "offer:inventory:${id}" "$remaining" "$ttl"
    invs=$((invs + 1))
  done < "$qfile"
  flush_sets

  echo "---- 预热 offer:plan:{planId}:quota:{userId}（已领次数 = GREATEST(COUNT(*), MAX(claim_seq))）----"
  begin_sets
  mysql_query "$qfile" -N -B -e "SET time_zone = '+08:00';
    SELECT v.user_id, v.plan_id,
           GREATEST(COUNT(*), COALESCE(MAX(v.claim_seq), 0)) AS claimed,
           GREATEST(60, UNIX_TIMESTAMP(MAX(p.usable_until)) - UNIX_TIMESTAMP(NOW())) AS ttl
    FROM issued_voucher v
    INNER JOIN offer_plan p ON p.id = v.plan_id
    WHERE v.del_flag = 0 AND p.del_flag = 0 AND p.status = 'open' AND p.usable_until > NOW()
    GROUP BY v.user_id, v.plan_id;"
  while IFS=$'\t' read -r user_id plan_id claimed ttl; do
    [ -z "${user_id:-}" ] && continue
    queue_set "offer:plan:${plan_id}:quota:${user_id}" "$claimed" "$ttl"
    plan_quotas=$((plan_quotas + 1))
  done < "$qfile"
  flush_sets

  echo "---- 预热 offer:inventory:{inventoryId}:quota:{userId}（该投放已领次数 = COUNT(*)）----"
  begin_sets
  mysql_query "$qfile" -N -B -e "SET time_zone = '+08:00';
    SELECT v.user_id, v.inventory_id, COUNT(*) AS claimed,
           GREATEST(60, UNIX_TIMESTAMP(MAX(p.usable_until)) - UNIX_TIMESTAMP(NOW())) AS ttl
    FROM issued_voucher v
    INNER JOIN offer_plan p ON p.id = v.plan_id
    INNER JOIN offer_inventory i ON i.id = v.inventory_id
    WHERE v.del_flag = 0 AND v.inventory_id > 0
      AND i.status = 'open' AND p.del_flag = 0 AND p.status = 'open' AND p.usable_until > NOW()
    GROUP BY v.user_id, v.inventory_id;"
  while IFS=$'\t' read -r user_id inventory_id claimed ttl; do
    [ -z "${user_id:-}" ] && continue
    queue_set "offer:inventory:${inventory_id}:quota:${user_id}" "$claimed" "$ttl"
    inv_quotas=$((inv_quotas + 1))
  done < "$qfile"
  flush_sets
  rm -f "$qfile"

  WARM_PLAN_STOCK="$plans"
  WARM_INV_STOCK="$invs"
  WARM_PLAN_QUOTA="$plan_quotas"
  WARM_INV_QUOTA="$inv_quotas"
  echo "预热完成: plan 库存 $plans，投放库存 $invs，方案限领 $plan_quotas，投放限领 $inv_quotas"
}

delete_coupon_keys() {
  local key n=0 batch=0
  local -a buf=()
  while IFS= read -r key; do
    [ -z "$key" ] && continue
    if [ "$DRY" -eq 1 ]; then
      echo "DEL $key"
      n=$((n + 1))
      continue
    fi
    buf+=("$key")
    batch=$((batch + 1))
    n=$((n + 1))
    if [ "$batch" -ge 100 ]; then
      redis_do DEL "${buf[@]}" >/dev/null
      buf=()
      batch=0
    fi
  done < <(scan_keys 'coupon:*')
  if [ "$DRY" -eq 0 ] && [ "$batch" -gt 0 ]; then
    redis_do DEL "${buf[@]}" >/dev/null
  fi
  DELETE_COUNT="$n"
  echo "删除 coupon:*: $n 个"
}

print_summary() {
  cat <<EOF

======== Redis 切换摘要 ========
时间(Asia/Shanghai): $(TZ=Asia/Shanghai date '+%Y-%m-%d %H:%M:%S')
模式: $([ "$DRY" -eq 1 ] && echo dry-run || echo apply)
目录: $BASE
发布 SHA: $SHA
coupon:* 扫描: ${COUPON_SCAN:-0}
备份文件: ${BACKUP_FILE:-无}
备份键数: ${BACKUP_COUNT:-0}
预热 plan 库存: ${WARM_PLAN_STOCK:-0}
预热投放库存: ${WARM_INV_STOCK:-0}
预热方案限领: ${WARM_PLAN_QUOTA:-0}
预热投放限领: ${WARM_INV_QUOTA:-0}
删除 coupon:*: ${DELETE_COUNT:-0}
跳过步骤: ${SKIPPED:-无}
================================
EOF
}

# ---- 主流程 ----
require_offer_schema
ensure_history_table

COUPON_SCAN="$(count_keys 'coupon:*')"
echo "当前 coupon:* 键数量: $COUPON_SCAN"
SKIPPED=""
BACKUP_FILE=""
BACKUP_COUNT=0
DELETE_COUNT=0
WARM_PLAN_STOCK=0
WARM_INV_STOCK=0
WARM_PLAN_QUOTA=0
WARM_INV_QUOTA=0

BACKUP_DIR="$BASE/backups"
if [ "$DRY" -eq 0 ]; then
  mkdir -p "$BACKUP_DIR"
  chmod 700 "$BACKUP_DIR" 2>/dev/null || true
fi
STAMP="$(TZ=Asia/Shanghai date '+%Y%m%d-%H%M%S')"

if [ "$WARM" -eq 1 ]; then
  done_warm="$(history_done redis-offer-warm)"
  if [ "$FORCE" -eq 0 ] && [ "${done_warm:-0}" != "0" ]; then
    echo "已记录 redis-offer-warm，跳过预热（需要重跑请加 --force）。"
    SKIPPED="${SKIPPED:+$SKIPPED,}redis-offer-warm"
  else
    if [ "$COUPON_SCAN" -gt 0 ]; then
      BACKUP_FILE="$BACKUP_DIR/redis-coupon-${STAMP}-warm.txt"
      backup_coupon_keys "$BACKUP_FILE"
    else
      echo "没有 coupon:* 键需要备份。"
    fi
    warm_offer_keys
    record_history redis-offer-warm "plan_stock=${WARM_PLAN_STOCK};inv_stock=${WARM_INV_STOCK};plan_quota=${WARM_PLAN_QUOTA};inv_quota=${WARM_INV_QUOTA};coupon_backup=${BACKUP_COUNT}"
  fi
fi

if [ "$DELETE_OLD" -eq 1 ]; then
  done_del="$(history_done redis-coupon-delete)"
  if [ "$FORCE" -eq 0 ] && [ "${done_del:-0}" != "0" ]; then
    echo "已记录 redis-coupon-delete，跳过删除（需要重跑请加 --force）。"
    SKIPPED="${SKIPPED:+$SKIPPED,}redis-coupon-delete"
  else
    COUPON_SCAN="$(count_keys 'coupon:*')"
    if [ "$COUPON_SCAN" -gt 0 ]; then
      BACKUP_FILE="$BACKUP_DIR/redis-coupon-${STAMP}-delete.txt"
      backup_coupon_keys "$BACKUP_FILE"
    else
      echo "没有 coupon:* 键需要删除。"
    fi
    delete_coupon_keys
    record_history redis-coupon-delete "deleted=${DELETE_COUNT}"
  fi
fi

print_summary
