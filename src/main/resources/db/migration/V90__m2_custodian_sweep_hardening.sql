-- V90__m2_custodian_sweep_hardening.sql
-- M2 Hardening:
--   1. Custodian trust balance freshness tracking (last_reconciled_at, last_statement_reference)
--   2. Platform operating account dual-control & identity metadata (verified_by_email, approved_by, cooling_off_until)
--   3. Platform revenue sweep proposal uniqueness & terminal mismatch reconciliation state
--   4. Maker-checker action_type support for OPERATING_ACCOUNT_REGISTER

ALTER TABLE custodian_trust_account
    ADD COLUMN IF NOT EXISTS last_reconciled_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_statement_reference text;

UPDATE custodian_trust_account
SET last_reconciled_at = updated_at
WHERE last_reconciled_bank_balance_pesewas IS NOT NULL
  AND last_reconciled_at IS NULL;

ALTER TABLE platform_operating_account
    ADD COLUMN IF NOT EXISTS verified_by_email text,
    ADD COLUMN IF NOT EXISTS approved_by text,
    ADD COLUMN IF NOT EXISTS cooling_off_until timestamptz;

CREATE UNIQUE INDEX IF NOT EXISTS uq_prs_proposal_id
    ON platform_revenue_sweep (proposal_id)
    WHERE proposal_id IS NOT NULL;

ALTER TABLE platform_revenue_sweep DROP CONSTRAINT IF EXISTS platform_revenue_sweep_status_check;
ALTER TABLE platform_revenue_sweep ADD CONSTRAINT platform_revenue_sweep_status_check
    CHECK (status IN ('reserved', 'submitted', 'succeeded', 'failed', 'returned', 'mismatch'));

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
        'REVENUE_SWEEP',
        'OPERATING_ACCOUNT_REGISTER'
    ));
