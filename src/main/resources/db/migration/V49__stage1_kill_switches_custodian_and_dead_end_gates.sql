-- ============================================================================
-- V49__stage1_kill_switches_custodian_and_dead_end_gates.sql
-- Stage 1 Launch Readiness Consolidated Migration:
-- 1. Module Kill Switches, Payout Safety Control & Global Velocity Windows
-- 2. Custodian Bank Trust Account & Daily Outflow Backstop
-- 3. Settlement Destination 24-Hour Cooling-Off Lock & Out-of-Band Verification
-- 4. Tiered API Key Types (pk_live, sk_live, payout_live) & IP Allowlist
-- 5. Universal Maker-Checker (Dual Control) Governance Table & Triggers
-- 6. Append-Only Immutability Triggers & PostgreSQL DDL Event Trigger Protection
-- ============================================================================

-- 1. Module Kill Switches & Payout Safety Controls
CREATE TABLE IF NOT EXISTS module_kill_switch (
    module_key  varchar(64) PRIMARY KEY,
    enabled     boolean NOT NULL DEFAULT false,
    stage       integer NOT NULL DEFAULT 2,
    reason      varchar(500),
    updated_by  varchar(255) NOT NULL DEFAULT 'system',
    updated_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO module_kill_switch (module_key, enabled, stage, reason, updated_by)
VALUES
    ('lending', false, 2, 'Disabled in Stage 1 launch scope', 'system'),
    ('virtual-cards', false, 2, 'Disabled in Stage 1 launch scope', 'system'),
    ('bill-payments', false, 2, 'Disabled in Stage 1 launch scope', 'system'),
    ('agent-banking', false, 2, 'Disabled in Stage 1 launch scope', 'system'),
    ('fx-trading', false, 2, 'Disabled in Stage 1 launch scope', 'system'),
    ('pos', true, 1, 'Configurable POS payment handling kill switch', 'system'),
    ('fx', true, 1, 'Configurable FX rate/pricing kill switch', 'system'),
    ('consumer-wallet', true, 1, 'Configurable consumer W2W kill switch', 'system'),
    ('consumer-w2w', true, 1, 'Configurable consumer W2W kill switch', 'system'),
    ('ussd', true, 1, 'Configurable USSD gateway kill switch', 'system'),
    ('mandates', true, 1, 'Configurable recurring mandate kill switch', 'system')
ON CONFLICT (module_key) DO NOTHING;

CREATE TABLE IF NOT EXISTS payout_safety_control (
    id                            integer PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    emergency_frozen              boolean NOT NULL DEFAULT false,
    freeze_reason                 varchar(500),
    frozen_by                     varchar(255),
    frozen_at                     timestamptz,
    hourly_ceiling_pesewas        bigint NOT NULL DEFAULT 50000000000,
    daily_ceiling_pesewas         bigint NOT NULL DEFAULT 200000000000,
    max_single_payout_pesewas     bigint NOT NULL DEFAULT 10000000000,
    auto_freeze_on_ceiling_breach boolean NOT NULL DEFAULT true,
    updated_by                    varchar(255) NOT NULL DEFAULT 'system',
    updated_at                    timestamptz NOT NULL DEFAULT now()
);

INSERT INTO payout_safety_control (
    id, emergency_frozen, hourly_ceiling_pesewas, daily_ceiling_pesewas,
    max_single_payout_pesewas, auto_freeze_on_ceiling_breach, updated_by
) VALUES (
    1, false, 50000000000, 200000000000, 10000000000, true, 'system'
) ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS payout_global_velocity_window (
    window_type           varchar(16) NOT NULL CHECK (window_type IN ('HOURLY', 'DAILY')),
    window_start          timestamptz NOT NULL,
    total_outflow_pesewas bigint NOT NULL DEFAULT 0,
    payout_count          integer NOT NULL DEFAULT 0,
    updated_at            timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (window_type, window_start)
);

ALTER TABLE merchant
    ADD COLUMN IF NOT EXISTS payouts_frozen boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS merchant_kill_switch (
    merchant_id     uuid PRIMARY KEY,
    payouts_frozen  boolean NOT NULL DEFAULT true,
    reason          varchar(500),
    updated_by      varchar(255) NOT NULL DEFAULT 'system',
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- 2. Custodian Bank Trust Account & Daily Outflow Backstop
CREATE TABLE IF NOT EXISTS custodian_trust_account (
    id                                   uuid PRIMARY KEY,
    account_code                         varchar(64) NOT NULL UNIQUE,
    bank_code                            varchar(32) NOT NULL,
    bank_account_number                  varchar(64) NOT NULL,
    currency                             varchar(3) NOT NULL DEFAULT 'GHS',
    daily_outflow_cap_pesewas            bigint NOT NULL DEFAULT 50000000000,
    per_payout_cap_pesewas               bigint NOT NULL DEFAULT 10000000000,
    min_reserve_floor_pesewas            bigint NOT NULL DEFAULT 0,
    last_reconciled_bank_balance_pesewas bigint,
    outflow_frozen                       boolean NOT NULL DEFAULT false,
    updated_by                           varchar(128) NOT NULL DEFAULT 'system',
    updated_at                           timestamptz NOT NULL DEFAULT now()
);

INSERT INTO custodian_trust_account (
    id, account_code, bank_code, bank_account_number, currency,
    daily_outflow_cap_pesewas, per_payout_cap_pesewas, min_reserve_floor_pesewas,
    last_reconciled_bank_balance_pesewas, outflow_frozen, updated_by
) VALUES (
    '00000000-0000-0000-0000-000000000049',
    'CUSTODIAN_TRUST_PRIMARY_GHS',
    'GCB',
    '10102030405060',
    'GHS',
    50000000000,
    10000000000,
    0,
    NULL,
    false,
    'system'
) ON CONFLICT (account_code) DO NOTHING;

CREATE TABLE IF NOT EXISTS custodian_daily_outflow (
    trust_account_code       varchar(64) NOT NULL REFERENCES custodian_trust_account(account_code),
    business_date            date NOT NULL,
    currency                 varchar(3) NOT NULL DEFAULT 'GHS',
    reserved_outflow_pesewas bigint NOT NULL DEFAULT 0,
    settled_outflow_pesewas  bigint NOT NULL DEFAULT 0,
    rejected_count           integer NOT NULL DEFAULT 0,
    updated_at               timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (trust_account_code, business_date)
);

-- 3. Gate 1: Settlement Destination 24-Hour Cooling-Off Lock & Out-of-Band Verification
ALTER TABLE settlement_destination
    ADD COLUMN IF NOT EXISTS cooling_off_until timestamptz,
    ADD COLUMN IF NOT EXISTS oob_verification_status text NOT NULL DEFAULT 'confirmed'
        CHECK (oob_verification_status IN ('pending_oob', 'confirmed')),
    ADD COLUMN IF NOT EXISTS oob_token_hash text,
    ADD COLUMN IF NOT EXISTS oob_confirmed_at timestamptz,
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE payout_policy
    ADD COLUMN IF NOT EXISTS destination_cooling_off_until timestamptz;

-- 4. Gate 1: Tiered API Key Types (publishable, secret, payout) & IP Allowlist
ALTER TABLE api_credential
    ADD COLUMN IF NOT EXISTS key_type text NOT NULL DEFAULT 'secret'
        CHECK (key_type IN ('publishable', 'secret', 'payout')),
    ADD COLUMN IF NOT EXISTS allowed_ips text[] NOT NULL DEFAULT '{}';

-- 5. Gate 2: Universal Maker-Checker Dual-Control Proposals
CREATE TABLE IF NOT EXISTS maker_checker_proposal (
    id                  uuid PRIMARY KEY,
    action_type         text NOT NULL CHECK (action_type IN (
                            'LARGE_PAYOUT', 'ESCROW_RELEASE', 'FORCE_SETTLEMENT',
                            'DISPUTE_RESOLUTION', 'SETTLEMENT_RULE_CHANGE',
                            'FEE_SCHEDULE_CHANGE', 'DESTINATION_OPS_VERIFY',
                            'KILL_SWITCH_CHANGE', 'VELOCITY_CEILING_CHANGE'
                        )),
    resource_type       text NOT NULL,
    resource_id         text NOT NULL,
    merchant_id         uuid,
    payload             jsonb NOT NULL DEFAULT '{}'::jsonb,
    reason              text,
    status              text NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    proposed_by         text NOT NULL,
    proposed_at         timestamptz NOT NULL DEFAULT now(),
    reviewed_by         text,
    reviewed_at         timestamptz,
    review_note         text,
    expires_at          timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
    CHECK (reviewed_by IS NULL OR reviewed_by <> proposed_by)
);

CREATE INDEX IF NOT EXISTS idx_maker_checker_status ON maker_checker_proposal (status, proposed_at DESC);
CREATE INDEX IF NOT EXISTS idx_maker_checker_resource ON maker_checker_proposal (resource_type, resource_id);

CREATE OR REPLACE FUNCTION enforce_maker_checker_dual_control() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'Maker-checker proposal % is already finalized (%)', OLD.id, OLD.status
            USING ERRCODE = '55000';
    END IF;
    IF NEW.proposed_by <> OLD.proposed_by OR NEW.action_type <> OLD.action_type
       OR NEW.resource_type <> OLD.resource_type OR NEW.resource_id <> OLD.resource_id
       OR NEW.payload IS DISTINCT FROM OLD.payload THEN
        RAISE EXCEPTION 'Maker-checker proposal payload and proposer are immutable'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.status = 'APPROVED' AND (NEW.reviewed_by IS NULL OR NEW.reviewed_by = OLD.proposed_by) THEN
        RAISE EXCEPTION 'Dual control violation: Maker (%) cannot approve their own proposal', OLD.proposed_by
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS maker_checker_proposal_guard ON maker_checker_proposal;
CREATE TRIGGER maker_checker_proposal_guard
    BEFORE UPDATE ON maker_checker_proposal
    FOR EACH ROW EXECUTE FUNCTION enforce_maker_checker_dual_control();

DROP TRIGGER IF EXISTS maker_checker_proposal_no_delete ON maker_checker_proposal;
CREATE TRIGGER maker_checker_proposal_no_delete
    BEFORE DELETE OR TRUNCATE ON maker_checker_proposal
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

-- 6. Gate 3: Append-Only Triggers on All Remaining Audit & Financial History Tables
DO $$
DECLARE
    tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'administrative_audit_event',
        'redaction_audit',
        'webhook_delivery_log',
        'marketplace_fee_schedule_history',
        'enterprise_prefunding_ledger_entry',
        'settlement_reserve_release'
    ]
    LOOP
        IF to_regclass('public.' || tbl) IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM pg_trigger WHERE tgname = tbl || '_immutable'
        ) THEN
            EXECUTE format(
                'CREATE TRIGGER %I BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation()',
                tbl || '_immutable', tbl
            );
        END IF;
    END LOOP;
