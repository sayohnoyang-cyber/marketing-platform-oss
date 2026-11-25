# 生产 Compose 文件名和 certbot 镜像。由 enable-https.sh、renew-cert.sh、remote-deploy.sh source。
# 服务器上的真实文件是 compose.yaml。docker-compose.yml 若只是指向它的符号链接，仍然选 compose.yaml，
# 这样 `docker compose -f` 不会再把两份配置一起读进去。

resolve_compose_file() {
  local base=$1 name item
  local -a real=() linked=()
  for name in compose.yaml compose.yml docker-compose.yaml docker-compose.yml; do
    if [ -L "$base/$name" ]; then
      linked+=("$name")
    elif [ -f "$base/$name" ]; then
      real+=("$name")
    fi
  done
  if [ "${#real[@]}" -gt 0 ]; then
    for name in compose.yaml compose.yml docker-compose.yaml docker-compose.yml; do
      for item in "${real[@]}"; do
        if [ "$item" = "$name" ]; then
          printf '%s\n' "$base/$name"
          return 0
        fi
      done
    done
  fi
  if [ "${#linked[@]}" -gt 0 ]; then
    printf '%s\n' "$base/${linked[0]}"
    return 0
  fi
  return 1
}

# 需要已设置 BASE 和 COMPOSE_FILE。HTTPS 覆盖文件存在时一并带上。
run_compose() {
  if [ -f "$BASE/docker-compose.https.yml" ]; then
    command docker compose -f "$COMPOSE_FILE" -f "$BASE/docker-compose.https.yml" "$@"
  else
    command docker compose -f "$COMPOSE_FILE" "$@"
  fi
}

# nginx -s reload 后旧 worker 还会再应答一小段时间。
retry_check() {
  local tries=$1 i
  shift
  for i in $(seq 1 "$tries"); do
    if "$@"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

CERTBOT_IMAGE_DEFAULT=certbot/certbot:v2.11.0
# 阿里云访问 Docker Hub 可能超时。本机没有镜像时，Hub 失败后再试这个镜像并打成上面的标签。
CERTBOT_MIRROR_DEFAULT=docker.m.daocloud.io/certbot/certbot:v2.11.0

ensure_certbot_image() {
  local image=${CERTBOT_IMAGE:-$CERTBOT_IMAGE_DEFAULT}
  local mirror=${CERTBOT_IMAGE_MIRROR:-$CERTBOT_MIRROR_DEFAULT}
  if docker image inspect "$image" >/dev/null 2>&1; then
    echo "使用本机已有镜像 $image，不访问镜像仓库"
    return 0
  fi
  echo "本机没有 $image，尝试从 Docker Hub 拉取"
  if docker pull "$image"; then
    return 0
  fi
  echo "Docker Hub 拉取失败。改从镜像拉取并打上本地标签："
  echo "  docker pull $mirror && docker tag $mirror $image"
  docker pull "$mirror"
  docker tag "$mirror" "$image"
}

# 后面的参数是 docker run 的选项和 certbot 参数，不要再写镜像名。已有本地镜像时 --pull never。
run_certbot() {
  ensure_certbot_image
  local image=${CERTBOT_IMAGE:-$CERTBOT_IMAGE_DEFAULT}
  docker run --pull never --rm "$@" "$image"
}
