-- T04—T07 增量。已有空库先执行 V001。

CREATE TABLE IF NOT EXISTS coupon_claim_request (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    coupon_template_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    source TINYINT NOT NULL,
    status TINYINT NOT NULL,
    receive_count INT NULL,
    user_coupon_id BIGINT NULL,
    redis_applied TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_claim_request (user_id, coupon_template_id, request_id)
);

ALTER TABLE coupon_settlement
    ADD UNIQUE KEY uk_coupon_settlement_order_coupon (order_no, coupon_id);

ALTER TABLE coupon_task
    ADD COLUMN total_count INT NOT NULL DEFAULT 0,
    ADD COLUMN success_count INT NOT NULL DEFAULT 0,
    ADD COLUMN fail_count INT NOT NULL DEFAULT 0,
    ADD COLUMN owner_token VARCHAR(64) NULL,
    ADD COLUMN lease_until DATETIME NULL;

ALTER TABLE coupon_task_fail
    ADD UNIQUE KEY uk_coupon_task_fail_row (task_id, row_no);

CREATE TABLE IF NOT EXISTS infra_outbox_event (
    id BIGINT NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    payload VARCHAR(4000) NOT NULL,
    status TINYINT NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME NOT NULL,
    last_error VARCHAR(512) NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event (event_id),
    KEY idx_outbox_due (status, next_retry_at)
);

CREATE TABLE IF NOT EXISTS infra_consumer_inbox (
    id BIGINT NOT NULL,
    consumer_group VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbox_event (consumer_group, event_id)
);

CREATE TABLE IF NOT EXISTS link_create_request (
    id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    link_id BIGINT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_link_create_request (merchant_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS link_stats_visitor (
    id BIGINT NOT NULL,
    link_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    visitor_id VARCHAR(64) NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_link_visitor (link_id, stat_date, visitor_id)
);
