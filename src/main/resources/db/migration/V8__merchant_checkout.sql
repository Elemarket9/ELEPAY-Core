-- Administrator role, per-allocation pending tracking and settlement batches.

ALTER TABLE merchant_member DROP CONSTRAINT merchant_member_role_check;
ALTER TABLE merchant_member ADD CONSTRAINT merchant_member_role_check
    CHECK (role IN ('owner', 'admin', 'finance', 'cashier'));

-- Net proceeds of an allocation still held in the merchant's pending bucket.
ALTER TABLE checkout_allocation ADD COLUMN pending_amount bigint NOT NULL DEFAULT 0 CHECK (pending_amount >= 0);
ALTER TABLE checkout_allocation ADD COLUMN settled_amount bigint NOT NULL DEFAULT 0 CHECK (settled_amount >= 0);

CREATE TABLE settlement_batch (
    id          uuid PRIMARY KEY,
    reference   text        NOT NULL UNIQUE,
    merchant_id uuid        NOT NULL REFERENCES merchant(id),
    amount      bigint      NOT NULL CHECK (amount > 0),
    lines       integer     NOT NULL CHECK (lines > 0),
    journal_tx  uuid        NOT NULL REFERENCES journal_transaction(id),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX settlement_batch_merchant ON settlement_batch (merchant_id, created_at);
ALTER TABLE checkout_allocation ADD COLUMN settlement_batch uuid REFERENCES settlement_batch(id);

CREATE INDEX checkout_status_expiry ON checkout (expires_at) WHERE status = 'open';
