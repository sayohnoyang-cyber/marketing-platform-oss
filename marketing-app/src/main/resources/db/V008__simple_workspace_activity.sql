-- 简单版：账号直接拥有工作空间，不再依赖成员关系或店铺创建券。
-- 保留既有商家/成员/店铺数据与 merchant_id 列名，兼容已有账号和历史业务。
ALTER TABLE iam_merchant ADD COLUMN owner_user_id BIGINT NULL,
    ADD UNIQUE KEY uk_workspace_owner (owner_user_id);
ALTER TABLE coupon_template MODIFY COLUMN shop_number VARCHAR(64) NULL;
ALTER TABLE campaign_coupon MODIFY COLUMN shop_number VARCHAR(64) NULL;
ALTER TABLE campaign_activity ADD COLUMN activity_type VARCHAR(32) NOT NULL DEFAULT '优惠券推广活动';
