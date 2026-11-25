#!/usr/bin/env bash
# 不依赖 docker。只检查 compose 文件选择。
set -euo pipefail
. "$(dirname "$0")/compose-project.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo 'services: {}' > "$tmp/compose.yaml"
[ "$(resolve_compose_file "$tmp")" = "$tmp/compose.yaml" ]

ln -s compose.yaml "$tmp/docker-compose.yml"
[ "$(resolve_compose_file "$tmp")" = "$tmp/compose.yaml" ]
[ -L "$tmp/docker-compose.yml" ]

rm -rf "$tmp"
mkdir -p "$tmp"
echo 'services: {}' > "$tmp/docker-compose.yml"
[ "$(resolve_compose_file "$tmp")" = "$tmp/docker-compose.yml" ]

echo 'services: {}' > "$tmp/compose.yaml"
[ "$(resolve_compose_file "$tmp")" = "$tmp/compose.yaml" ]

rm "$tmp/compose.yaml" "$tmp/docker-compose.yml"
echo 'services: {}' > "$tmp/real.yaml"
ln -s real.yaml "$tmp/docker-compose.yml"
[ "$(resolve_compose_file "$tmp")" = "$tmp/docker-compose.yml" ]

if resolve_compose_file "$tmp/missing" >/dev/null 2>&1; then
  echo "空目录不应选中文件" >&2
  exit 1
fi

echo "compose file selection ok"
