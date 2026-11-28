-- V87__platform_revenue_sweep.sql
-- Dual-controlled ELEPAY platform earned-revenue sweep from Custodian Trust Account
-- to ELEPAY's pre-approved Corporate Operating Bank Account.

-- 1. Pre-approved ELEPAY Corporate Operating Bank Accounts (destinations for revenue sweeps)
CREATE TABLE IF NOT EXISTS platform_operating_account (
    id             uuid PRIMARY KEY,
    bank_code      varchar(20) NOT NULL CHECK (bank_code ~ '^[A-Z0-9]{2,20}$'),
    account_number varchar(34) NOT NULL CHECK (account_number ~ '^[0-9]{6,34}$'),
    account_name   varchar(150) NOT NULL,
    currency       char(3)     NOT NULL DEFAULT 'GHS' CHECK (currency ~ '^[A-Z]{3}$'),
    status         varchar(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    verified_by    text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (bank_code, account_number, currency)
);

CREATE INDEX IF NOT EXISTS idx_poa_currency_status
    ON platform_operating_account (currency, status);

-- 2. Platform Revenue Sweep Ledger & Lifecycle Table
CREATE TABLE IF NOT EXISTS platform_revenue_sweep (
    id                      uuid PRIMARY KEY,
    reference               text        NOT NULL UNIQUE,
    operating_account_id    uuid        NOT NULL REFERENCES platform_operating_account(id),
    currency                char(3)     NOT NULL DEFAULT 'GHS' CHECK (currency ~ '^[A-Z]{3}$'),
    amount_pesewas          bigint      NOT NULL CHECK (amount_pesewas > 0),
    revenue_buffer_pesewas  bigint      NOT NULL CHECK (revenue_buffer_pesewas >= 0),
    trust_headroom_pesewas  bigint      NOT NULL CHECK (trust_headroom_pesewas >= 0),
    status                  varchar(16) NOT NULL CHECK (status IN ('reserved', 'submitted', 'succeeded', 'failed', 'returned')),
    reservation_tx          uuid        NOT NULL REFERENCES journal_transaction(id),
    completion_tx           uuid        REFERENCES journal_transaction(id),
    return_tx               uuid        REFERENCES journal_transaction(id),
    rail_tx                 uuid        NOT NULL REFERENCES rail_transaction(id),
    proposal_id             uuid        REFERENCES maker_checker_proposal(id),
    requested_by            text        NOT NULL,
    approved_by             text        NOT NULL,
    failure_reason          text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    completed_at            timestamptz
);

CREATE INDEX IF NOT EXISTS idx_prs_status_created
    ON platform_revenue_sweep (status, created_at DESC);

-- 3. Non-negative system holding account for in-flight revenue sweeps
INSERT INTO ledger_account (code, owner_type, bucket, currency, allow_negative)
VALUES ('system:revenue_sweep_reserved:GHS', 'system', 'reserved', 'GHS', false)
ON CONFLICT (code) DO NOTHING;

-- 4. Allow 'REVENUE_SWEEP' in Maker-Checker dual-control proposals
ALTER TABLE maker_checker_proposal DROP CONSTRAINT IF EXISTS maker_checker_proposal_action_type_check;
ALTER TABLE maker_checker_proposal ADD CONSTRAINT maker_checker_proposal_action_type_check
    CHECK (action_type IN (
        'LARGE_PAYOUT',
        'ESCROW_RELEASE',
        'FORCE_SETTLEMENT',
        'DISPUTE_RESOLUTION',
        'SETTLEMENT_RULE_CHANGE',
        'FEE_SCHEDULE_CHANGE',
        'DESTINATION_OPS_VERIFY',
        'KILL_SWITCH_CHANGE',
        'VELOCITY_CEILING_CHANGE',
        'PHONE_CHANGE',
        'CUSTODIAN_CONFIG',
        'MODULE_ENABLE',
        'MERCHANT_UNFREEZE',
        'REVENUE_SWEEP'
    ));
