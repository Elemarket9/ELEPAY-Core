-- An unguessable public receipt token is issued only for a verified paid POS order.
-- Store only SHA256(token); raw token appears only in the issued receipt link.
CREATE TABLE IF NOT EXISTS pos_receipt_public_token (
    receipt_id uuid PRIMARY KEY REFERENCES pos_receipt(id),
    token_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_pos_receipt_token_expires ON pos_receipt_public_token(expires_at);
