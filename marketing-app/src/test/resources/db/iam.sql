CREATE TABLE IF NOT EXISTS iam_user (
    id BIGINT NOT NULL,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_iam_user_username (username)
);

CREATE TABLE IF NOT EXISTS iam_merchant (
    id BIGINT NOT NULL,
    owner_user_id BIGINT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS iam_merchant_member (
    id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_iam_member_merchant_user (merchant_id, user_id),
    KEY idx_iam_member_user_status (user_id, status)
);

CREATE TABLE IF NOT EXISTS iam_shop (
    id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    store_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_iam_shop_store_code (store_code),
    KEY idx_iam_shop_merchant_status (merchant_id, status)
);
