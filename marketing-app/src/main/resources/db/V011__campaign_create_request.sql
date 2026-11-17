-- 创建活动按工作空间和请求编号幂等，支持响应丢失后安全续存。
CREATE TABLE IF NOT EXISTS campaign_create_request (
    id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    activity_id BIGINT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_create_request (merchant_id, idempotency_key)
);
