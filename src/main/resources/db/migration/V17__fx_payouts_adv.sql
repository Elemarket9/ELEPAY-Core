-- Advanced FX and Cross-Border Capabilities (Algorithms 059-064) and Payout Hardening

-- 059: Rate Sourcing & Multi-Source Ingestion
CREATE TABLE fx_rate_source (
    id                   uuid PRIMARY KEY,
    source               text NOT NULL,
    source_currency      char(3) NOT NULL REFERENCES currency(code),
    destination_currency char(3) NOT NULL REFERENCES currency(code),
    bid_rate             numeric(24, 12) NOT NULL CHECK (bid_rate > 0),
    ask_rate             numeric(24, 12) NOT NULL CHECK (ask_rate >= bid_rate),
    mid_rate             numeric(24, 12) NOT NULL CHECK (mid_rate > 0),
    weight               numeric(5, 2) NOT NULL DEFAULT 1.0 CHECK (weight > 0),
    captured_at          timestamptz NOT NULL DEFAULT now(),
    status               text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'stale', 'outlier', 'rejected')),
    CHECK (source_currency <> destination_currency)
);
CREATE INDEX fx_rate_source_pair_time ON fx_rate_source (source_currency, destination_currency, captured_at DESC);

CREATE TABLE fx_blended_rate (
    id                   uuid PRIMARY KEY,
    source_currency      char(3) NOT NULL REFERENCES currency(code),
    destination_currency char(3) NOT NULL REFERENCES currency(code),
    mid_rate             numeric(24, 12) NOT NULL CHECK (mid_rate > 0),
    spread_bps           integer NOT NULL DEFAULT 0 CHECK (spread_bps >= 0),
    sources_count        integer NOT NULL CHECK (sources_count >= 0),
    outliers_count       integer NOT NULL CHECK (outliers_count >= 0),
    calculated_at        timestamptz NOT NULL DEFAULT now(),
    valid_until          timestamptz NOT NULL,
    CHECK (source_currency <> destination_currency),
    CHECK (valid_until > calculated_at)
);
CREATE INDEX fx_blended_rate_pair ON fx_blended_rate (source_currency, destination_currency, valid_until DESC);

-- 060: Dynamic FX Margin Pricing & Tiers
CREATE TABLE fx_margin_tier (
    id             uuid PRIMARY KEY,
    corridor       text REFERENCES fx_corridor(code),
    customer_tier  text NOT NULL DEFAULT 'standard' CHECK (customer_tier IN ('standard', 'vip', 'enterprise', 'merchant')),
    min_amount     bigint NOT NULL CHECK (min_amount >= 0),
    max_amount     bigint NOT NULL CHECK (max_amount >= min_amount),
    margin_bps     integer NOT NULL CHECK (margin_bps BETWEEN 0 AND 5000),
    fixed_fee      bigint NOT NULL DEFAULT 0 CHECK (fixed_fee >= 0),
    active         boolean NOT NULL DEFAULT true,
    version        integer NOT NULL DEFAULT 1
);
CREATE INDEX fx_margin_tier_corridor ON fx_margin_tier (corridor, customer_tier, active);

-- 061: Corridor Partner Routing
CREATE TABLE fx_partner_route (
    id                   uuid PRIMARY KEY,
    corridor             text NOT NULL REFERENCES fx_corridor(code),
    partner              text NOT NULL,
    cost_bps             integer NOT NULL DEFAULT 0 CHECK (cost_bps >= 0),
    fixed_cost           bigint NOT NULL DEFAULT 0 CHECK (fixed_cost >= 0),
    avg_delivery_minutes integer NOT NULL CHECK (avg_delivery_minutes > 0),
    liquidity_score      integer NOT NULL DEFAULT 100 CHECK (liquidity_score BETWEEN 0 AND 100),
    success_rate_bps     integer NOT NULL DEFAULT 10000 CHECK (success_rate_bps BETWEEN 0 AND 10000),
    active               boolean NOT NULL DEFAULT true,
    priority             integer NOT NULL DEFAULT 1,
    UNIQUE (corridor, partner)
);

