-- New disputes retain the exact original reserve contribution for merchant-win restoration.
-- Historical dispute provenance is unknown; leave old rows at zero for reconciliation.
ALTER TABLE dispute ADD COLUMN seller_from_reserve bigint NOT NULL DEFAULT 0
    CHECK (seller_from_reserve >= 0 AND seller_from_reserve <= seller_part);
