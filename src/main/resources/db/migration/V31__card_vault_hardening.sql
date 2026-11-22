-- PCI DSS 3.5 / 7.2: card tokens record who owns them, and card fingerprints become keyed (HMAC) so a
-- fingerprint cannot be brute-forced back to the card number. Existing rows are upgraded at start-up
-- (re-encrypted under the configured vault key and re-fingerprinted); fingerprint_version tracks this.
ALTER TABLE card_token ADD COLUMN IF NOT EXISTS owner_subject text;
ALTER TABLE card_token ADD COLUMN IF NOT EXISTS fingerprint_version smallint NOT NULL DEFAULT 1;
CREATE INDEX IF NOT EXISTS idx_card_token_owner ON card_token(owner_subject);
