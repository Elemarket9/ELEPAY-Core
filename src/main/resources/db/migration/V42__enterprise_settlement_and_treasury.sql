-- V42__enterprise_settlement_and_treasury.sql
-- Enterprise Embedded Finance: Conditional Settlement Release, Enterprise Treasury & Universal Connectors
-- Supports configurable settlement rules & schedules, delivery-conditioned escrow holds, missing delivery handling,
-- enterprise prefunding management, designated safeguarded settlement accounts, funding shortfall detection & holds,
-- liquidity forecasting, and universal connector framework configurations.

-- 1. Enterprise Settlement Rules & Schedules
CREATE TABLE IF NOT EXISTS enterprise_settlement_rule (
    id                          uuid PRIMARY KEY,
    enterprise_id               uuid        NOT NULL REFERENCES enterprise(id) ON DELETE CASCADE,
    merchant_id                 uuid,
    schedule_type               text        NOT NULL CHECK (schedule_type IN (
                                                'IMMEDIATE', 'SCHEDULED', 'DAILY', 'WEEKLY',
                                                'ENTERPRISE_TRIGGERED', 'MERCHANT_REQUESTED',
                                                'THRESHOLD', 'PARTIAL', 'BATCH'
                                            )),
    settlement_timing_days      integer     NOT NULL DEFAULT 0 CHECK (settlement_timing_days >= 0),
    min_threshold_pesewas       bigint      NOT NULL DEFAULT 0 CHECK (min_threshold_pesewas >= 0),
    delivery_condition_required boolean     NOT NULL DEFAULT true,
    delivery_timeout_days       integer     NOT NULL DEFAULT 14 CHECK (delivery_timeout_days >= 1),
    net_gross_mode              text        NOT NULL DEFAULT 'NET' CHECK (net_gross_mode IN ('NET', 'GROSS')),
    currency                    char(3)     NOT NULL DEFAULT 'GHS' CHECK (currency ~ '^[A-Z]{3}$'),
    auto_payout_enabled         boolean     NOT NULL DEFAULT true,
    status                      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED', 'DISABLED')),
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_esr_enterprise ON enterprise_settlement_rule (enterprise_id);
CREATE INDEX IF NOT EXISTS idx_esr_merchant ON enterprise_settlement_rule (enterprise_id, merchant_id);

-- 2. Enterprise Delivery Escrow Ledger
CREATE TABLE IF NOT EXISTS enterprise_delivery_escrow (
    id                      uuid PRIMARY KEY,
    enterprise_id           uuid        NOT NULL REFERENCES enterprise(id),
    order_reference         text        NOT NULL,
    item_reference          text        NOT NULL,
    merchant_id             uuid        NOT NULL,
    gross_amount_pesewas    bigint      NOT NULL CHECK (gross_amount_pesewas > 0),
    net_payable_pesewas     bigint      NOT NULL CHECK (net_payable_pesewas >= 0),
    commission_pesewas      bigint      NOT NULL DEFAULT 0 CHECK (commission_pesewas >= 0),
    currency                char(3)     NOT NULL DEFAULT 'GHS' CHECK (currency ~ '^[A-Z]{3}$'),
    delivery_status         text        NOT NULL DEFAULT 'PENDING' CHECK (delivery_status IN (
                                            'PENDING', 'DELIVERED', 'MISSING_CONFIRMATION', 'REJECTED', 'CANCELLED', 'DISPUTED'
                                        )),
    escrow_status           text        NOT NULL DEFAULT 'HELD' CHECK (escrow_status IN (
                                            'HELD', 'RELEASED', 'REFUNDED', 'DISPUTED'
                                        )),
    delivered_at            timestamptz,
    released_at             timestamptz,
    ledger_hold_tx_id       uuid        REFERENCES journal_transaction(id),
    ledger_release_tx_id    uuid        REFERENCES journal_transaction(id),
    notes                   text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    UNIQUE (enterprise_id, order_reference, item_reference)
);

CREATE INDEX IF NOT EXISTS idx_ede_order ON enterprise_delivery_escrow (order_reference);
CREATE INDEX IF NOT EXISTS idx_ede_merchant ON enterprise_delivery_escrow (enterprise_id, merchant_id);
CREATE INDEX IF NOT EXISTS idx_ede_status ON enterprise_delivery_escrow (delivery_status, escrow_status);

-- 3. Enterprise Settlement Batch
CREATE TABLE IF NOT EXISTS enterprise_settlement_batch (
    id                          uuid PRIMARY KEY,
    enterprise_id               uuid        NOT NULL REFERENCES enterprise(id),
    batch_reference             text        NOT NULL UNIQUE,
    settlement_type             text        NOT NULL CHECK (settlement_type IN (
                                                'IMMEDIATE', 'SCHEDULED', 'DAILY', 'WEEKLY',
                                                'ENTERPRISE_TRIGGERED', 'MERCHANT_REQUESTED',
                                                'THRESHOLD', 'PARTIAL', 'BATCH'
                                            )),
    total_merchants             integer     NOT NULL CHECK (total_merchants >= 0),
    total_items                 integer     NOT NULL CHECK (total_items >= 0),
    total_gross_pesewas         bigint      NOT NULL CHECK (total_gross_pesewas >= 0),
    total_net_pesewas           bigint      NOT NULL CHECK (total_net_pesewas >= 0),
    total_commission_pesewas    bigint      NOT NULL DEFAULT 0 CHECK (total_commission_pesewas >= 0),
    currency                    char(3)     NOT NULL DEFAULT 'GHS' CHECK (currency ~ '^[A-Z]{3}$'),
    status                      text        NOT NULL DEFAULT 'PENDING' CHECK (status IN (
                                                'PENDING', 'PROCESSING', 'RELEASED', 'PAID_OUT', 'FAILED', 'PARTIALLY_RELEASED'
                                            )),
    shortfall_detected          boolean     NOT NULL DEFAULT false,
    ledger_tx_id                uuid        REFERENCES journal_transaction(id),
    payout_batch_id             uuid        REFERENCES payout_batch(id),
    created_at                  timestamptz NOT NULL DEFAULT now(),
    completed_at                timestamptz
);

CREATE INDEX IF NOT EXISTS idx_esb_enterprise ON enterprise_settlement_batch (enterprise_id);
CREATE INDEX IF NOT EXISTS idx_esb_status ON enterprise_settlement_batch (status);

-- 4. Enterprise Settlement Batch Items
CREATE TABLE IF NOT EXISTS enterprise_settlement_batch_item (
    id              uuid PRIMARY KEY,
    batch_id        uuid        NOT NULL REFERENCES enterprise_settlement_batch(id) ON DELETE CASCADE,
    escrow_id       uuid        REFERENCES enterprise_delivery_escrow(id),
    merchant_id     uuid        NOT NULL,
    order_reference text        NOT NULL,
    item_reference  text        NOT NULL,
    amount_pesewas  bigint      NOT NULL CHECK (amount_pesewas >= 0),
    status          text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RELEASED', 'HELD', 'EXCLUDED')),
    reason          text,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_esbi_batch ON enterprise_settlement_batch_item (batch_id);
CREATE INDEX IF NOT EXISTS idx_esbi_merchant ON enterprise_settlement_batch_item (merchant_id);

-- 5. Enterprise Treasury Accounts (Strict Segregation of Operational & Safeguarded Funds)
CREATE TABLE IF NOT EXISTS enterprise_treasury_account (
    id                  uuid PRIMARY KEY,
    enterprise_id       uuid        NOT NULL REFERENCES enterprise(id),
    account_type        text        NOT NULL CHECK (account_type IN (
                                        'PREFUNDING', 'SAFEGUARDED_SETTLEMENT', 'EXTERNAL_RECEIVABLES', 'OPERATIONAL_REVENUE'
                                    )),
    account_code        text        NOT NULL UNIQUE,
    currency            char(3)     NOT NULL DEFAULT 'GHS' CHECK (currency ~ '^[A-Z]{3}$'),
    balance_pesewas     bigint      NOT NULL DEFAULT 0,
    reserved_pesewas    bigint      NOT NULL DEFAULT 0 CHECK (reserved_pesewas >= 0),
    status              text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    strict_segregation  boolean     NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (enterprise_id, account_type, currency)
);

CREATE INDEX IF NOT EXISTS idx_eta_enterprise ON enterprise_treasury_account (enterprise_id);

-- 6. Enterprise Prefunding & Treasury Ledger
CREATE TABLE IF NOT EXISTS enterprise_prefunding_ledger (
    id                      uuid PRIMARY KEY,
    enterprise_id           uuid        NOT NULL REFERENCES enterprise(id),
    treasury_account_id     uuid        NOT NULL REFERENCES enterprise_treasury_account(id),
    transaction_reference   text        NOT NULL UNIQUE,
    entry_type              text        NOT NULL CHECK (entry_type IN (
                                            'DEPOSIT', 'ALLOCATION', 'RELEASE',
                                            'RECEIVABLE_INCURRED', 'RECEIVABLE_SETTLED', 'ADJUSTMENT'
                                        )),
    amount_pesewas          bigint      NOT NULL,
    balance_after_pesewas   bigint      NOT NULL,
    currency                char(3)     NOT NULL DEFAULT 'GHS' CHECK (currency ~ '^[A-Z]{3}$'),
    ledger_tx_id            uuid        REFERENCES journal_transaction(id),
    description             text,
    created_at              timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_epl_account ON enterprise_prefunding_ledger (treasury_account_id);
CREATE INDEX IF NOT EXISTS idx_epl_enterprise ON enterprise_prefunding_ledger (enterprise_id);

-- 7. Enterprise Treasury Shortfall Alerts & Holds
CREATE TABLE IF NOT EXISTS enterprise_treasury_shortfall (
    id                          uuid PRIMARY KEY,
    enterprise_id               uuid        NOT NULL REFERENCES enterprise(id),
    obligation_type             text        NOT NULL CHECK (obligation_type IN ('SETTLEMENT_RELEASE', 'WITHDRAWAL', 'PAYOUT_BATCH')),
    required_funding_pesewas    bigint      NOT NULL CHECK (required_funding_pesewas >= 0),
    available_funding_pesewas   bigint      NOT NULL,
    shortfall_pesewas           bigint      NOT NULL CHECK (shortfall_pesewas >= 0),
    status                      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RESOLVED', 'ACKNOWLEDGED')),
    action_taken                text        NOT NULL CHECK (action_taken IN ('HOLD_PLACED', 'ALERT_DISPATCHED', 'NONE')),
    created_at                  timestamptz NOT NULL DEFAULT now(),
    resolved_at                 timestamptz
);

CREATE INDEX IF NOT EXISTS idx_ets_enterprise ON enterprise_treasury_shortfall (enterprise_id, status);

-- 8. Enterprise Universal Connector Configuration
CREATE TABLE IF NOT EXISTS enterprise_connector_config (
    id                          uuid PRIMARY KEY,
    enterprise_id               uuid        NOT NULL REFERENCES enterprise(id),
    connector_type              text        NOT NULL CHECK (connector_type IN ('JUMIAPAY', 'REST_WEBHOOK', 'SFTP_RECON')),
    endpoint_url                text        NOT NULL,
    auth_type                   text        NOT NULL CHECK (auth_type IN ('OAUTH2', 'HMAC_SHA256', 'MTLS', 'API_KEY')),
    client_id                   text,
    client_secret_encrypted     text,
    signing_secret              text,
    circuit_breaker_enabled     boolean     NOT NULL DEFAULT true,
    rate_limit_per_minute       integer     NOT NULL DEFAULT 600 CHECK (rate_limit_per_minute > 0),
    status                      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED', 'DISABLED')),
    metadata                    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (enterprise_id, connector_type)
);

CREATE INDEX IF NOT EXISTS idx_ecc_enterprise ON enterprise_connector_config (enterprise_id);
