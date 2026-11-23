-- Configurable, auditable merchant loyalty rules; legacy behavior is default.
CREATE TABLE IF NOT EXISTS crm_loyalty_rules (
    merchant_id uuid PRIMARY KEY REFERENCES merchant(id),
    points_per_1000_pesewas integer NOT NULL DEFAULT 1
        CHECK (points_per_1000_pesewas BETWEEN 0 AND 1000),
    pesewas_per_redeemed_point bigint NOT NULL DEFAULT 10
        CHECK (pesewas_per_redeemed_point BETWEEN 0 AND 100000),
    enabled boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now()
);
