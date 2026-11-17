-- 第 2 批重构：将状态/类型编码从整型改为字符串枚举，并重命名 claim_policy / benefit_policy JSON 键。

-- 1. offer_plan 类型、来源、对象、状态改为字符串并映射已有数据
ALTER TABLE offer_plan
    MODIFY COLUMN issuer_scope VARCHAR(32) NOT NULL,
    MODIFY COLUMN apply_scope VARCHAR(32) NOT NULL,
    MODIFY COLUMN benefit_kind VARCHAR(32) NOT NULL,
    MODIFY COLUMN status VARCHAR(32) NOT NULL;

UPDATE offer_plan
SET issuer_scope = CASE issuer_scope
        WHEN '0' THEN 'merchant'
        WHEN '1' THEN 'platform'
        ELSE issuer_scope
    END,
    apply_scope = CASE apply_scope
        WHEN '0' THEN 'sku'
        WHEN '1' THEN 'storewide'
        ELSE apply_scope
    END,
    benefit_kind = CASE benefit_kind
        WHEN '0' THEN 'amount_off'
        WHEN '1' THEN 'amount_off_over'
        WHEN '2' THEN 'percent_off'
        ELSE benefit_kind
    END,
    status = CASE status
        WHEN '0' THEN 'open'
        WHEN '1' THEN 'closed'
        ELSE status
    END;

-- 2. offer_plan JSON 规则键转换
UPDATE offer_plan
SET claim_policy = JSON_REMOVE(
        JSON_SET(claim_policy, '$.maxClaimsPerUser', JSON_EXTRACT(claim_policy, '$.limitPerPerson')),
        '$.limitPerPerson'
    )
WHERE JSON_CONTAINS_PATH(claim_policy, 'one', '$.limitPerPerson');

UPDATE offer_plan
SET benefit_policy = JSON_REMOVE(
        JSON_SET(benefit_policy, '$.minSpend', JSON_EXTRACT(benefit_policy, '$.termsOfUse')),
        '$.termsOfUse'
    )
WHERE JSON_CONTAINS_PATH(benefit_policy, 'one', '$.termsOfUse');

UPDATE offer_plan
SET benefit_policy = JSON_REMOVE(
        JSON_SET(benefit_policy, '$.capAmount', JSON_EXTRACT(benefit_policy, '$.maximumDiscountAmount')),
        '$.maximumDiscountAmount'
    )
WHERE JSON_CONTAINS_PATH(benefit_policy, 'one', '$.maximumDiscountAmount');

UPDATE offer_plan
SET benefit_policy = JSON_REMOVE(
        JSON_SET(benefit_policy, '$.percentOff', JSON_EXTRACT(benefit_policy, '$.discountRate')),
        '$.discountRate'
    )
WHERE JSON_CONTAINS_PATH(benefit_policy, 'one', '$.discountRate');

-- 3. issued_voucher 来源、状态
ALTER TABLE issued_voucher
    MODIFY COLUMN issuer_scope VARCHAR(32) NOT NULL,
    MODIFY COLUMN status VARCHAR(32) NOT NULL;

UPDATE issued_voucher
SET issuer_scope = CASE issuer_scope
        WHEN '0' THEN 'merchant'
        WHEN '1' THEN 'platform'
        ELSE issuer_scope
    END,
    status = CASE status
        WHEN '0' THEN 'available'
        WHEN '1' THEN 'held'
        WHEN '2' THEN 'redeemed'
        WHEN '3' THEN 'expired'
        WHEN '4' THEN 'voided'
        ELSE status
    END;

-- 4. voucher_redemption 状态
ALTER TABLE voucher_redemption
    MODIFY COLUMN status VARCHAR(32) NOT NULL;

UPDATE voucher_redemption
SET status = CASE status
    WHEN '0' THEN 'held'
    WHEN '1' THEN 'voided'
    WHEN '2' THEN 'completed'
    WHEN '3' THEN 'refunded'
    ELSE status
END;

-- 5. grant_batch 状态
ALTER TABLE grant_batch
    MODIFY COLUMN status VARCHAR(32) NOT NULL;

UPDATE grant_batch
SET status = CASE status
    WHEN '0' THEN 'queued'
    WHEN '1' THEN 'running'
    WHEN '2' THEN 'done'
    WHEN '3' THEN 'partial'
    WHEN '4' THEN 'failed'
    ELSE status
END;

-- 6. claim_ticket 来源、状态
ALTER TABLE claim_ticket
    MODIFY COLUMN issuer_scope VARCHAR(32) NOT NULL,
    MODIFY COLUMN status VARCHAR(32) NOT NULL;

UPDATE claim_ticket
SET issuer_scope = CASE issuer_scope
        WHEN '0' THEN 'merchant'
        WHEN '1' THEN 'platform'
        ELSE issuer_scope
    END,
    status = CASE status
        WHEN '0' THEN 'pending'
        WHEN '1' THEN 'succeeded'
        WHEN '2' THEN 'failed'
        WHEN '3' THEN 'reserved'
        ELSE status
    END;

-- 7. offer_inventory 状态
ALTER TABLE offer_inventory
    MODIFY COLUMN status VARCHAR(32) NOT NULL;

UPDATE offer_inventory
SET status = CASE status
    WHEN '0' THEN 'open'
    WHEN '1' THEN 'frozen'
    ELSE status
END;
