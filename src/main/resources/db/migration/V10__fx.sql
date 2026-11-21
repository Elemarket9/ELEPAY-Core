-- International payments and foreign exchange.

ALTER TABLE ledger_account DROP CONSTRAINT ledger_account_bucket_check;
ALTER TABLE ledger_account ADD CONSTRAINT ledger_account_bucket_check CHECK (bucket IN (
    'available', 'pending', 'reserved', 'clearing', 'revenue', 'suspense', 'refunds_in_flight', 'disputes',
    'rail_fees', 'fx_clearing', 'fx_in_flight'));

-- Minor-unit exponent per currency; amounts are always stored as integers.
CREATE TABLE currency (
    code     char(3) PRIMARY KEY CHECK (code ~ '^[A-Z]{3}$'),
    exponent integer NOT NULL CHECK (exponent BETWEEN 0 AND 4),
    name     text    NOT NULL
);
INSERT INTO currency VALUES ('GHS', 2, 'Ghana cedi'), ('USD', 2, 'US dollar'), ('GBP', 2, 'Pound sterling'), ('EUR', 2, 'Euro');

-- A corridor is defined by sending country, receiving country, currencies,
-- funding method, partner and delivery method.
CREATE TABLE fx_corridor (
    code                 text PRIMARY KEY,
    direction            text    NOT NULL CHECK (direction IN ('inbound', 'outbound')),
    sending_country      char(2) NOT NULL,
    receiving_country    char(2) NOT NULL,
    source_currency      char(3) NOT NULL REFERENCES currency(code),
    destination_currency char(3) NOT NULL REFERENCES currency(code),
    funding_method       text    NOT NULL CHECK (funding_method IN ('wallet', 'bank', 'card', 'partner_collected')),
    delivery_method      text    NOT NULL CHECK (delivery_method IN ('wallet', 'momo', 'bank', 'cash_pickup')),
    partner              text    NOT NULL,
    fee_fixed            bigint  NOT NULL DEFAULT 0 CHECK (fee_fixed >= 0),
    fee_bps              integer NOT NULL DEFAULT 0 CHECK (fee_bps BETWEEN 0 AND 10000),
    min_amount           bigint  NOT NULL CHECK (min_amount > 0),
    max_amount           bigint  NOT NULL,
    quote_ttl_seconds    integer NOT NULL DEFAULT 600 CHECK (quote_ttl_seconds BETWEEN 30 AND 3600),
    est_delivery_minutes integer NOT NULL,
    active               boolean NOT NULL DEFAULT true,
    CHECK (source_currency <> destination_currency),
    CHECK (max_amount >= min_amount)
);

CREATE TABLE fx_quote (
    id                   uuid PRIMARY KEY,
    corridor             text          NOT NULL REFERENCES fx_corridor(code),
    customer_id          uuid REFERENCES customer(id),
    partner              text          NOT NULL,
    partner_quote_ref    text          NOT NULL,
    source_currency      char(3)       NOT NULL,
    source_amount        bigint        NOT NULL CHECK (source_amount > 0),
    fee                  bigint        NOT NULL CHECK (fee >= 0),
    total_debit          bigint        NOT NULL,
    rate                 numeric(24, 12) NOT NULL CHECK (rate > 0),
    destination_currency char(3)       NOT NULL,
    destination_amount   bigint        NOT NULL CHECK (destination_amount > 0),
    expires_at           timestamptz   NOT NULL,
    est_delivery_minutes integer       NOT NULL,
    status               text          NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'accepted', 'expired', 'withdrawn')),
    created_at           timestamptz   NOT NULL DEFAULT now(),
    CHECK (total_debit = source_amount + fee)
);

CREATE TABLE fx_transfer (
    id                   uuid PRIMARY KEY,
    reference            text        NOT NULL UNIQUE,
    direction            text        NOT NULL CHECK (direction IN ('inbound', 'outbound')),
    corridor             text        NOT NULL REFERENCES fx_corridor(code),
    quote_id             uuid UNIQUE REFERENCES fx_quote(id),
    customer_id          uuid REFERENCES customer(id),
    partner              text        NOT NULL,
    partner_reference    text,
    source_currency      char(3)     NOT NULL,
    source_amount        bigint      NOT NULL,
    fee                  bigint      NOT NULL,
    rate                 numeric(24, 12) NOT NULL,
    destination_currency char(3)     NOT NULL,
    destination_amount   bigint      NOT NULL,
    recipient            jsonb       NOT NULL,
    status               text        NOT NULL CHECK (status IN ('pending', 'paid', 'failed', 'returned', 'unknown')),
    journal_tx           uuid        NOT NULL REFERENCES journal_transaction(id),
    delivery_tx          uuid REFERENCES journal_transaction(id),
    return_tx            uuid REFERENCES journal_transaction(id),
    reversal_tx          uuid REFERENCES journal_transaction(id),
    failure_reason       text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX fx_transfer_customer ON fx_transfer (customer_id, created_at);
CREATE UNIQUE INDEX fx_transfer_partner_ref ON fx_transfer (partner, partner_reference) WHERE partner_reference IS NOT NULL;

-- Simulated remittance partner (sandbox and tests only).
CREATE TABLE simulated_fx_partner (
    reference  text PRIMARY KEY,
    quote_ref  text        NOT NULL,
    status     text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO ledger_account (code, owner_type, bucket, currency, allow_negative) VALUES
    ('system:revenue:USD',      'system', 'revenue',      'USD', false),
    ('system:fx_clearing:GHS',  'system', 'fx_clearing',  'GHS', true),
    ('system:fx_clearing:USD',  'system', 'fx_clearing',  'USD', true),
    ('system:fx_in_flight:USD', 'system', 'fx_in_flight', 'USD', false),
    ('system:fx_in_flight:GHS', 'system', 'fx_in_flight', 'GHS', false);
