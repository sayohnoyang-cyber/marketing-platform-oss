#!/usr/bin/env bash
# 续期 sayohno.cn 的 Let's Encrypt 证书，并让 nginx 重新加载。由 /etc/cron.d/sayohno-certbot 每天调用。
set -euo pipefail
BASE=${MARKETING_BASE:-/opt/marketing-platform}
IMAGE=${CERTBOT_IMAGE:-certbot/certbot:v2.11.0}
cd "$BASE"
exec 9>/tmp/marketing-deploy.lock
flock -n 9 || { echo "部署进行中，跳过证书续期"; exit 0; }
[ -f "$BASE/nginx/https.enabled" ] || { echo "HTTPS 尚未启用"; exit 0; }

docker run --rm \
  -v "$BASE/certbot/conf:/etc/letsencrypt" \
  -v "$BASE/certbot/www:/var/www/certbot" \
  "$IMAGE" renew --webroot -w /var/www/certbot --quiet

if [ -f "$BASE/docker-compose.https.yml" ]; then
  cid=$(docker compose -f docker-compose.yml -f docker-compose.https.yml ps -q nginx)
else
  cid=$(docker compose ps -q nginx)
fi
# shellcheck disable=SC1091
. "$BASE/nginx/cert-perms.sh"
gid=$(docker exec "$cid" id -g nginx | tr -d '\r')
fix_cert_perms "$BASE/certbot/conf" "$gid"
docker exec "$cid" nginx -s reload
echo "证书续期检查完成"
