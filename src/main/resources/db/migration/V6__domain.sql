-- Customers, merchants, checkout, rails, payouts, disputes, reconciliation and operations.

ALTER TABLE ledger_account DROP CONSTRAINT ledger_account_bucket_check;
ALTER TABLE ledger_account ADD CONSTRAINT ledger_account_bucket_check CHECK (bucket IN (
    'available', 'pending', 'reserved', 'clearing', 'revenue', 'suspense',
    'refunds_in_flight', 'disputes', 'rail_fees'));
INSERT INTO ledger_account (code, owner_type, bucket, currency, allow_negative)
VALUES ('system:rail_fees:GHS', 'system', 'rail_fees', 'GHS', true);

CREATE TABLE customer (
    id           uuid PRIMARY KEY,
    subject      text        NOT NULL UNIQUE,
    phone        text        NOT NULL UNIQUE CHECK (phone ~ '^233[0-9]{9}$'),
    display_name text        NOT NULL,
    email        text,
    kyc_tier     integer     NOT NULL DEFAULT 1 CHECK (kyc_tier BETWEEN 1 AND 3),
    status       text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'frozen', 'closed')),
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE merchant (
    id                     uuid PRIMARY KEY,
    legal_name             text        NOT NULL,
    trading_name           text        NOT NULL,
    kind                   text        NOT NULL CHECK (kind IN ('merchant', 'delivery', 'marketplace')),
    status                 text        NOT NULL DEFAULT 'pending_review'
                               CHECK (status IN ('pending_review', 'active', 'suspended', 'rejected')),
    contact_phone          text        NOT NULL,
    contact_email          text,
    registration_number    text,
    merchant_code          text        NOT NULL UNIQUE CHECK (merchant_code ~ '^[0-9]{6}$'),
    fee_bps                integer     NOT NULL DEFAULT 0 CHECK (fee_bps BETWEEN 0 AND 10000),
    fee_fixed              bigint      NOT NULL DEFAULT 0 CHECK (fee_fixed >= 0),
    settlement_delay_hours integer     NOT NULL DEFAULT 24 CHECK (settlement_delay_hours BETWEEN 0 AND 720),
    created_by             text        NOT NULL,
    reviewed_by            text,
    reviewed_at            timestamptz,
    review_note            text,
    created_at             timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE merchant_branch (
    id          uuid PRIMARY KEY,
    merchant_id uuid        NOT NULL REFERENCES merchant(id),
    name        text        NOT NULL,
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (merchant_id, name)
);

CREATE TABLE merchant_member (
    merchant_id uuid        NOT NULL REFERENCES merchant(id),
    subject     text        NOT NULL,
    role        text        NOT NULL CHECK (role IN ('owner', 'finance', 'cashier')),
    branch_id   uuid REFERENCES merchant_branch(id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, subject),
    CHECK (role = 'cashier' OR branch_id IS NULL)
);
CREATE INDEX merchant_member_subject ON merchant_member (subject);

CREATE TABLE settlement_destination (
    id             uuid PRIMARY KEY,
    owner_type     text        NOT NULL CHECK (owner_type IN ('merchant', 'customer')),
    owner_id       uuid        NOT NULL,
    type           text        NOT NULL CHECK (type IN ('momo', 'bank')),
    network        text        NOT NULL,
    account_number text        NOT NULL,
    account_name   text        NOT NULL,
    status         text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'removed')),
    created_by     text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX settlement_destination_owner ON settlement_destination (owner_type, owner_id);

