-- 一个已有库存池若绑定了多场活动，需先核实归属；不可复制库存。
ALTER TABLE campaign_offer ADD UNIQUE KEY uk_campaign_inventory (inventory_id);

ALTER TABLE offer_inventory ADD COLUMN activity_id BIGINT NOT NULL DEFAULT 0;
UPDATE offer_inventory i JOIN campaign_offer c ON c.inventory_id = i.id
SET i.activity_id = c.activity_id;
ALTER TABLE offer_inventory
    DROP INDEX uk_offer_inventory_key,
    ADD UNIQUE KEY uk_offer_inventory_key (merchant_id, activity_id, idempotency_key);
