-- V92: Per-merchant external POS tender reference deduplication (M4 Feature #14)
-- Prevents cashier cash-skimming via external processor reference replay across orders.

ALTER TABLE pos_tender_payment
    ADD COLUMN IF NOT EXISTS merchant_id uuid;

UPDATE pos_tender_payment p
SET merchant_id = o.merchant_id
FROM pos_order o
WHERE p.order_id = o.id
  AND p.merchant_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_pos_tender_external_ref
    ON pos_tender_payment (merchant_id, tender_type, lower(trim(reference)))
    WHERE status = 'SUCCESS'
      AND reference IS NOT NULL
      AND merchant_id IS NOT NULL
      AND tender_type IN ('BANK_TRANSFER', 'EXTERNAL_MOMO', 'EXTERNAL_CARD', 'EXTERNAL_POS');
