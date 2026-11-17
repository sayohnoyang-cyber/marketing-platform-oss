CREATE TABLE IF NOT EXISTS campaign_activity (
    id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    warmup_start_time DATETIME NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    status TINYINT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_campaign_activity_merchant (merchant_id, status)
);

CREATE TABLE IF NOT EXISTS campaign_coupon (
    id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    shop_number VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_coupon (activity_id, template_id)
);

CREATE TABLE IF NOT EXISTS campaign_channel (
    id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    channel_code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    link_id BIGINT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_channel_key (activity_id, idempotency_key),
    UNIQUE KEY uk_campaign_channel_code (activity_id, channel_code),
    UNIQUE KEY uk_campaign_channel_link (link_id)
);

CREATE TABLE IF NOT EXISTS campaign_reservation (
    id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    status TINYINT NOT NULL,
    reserved_at DATETIME NOT NULL,
    cancelled_at DATETIME NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_reservation (activity_id, user_id)
);

CREATE TABLE IF NOT EXISTS campaign_visit (
    id BIGINT NOT NULL,
    visit_token_hash CHAR(64) NOT NULL,
    activity_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    visitor_id VARCHAR(64) NULL,
    user_id BIGINT NULL,
    visited_at DATETIME NOT NULL,
    expire_at DATETIME NOT NULL,
    valid_flag TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_visit_token (visit_token_hash)
);

CREATE TABLE IF NOT EXISTS campaign_conversion (
    id BIGINT NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    activity_id BIGINT NOT NULL,
    channel_id BIGINT NULL,
    visit_id BIGINT NULL,
    user_id BIGINT NOT NULL,
    user_coupon_id BIGINT NULL,
    settlement_id BIGINT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at DATETIME NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_conversion_event (event_id),
    KEY idx_campaign_conversion_channel (activity_id, channel_id, occurred_at)
);

CREATE TABLE IF NOT EXISTS campaign_claim_intent (
    id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    visit_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    user_coupon_id BIGINT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_claim_intent (user_id, template_id, request_id)
);
