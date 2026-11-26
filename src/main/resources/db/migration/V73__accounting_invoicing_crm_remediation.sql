-- V73: Accounting, Invoicing & CRM Remediation
-- 1. Preserve line-level discounts on quotes (merchant_quote_item.discount_pesewas)
-- 2. Enforce idempotency on CRM loyalty earn/redeem ledger entries per (merchant_id, customer_id, idempotency_key)
-- 3. Enforce unique normalized customer phone per merchant (partial unique index when existing data permits)

ALTER TABLE merchant_quote_item
    ADD COLUMN IF NOT EXISTS discount_pesewas bigint NOT NULL DEFAULT 0;

ALTER TABLE crm_loyalty_ledger
    ADD COLUMN IF NOT EXISTS merchant_id uuid,
    ADD COLUMN IF NOT EXISTS customer_id uuid,
    ADD COLUMN IF NOT EXISTS idempotency_key varchar(200),
    ADD COLUMN IF NOT EXISTS payload_hash varchar(64),
    ADD COLUMN IF NOT EXISTS balance_after bigint,
    ADD COLUMN IF NOT EXISTS tier_after varchar(32),
    ADD COLUMN IF NOT EXISTS monetary_equivalent_pesewas bigint;

CREATE UNIQUE INDEX IF NOT EXISTS uq_crm_loyalty_ledger_idem
    ON crm_loyalty_ledger (merchant_id, customer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM crm_customer
        WHERE phone IS NOT NULL
        GROUP BY merchant_id, phone
        HAVING COUNT(*) > 1
    ) THEN
        CREATE UNIQUE INDEX IF NOT EXISTS uq_crm_customer_merchant_phone
            ON crm_customer (merchant_id, phone)
            WHERE phone IS NOT NULL;
    END IF;
END $$;
