-- 为短链访问明细增加渠道标识，支持活动主链共享短码下的“短链点击 -> 落地页到达 -> 预约 -> 领取 -> 核销”五级漏斗归因。
ALTER TABLE link_access_log
    ADD COLUMN channel_code VARCHAR(32) NULL AFTER visitor_id,
    ADD KEY idx_link_access_channel (link_id, channel_code);
