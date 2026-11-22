-- V40__enterprise_managed_accounts.sql
-- Enterprise Embedded Finance & Managed Accounts Engine
-- Supports auto-provisioning, deterministic external identity mapping, duplicate tracking,
-- tier limit synchronization, and multi-subaccount ledger tracking (operating, holding, reserved, rewards).

-- 1. Extend ledger_account constraints for enterprise managed accounts and subaccount buckets
ALTER TABLE ledger_account DROP CONSTRAINT IF EXISTS ledger_account_bucket_check;
ALTER TABLE ledger_account ADD CONSTRAINT ledger_account_bucket_check CHECK (bucket IN (
    'available', 'pending', 'reserved', 'clearing', 'revenue', 'suspense', 'refunds_in_flight', 'disputes',
    'rail_fees', 'fx_clearing', 'fx_in_flight', 'deferred_revenue', 'revenue_earned',
    'operating', 'holding', 'rewards'
));

ALTER TABLE ledger_account DROP CONSTRAINT IF EXISTS ledger_account_owner_type_check;
ALTER TABLE ledger_account ADD CONSTRAINT ledger_account_owner_type_check CHECK (owner_type IN (
    'customer', 'merchant', 'system', 'enterprise', 'enterprise_managed'
));

-- 2. Enterprise Organization / Client Registry
CREATE TABLE IF NOT EXISTS enterprise (
    id                  uuid PRIMARY KEY,
    code                text        NOT NULL UNIQUE,
    name                text        NOT NULL,
    status              text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'inactive')),
    contact_email       text,
    contact_phone       text,
    webhook_url         text,
    kyc_status          text        NOT NULL DEFAULT 'verified' CHECK (kyc_status IN ('pending', 'verified', 'rejected')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- 3. Enterprise KYC Tier Limits Configuration
CREATE TABLE IF NOT EXISTS enterprise_tier_limit (
    id                     uuid PRIMARY KEY,
    enterprise_id          uuid        NOT NULL REFERENCES enterprise(id) ON DELETE CASCADE,
    kyc_tier               integer     NOT NULL CHECK (kyc_tier BETWEEN 1 AND 3),
    max_balance            bigint      NOT NULL CHECK (max_balance >= 0),
    daily_limit            bigint      NOT NULL CHECK (daily_limit >= 0),
    monthly_limit          bigint      NOT NULL CHECK (monthly_limit >= 0),
    per_transaction_limit  bigint      NOT NULL CHECK (per_transaction_limit >= 0),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (enterprise_id, kyc_tier)
);

-- 4. Enterprise Managed Account (Core subaccount entity)
CREATE TABLE IF NOT EXISTS enterprise_managed_account (
    id                     uuid PRIMARY KEY,
    enterprise_id          uuid        NOT NULL REFERENCES enterprise(id),
    external_customer_id   text        NOT NULL,
    account_type           text        NOT NULL DEFAULT 'customer' CHECK (account_type IN ('customer', 'merchant', 'subaccount')),
    display_name           text        NOT NULL,
    phone                  text,
    email                  text,
    national_id            text,
    kyc_tier               integer     NOT NULL DEFAULT 1 CHECK (kyc_tier BETWEEN 1 AND 3),
    status                 text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'closed', 'flagged_duplicate')),
    currency               char(3)     NOT NULL DEFAULT 'GHS' CHECK (currency ~ '^[A-Z]{3}$'),
    metadata               jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (enterprise_id, external_customer_id)
);

CREATE INDEX IF NOT EXISTS idx_ema_enterprise ON enterprise_managed_account (enterprise_id);
CREATE INDEX IF NOT EXISTS idx_ema_phone ON enterprise_managed_account (phone) WHERE phone IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ema_email ON enterprise_managed_account (lower(email)) WHERE email IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ema_national_id ON enterprise_managed_account (national_id) WHERE national_id IS NOT NULL;

-- 5. Deterministic External Identity Mapping
CREATE TABLE IF NOT EXISTS enterprise_identity_mapping (
    id                     uuid PRIMARY KEY,
    enterprise_id          uuid        NOT NULL REFERENCES enterprise(id) ON DELETE CASCADE,
    external_customer_id   text        NOT NULL,
    elepay_account_id      uuid        NOT NULL REFERENCES enterprise_managed_account(id) ON DELETE CASCADE,
    id_hash                text        NOT NULL UNIQUE,
    mapped_at              timestamptz NOT NULL DEFAULT now(),
    UNIQUE (enterprise_id, external_customer_id)
);

CREATE INDEX IF NOT EXISTS idx_eim_elepay_acc ON enterprise_identity_mapping (elepay_account_id);

-- 6. Duplicate Account Tracking
CREATE TABLE IF NOT EXISTS enterprise_duplicate_tracking (
    id                     uuid PRIMARY KEY,
    enterprise_id          uuid        NOT NULL REFERENCES enterprise(id),
    account_id             uuid        NOT NULL REFERENCES enterprise_managed_account(id) ON DELETE CASCADE,
    matched_account_id     uuid        NOT NULL REFERENCES enterprise_managed_account(id) ON DELETE CASCADE,
    match_field            text        NOT NULL CHECK (match_field IN ('phone', 'email', 'national_id')),
    match_value            text        NOT NULL,
    resolution             text        NOT NULL DEFAULT 'flagged' CHECK (resolution IN ('flagged', 'merged', 'dismissed', 'blocked')),
    resolution_notes       text,
    detected_at            timestamptz NOT NULL DEFAULT now(),
    resolved_at            timestamptz
);

CREATE INDEX IF NOT EXISTS idx_edt_account ON enterprise_duplicate_tracking (account_id);
CREATE INDEX IF NOT EXISTS idx_edt_matched ON enterprise_duplicate_tracking (matched_account_id);
