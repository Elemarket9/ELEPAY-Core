-- Security control hardening: out-of-band destination confirmation, PIN lockout,
-- callback replay nonces, and maker-checker action types for sensitive operations.

ALTER TABLE settlement_destination
    ADD COLUMN IF NOT EXISTS oob_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS oob_expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS name_match_score integer;

ALTER TABLE customer
    ADD COLUMN IF NOT EXISTS ussd_pin_total_failures integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS ussd_pin_hard_locked boolean NOT NULL DEFAULT false;

ALTER TABLE pos_cashier
    ADD COLUMN IF NOT EXISTS pin_failures integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pin_locked_until timestamptz;

ALTER TABLE checkout
    ADD COLUMN IF NOT EXISTS payer_phone text;

ALTER TABLE maker_checker_proposal
    ADD COLUMN IF NOT EXISTS executed_at timestamptz;

CREATE TABLE IF NOT EXISTS callback_replay_nonce (
    nonce     text PRIMARY KEY,
    seen_at   timestamptz NOT NULL DEFAULT now()
);

-- Extend the action-type check so phone changes, custodian settings and module
-- changes can be proposed. The original inline check name is stable in PostgreSQL.
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
        'MERCHANT_UNFREEZE'
    ));
