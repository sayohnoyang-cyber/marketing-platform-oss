-- 外部短链可按活动归档；不改变活动主链和渠道的跳转目的。
ALTER TABLE link ADD COLUMN activity_id BIGINT NULL;
CREATE INDEX idx_link_merchant_activity ON link (merchant_id, activity_id);
