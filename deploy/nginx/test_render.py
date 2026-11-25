#!/usr/bin/env python3
import importlib.util
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("render_config", HERE / "render_config.py")
render_config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(render_config)

LIVE = """
# configuration file /etc/nginx/nginx.conf:
worker_processes auto;
events { worker_connections 1024; }
http {
    include /etc/nginx/conf.d/*.conf;
}

# configuration file /etc/nginx/conf.d/default.conf:
server {
    listen 80 default_server;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;
    client_max_body_size 12m;

    location /assets/ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }

    location /api/ {
        proxy_pass http://app:9100;
        proxy_set_header Host $host;
    }

    location /s/ {
        proxy_pass http://app:9100;
    }

    location / {
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "no-cache";
    }
}
"""


class RenderConfigTest(unittest.TestCase):
    def test_extract_preserves_proxy_cache_and_body_size(self):
        site = render_config.extract_site(LIVE)
        self.assertTrue(site["parsed"])
        self.assertEqual(site["config_file"], "/etc/nginx/conf.d/default.conf")
        self.assertEqual(site["proxy_pass"], "http://app:9100")
        self.assertEqual(site["root"], "/usr/share/nginx/html")
        self.assertEqual(site["client_max_body_size"], "12m")
        self.assertIn('add_header Cache-Control "public, immutable"', site["server_body"])
        self.assertIn('add_header Cache-Control "no-cache"', site["server_body"])
        self.assertIn("proxy_set_header Host $host;", site["server_body"])
        self.assertNotIn("listen ", site["server_body"])

    def test_https_wraps_live_server_and_second_pass_does_not_grow(self):
        site = render_config.extract_site(LIVE)
        first = render_config.render("https", site, "/certs/fullchain.pem", "/certs/privkey.pem")
        self.assertIn("server_name sayohno.cn www.sayohno.cn;", first)
        self.assertIn("return 301 https://sayohno.cn$request_uri;", first)
        self.assertIn("ssl_certificate /certs/fullchain.pem;", first)
        self.assertEqual(first.count("location /api/"), 2)
        self.assertEqual(first.count('add_header Cache-Control "no-cache"'), 2)
        self.assertNotIn("__", first)
        dumped = "# configuration file /etc/nginx/conf.d/default.conf:\n" + first
        again = render_config.extract_site(dumped)
        second = render_config.render("https", again, "/certs/fullchain.pem", "/certs/privkey.pem")
        self.assertEqual(first, second)

    def test_bootstrap_has_no_certificate(self):
        site = render_config.extract_site(LIVE)
        text = render_config.render("bootstrap", site, "/certs/fullchain.pem", "/certs/privkey.pem")
        self.assertNotIn("ssl_certificate", text)
        self.assertIn("acme-challenge", text)
        self.assertIn("proxy_pass http://app:9100;", text)
        self.assertNotIn("return 301", text)

    def test_fallback_mentions_observed_cache_rules(self):
        site = render_config.fallback_site("http://127.0.0.1:9100", "/usr/share/nginx/html", "8m")
        text = render_config.render("https", site, "/etc/letsencrypt/live/sayohno.cn/fullchain.pem", "/etc/letsencrypt/live/sayohno.cn/privkey.pem")
        self.assertIn("expires 30d;", text)
        self.assertIn('add_header Cache-Control "public, immutable";', text)
        self.assertIn('add_header Cache-Control "no-cache";', text)
        self.assertIn("proxy_pass http://127.0.0.1:9100;", text)
        self.assertIn("client_max_body_size 8m;", text)

    def test_override_adds_only_missing_https_mounts(self):
        compose = {
            "services": {
                "nginx": {
                    "ports": [{"target": 80, "published": "80", "protocol": "tcp"}],
                    "volumes": [
                        {"type": "bind", "source": "/opt/marketing-platform/dist", "target": "/usr/share/nginx/html", "read_only": True}
                    ],
                }
            }
        }
        plan = render_config.plan_override(compose, "/etc/nginx/conf.d/default.conf", Path("/opt/marketing-platform"))
        self.assertIn('"443:443"', plan["yaml"])
        self.assertIn("./certbot/www:/var/www/certbot:ro", plan["yaml"])
        self.assertIn("./certbot/conf:/etc/letsencrypt:ro", plan["yaml"])
        self.assertIn("./nginx/conf.d/default.conf:/etc/nginx/conf.d/default.conf:ro", plan["yaml"])
        self.assertEqual(plan["host_config"], "/opt/marketing-platform/nginx/conf.d/default.conf")

        compose["services"]["nginx"]["ports"].append({"target": 443, "published": "443"})
        compose["services"]["nginx"]["volumes"].append(
            {"type": "bind", "source": "/opt/marketing-platform/nginx/conf.d/default.conf", "target": "/etc/nginx/conf.d/default.conf"}
        )
        compose["services"]["nginx"]["volumes"].append(
            {"type": "bind", "source": "/opt/marketing-platform/certbot/www", "target": "/var/www/certbot"}
        )
        compose["services"]["nginx"]["volumes"].append(
            {"type": "bind", "source": "/opt/marketing-platform/certbot/conf", "target": "/etc/letsencrypt"}
        )
        again = render_config.plan_override(compose, "/etc/nginx/conf.d/default.conf", Path("/opt/marketing-platform"))
        self.assertEqual(again["yaml"], "")
        self.assertEqual(again["host_config"], "/opt/marketing-platform/nginx/conf.d/default.conf")

    def test_cli_writes_upstream(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dump = root / "nginx-t.txt"
            dump.write_text(LIVE, encoding="utf-8")
            out = root / "default.conf"
            upstream = root / "upstream.env"
            body = root / "server-body.inc"
            code = render_config.main([
                "--mode", "bootstrap",
                "--nginx-t", str(dump),
                "--out", str(out),
                "--write-upstream", str(upstream),
                "--write-server-body", str(body),
            ])
            self.assertEqual(code, 0)
            self.assertIn("PROXY_PASS=http://app:9100", upstream.read_text(encoding="utf-8"))
            self.assertIn("location /s/", body.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
