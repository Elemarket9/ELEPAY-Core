-- V81: Per-merchant KDS urgency thresholds (defaults 10 min warning / 15 min critical)
-- and multi-round ticket tracking for add-on kitchen runs.

CREATE TABLE IF NOT EXISTS restaurant_kds_settings (
    merchant_id      uuid PRIMARY KEY REFERENCES merchant(id),
    warning_minutes  integer NOT NULL DEFAULT 10 CHECK (warning_minutes >= 1 AND warning_minutes <= 240),
    critical_minutes integer NOT NULL DEFAULT 15 CHECK (critical_minutes > warning_minutes AND critical_minutes <= 480),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE restaurant_kds_ticket
    ADD COLUMN IF NOT EXISTS round integer NOT NULL DEFAULT 1 CHECK (round >= 1);
