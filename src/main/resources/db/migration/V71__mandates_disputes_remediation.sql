-- V71: Mandate billing frequency tracking & dispute rolling-reserve restoration

ALTER TABLE mandate
    ADD COLUMN IF NOT EXISTS last_charged_at timestamptz,
    ADD COLUMN IF NOT EXISTS next_billing_at timestamptz;

ALTER TABLE mandate DROP CONSTRAINT IF EXISTS mandate_frequency_check;
ALTER TABLE mandate ADD CONSTRAINT mandate_frequency_check
    CHECK (frequency IN ('daily', 'weekly', 'biweekly', 'monthly', 'quarterly', 'yearly', 'ad_hoc'));

UPDATE mandate m
SET last_charged_at = sub.last_at,
    next_billing_at = CASE m.frequency
        WHEN 'daily'     THEN sub.last_at + interval '1 day'
        WHEN 'weekly'    THEN sub.last_at + interval '7 days'
        WHEN 'biweekly'  THEN sub.last_at + interval '14 days'
        WHEN 'monthly'   THEN sub.last_at + interval '1 month'
        WHEN 'quarterly' THEN sub.last_at + interval '3 months'
        WHEN 'yearly'    THEN sub.last_at + interval '1 year'
        ELSE NULL
    END
FROM (
    SELECT mandate_id, max(created_at) AS last_at
    FROM mandate_charge
    WHERE status = 'succeeded'
    GROUP BY mandate_id
) sub
WHERE m.id = sub.mandate_id
  AND m.last_charged_at IS NULL;

ALTER TABLE dispute
    ADD COLUMN IF NOT EXISTS seller_reserved_part bigint NOT NULL DEFAULT 0 CHECK (seller_reserved_part >= 0);