-- 062: Net Open Foreign Currency Exposure Limits & Hedging Triggers
CREATE TABLE fx_exposure_limit (
    currency               char(3) PRIMARY KEY REFERENCES currency(code),
    max_net_open_position  bigint NOT NULL CHECK (max_net_open_position > 0),
    warning_threshold_pct  integer NOT NULL DEFAULT 80 CHECK (warning_threshold_pct BETWEEN 10 AND 100),
    max_daily_volume       bigint NOT NULL CHECK (max_daily_volume > 0),
    active                 boolean NOT NULL DEFAULT true
);

CREATE TABLE fx_hedging_trigger (
    id                   uuid PRIMARY KEY,
    currency             char(3) NOT NULL REFERENCES currency(code),
    net_open_position    bigint NOT NULL,
    exposure_limit       bigint NOT NULL,
    trigger_type         text NOT NULL CHECK (trigger_type IN ('warning', 'breach', 'rebalance')),
    recommended_action   text NOT NULL,
    hedge_amount         bigint NOT NULL,
    status               text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'acknowledged', 'executed', 'cancelled')),
    triggered_at         timestamptz NOT NULL DEFAULT now()
);

-- 063: Delivery-time SLA Estimation Rules
CREATE TABLE fx_delivery_estimate_rule (
    id                  uuid PRIMARY KEY,
    corridor            text REFERENCES fx_corridor(code),
    delivery_method     text NOT NULL CHECK (delivery_method IN ('wallet', 'momo', 'bank', 'cash_pickup')),
    destination_network text,
    min_minutes         integer NOT NULL CHECK (min_minutes >= 0),
    p50_minutes         integer NOT NULL CHECK (p50_minutes >= min_minutes),
    p95_minutes         integer NOT NULL CHECK (p95_minutes >= p50_minutes),
    max_minutes         integer NOT NULL CHECK (max_minutes >= p95_minutes),
    daily_cutoff_hour   integer CHECK (daily_cutoff_hour BETWEEN 0 AND 23),
    weekend_delay_hours integer NOT NULL DEFAULT 0 CHECK (weekend_delay_hours >= 0)
);

-- 064: Dual-Currency Reconciliation Statements
CREATE TABLE fx_dual_currency_statement (
    id                          uuid PRIMARY KEY,
    partner                     text NOT NULL,
    period                      text NOT NULL,
    currency_pair               text NOT NULL,
    source_currency             char(3) NOT NULL REFERENCES currency(code),
    destination_currency        char(3) NOT NULL REFERENCES currency(code),
    total_source_debits         bigint NOT NULL DEFAULT 0,
    total_destination_credits   bigint NOT NULL DEFAULT 0,
    partner_fee_amount          bigint NOT NULL DEFAULT 0,
    fee_currency                char(3) NOT NULL REFERENCES currency(code),
    clearing_balance_stated     bigint NOT NULL,
    clearing_balance_computed   bigint NOT NULL,
    status                      text NOT NULL DEFAULT 'unreconciled' CHECK (status IN ('unreconciled', 'matched', 'discrepancy')),
    reconciled_at               timestamptz,
    notes                       text
);

-- Seed default exposure limits
INSERT INTO fx_exposure_limit (currency, max_net_open_position, warning_threshold_pct, max_daily_volume, active) VALUES
    ('USD', 50000000, 80, 200000000, true), -- $500k max open position, 80% warning
    ('EUR', 40000000, 80, 150000000, true), -- €400k max open position, 80% warning
    ('GBP', 35000000, 80, 120000000, true)  -- £350k max open position, 80% warning
ON CONFLICT (currency) DO NOTHING;

-- Seed default delivery estimate rules
INSERT INTO fx_delivery_estimate_rule (id, corridor, delivery_method, destination_network, min_minutes, p50_minutes, p95_minutes, max_minutes, daily_cutoff_hour, weekend_delay_hours) VALUES
    ('00000000-0000-0000-0000-000000000001', NULL, 'wallet', NULL, 1, 5, 15, 30, NULL, 0),
    ('00000000-0000-0000-0000-000000000002', NULL, 'momo', NULL, 1, 10, 30, 60, NULL, 0),
    ('00000000-0000-0000-0000-000000000003', NULL, 'bank', NULL, 60, 240, 1440, 2880, 15, 48),
    ('00000000-0000-0000-0000-000000000004', NULL, 'cash_pickup', NULL, 15, 60, 180, 360, 17, 24)
ON CONFLICT (id) DO NOTHING;
