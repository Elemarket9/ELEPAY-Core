-- V18: Partner Rails, Smart Routing, Health Detection, Card BIN, 3DS 2.x, and Tokenization Vault
-- Algorithms 011 - 019

-- 1. Card BIN database (Algorithm 017)
CREATE TABLE card_bin (
    bin             varchar(8) PRIMARY KEY,
    scheme          varchar(32) NOT NULL,
    card_type       varchar(32) NOT NULL,
    issuer_bank     varchar(128) NOT NULL,
    country_code    char(2) NOT NULL,
    currency        char(3) NOT NULL DEFAULT 'GHS',
    version         varchar(32) NOT NULL DEFAULT '2026.1',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_card_bin_scheme ON card_bin(scheme);
CREATE INDEX idx_card_bin_country ON card_bin(country_code);

-- Seed Ghanaian and international test BIN ranges
INSERT INTO card_bin (bin, scheme, card_type, issuer_bank, country_code, currency, version) VALUES
    ('411111', 'visa', 'debit', 'GCB Bank PLC', 'GH', 'GHS', '2026.1'),
    ('512345', 'mastercard', 'debit', 'GCB Bank PLC', 'GH', 'GHS', '2026.1'),
    ('402234', 'visa', 'debit', 'Ecobank Ghana PLC', 'GH', 'GHS', '2026.1'),
    ('539983', 'mastercard', 'credit', 'Ecobank Ghana PLC', 'GH', 'GHS', '2026.1'),
    ('428421', 'visa', 'debit', 'Stanbic Bank Ghana', 'GH', 'GHS', '2026.1'),
    ('524128', 'mastercard', 'debit', 'Stanbic Bank Ghana', 'GH', 'GHS', '2026.1'),
    ('456789', 'visa', 'debit', 'Absa Bank Ghana PLC', 'GH', 'GHS', '2026.1'),
    ('541234', 'mastercard', 'debit', 'Absa Bank Ghana PLC', 'GH', 'GHS', '2026.1'),
    ('489012', 'visa', 'debit', 'Zenith Bank Ghana', 'GH', 'GHS', '2026.1'),
    ('434567', 'visa', 'debit', 'Fidelity Bank Ghana', 'GH', 'GHS', '2026.1'),
    ('476543', 'visa', 'credit', 'Standard Chartered Bank Ghana', 'GH', 'GHS', '2026.1'),
    ('900101', 'gh-link', 'debit', 'GhIPSS Gh-Link', 'GH', 'GHS', '2026.1'),
    ('400000', 'visa', 'credit', 'Chase Bank USA', 'US', 'USD', '2026.1'),
    ('555555', 'mastercard', 'credit', 'Barclays Bank UK', 'GB', 'GBP', '2026.1');

-- 2. Card Tokenization Vault (Algorithm 019)
CREATE TABLE card_token (
    id              uuid PRIMARY KEY,
    token           varchar(64) NOT NULL UNIQUE,
    customer_id     uuid,
    masked_pan      varchar(32) NOT NULL,
    fingerprint     varchar(64) NOT NULL,
    scheme          varchar(32) NOT NULL,
    card_type       varchar(32) NOT NULL DEFAULT 'debit',
    issuer_bank     varchar(128),
    expiry_month    integer NOT NULL CHECK (expiry_month BETWEEN 1 AND 12),
    expiry_year     integer NOT NULL CHECK (expiry_year >= 2024),
    cardholder_name varchar(128),
    encrypted_pan   text NOT NULL,
    status          varchar(32) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked', 'expired')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    revoked_at      timestamptz
);

CREATE INDEX idx_card_token_customer ON card_token(customer_id);
CREATE INDEX idx_card_token_fingerprint ON card_token(fingerprint);
CREATE INDEX idx_card_token_status ON card_token(status);

-- 3. Risk-based 3-D Secure Sessions (Algorithm 018)
CREATE TABLE three_d_secure_session (
    id                  uuid PRIMARY KEY,
    session_id          varchar(64) NOT NULL UNIQUE,
    reference           varchar(64) NOT NULL,
    amount              bigint NOT NULL CHECK (amount > 0),
    currency            char(3) NOT NULL DEFAULT 'GHS',
    card_token          varchar(64),
    flow_type           varchar(32) NOT NULL CHECK (flow_type IN ('frictionless', 'challenge')),
    status              varchar(32) NOT NULL CHECK (status IN ('initiated', 'challenged', 'authenticated', 'rejected', 'exempted')),
    ds_trans_id         varchar(64),
    eci                 varchar(8),
    cavv                varchar(64),
    liability_shift     boolean NOT NULL DEFAULT false,
    risk_score          integer NOT NULL DEFAULT 0,
    challenge_url       text,
    otp_code            varchar(16),
    challenge_attempts  integer NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_3ds_reference ON three_d_secure_session(reference);
CREATE INDEX idx_3ds_status ON three_d_secure_session(status);

-- 4. Smart Route Configuration (Algorithm 014)
CREATE TABLE rail_route_config (
    id              uuid PRIMARY KEY,
    channel         varchar(32) NOT NULL,
    network         varchar(32) NOT NULL,
    primary_rail    varchar(64) NOT NULL,
    secondary_rail  varchar(64),
    cost_bps        integer NOT NULL DEFAULT 100,
    cost_fixed      bigint NOT NULL DEFAULT 0,
    priority        integer NOT NULL DEFAULT 1,
    enabled         boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_route_config_channel ON rail_route_config(channel, network);

INSERT INTO rail_route_config (id, channel, network, primary_rail, secondary_rail, cost_bps, cost_fixed, priority, enabled) VALUES
    ('00000000-0000-0000-0000-000000000001', 'momo', 'mtn', 'mtn', 'sandbox', 100, 0, 1, true),
    ('00000000-0000-0000-0000-000000000002', 'momo', 'telecel', 'telecel', 'sandbox', 75, 0, 1, true),
    ('00000000-0000-0000-0000-000000000003', 'momo', 'airteltigo', 'airteltigo', 'sandbox', 50, 0, 1, true),
    ('00000000-0000-0000-0000-000000000004', 'bank', 'ghipss', 'ghipss', 'sandbox', 0, 50, 1, true),
    ('00000000-0000-0000-0000-000000000005', 'card', 'card', 'card', 'sandbox', 150, 0, 1, true);

-- 5. Network Health Windows and Circuit Breaker (Algorithm 016)
CREATE TABLE rail_health_window (
    id                  uuid PRIMARY KEY,
    rail                varchar(64) NOT NULL,
    window_start        timestamptz NOT NULL,
    window_end          timestamptz NOT NULL,
    total_requests      integer NOT NULL DEFAULT 0,
    successful_requests integer NOT NULL DEFAULT 0,
    failed_requests     integer NOT NULL DEFAULT 0,
    timeout_requests    integer NOT NULL DEFAULT 0,
    average_latency_ms  integer NOT NULL DEFAULT 0,
    p95_latency_ms      integer NOT NULL DEFAULT 0,
    circuit_state       varchar(32) NOT NULL DEFAULT 'closed' CHECK (circuit_state IN ('closed', 'half_open', 'open')),
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_health_rail_window ON rail_health_window(rail, window_start DESC);

-- 6. Safe Failover Audit Log (Algorithm 015)
CREATE TABLE rail_failover_log (
    id                          uuid PRIMARY KEY,
    reference                   varchar(64) NOT NULL,
    primary_rail                varchar(64) NOT NULL,
    failover_rail               varchar(64) NOT NULL,
    reason                      text NOT NULL,
    fund_movement_verified_none boolean NOT NULL DEFAULT true,
    created_at                  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_failover_reference ON rail_failover_log(reference);
