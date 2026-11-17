-- 只记录业务实际录入的金额；旧记录不推算现金退款。
ALTER TABLE coupon_settlement
    ADD COLUMN order_amount DECIMAL(12,2) NULL,
    ADD COLUMN refund_amount DECIMAL(12,2) NULL,
    ADD COLUMN refunded_at DATETIME NULL;
CREATE INDEX idx_visit_activity_time_user ON campaign_visit (activity_id, visited_at, user_id);
CREATE INDEX idx_conversion_activity_type_time ON campaign_conversion (activity_id, event_type, occurred_at);
CREATE INDEX idx_inbox_event_time ON infra_consumer_inbox (event_id, create_time);
