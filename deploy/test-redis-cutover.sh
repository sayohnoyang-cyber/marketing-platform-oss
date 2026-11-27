#!/usr/bin/env bash
# 在独立容器内验证预热键、在途扣减和重复执行，不读取本机业务数据。
set -euo pipefail
script_dir=$(cd "$(dirname "$0")" && pwd)
work_dir=$(mktemp -d)
mysql_id=
redis_id=
cleanup() {
  if [ -n "$redis_id" ]; then docker rm -f -v "$redis_id" >/dev/null; fi
  if [ -n "$mysql_id" ]; then docker rm -f -v "$mysql_id" >/dev/null; fi
  rm -rf "$work_dir"
}
trap cleanup EXIT

export MYSQL_ROOT_PASSWORD=cutover-test MYSQL_USER=root MYSQL_DATABASE=marketing REDIS_PASSWORD=''
mysql_id=$(docker run -d --network none -e MYSQL_ROOT_PASSWORD -e MYSQL_DATABASE mysql:8.0.36)
redis_id=$(docker run -d --network none redis:7.2)
mysql_test() {
  docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$mysql_id" mysql -h127.0.0.1 -uroot marketing "$@"
}
for i in $(seq 1 120); do
  if mysql_test -e 'SELECT 1' >/dev/null 2>&1; then break; fi
  sleep 1
done
mysql_test <<'SQL'
CREATE TABLE offer_plan (id BIGINT, inventory_qty INT, del_flag INT, status VARCHAR(16), usable_until DATETIME);
CREATE TABLE offer_inventory (plan_id BIGINT, id BIGINT, quantity INT, claimed_count INT, status VARCHAR(16));
CREATE TABLE issued_voucher (user_id BIGINT, plan_id BIGINT, inventory_id BIGINT, del_flag INT);
CREATE TABLE claim_ticket (user_id BIGINT, plan_id BIGINT, inventory_id BIGINT, status VARCHAR(16));
INSERT INTO offer_plan VALUES (18, 8, 0, 'open', NOW() + INTERVAL 1 DAY);
INSERT INTO offer_inventory VALUES (18, 301, 5, 1, 'open');
INSERT INTO issued_voucher VALUES (7, 18, 301, 0);
INSERT INTO claim_ticket VALUES (7, 18, 301, 'reserved'), (8, 18, 0, 'reserved');
CREATE TABLE deploy_cutover_history (step VARCHAR(64) PRIMARY KEY, applied_at DATETIME, release_sha VARCHAR(64), detail TEXT);
INSERT INTO deploy_cutover_history VALUES ('redis-offer-warm', NOW(), 'legacy', '');
SQL

warm() {
  bash "$script_dir/redis-cutover.sh" --base "$work_dir" --mysql-container "$mysql_id" --redis-container "$redis_id" --warm "$@"
}
redis_test() { docker exec "$redis_id" redis-cli --raw "$@"; }
warm
[ "$(redis_test GET 'offer:plan:{18}:inventory')" = 7 ]
[ "$(redis_test GET 'offer:plan:{18}:inventory:301')" = 3 ]
[ "$(redis_test GET 'offer:plan:{18}:quota:7')" = 2 ]
[ "$(redis_test GET 'offer:plan:{18}:inventory:301:quota:7')" = 2 ]
[ "$(redis_test GET 'offer:plan:{18}:quota:8')" = 1 ]
redis_test DECR 'offer:plan:{18}:inventory:301' >/dev/null
warm --force
[ "$(redis_test GET 'offer:plan:{18}:inventory:301')" = 2 ]
echo "Redis 库存与限领键预热回归测试通过"
