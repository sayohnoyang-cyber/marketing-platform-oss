-- 第 1 批重构：将权益/发券域表名、列名、索引名统一为独立领域命名（保留原 V001-V011 历史迁移不变）。

-- 1. 店铺编码
ALTER TABLE iam_shop
    DROP INDEX uk_iam_shop_number,
    RENAME COLUMN shop_number TO store_code,
    ADD UNIQUE KEY uk_iam_shop_store_code (store_code);

-- 2. 权益方案（原 coupon_template）
ALTER TABLE coupon_template
    DROP INDEX idx_coupon_template_merchant_status,
    DROP INDEX idx_coupon_template_shop_id,
    RENAME COLUMN shop_number TO store_code,
    RENAME COLUMN name TO title,
    RENAME COLUMN source TO issuer_scope,
    RENAME COLUMN target TO apply_scope,
    RENAME COLUMN goods TO sku_code,
    RENAME COLUMN type TO benefit_kind,
    RENAME COLUMN stock TO inventory_qty,
    RENAME COLUMN issue_total TO issued_total,
    RENAME COLUMN valid_start_time TO usable_from,
    RENAME COLUMN valid_end_time TO usable_until,
    RENAME COLUMN receive_rule TO claim_policy,
    RENAME COLUMN consume_rule TO benefit_policy,
    ADD KEY idx_offer_plan_merchant_status (merchant_id, status),
    ADD KEY idx_offer_plan_store_id (store_code, id);

RENAME TABLE coupon_template TO offer_plan;

-- 3. 已发卡券（原 coupon_user）
ALTER TABLE coupon_user
    DROP INDEX uk_coupon_user_receive,
    DROP INDEX idx_coupon_user_user,
    RENAME COLUMN coupon_template_id TO plan_id,
    RENAME COLUMN allocation_id TO inventory_id,
    RENAME COLUMN receive_count TO claim_seq,
    RENAME COLUMN source TO issuer_scope,
    RENAME COLUMN receive_time TO granted_at,
    RENAME COLUMN valid_start_time TO usable_from,
    RENAME COLUMN valid_end_time TO usable_until,
    ADD UNIQUE KEY uk_issued_voucher_claim (user_id, plan_id, claim_seq),
    ADD KEY idx_issued_voucher_user (user_id, status);

RENAME TABLE coupon_user TO issued_voucher;

-- 4. 核销单（原 coupon_settlement）
ALTER TABLE coupon_settlement
    DROP INDEX uk_coupon_settlement_order_coupon,
    DROP INDEX idx_coupon_settlement_order,
    DROP INDEX idx_coupon_settlement_coupon,
    DROP INDEX idx_coupon_settlement_user,
    RENAME COLUMN coupon_id TO voucher_id,
    ADD UNIQUE KEY uk_voucher_redemption_order_voucher (order_no, voucher_id),
    ADD KEY idx_voucher_redemption_user (user_id),
    ADD KEY idx_voucher_redemption_voucher (voucher_id);

RENAME TABLE coupon_settlement TO voucher_redemption;

-- 5. 方案审计（原 coupon_template_log）
ALTER TABLE coupon_template_log
    DROP INDEX idx_coupon_template_log_time,
    RENAME COLUMN template_id TO plan_id,
    RENAME COLUMN operation TO action,
    RENAME COLUMN detail TO payload,
    ADD KEY idx_offer_plan_audit_time (plan_id, create_time);

RENAME TABLE coupon_template_log TO offer_plan_audit;

-- 6. 发放批次（原 coupon_task）
ALTER TABLE coupon_task
    DROP INDEX uk_coupon_task_batch,
    DROP INDEX idx_coupon_task_status_time,
    RENAME COLUMN template_id TO plan_id,
    RENAME COLUMN batch_no TO batch_code,
    RENAME COLUMN execute_time TO run_at,
    ADD UNIQUE KEY uk_grant_batch_code (batch_code),
    ADD KEY idx_grant_batch_status_time (status, run_at);

RENAME TABLE coupon_task TO grant_batch;

-- 7. 发放批次失败明细（原 coupon_task_fail）
ALTER TABLE coupon_task_fail
    DROP INDEX uk_coupon_task_fail_row,
    DROP INDEX idx_coupon_task_fail_task,
    RENAME COLUMN task_id TO batch_id,
    ADD UNIQUE KEY uk_grant_batch_error_row (batch_id, row_no),
    ADD KEY idx_grant_batch_error_batch (batch_id, id);

RENAME TABLE coupon_task_fail TO grant_batch_error;

-- 8. 方案库存（原 coupon_allocation）
ALTER TABLE coupon_allocation
    DROP INDEX uk_coupon_allocation_key,
    DROP INDEX idx_coupon_allocation_template,
    RENAME COLUMN template_id TO plan_id,
    ADD UNIQUE KEY uk_offer_inventory_key (merchant_id, idempotency_key),
    ADD KEY idx_offer_inventory_plan (plan_id, status);

RENAME TABLE coupon_allocation TO offer_inventory;

-- 9. 方案库存命令（原 coupon_allocation_command）
ALTER TABLE coupon_allocation_command
    DROP INDEX uk_coupon_allocation_command,
    RENAME COLUMN allocation_id TO inventory_id,
    ADD UNIQUE KEY uk_offer_inventory_command (inventory_id, idempotency_key);

RENAME TABLE coupon_allocation_command TO offer_inventory_command;

-- 10. 领取票据（原 coupon_claim_request）
ALTER TABLE coupon_claim_request
    DROP INDEX uk_coupon_claim_request,
    RENAME COLUMN coupon_template_id TO plan_id,
    RENAME COLUMN allocation_id TO inventory_id,
    RENAME COLUMN source TO issuer_scope,
    RENAME COLUMN receive_count TO claim_seq,
    RENAME COLUMN user_coupon_id TO voucher_id,
    ADD UNIQUE KEY uk_claim_ticket_req (user_id, plan_id, inventory_id, request_id);

RENAME TABLE coupon_claim_request TO claim_ticket;

-- 11. 活动关联权益（原 campaign_coupon）
ALTER TABLE campaign_coupon
    DROP INDEX uk_campaign_coupon,
    RENAME COLUMN template_id TO plan_id,
    RENAME COLUMN shop_number TO store_code,
    RENAME COLUMN allocation_id TO inventory_id,
    ADD UNIQUE KEY uk_campaign_offer (activity_id, plan_id);

RENAME TABLE campaign_coupon TO campaign_offer;

-- 12. 活动领取意向与转化外键列
ALTER TABLE campaign_claim_intent
    DROP INDEX uk_campaign_claim_intent,
    RENAME COLUMN template_id TO plan_id,
    RENAME COLUMN allocation_id TO inventory_id,
    RENAME COLUMN user_coupon_id TO voucher_id,
    ADD UNIQUE KEY uk_campaign_claim_intent (user_id, plan_id, inventory_id, request_id);

ALTER TABLE campaign_conversion
    RENAME COLUMN user_coupon_id TO voucher_id;