CREATE TABLE marketplace_seller (
    marketplace_id uuid        NOT NULL REFERENCES merchant(id),
    seller_id      uuid        NOT NULL REFERENCES merchant(id),
    status         text        NOT NULL DEFAULT 'invited' CHECK (status IN ('invited', 'active', 'removed')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (marketplace_id, seller_id),
    CHECK (marketplace_id <> seller_id)
);

CREATE TABLE payment_link (
    id          uuid PRIMARY KEY,
    merchant_id uuid        NOT NULL REFERENCES merchant(id),
    branch_id   uuid REFERENCES merchant_branch(id),
    slug        text        NOT NULL UNIQUE,
    title       text        NOT NULL,
    amount      bigint CHECK (amount IS NULL OR amount > 0),
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE checkout (
    id              uuid PRIMARY KEY,
    reference       text        NOT NULL UNIQUE,
    collector_id    uuid        NOT NULL REFERENCES merchant(id),
    branch_id       uuid REFERENCES merchant_branch(id),
    payment_link_id uuid REFERENCES payment_link(id),
    customer_id     uuid REFERENCES customer(id),
    status          text        NOT NULL DEFAULT 'open'
                        CHECK (status IN ('open', 'processing', 'paid', 'expired', 'cancelled')),
    currency        char(3)     NOT NULL DEFAULT 'GHS',
    subtotal        bigint      NOT NULL CHECK (subtotal > 0),
    description     text,
    payment_method  text CHECK (payment_method IN ('wallet', 'momo', 'card')),
    customer_fee    bigint      NOT NULL DEFAULT 0 CHECK (customer_fee >= 0),
    total_paid      bigint CHECK (total_paid IS NULL OR total_paid > 0),
    journal_tx      uuid REFERENCES journal_transaction(id),
    expires_at      timestamptz NOT NULL,
    paid_at         timestamptz,
    created_by      text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX checkout_collector ON checkout (collector_id, created_at);

CREATE TABLE checkout_allocation (
    checkout_id   uuid        NOT NULL REFERENCES checkout(id),
    line          integer     NOT NULL CHECK (line >= 0),
    merchant_id   uuid        NOT NULL REFERENCES merchant(id),
    kind          text        NOT NULL CHECK (kind IN ('sale', 'delivery')),
    description   text,
    amount        bigint      NOT NULL CHECK (amount > 0),
    merchant_fee  bigint      NOT NULL CHECK (merchant_fee >= 0 AND merchant_fee <= amount),
    refunded      bigint      NOT NULL DEFAULT 0,
    refunded_fee  bigint      NOT NULL DEFAULT 0,
    disputed      bigint      NOT NULL DEFAULT 0 CHECK (disputed >= 0),
    settled_at    timestamptz,
    settlement_tx uuid REFERENCES journal_transaction(id),
    PRIMARY KEY (checkout_id, line),
    CHECK (refunded >= 0 AND refunded <= amount),
    CHECK (refunded_fee >= 0 AND refunded_fee <= merchant_fee),
    CHECK (refunded + disputed <= amount)
);
CREATE INDEX checkout_allocation_merchant ON checkout_allocation (merchant_id, checkout_id);
CREATE INDEX checkout_allocation_unsettled ON checkout_allocation (checkout_id) WHERE settled_at IS NULL;

CREATE TABLE rail_transaction (
    id                 uuid PRIMARY KEY,
    rail           text        NOT NULL,
    direction          text        NOT NULL CHECK (direction IN ('charge', 'payout', 'refund')),
    reference          text        NOT NULL UNIQUE,
    purpose            text        NOT NULL,
    purpose_id         uuid        NOT NULL,
    amount             bigint      NOT NULL CHECK (amount > 0),
    currency           char(3)     NOT NULL DEFAULT 'GHS',
    channel            text,
    msisdn             text,
    request            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status             text        NOT NULL DEFAULT 'created'
                           CHECK (status IN ('created', 'pending', 'succeeded', 'failed', 'unknown', 'mismatch')),
    rail_reference text,
    rail_fee       bigint,
    redirect_url       text,
    failure_reason     text,
    check_attempts     integer     NOT NULL DEFAULT 0,
    next_check_at      timestamptz NOT NULL DEFAULT now(),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    completed_at       timestamptz
);
CREATE INDEX rail_transaction_open ON rail_transaction (next_check_at)
    WHERE status IN ('created', 'pending', 'unknown');
CREATE INDEX rail_transaction_purpose ON rail_transaction (purpose, purpose_id);

CREATE TABLE rail_callback (
    id          uuid PRIMARY KEY,
    rail    text        NOT NULL,
    dedupe_key  text        NOT NULL,
    reference   text,
    event_type  text,
    payload     jsonb       NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    outcome     text,
    UNIQUE (rail, dedupe_key)
);

-- One payment attempt per checkout may be in flight; a new charge is only
-- possible after the previous attempt has definitively failed.
CREATE TABLE payment_attempt (
    id           uuid PRIMARY KEY,
    checkout_id  uuid        NOT NULL REFERENCES checkout(id),
    method       text        NOT NULL CHECK (method IN ('wallet', 'momo', 'card')),
    rail_tx  uuid REFERENCES rail_transaction(id),
    status       text        NOT NULL CHECK (status IN ('pending', 'succeeded', 'failed')),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX payment_attempt_one_active ON payment_attempt (checkout_id) WHERE status IN ('pending', 'succeeded');

CREATE TABLE refund (
    id             uuid PRIMARY KEY,
    reference      text        NOT NULL UNIQUE,
    checkout_id    uuid        NOT NULL REFERENCES checkout(id),
    line           integer     NOT NULL,
    amount         bigint      NOT NULL CHECK (amount > 0),
    fee_returned   bigint      NOT NULL CHECK (fee_returned >= 0),
    destination    text        NOT NULL CHECK (destination IN ('wallet', 'rail')),
    status         text        NOT NULL CHECK (status IN ('completed', 'pending', 'failed')),
    journal_tx     uuid        NOT NULL REFERENCES journal_transaction(id),
    completion_tx  uuid REFERENCES journal_transaction(id),
    rail_tx    uuid REFERENCES rail_transaction(id),
    reason         text,
    created_by     text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (checkout_id, line) REFERENCES checkout_allocation(checkout_id, line)
);

CREATE TABLE wallet_operation (
    id          uuid PRIMARY KEY,
    reference   text        NOT NULL UNIQUE,
    customer_id uuid        NOT NULL REFERENCES customer(id),
    kind        text        NOT NULL CHECK (kind IN ('topup', 'transfer_out', 'transfer_in', 'withdrawal', 'payment', 'refund')),
    amount      bigint      NOT NULL CHECK (amount > 0),
    status      text        NOT NULL CHECK (status IN ('pending', 'completed', 'failed')),
    counterparty text,
    note        text,
    journal_tx  uuid REFERENCES journal_transaction(id),
    rail_tx uuid REFERENCES rail_transaction(id),
    related_id  uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX wallet_operation_customer ON wallet_operation (customer_id, created_at);

CREATE TABLE payout (
    id             uuid PRIMARY KEY,
    reference      text        NOT NULL UNIQUE,
    owner_type     text        NOT NULL CHECK (owner_type IN ('merchant', 'customer')),
    owner_id       uuid        NOT NULL,
    destination    jsonb       NOT NULL,
    amount         bigint      NOT NULL CHECK (amount > 0),
    rail       text        NOT NULL,
    status         text        NOT NULL CHECK (status IN ('reserved', 'submitted', 'succeeded', 'failed')),
    reservation_tx uuid        NOT NULL REFERENCES journal_transaction(id),
    completion_tx  uuid REFERENCES journal_transaction(id),
    rail_tx    uuid REFERENCES rail_transaction(id),
    requested_by   text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    completed_at   timestamptz
);
CREATE INDEX payout_owner ON payout (owner_type, owner_id, created_at);

CREATE TABLE dispute (
    id            uuid PRIMARY KEY,
    checkout_id   uuid        NOT NULL REFERENCES checkout(id),
    line          integer     NOT NULL,
    amount        bigint      NOT NULL CHECK (amount > 0),
    reason        text        NOT NULL,
    status        text        NOT NULL CHECK (status IN ('open', 'merchant_won', 'customer_won')),
    hold_tx       uuid        NOT NULL REFERENCES journal_transaction(id),
    resolution_tx uuid REFERENCES journal_transaction(id),
    rail_case text,
    opened_by     text        NOT NULL,
    resolved_by   text,
    resolution_note text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    resolved_at   timestamptz,
    FOREIGN KEY (checkout_id, line) REFERENCES checkout_allocation(checkout_id, line)
);

CREATE TABLE ops_item (
    id          uuid PRIMARY KEY,
    kind        text        NOT NULL,
    severity    text        NOT NULL CHECK (severity IN ('low', 'medium', 'high')),
    subject     text        NOT NULL,
    details     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status      text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    resolved_by text,
    resolved_at timestamptz,
    resolution  text
);
CREATE UNIQUE INDEX ops_item_open_once ON ops_item (kind, subject) WHERE status = 'open';

CREATE TABLE reconciliation_run (
    id          uuid PRIMARY KEY,
    rail    text        NOT NULL,
    period      text        NOT NULL,
    imported_by text        NOT NULL,
    summary     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reconciliation_line (
    run_id             uuid    NOT NULL REFERENCES reconciliation_run(id),
    line               integer NOT NULL,
    reference          text    NOT NULL,
    direction          text    NOT NULL,
    amount             bigint  NOT NULL,
    fee                bigint  NOT NULL DEFAULT 0,
    rail_status    text    NOT NULL,
    rail_tx        uuid REFERENCES rail_transaction(id),
    result             text    NOT NULL CHECK (result IN (
                           'matched', 'missing_internal', 'missing_rail', 'amount_mismatch', 'status_mismatch')),
    PRIMARY KEY (run_id, line)
);

CREATE TABLE ussd_session (
    session_id text PRIMARY KEY,
    msisdn     text        NOT NULL,
    state      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Simulated rail state for the sandbox adapter (never used with live rails).
CREATE TABLE simulated_rail_txn (
    reference   text PRIMARY KEY,
    direction   text        NOT NULL,
    amount      bigint      NOT NULL,
    msisdn      text,
    outcome     text        NOT NULL,
    status      text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
