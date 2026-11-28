CREATE TABLE settlement_bank_credit (
    source_key text NOT NULL, bank_reference text NOT NULL, amount bigint NOT NULL CHECK (amount > 0),
    booked_at timestamptz NOT NULL, PRIMARY KEY (source_key, bank_reference)
);
CREATE TABLE settlement_funded_checkout (
    checkout_id uuid PRIMARY KEY REFERENCES checkout, source_key text NOT NULL, bank_reference text NOT NULL,
    FOREIGN KEY (source_key, bank_reference) REFERENCES settlement_bank_credit
);
CREATE TABLE settlement_merchant_funding (
    merchant_id uuid NOT NULL REFERENCES merchant, source_key text NOT NULL,
    remaining bigint NOT NULL CHECK (remaining >= 0), PRIMARY KEY (merchant_id, source_key)
);
CREATE TABLE settlement_bank_instruction (
    rail_tx uuid PRIMARY KEY REFERENCES rail_transaction, merchant_id uuid NOT NULL REFERENCES merchant,
    source_key text NOT NULL, amount bigint NOT NULL CHECK (amount > 0),
    statement_reference text, bank_booked_at timestamptz, UNIQUE (source_key, statement_reference),
    FOREIGN KEY (merchant_id, source_key) REFERENCES settlement_merchant_funding
);

CREATE TABLE settlement_statement_work (
    rail_tx uuid PRIMARY KEY REFERENCES rail_transaction,
    next_check_at timestamptz NOT NULL DEFAULT now(),
    last_error text
);
CREATE INDEX settlement_statement_work_due ON settlement_statement_work(next_check_at);

CREATE TABLE settlement_bank_collection (
    rail_tx uuid PRIMARY KEY REFERENCES rail_transaction, source_key text NOT NULL
);
