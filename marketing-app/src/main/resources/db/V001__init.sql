-- 空库初始化。活动、转化、预测和预热表留到对应任务的后续脚本。
-- 时间使用 DATETIME，应用按时区 Asia/Shanghai 读写 LocalDateTime。
-- 主键由应用雪花算法写入，不使用自增。

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
    shop_number VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_iam_shop_number (shop_number),
    KEY idx_iam_shop_merchant_status (merchant_id, status)
);

CREATE TABLE IF NOT EXISTS coupon_template (
    id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    shop_number VARCHAR(64) NOT NULL,
    name VARCHAR(256) NOT NULL,
    source TINYINT NOT NULL,
    target TINYINT NOT NULL,
    goods VARCHAR(64) NULL,
    type TINYINT NOT NULL,
    stock INT NOT NULL,
    valid_start_time DATETIME NOT NULL,
    valid_end_time DATETIME NOT NULL,
    receive_rule JSON NOT NULL,
    consume_rule JSON NOT NULL,
    status TINYINT NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    del_flag TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_coupon_template_merchant_status (merchant_id, status),
    KEY idx_coupon_template_shop_id (shop_number, id)
);

CREATE TABLE IF NOT EXISTS coupon_user (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    coupon_template_id BIGINT NOT NULL,
    receive_count INT NOT NULL,
    source TINYINT NOT NULL,
    receive_time DATETIME NOT NULL,
    valid_start_time DATETIME NOT NULL,
    valid_end_time DATETIME NOT NULL,
    status TINYINT NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    del_flag TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_user_receive (user_id, coupon_template_id, receive_count),
    KEY idx_coupon_user_user (user_id, status)
);

CREATE TABLE IF NOT EXISTS coupon_settlement (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    discount_amount DECIMAL(12, 2) NOT NULL,
    status TINYINT NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_coupon_settlement_user (user_id),
    KEY idx_coupon_settlement_coupon (coupon_id),
    KEY idx_coupon_settlement_order (order_no, coupon_id)
);

CREATE TABLE IF NOT EXISTS coupon_task (
    id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    file_path VARCHAR(512) NOT NULL,
    execute_time DATETIME NULL,
    status TINYINT NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    completion_time DATETIME NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    del_flag TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_task_batch (batch_no),
    KEY idx_coupon_task_status_time (status, execute_time)
);

CREATE TABLE IF NOT EXISTS coupon_task_fail (
    id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    row_no INT NOT NULL,
    user_id BIGINT NULL,
    reason VARCHAR(512) NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_coupon_task_fail_task (task_id, id)
);

CREATE TABLE IF NOT EXISTS coupon_template_log (
    id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    operation VARCHAR(64) NOT NULL,
    detail VARCHAR(1024) NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_coupon_template_log_time (template_id, create_time)
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
    UNIQUE KEY uk_link_group_gid (gid),
    KEY idx_link_group_merchant_status (merchant_id, status)
);

CREATE TABLE IF NOT EXISTS link (
    id BIGINT NOT NULL,
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
    UNIQUE KEY uk_link_full_del (full_short_url, del_time),
    KEY idx_link_merchant_gid (merchant_id, gid, enable_status)
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
    visit_time DATETIME NOT NULL,
    device VARCHAR(64) NULL,
    browser VARCHAR(64) NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_link_access_event (event_id),
    KEY idx_link_access_time (link_id, visit_time)
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
