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
 payload_json LONGTEXT NOT NULL,
 create_time DATETIME NOT NULL,
 update_time DATETIME NOT NULL,
 UNIQUE KEY uk_campaign_operation_request (activity_id, kind, request_key),
 KEY idx_campaign_operation_latest (activity_id, kind, create_time)
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
 update_time DATETIME NOT NULL,
 KEY idx_capacity_merchant (merchant_id, deployment_id, expires_at)
);
CREATE INDEX idx_campaign_visit_metrics ON campaign_visit (activity_id, channel_id, valid_flag, visitor_id);
CREATE INDEX idx_link_access_visitor ON link_access_log (link_id, visitor_id);
