-- Stage 2: staff attribution comes from the authenticated server actor.
-- Backward-compatible: historical orders retain NULL; never invent historic staff identities.
ALTER TABLE pos_order ADD COLUMN IF NOT EXISTS created_by_subject varchar(200);
CREATE INDEX IF NOT EXISTS idx_pos_order_staff_daily
    ON pos_order(merchant_id, created_by_subject, created_at DESC)
    WHERE created_by_subject IS NOT NULL;
