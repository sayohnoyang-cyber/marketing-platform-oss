    root __ROOT__;
    index index.html;
    client_max_body_size __CLIENT_MAX_BODY_SIZE__;

    location /assets/ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }

    location /api/ {
        proxy_pass __PROXY_PASS__;
    }

    location /s/ {
        proxy_pass __PROXY_PASS__;
    }

    location / {
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "no-cache";
    }
