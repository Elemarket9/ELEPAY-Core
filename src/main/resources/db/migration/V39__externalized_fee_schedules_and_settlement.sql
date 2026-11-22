-- V39: Externalize fee schedules, settlement configurations, merchant reserves, and GRA e-VAT fiscal parameters.

-- 1. Enhance marketplace fee schedule and allocation with versioning and merchant reserve retention
ALTER TABLE marketplace_fee_schedule
    ADD COLUMN IF NOT EXISTS version     integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS reserve_bps integer NOT NULL DEFAULT 0 CHECK (reserve_bps BETWEEN 0 AND 5000);

ALTER TABLE marketplace_fee_rule
    ADD COLUMN IF NOT EXISTS reserve_bps integer NOT NULL DEFAULT 0 CHECK (reserve_bps BETWEEN 0 AND 5000);

ALTER TABLE checkout_allocation
    ADD COLUMN IF NOT EXISTS reserve_amount   bigint NOT NULL DEFAULT 0 CHECK (reserve_amount >= 0),
    ADD COLUMN IF NOT EXISTS reserve_released bigint NOT NULL DEFAULT 0 CHECK (reserve_released >= 0),
    ADD COLUMN IF NOT EXISTS release_condition varchar(64) NOT NULL DEFAULT 'DELIVERY_CONFIRMED',
    ADD COLUMN IF NOT EXISTS release_status   varchar(32) NOT NULL DEFAULT 'HELD';

CREATE TABLE marketplace_fee_schedule_history (
    id                    uuid PRIMARY KEY,
    marketplace_id        uuid NOT NULL,
    version               integer NOT NULL,
    delivery_confirm_days integer NOT NULL,
    reserve_bps           integer NOT NULL DEFAULT 0,
    rules_json            text NOT NULL,
    change_reason         text,
    created_by            text NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_marketplace_fee_schedule_history_mkt ON marketplace_fee_schedule_history(marketplace_id, version);

-- 2. Versioned checkout limits and platform fee configuration
CREATE TABLE checkout_config (
    config_key            varchar(64) PRIMARY KEY,
    config_value          text NOT NULL,
    version               integer NOT NULL DEFAULT 1,
    description           text,
    updated_by            text NOT NULL DEFAULT 'system',
    updated_at            timestamptz NOT NULL DEFAULT now()
);

INSERT INTO checkout_config (config_key, config_value, version, description) VALUES
    ('checkout.max_allocations', '50', 1, 'Maximum allocation splits per checkout'),
    ('checkout.max_total', '500000000', 1, 'Maximum checkout amount in minor units (pesewas)'),
    ('checkout.default_delivery_confirm_days', '14', 1, 'Default days to auto-confirm delivery'),
    ('checkout.default_settlement_delay_hours', '24', 1, 'Default settlement delay in hours'),
    ('checkout.fee.customer.wallet_bps', '0', 1, 'Customer wallet payment fee basis points'),
    ('checkout.fee.customer.wallet_fixed', '0', 1, 'Customer wallet payment fixed fee pesewas'),
    ('checkout.fee.customer.momo_bps', '0', 1, 'Customer MoMo payment fee basis points'),
    ('checkout.fee.customer.momo_fixed', '0', 1, 'Customer MoMo payment fixed fee pesewas'),
    ('checkout.fee.customer.card_bps', '0', 1, 'Customer card payment fee basis points'),
    ('checkout.fee.customer.card_fixed', '0', 1, 'Customer card payment fixed fee pesewas')
ON CONFLICT (config_key) DO NOTHING;

CREATE TABLE checkout_config_history (
    id                    uuid PRIMARY KEY,
    config_key            varchar(64) NOT NULL,
    config_value          text NOT NULL,
    version               integer NOT NULL,
    updated_by            text NOT NULL,
    updated_at            timestamptz NOT NULL DEFAULT now(),
    change_reason         text
);

-- 3. Merchant reserve releases tracking
CREATE TABLE settlement_reserve_release (
    id                    uuid PRIMARY KEY,
    checkout_id           uuid NOT NULL REFERENCES checkout(id),
    line                  integer NOT NULL,
    merchant_id           uuid NOT NULL,
    amount                bigint NOT NULL CHECK (amount > 0),
    journal_tx            uuid NOT NULL,
    reason                text,
    released_by           text NOT NULL,
    released_at           timestamptz NOT NULL DEFAULT now()
);

-- 4. Externalized GRA e-VAT fiscal configuration for merchant backoffice & invoicing
CREATE TABLE gra_fiscal_config (
    id                    uuid PRIMARY KEY,
    merchant_id           uuid, -- NULL indicates global default
    version               integer NOT NULL DEFAULT 1,
    vat_rate_bps          integer NOT NULL DEFAULT 1500, -- 15.0%
    nhil_rate_bps         integer NOT NULL DEFAULT 250,  -- 2.5%
    getfund_rate_bps      integer NOT NULL DEFAULT 250,  -- 2.5%
    covid_levy_rate_bps   integer NOT NULL DEFAULT 100,  -- 1.0%
    vsdc_endpoint         text NOT NULL DEFAULT 'https://gra.gov.gh/evat/api/v1',
    vsdc_timeout_ms       integer NOT NULL DEFAULT 5000,
    is_active             boolean NOT NULL DEFAULT true,
    updated_by            text NOT NULL DEFAULT 'system',
    updated_at            timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_gra_fiscal_config_merchant ON gra_fiscal_config(merchant_id);

INSERT INTO gra_fiscal_config (id, merchant_id, version, vat_rate_bps, nhil_rate_bps, getfund_rate_bps, covid_levy_rate_bps, vsdc_endpoint, vsdc_timeout_ms, is_active, updated_by)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 1, 1500, 250, 250, 100, 'https://gra.gov.gh/evat/api/v1', 5000, true, 'system')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE gra_fiscal_config_history (
    id                    uuid PRIMARY KEY,
    config_id             uuid NOT NULL,
    merchant_id           uuid,
    version               integer NOT NULL,
    vat_rate_bps          integer NOT NULL,
    nhil_rate_bps         integer NOT NULL,
    getfund_rate_bps      integer NOT NULL,
    covid_levy_rate_bps   integer NOT NULL,
    vsdc_endpoint         text NOT NULL,
    vsdc_timeout_ms       integer NOT NULL,
    change_reason         text,
    updated_by            text NOT NULL,
    updated_at            timestamptz NOT NULL DEFAULT now()
);
