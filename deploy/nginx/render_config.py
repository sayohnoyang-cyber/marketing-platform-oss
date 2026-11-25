#!/usr/bin/env python3
"""Render the sayohno.cn nginx site config.

The live server block is kept (proxy, cache, body size) and wrapped with
HTTP redirects plus HTTPS. Pass --nginx-t from `nginx -T` to capture it.
Without that dump, server-body.fallback.inc is used.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

DEFAULT_PROXY_PASS = "http://app:9100"
DEFAULT_ROOT = "/usr/share/nginx/html"
DEFAULT_BODY_SIZE = "8m"
DEFAULT_CERT_DIR = "/etc/letsencrypt/live/sayohno.cn"
HERE = Path(__file__).resolve().parent


def _brace_end(text: str, open_index: int) -> int:
    depth = 0
    for index in range(open_index, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
    raise ValueError("unbalanced braces in nginx config")


def _iter_blocks(text: str, header_re: str):
    pattern = re.compile(header_re)
    cursor = 0
    while True:
        match = pattern.search(text, cursor)
        if not match:
            return
        open_index = text.find("{", match.start())
        if open_index < 0:
            return
        close_index = _brace_end(text, open_index)
        yield match.start(), text[open_index + 1 : close_index], close_index
        cursor = close_index + 1


def dedent(body: str) -> str:
    lines = body.splitlines()
    indents = [len(line) - len(line.lstrip(" ")) for line in lines if line.strip()]
    cut = min(indents) if indents else 0
    out = []
    for line in lines:
        if line.strip():
            out.append(line[cut:])
        else:
            out.append("")
    return "\n".join(out).strip("\n") + "\n"


def _depth0_lines(body: str):
    depth = 0
    for line in body.splitlines(keepends=True):
        yield depth, line
        depth += line.count("{") - line.count("}")


def _strip_depth0(body: str, names: tuple[str, ...]) -> str:
    kept = []
    for depth, line in _depth0_lines(body):
        stripped = line.strip()
        if depth == 0 and any(
            stripped == f"{name};" or stripped.startswith(f"{name} ") or stripped.startswith(f"{name}\t")
            for name in names
        ):
            continue
        kept.append(line)
    return "".join(kept)


def _strip_acme_locations(body: str) -> str:
    cursor = 0
    pieces = []
    for start, _inner, close in _iter_blocks(body, r"\blocation\b"):
        header = body[start:body.find("{", start)]
        if "acme-challenge" in header:
            pieces.append(body[cursor:start])
            cursor = close + 1
    pieces.append(body[cursor:])
    return "".join(pieces)


def sanitize_server_body(body: str) -> str:
    body = _strip_acme_locations(body)
    body = _strip_depth0(
        body,
        ("listen", "server_name", "ssl_certificate", "ssl_certificate_key", "ssl_protocols", "ssl_session_cache", "ssl_session_timeout"),
    )
    return dedent(body)


def _directive(body: str, name: str) -> str | None:
    for depth, line in _depth0_lines(body):
        if depth != 0:
            continue
        match = re.match(rf"\s*{name}\s+(.+);", line)
        if match:
            return match.group(1).strip()
    return None


def _proxy_pass(body: str) -> str | None:
    for start, inner, _close in _iter_blocks(body, r"\blocation\b"):
        header = body[start:body.find("{", start)]
        if re.search(r"location\s+/api/\s*$", header.strip()):
            found = re.search(r"proxy_pass\s+([^;]+);", inner)
            return found.group(1).strip() if found else None
    return None


def split_config_files(nginx_t: str) -> list[tuple[str, str]]:
    parts = re.split(r"(?m)^# configuration file (.+):\s*$", nginx_t)
    if len(parts) == 1:
        return [("/etc/nginx/conf.d/default.conf", nginx_t)]
    files = []
    preamble = parts[0]
    if preamble.strip() and "server" in preamble and "# configuration file" not in preamble:
        files.append(("/etc/nginx/nginx.conf", preamble))
    for path, content in zip(parts[1::2], parts[2::2]):
        files.append((path.strip(), content))
    return files


def extract_site(nginx_t: str) -> dict:
    chosen_path = ""
    chosen_body = ""
    for path, content in split_config_files(nginx_t):
        for _start, body, _close in _iter_blocks(content, r"\bserver\s*\{"):
            if re.search(r"location\s+/api/\s*\{", body):
                chosen_path = path
                chosen_body = body
    if not chosen_body:
        raise ValueError("nginx -T 里没有找到反代 /api/ 的 server")
    if "/conf.d/" not in chosen_path.replace("\\", "/"):
        raise ValueError(f"站点配置不在 conf.d 中，无法安全替换: {chosen_path}")
    body = sanitize_server_body(chosen_body)
    proxy_pass = _proxy_pass(body) or DEFAULT_PROXY_PASS
    root = _directive(body, "root") or DEFAULT_ROOT
    body_size = _directive(body, "client_max_body_size")
    if body_size is None:
        body_size = DEFAULT_BODY_SIZE
        body = f"client_max_body_size {body_size};\n{body}"
    if _directive(body, "root") is None:
        body = f"root {root};\n{body}"
    return {
        "config_file": chosen_path,
        "proxy_pass": proxy_pass,
        "root": root,
        "client_max_body_size": body_size,
        "server_body": body if body.endswith("\n") else body + "\n",
        "parsed": True,
    }


def fallback_site(proxy_pass: str, root: str, body_size: str) -> dict:
    raw = (HERE / "server-body.fallback.inc").read_text(encoding="utf-8")
    body = (
        raw.replace("__PROXY_PASS__", proxy_pass)
        .replace("__ROOT__", root)
        .replace("__CLIENT_MAX_BODY_SIZE__", body_size)
    )
    return {
        "config_file": "/etc/nginx/conf.d/default.conf",
        "proxy_pass": proxy_pass,
        "root": root,
        "client_max_body_size": body_size,
        "server_body": body if body.endswith("\n") else body + "\n",
        "parsed": False,
    }


def load_upstream(path: Path) -> dict[str, str]:
    values = {}
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def write_upstream(path: Path, site: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(
            [
                f"PROXY_PASS={site['proxy_pass']}",
                f"ROOT={site['root']}",
                f"CLIENT_MAX_BODY_SIZE={site['client_max_body_size']}",
                f"CONFIG_FILE={site['config_file']}",
                f"PARSED={'1' if site['parsed'] else '0'}",
                "",
            ]
        ),
        encoding="utf-8",
    )


def render(mode: str, site: dict, fullchain: str, key: str) -> str:
    template_name = "sayohno.bootstrap.conf.template" if mode == "bootstrap" else "sayohno.https.conf.template"
    template = (HERE / template_name).read_text(encoding="utf-8")
    body = dedent(site["server_body"]).rstrip("\n")
    indented = "\n".join(("    " + line) if line.strip() else line for line in body.splitlines())
    rendered = (
        template.replace("__SERVER_BODY__", indented)
        .replace("__CERT_FULLCHAIN__", fullchain)
        .replace("__CERT_KEY__", key)
    )
    if "__" in rendered:
        raise ValueError("nginx 模板还有未替换的占位符")
    if not rendered.endswith("\n"):
        rendered += "\n"
    return rendered


def _volume_target(volume):
    if isinstance(volume, str):
        parts = volume.split(":")
        if len(parts) >= 3 and parts[-1] in {"ro", "rw", "z", "Z"}:
            return parts[-2]
        if len(parts) >= 2:
            return parts[-1]
        return None
    if isinstance(volume, dict):
        return volume.get("target") or volume.get("destination")
    return None


def _volume_source(volume):
    if isinstance(volume, str):
        return volume.split(":")[0]
    if isinstance(volume, dict):
        return volume.get("source") or ""
    return ""


def _port_published(port) -> bool:
    if isinstance(port, str):
        return port == "443" or port.startswith("443:") or ":443" in port or port.endswith(":443")
    if isinstance(port, dict):
        published = str(port.get("published", ""))
        target = str(port.get("target", ""))
        return published == "443" or target == "443"
    return False


def plan_override(compose: dict, config_file: str, base: Path) -> dict:
    service = compose.get("services", {}).get("nginx")
    if not service:
        raise ValueError("compose 文件里没有 nginx 服务")
    volumes = service.get("volumes") or []
    ports = service.get("ports") or []
    targets = {}
    for volume in volumes:
        target = _volume_target(volume)
        if target:
            targets[target] = _volume_source(volume)
    lines = ["services:", "  nginx:"]
    extras = False
    if not any(_port_published(port) for port in ports):
        lines.extend(["    ports:", '      - "443:443"'])
        extras = True
    mount_lines = []
    name = Path(config_file).name
    if config_file in targets:
        source = targets[config_file]
        host_config = source if Path(source).is_absolute() else str(base / source)
    else:
        host_config = str(base / "nginx" / "conf.d" / name)
        mount_lines.append(f"      - ./nginx/conf.d/{name}:{config_file}:ro")
    if "/var/www/certbot" not in targets:
        mount_lines.append("      - ./certbot/www:/var/www/certbot:ro")
    if "/etc/letsencrypt" not in targets:
        mount_lines.append("      - ./certbot/conf:/etc/letsencrypt:ro")
    if mount_lines:
        lines.append("    volumes:")
        lines.extend(mount_lines)
        extras = True
    yaml = ("\n".join(lines) + "\n") if extras else ""
    return {"yaml": yaml, "host_config": host_config, "container_config": config_file}


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="render sayohno nginx config")
    parser.add_argument("--mode", choices=("bootstrap", "https"))
    parser.add_argument("--out", type=Path)
    parser.add_argument("--plan-override", action="store_true")
    parser.add_argument("--compose-json", type=Path)
    parser.add_argument("--override-out", type=Path)
    parser.add_argument("--install-env", type=Path)
    parser.add_argument("--base", type=Path)
    parser.add_argument("--config-file")
    parser.add_argument("--nginx-t", type=Path)
    parser.add_argument("--server-body", type=Path, help="previously sanitized server body")
    parser.add_argument("--upstream-env", type=Path)
    parser.add_argument("--write-upstream", type=Path)
    parser.add_argument("--write-server-body", type=Path)
    parser.add_argument("--proxy-pass", default=DEFAULT_PROXY_PASS)
    parser.add_argument("--root", default=DEFAULT_ROOT)
    parser.add_argument("--client-max-body-size", default=DEFAULT_BODY_SIZE)
    parser.add_argument("--cert-fullchain", default=f"{DEFAULT_CERT_DIR}/fullchain.pem")
    parser.add_argument("--cert-key", default=f"{DEFAULT_CERT_DIR}/privkey.pem")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.plan_override:
        if not args.compose_json or not args.base or not args.config_file or not args.install_env:
            raise ValueError("规划挂载需要 --compose-json --base --config-file --install-env")
        plan = plan_override(json.loads(args.compose_json.read_text(encoding="utf-8")), args.config_file, args.base)
        if args.override_out:
            if plan["yaml"]:
                args.override_out.write_text(plan["yaml"], encoding="utf-8")
            elif args.override_out.exists():
                args.override_out.unlink()
        args.install_env.parent.mkdir(parents=True, exist_ok=True)
        args.install_env.write_text(
            f"HOST_CONFIG={plan['host_config']}\nCONTAINER_CONFIG={plan['container_config']}\n",
            encoding="utf-8",
        )
        return 0
    if not args.mode or not args.out:
        raise ValueError("渲染配置需要 --mode 和 --out")
    upstream = load_upstream(args.upstream_env) if args.upstream_env else {}
    proxy_pass = upstream.get("PROXY_PASS", args.proxy_pass)
    root = upstream.get("ROOT", args.root)
    body_size = upstream.get("CLIENT_MAX_BODY_SIZE", args.client_max_body_size)
    if args.server_body and args.server_body.is_file():
        site = fallback_site(proxy_pass, root, body_size)
        site["server_body"] = args.server_body.read_text(encoding="utf-8")
        site["parsed"] = True
        site["config_file"] = upstream.get("CONFIG_FILE", site["config_file"])
        site["proxy_pass"] = _proxy_pass(site["server_body"]) or proxy_pass
        site["root"] = _directive(site["server_body"], "root") or root
        site["client_max_body_size"] = _directive(site["server_body"], "client_max_body_size") or body_size
    elif args.nginx_t:
        site = extract_site(args.nginx_t.read_text(encoding="utf-8"))
    else:
        site = fallback_site(proxy_pass, root, body_size)
    text = render(args.mode, site, args.cert_fullchain, args.cert_key)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(text, encoding="utf-8")
    if args.write_server_body:
        args.write_server_body.parent.mkdir(parents=True, exist_ok=True)
        args.write_server_body.write_text(site["server_body"], encoding="utf-8")
    if args.write_upstream:
        write_upstream(args.write_upstream, site)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as error:  # noqa: BLE001 - CLI boundary
        print(f"渲染 nginx 配置失败: {error}", file=sys.stderr)
        sys.exit(1)
