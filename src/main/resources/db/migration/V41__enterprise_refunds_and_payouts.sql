-- V41__enterprise_refunds_and_payouts.sql
-- Enterprise Embedded Finance: Item-Level Refunds & External Wallet Withdrawals Engine
-- Supports full/partial item refunds, multi-merchant reversals, JumiaPay/external wallet connector refunds,
-- customer wallet withdrawals to MoMo/bank, delegated auth validation, BoG KYC tier enforcement, and duplicate prevention.

-- 1. Enterprise Refund Master Record
CREATE TABLE IF NOT EXISTS enterprise_refund (
    id                      uuid PRIMARY KEY,
    enterprise_id           uuid        NOT NULL REFERENCES enterprise(id),
    idempotency_key         text        NOT NULL UNIQUE,
    refund_reference        text        NOT NULL UNIQUE,
    order_reference         text        NOT NULL,
    external_capture_id    text,
    external_customer_id    text        NOT NULL,
    currency                char(3)     NOT NULL DEFAULT 'GHS' CHECK (currency ~ '^[A-Z]{3}$'),
    total_refund_pesewas    bigint      NOT NULL CHECK (total_refund_pesewas > 0),
    refund_destination      text        NOT NULL CHECK (refund_destination IN ('EXTERNAL_WALLET', 'DOMESTIC_MOMO', 'BANK_ACCOUNT', 'INTERNAL_WALLET')),
    destination_details     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status                  text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'REJECTED')),
    ledger_tx_id            uuid        REFERENCES journal_transaction(id),
    connector_refund_id     text,
    reason                  text,
    metadata                jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at              timestamptz NOT NULL DEFAULT now(),
    completed_at            timestamptz
);

CREATE INDEX IF NOT EXISTS idx_er_enterprise ON enterprise_refund (enterprise_id);
CREATE INDEX IF NOT EXISTS idx_er_order_ref ON enterprise_refund (order_reference);
CREATE INDEX IF NOT EXISTS idx_er_cust_id ON enterprise_refund (external_customer_id);
CREATE INDEX IF NOT EXISTS idx_er_status ON enterprise_refund (status);

