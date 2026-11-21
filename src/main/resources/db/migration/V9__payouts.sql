-- Single and batch payouts with returns and safe retries.

ALTER TABLE rail_transaction DROP CONSTRAINT rail_transaction_status_check;
ALTER TABLE rail_transaction ADD CONSTRAINT rail_transaction_status_check
    CHECK (status IN ('created', 'pending', 'succeeded', 'failed', 'unknown', 'mismatch', 'returned'));

CREATE TABLE payout_batch (
    id           uuid PRIMARY KEY,
    reference    text        NOT NULL UNIQUE,
    owner_type   text        NOT NULL CHECK (owner_type IN ('merchant', 'customer')),
    owner_id     uuid        NOT NULL,
    item_count   integer     NOT NULL CHECK (item_count > 0),
    total        bigint      NOT NULL CHECK (total > 0),
    description  text,
    created_by   text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payout_batch_owner ON payout_batch (owner_type, owner_id, created_at);

ALTER TABLE payout DROP CONSTRAINT payout_status_check;
ALTER TABLE payout ADD CONSTRAINT payout_status_check
    CHECK (status IN ('rejected', 'reserved', 'submitted', 'succeeded', 'failed', 'returned'));
ALTER TABLE payout ALTER COLUMN reservation_tx DROP NOT NULL;
ALTER TABLE payout ADD COLUMN batch_id uuid REFERENCES payout_batch(id);
ALTER TABLE payout ADD COLUMN client_reference text;
ALTER TABLE payout ADD COLUMN platform text;
ALTER TABLE payout ADD COLUMN verified_name text;
ALTER TABLE payout ADD COLUMN verification text NOT NULL DEFAULT 'not_checked'
    CHECK (verification IN ('verified', 'unverified', 'not_found', 'not_checked', 'confirmed'));
ALTER TABLE payout ADD COLUMN failure_reason text;
ALTER TABLE payout ADD COLUMN return_tx uuid REFERENCES journal_transaction(id);
ALTER TABLE payout ADD COLUMN retry_of uuid REFERENCES payout(id);
ALTER TABLE payout ADD CONSTRAINT payout_reserved_needs_tx CHECK (status = 'rejected' OR reservation_tx IS NOT NULL);
CREATE UNIQUE INDEX payout_batch_client_reference ON payout (batch_id, client_reference) WHERE batch_id IS NOT NULL;
-- A payout can be retried at most once; the retry itself may be retried in turn.
CREATE UNIQUE INDEX payout_single_retry ON payout (retry_of) WHERE retry_of IS NOT NULL;
