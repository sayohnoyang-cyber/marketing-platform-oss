-- 活动投放与主链。不修改已经执行过的 V001—V004。

ALTER TABLE coupon_template
    ADD COLUMN issue_total INT NULL;

UPDATE coupon_template t
LEFT JOIN (
    SELECT coupon_template_id, COUNT(*) AS claimed
    FROM coupon_user
    WHERE del_flag = 0
    GROUP BY coupon_template_id
) u ON u.coupon_template_id = t.id
SET t.issue_total = t.stock + IFNULL(u.claimed, 0);

ALTER TABLE coupon_template
    MODIFY issue_total INT NOT NULL;

ALTER TABLE coupon_user
    ADD COLUMN allocation_id BIGINT NOT NULL DEFAULT 0;

ALTER TABLE coupon_claim_request
    ADD COLUMN allocation_id BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN alloc_receive_count INT NULL;

ALTER TABLE coupon_claim_request
    DROP INDEX uk_coupon_claim_request,
    ADD UNIQUE KEY uk_coupon_claim_request (user_id, coupon_template_id, allocation_id, request_id);

CREATE TABLE IF NOT EXISTS coupon_allocation (
    id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    claimed_count INT NOT NULL DEFAULT 0,
    limit_per_person INT NOT NULL,
    status TINYINT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_allocation_key (merchant_id, idempotency_key),
    KEY idx_coupon_allocation_template (template_id, status)
);

CREATE TABLE IF NOT EXISTS coupon_allocation_command (
    id BIGINT NOT NULL,
    allocation_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    target_quantity INT NOT NULL,
    limit_per_person INT NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_allocation_command (allocation_id, idempotency_key)
);

ALTER TABLE campaign_activity
    ADD COLUMN main_link_id BIGINT NULL,
    ADD COLUMN main_enabled TINYINT NOT NULL DEFAULT 1,
    ADD COLUMN budget_amount DECIMAL(12, 2) NULL;

ALTER TABLE campaign_coupon
    ADD COLUMN allocation_id BIGINT NULL;

ALTER TABLE campaign_channel
    DROP INDEX uk_campaign_channel_link,
    ADD KEY idx_campaign_channel_link (link_id);

ALTER TABLE campaign_reservation
    MODIFY channel_id BIGINT NULL;

ALTER TABLE campaign_visit
    MODIFY channel_id BIGINT NULL;

ALTER TABLE campaign_claim_intent
    ADD COLUMN allocation_id BIGINT NOT NULL DEFAULT 0,
    MODIFY channel_id BIGINT NULL;

ALTER TABLE campaign_claim_intent
    DROP INDEX uk_campaign_claim_intent,
    ADD UNIQUE KEY uk_campaign_claim_intent (user_id, template_id, allocation_id, request_id);