-- 2. Enterprise Item-Level Refund Breakdown
CREATE TABLE IF NOT EXISTS enterprise_refund_item (
    id                              uuid PRIMARY KEY,
    refund_id                       uuid        NOT NULL REFERENCES enterprise_refund(id) ON DELETE CASCADE,
    item_reference                  text        NOT NULL,
    merchant_id                     uuid        NOT NULL,
    item_gross_pesewas              bigint      NOT NULL CHECK (item_gross_pesewas > 0),
    merchant_reversal_pesewas       bigint      NOT NULL CHECK (merchant_reversal_pesewas >= 0),
    commission_reversal_pesewas     bigint      NOT NULL CHECK (commission_reversal_pesewas >= 0),
    platform_fee_reversal_pesewas   bigint      NOT NULL DEFAULT 0 CHECK (platform_fee_reversal_pesewas >= 0),
    reserve_reversal_pesewas        bigint      NOT NULL DEFAULT 0 CHECK (reserve_reversal_pesewas >= 0),
    tax_reversal_pesewas            bigint      NOT NULL DEFAULT 0 CHECK (tax_reversal_pesewas >= 0),
    delivery_reversal_pesewas       bigint      NOT NULL DEFAULT 0 CHECK (delivery_reversal_pesewas >= 0),
    reason                          text,
    created_at                      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_eri_refund ON enterprise_refund_item (refund_id);
CREATE INDEX IF NOT EXISTS idx_eri_merchant ON enterprise_refund_item (merchant_id);
CREATE INDEX IF NOT EXISTS idx_eri_item_ref ON enterprise_refund_item (item_reference);

-- 3. Enterprise Refund Liabilities (Pending settlement balance adjustments)
CREATE TABLE IF NOT EXISTS enterprise_refund_liability (
    id                  uuid PRIMARY KEY,
    enterprise_id       uuid        NOT NULL REFERENCES enterprise(id),
    merchant_id         uuid        NOT NULL,
    refund_id           uuid        NOT NULL REFERENCES enterprise_refund(id) ON DELETE CASCADE,
    amount_pesewas      bigint      NOT NULL CHECK (amount_pesewas > 0),
    settled             boolean     NOT NULL DEFAULT false,
    settled_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_erl_merchant_settled ON enterprise_refund_liability (enterprise_id, merchant_id, settled);

-- 4. Order-Level Cumulative Refund Aggregate Tracker
CREATE TABLE IF NOT EXISTS enterprise_order_refund_aggregate (
    id                              uuid PRIMARY KEY,
    enterprise_id                   uuid        NOT NULL REFERENCES enterprise(id),
    order_reference                 text        NOT NULL,
    original_gross_pesewas          bigint      NOT NULL CHECK (original_gross_pesewas > 0),
    cumulative_refunded_pesewas     bigint      NOT NULL DEFAULT 0 CHECK (cumulative_refunded_pesewas >= 0),
    currency                        char(3)     NOT NULL DEFAULT 'GHS' CHECK (currency ~ '^[A-Z]{3}$'),
    status                          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PARTIALLY_REFUNDED', 'FULLY_REFUNDED')),
    created_at                      timestamptz NOT NULL DEFAULT now(),
    updated_at                      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (enterprise_id, order_reference)
);

CREATE INDEX IF NOT EXISTS idx_eora_order ON enterprise_order_refund_aggregate (order_reference);

-- 5. External Wallet Customer Withdrawals to MoMo/Bank Rails
CREATE TABLE IF NOT EXISTS enterprise_wallet_withdrawal (
    id                      uuid PRIMARY KEY,
    enterprise_id           uuid        NOT NULL REFERENCES enterprise(id),
    idempotency_key         text        NOT NULL UNIQUE,
    withdrawal_reference    text        NOT NULL UNIQUE,
    delegated_token_id      text,
    external_customer_id    text        NOT NULL,
    customer_name           text        NOT NULL,
    national_id             text,
    kyc_tier                integer     NOT NULL DEFAULT 1 CHECK (kyc_tier BETWEEN 1 AND 3),
    amount_pesewas          bigint      NOT NULL CHECK (amount_pesewas > 0),
    currency                char(3)     NOT NULL DEFAULT 'GHS' CHECK (currency ~ '^[A-Z]{3}$'),
    destination_rail        text        NOT NULL CHECK (destination_rail IN ('momo', 'bank')),
    destination_network     text        NOT NULL,
    destination_account     text        NOT NULL,
    payout_batch_id         uuid        REFERENCES payout_batch(id),
    payout_id               uuid        REFERENCES payout(id),
    status                  text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUBMITTED', 'SUCCEEDED', 'FAILED', 'RETURNED')),
    failure_reason          text,
    retry_of                uuid        REFERENCES enterprise_wallet_withdrawal(id),
    compliance_checked      boolean     NOT NULL DEFAULT false,
    compliance_details      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at              timestamptz NOT NULL DEFAULT now(),
    completed_at            timestamptz
);

CREATE INDEX IF NOT EXISTS idx_eww_enterprise ON enterprise_wallet_withdrawal (enterprise_id);
CREATE INDEX IF NOT EXISTS idx_eww_customer ON enterprise_wallet_withdrawal (external_customer_id);
CREATE INDEX IF NOT EXISTS idx_eww_payout ON enterprise_wallet_withdrawal (payout_id);
CREATE INDEX IF NOT EXISTS idx_eww_status ON enterprise_wallet_withdrawal (status);

-- 6. External Customer Withdrawal Daily Accumulator for Tier Limit Compliance
CREATE TABLE IF NOT EXISTS enterprise_withdrawal_daily_accumulators (
    id                          uuid PRIMARY KEY,
    enterprise_id               uuid        NOT NULL REFERENCES enterprise(id),
    external_customer_id        text        NOT NULL,
    accumulation_date           date        NOT NULL,
    total_withdrawn_pesewas     bigint      NOT NULL DEFAULT 0 CHECK (total_withdrawn_pesewas >= 0),
    transaction_count           integer     NOT NULL DEFAULT 0 CHECK (transaction_count >= 0),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (enterprise_id, external_customer_id, accumulation_date)
);

CREATE INDEX IF NOT EXISTS idx_ewda_lookup ON enterprise_withdrawal_daily_accumulators (enterprise_id, external_customer_id, accumulation_date);
