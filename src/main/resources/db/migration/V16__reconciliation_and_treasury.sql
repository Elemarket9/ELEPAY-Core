-- Reconciliation, treasury suspense, and automated 4-way matching (Algorithms 049, 052, 053, 054, 055)

CREATE TABLE statement_upload (
    id                  uuid PRIMARY KEY,
    rail                varchar(32) NOT NULL,
    filename            varchar(255) NOT NULL,
    uploaded_at         timestamptz NOT NULL DEFAULT now(),
    uploaded_by         uuid
);

CREATE TABLE statement_line (
    id                  uuid PRIMARY KEY,
    upload_id           uuid NOT NULL REFERENCES statement_upload(id),
    external_reference  varchar(128),
    amount_pesewas      bigint NOT NULL,
    currency            varchar(3) NOT NULL DEFAULT 'GHS',
    fee_pesewas         bigint NOT NULL DEFAULT 0,
    transaction_time    timestamptz,
    raw_description     text,
    match_status        varchar(32) NOT NULL DEFAULT 'UNMATCHED'
);

CREATE INDEX statement_line_upload_idx ON statement_line(upload_id);
CREATE INDEX statement_line_ref_idx ON statement_line(external_reference);

CREATE TABLE reconciliation_match (
    id                  uuid PRIMARY KEY,
    statement_line_id   uuid NOT NULL REFERENCES statement_line(id),
    internal_reference  varchar(128) NOT NULL,
    match_type          varchar(32) NOT NULL,
    confidence          numeric(5,2) NOT NULL,
    matched_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX rec_match_line_idx ON reconciliation_match(statement_line_id);
CREATE INDEX rec_match_internal_idx ON reconciliation_match(internal_reference);

CREATE TABLE suspense_item (
    id                      uuid PRIMARY KEY,
    source                  varchar(64) NOT NULL,
    amount_pesewas          bigint NOT NULL,
    currency                varchar(3) NOT NULL DEFAULT 'GHS',
    reason                  text,
    status                  varchar(32) NOT NULL DEFAULT 'OPEN',
    proposed_by             uuid,
    proposed_action         varchar(64),
    proposed_target_account varchar(128),
    approved_by             uuid,
    created_at              timestamptz NOT NULL DEFAULT now(),
    resolved_at             timestamptz
);

CREATE INDEX suspense_item_status_idx ON suspense_item(status, created_at);

ALTER TABLE ledger_account DROP CONSTRAINT ledger_account_bucket_check;
ALTER TABLE ledger_account ADD CONSTRAINT ledger_account_bucket_check CHECK (bucket IN (
    'available', 'pending', 'reserved', 'clearing', 'revenue', 'suspense', 'refunds_in_flight', 'disputes',
    'rail_fees', 'fx_clearing', 'fx_in_flight', 'deferred_revenue', 'revenue_earned'));

INSERT INTO ledger_account (code, owner_type, bucket, currency, allow_negative) VALUES
    ('system:deferred_revenue:GHS', 'system', 'deferred_revenue', 'GHS', true),
    ('system:revenue:earned:GHS',   'system', 'revenue_earned',   'GHS', false)
ON CONFLICT (code) DO NOTHING;
