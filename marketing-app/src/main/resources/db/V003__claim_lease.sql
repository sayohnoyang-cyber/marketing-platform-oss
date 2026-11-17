ALTER TABLE coupon_claim_request
    ADD COLUMN reservation_token VARCHAR(64) NULL,
    ADD COLUMN lease_until DATETIME NULL;
