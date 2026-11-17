-- 第 5 批重构：将 offer_inventory 与 offer_inventory_command 的 limit_per_person 统一改为 max_claims_per_user。

ALTER TABLE offer_inventory
    RENAME COLUMN limit_per_person TO max_claims_per_user;

ALTER TABLE offer_inventory_command
    RENAME COLUMN limit_per_person TO max_claims_per_user;
