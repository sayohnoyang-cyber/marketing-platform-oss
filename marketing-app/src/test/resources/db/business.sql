CREATE TABLE IF NOT EXISTS offer_plan (
    id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    store_code VARCHAR(64) NULL,
    title VARCHAR(256) NOT NULL,
    issuer_scope VARCHAR(32) NOT NULL,
    apply_scope VARCHAR(32) NOT NULL,
    sku_code VARCHAR(64) NULL,
    benefit_kind VARCHAR(32) NOT NULL,
    inventory_qty INT NOT NULL,
    issued_total INT NOT NULL,
    usable_from DATETIME NOT NULL,
    usable_until DATETIME NOT NULL,
    claim_policy VARCHAR(2000) NOT NULL,
    benefit_policy VARCHAR(2000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    del_flag TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS issued_voucher (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    inventory_id BIGINT NOT NULL DEFAULT 0,
    claim_seq INT NOT NULL,
    issuer_scope VARCHAR(32) NOT NULL,
    granted_at DATETIME NOT NULL,
    usable_from DATETIME NOT NULL,
    usable_until DATETIME NOT NULL,
    status VARCHAR(32) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    del_flag TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_issued_voucher_claim (user_id, plan_id, claim_seq)
);

CREATE TABLE IF NOT EXISTS voucher_redemption (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    voucher_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    discount_amount DECIMAL(12, 2) NOT NULL,
    order_amount DECIMAL(12, 2) NULL,
    refund_amount DECIMAL(12, 2) NULL,
    refunded_at DATETIME NULL,
    status VARCHAR(32) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_voucher_redemption_order_voucher (order_no, voucher_id)
);

CREATE TABLE IF NOT EXISTS claim_ticket (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    inventory_id BIGINT NOT NULL DEFAULT 0,
    request_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    issuer_scope VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    claim_seq INT NULL,
    alloc_receive_count INT NULL,
    voucher_id BIGINT NULL,
    redis_applied TINYINT NOT NULL DEFAULT 0,
    reservation_token VARCHAR(64) NULL,
    lease_until DATETIME NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_claim_ticket_req (user_id, plan_id, inventory_id, request_id)
);

CREATE TABLE IF NOT EXISTS grant_batch (
    id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    batch_code VARCHAR(64) NOT NULL,
    file_path VARCHAR(512) NOT NULL,
    run_at DATETIME NULL,
    status VARCHAR(32) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    total_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    fail_count INT NOT NULL DEFAULT 0,
    owner_token VARCHAR(64) NULL,
    lease_until DATETIME NULL,
    completion_time DATETIME NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    del_flag TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_grant_batch_code (batch_code)
);

CREATE TABLE IF NOT EXISTS grant_batch_error (
    id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    row_no INT NOT NULL,
    user_id BIGINT NULL,
    reason VARCHAR(512) NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_grant_batch_error_row (batch_id, row_no)
);

CREATE TABLE IF NOT EXISTS offer_plan_audit (
    id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    payload VARCHAR(1024) NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS link_group (
    id BIGINT NOT NULL,
    gid VARCHAR(32) NOT NULL,
    merchant_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    del_flag TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_link_group_gid (gid)
);

CREATE TABLE IF NOT EXISTS link (
    activity_id BIGINT NULL,
    id BIGINT NOT NULL,
    name VARCHAR(128) NULL,
    merchant_id BIGINT NOT NULL,
    gid VARCHAR(32) NOT NULL,
    domain VARCHAR(128) NOT NULL,
    short_uri VARCHAR(16) NOT NULL,
    full_short_url VARCHAR(256) NOT NULL,
    origin_url VARCHAR(2048) NOT NULL,
    valid_date DATETIME NULL,
    enable_status TINYINT NOT NULL DEFAULT 0,
    del_time BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    del_flag TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_link_full_del (full_short_url, del_time)
);

CREATE TABLE IF NOT EXISTS link_goto (
    id BIGINT NOT NULL,
    full_short_url VARCHAR(256) NOT NULL,
    gid VARCHAR(32) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_link_goto_full (full_short_url)
);

CREATE TABLE IF NOT EXISTS link_access_log (
    id BIGINT NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    link_id BIGINT NOT NULL,
    visitor_id VARCHAR(64) NULL,
    channel_code VARCHAR(32) NULL,
    visit_time DATETIME NOT NULL,
    device VARCHAR(64) NULL,
    browser VARCHAR(64) NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_link_access_event (event_id)
);

CREATE TABLE IF NOT EXISTS link_stats_daily (
    id BIGINT NOT NULL,
    link_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    pv BIGINT NOT NULL DEFAULT 0,
    uv BIGINT NOT NULL DEFAULT 0,
    uip BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_link_stats_daily (link_id, stat_date)
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
    UNIQUE KEY uk_outbox_event (event_id)
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

CREATE TABLE IF NOT EXISTS campaign_activity (
    id BIGINT NOT NULL,
    activity_type VARCHAR(32) NOT NULL DEFAULT '优惠券推广活动',
    merchant_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    warmup_start_time DATETIME NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    status TINYINT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    main_link_id BIGINT NULL,
    main_enabled TINYINT NOT NULL DEFAULT 1,
    budget_amount DECIMAL(12, 2) NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS campaign_offer (
    id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    store_code VARCHAR(64) NULL,
    inventory_id BIGINT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_offer (activity_id, plan_id)
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
    UNIQUE KEY uk_campaign_channel_code (activity_id, channel_code)
);

CREATE TABLE IF NOT EXISTS campaign_reservation (
    id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    channel_id BIGINT NULL,
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
    channel_id BIGINT NULL,
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
    voucher_id BIGINT NULL,
    settlement_id BIGINT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at DATETIME NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_conversion_event (event_id)
);

CREATE TABLE IF NOT EXISTS campaign_claim_intent (
    id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    inventory_id BIGINT NOT NULL DEFAULT 0,
    channel_id BIGINT NULL,
    visit_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    voucher_id BIGINT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_claim_intent (user_id, plan_id, inventory_id, request_id)
);

CREATE TABLE IF NOT EXISTS offer_inventory (
    id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    claimed_count INT NOT NULL DEFAULT 0,
    max_claims_per_user INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_offer_inventory_key (merchant_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS offer_inventory_command (
    id BIGINT NOT NULL,
    inventory_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    target_quantity INT NOT NULL,
    max_claims_per_user INT NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_offer_inventory_command (inventory_id, idempotency_key)
);

-- Durable predictions, preparation results and applied policies.
CREATE TABLE IF NOT EXISTS campaign_operation (
 id BIGINT NOT NULL PRIMARY KEY,
 merchant_id BIGINT NOT NULL,
 activity_id BIGINT NOT NULL,
 kind VARCHAR(16) NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 configuration_hash CHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL,
 execution_token VARCHAR(64) NULL,
 payload_json CLOB NOT NULL,
 create_time DATETIME NOT NULL,
 update_time DATETIME NOT NULL,
 CONSTRAINT uk_campaign_operation_request UNIQUE (activity_id, kind, request_key)
);
CREATE TABLE IF NOT EXISTS campaign_capacity_baseline (
 id BIGINT NOT NULL PRIMARY KEY,
 merchant_id BIGINT NOT NULL,
 deployment_id VARCHAR(128) NOT NULL,
 report_name VARCHAR(256) NOT NULL,
 endpoint VARCHAR(64) NOT NULL,
 sustained_qps INT NOT NULL,
 p95_millis INT NOT NULL,
 p99_millis INT NOT NULL,
 error_rate DECIMAL(8,6) NOT NULL,
 duration_seconds INT NOT NULL,
 sample_count INT NOT NULL,
 measured_at DATETIME NOT NULL,
 expires_at DATETIME NOT NULL,
 create_time DATETIME NOT NULL,
 update_time DATETIME NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_campaign_visit_metrics ON campaign_visit (activity_id, channel_id, valid_flag, visitor_id);
CREATE INDEX IF NOT EXISTS idx_link_access_visitor ON link_access_log (link_id, visitor_id);

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
