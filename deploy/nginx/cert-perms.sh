#!/usr/bin/env bash
# Let's Encrypt 私钥默认是 root:600。容器里的 nginx 用户需要能读到它。
# 用法: fix_cert_perms <证书目录> <nginx 容器内 nginx 用户的 gid>
fix_cert_perms() {
  local conf_dir=$1 gid=$2
  [ -d "$conf_dir/archive" ] || return 0
  chgrp -R "$gid" "$conf_dir/archive" "$conf_dir/live" || true
  find "$conf_dir/archive" "$conf_dir/live" -type d -exec chmod 750 {} \;
  chmod 755 "$conf_dir" "$conf_dir/live" || true
  find "$conf_dir/archive" -name 'privkey*.pem' -exec chmod 640 {} \;
  find "$conf_dir/archive" \( -name 'cert*.pem' -o -name 'chain*.pem' -o -name 'fullchain*.pem' \) -exec chmod 644 {} \;
}