END;
$$;

-- Block ALTER TABLE ... DISABLE TRIGGER on immutable ledger and audit tables
CREATE OR REPLACE FUNCTION block_immutable_trigger_disable() RETURNS event_trigger LANGUAGE plpgsql AS $$
DECLARE
    disabled_count integer;
BEGIN
    IF current_setting('elepay.test_allow_insider_simulation', true) = 'true' THEN
        RETURN;
    END IF;

    SELECT count(*) INTO disabled_count
    FROM pg_trigger t
    JOIN pg_class c ON c.oid = t.tgrelid
    WHERE c.relname IN (
        'journal_transaction', 'journal_entry', 'ledger_account', 'ledger_account_snapshot',
        'ledger_move', 'ledger_operation', 'audit_log', 'audit_checkpoint', 'integrity_key',
        'administrative_audit_event', 'redaction_audit', 'webhook_delivery_log',
        'marketplace_fee_schedule_history', 'enterprise_prefunding_ledger_entry',
        'settlement_reserve_release', 'maker_checker_proposal'
    )
    AND (t.tgname LIKE '%_immutable' OR t.tgname IN ('ledger_account_guard', 'maker_checker_proposal_guard', 'maker_checker_proposal_no_delete'))
    AND t.tgenabled <> 'O';

    IF disabled_count > 0 THEN
        RAISE EXCEPTION 'Disabling immutability triggers on financial ledger or audit tables is strictly forbidden'
            USING ERRCODE = '42501';
    END IF;
END;
$$;

DROP EVENT TRIGGER IF EXISTS elepay_block_immutable_trigger_disable;
CREATE EVENT TRIGGER elepay_block_immutable_trigger_disable
    ON ddl_command_end
    WHEN TAG IN ('ALTER TABLE')
    EXECUTE FUNCTION block_immutable_trigger_disable();
