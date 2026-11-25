#!/usr/bin/env bash
# 续期 sayohno.cn 的 Let's Encrypt 证书，并让 nginx 重新加载。由 /etc/cron.d/sayohno-certbot 每天调用。
# 本机已有 certbot/certbot:v2.11.0 时不访问镜像仓库。没有镜像且 Docker Hub 失败时，
# 改拉 docker.m.daocloud.io/certbot/certbot:v2.11.0 并打成同样的标签。
set -euo pipefail
BASE=${MARKETING_BASE:-/opt/marketing-platform}
KIT=$(cd "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
. "$KIT/compose-project.sh"
cd "$BASE"
exec 9>/tmp/marketing-deploy.lock
flock -n 9 || { echo "部署进行中，跳过证书续期"; exit 0; }
[ -f "$BASE/nginx/https.enabled" ] || { echo "HTTPS 尚未启用"; exit 0; }
COMPOSE_FILE=$(resolve_compose_file "$BASE") || { echo "找不到 compose.yaml 或 docker-compose.yml"; exit 1; }

run_certbot \
  -v "$BASE/certbot/conf:/etc/letsencrypt" \
  -v "$BASE/certbot/www:/var/www/certbot" \
  renew --webroot -w /var/www/certbot --quiet

cid=$(run_compose ps -q nginx)
# shellcheck disable=SC1091
. "$BASE/nginx/cert-perms.sh"
gid=$(docker exec "$cid" id -g nginx | tr -d '\r')
fix_cert_perms "$BASE/certbot/conf" "$gid"
docker exec "$cid" nginx -s reload
echo "证书续期检查完成"
