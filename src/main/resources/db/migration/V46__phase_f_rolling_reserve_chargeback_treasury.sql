-- V46__phase_f_rolling_reserve_chargeback_treasury.sql
-- Phase F: Rolling Reserve Scheduling, Dispute Chargebacks, and Treasury FX Revaluation

-- 1. Settlement Rolling Reserve Tranches
CREATE TABLE IF NOT EXISTS settlement_rolling_reserve_tranche (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id             uuid NOT NULL REFERENCES merchant(id),
    currency                varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    gross_amount            bigint NOT NULL CHECK (gross_amount > 0),
    reserve_rate_bps        int NOT NULL CHECK (reserve_rate_bps >= 0 AND reserve_rate_bps <= 10000),
    reserve_amount          bigint NOT NULL CHECK (reserve_amount >= 0),
    impaired_amount         bigint NOT NULL DEFAULT 0 CHECK (impaired_amount >= 0),
    released_amount         bigint NOT NULL DEFAULT 0 CHECK (released_amount >= 0),
    status                  varchar(32) NOT NULL DEFAULT 'HELD' CHECK (status IN ('HELD', 'MATURED', 'RELEASED', 'IMPAIRED')),
    held_at                 timestamptz NOT NULL DEFAULT clock_timestamp(),
    matures_at              timestamptz NOT NULL,
    released_at             timestamptz,
    hold_journal_tx_id      uuid NOT NULL,
    release_journal_tx_id    uuid
);

CREATE INDEX IF NOT EXISTS idx_srrt_merchant ON settlement_rolling_reserve_tranche (merchant_id, status);
CREATE INDEX IF NOT EXISTS idx_srrt_maturity ON settlement_rolling_reserve_tranche (matures_at, status);

-- 2. Settlement Dispute & Chargeback Lifecycle
CREATE TABLE IF NOT EXISTS settlement_dispute_chargeback (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dispute_reference       varchar(64) NOT NULL UNIQUE,
    transaction_reference   varchar(64) NOT NULL,
    merchant_id             uuid NOT NULL REFERENCES merchant(id),
    customer_id             varchar(64),
    currency                varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    disputed_amount         bigint NOT NULL CHECK (disputed_amount > 0),
    fee_amount              bigint NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    status                  varchar(32) NOT NULL DEFAULT 'INITIATED' CHECK (status IN (
                                'INITIATED', 'EVIDENCE_SUBMITTED', 'UNDER_REVIEW', 'MERCHANT_WON', 'MERCHANT_LOST', 'ARBITRATION'
                            )),
    reason_code             varchar(32) NOT NULL,
    evidence_hash           varchar(64),
    initiated_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
    resolved_at             timestamptz,
    hold_journal_tx_id      uuid,
    resolution_journal_tx_id uuid
);

CREATE INDEX IF NOT EXISTS idx_sdc_merchant ON settlement_dispute_chargeback (merchant_id, status);
CREATE INDEX IF NOT EXISTS idx_sdc_tx_ref ON settlement_dispute_chargeback (transaction_reference);

-- 3. Treasury FX Position Snapshot
CREATE TABLE IF NOT EXISTS treasury_fx_position_snapshot (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_date           date NOT NULL,
    currency                varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    position_balance        bigint NOT NULL,
    bog_mid_rate_e4         bigint NOT NULL CHECK (bog_mid_rate_e4 > 0),
    revalued_ghs_amount     bigint NOT NULL,
    unrealized_gain_loss    bigint NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_treasury_fx_snapshot UNIQUE (snapshot_date, currency)
);

CREATE INDEX IF NOT EXISTS idx_tfps_date ON treasury_fx_position_snapshot (snapshot_date);
