-- Tamper evidence that holds against insiders and against future quantum computers.
--
-- Audit entries (hash_version 2) are chained with HMAC-SHA-512 under a secret key; the key id is stored
-- so keys can rotate. Version 1 entries (unkeyed SHA-256) still verify as before.
-- Checkpoints over the chain head are signed twice, with Ed25519 and with ML-DSA-65 (FIPS 204): the
-- signature holds unless both schemes are broken. integrity_key records every public key used.
-- Balance snapshots move to keyed signatures in the same way.
ALTER TABLE audit_log ALTER COLUMN prev_hash TYPE text, ALTER COLUMN entry_hash TYPE text;
ALTER TABLE audit_log ADD COLUMN hash_version smallint NOT NULL DEFAULT 1;
ALTER TABLE audit_log ADD COLUMN hash_key_id text;

CREATE TABLE integrity_key (
    key_id          text PRIMARY KEY,
    algorithm       text        NOT NULL,
    ed25519_public  text        NOT NULL,
    mldsa65_public  text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_checkpoint (
    id                uuid PRIMARY KEY,
    last_entry_id     bigint      NOT NULL,
    last_entry_hash   text        NOT NULL,
    entries           bigint      NOT NULL,
    created_at        timestamptz NOT NULL,
    key_id            text        NOT NULL REFERENCES integrity_key(key_id),
    algorithm         text        NOT NULL,
    ed25519_signature text        NOT NULL,
    mldsa65_signature text        NOT NULL
);
CREATE INDEX audit_checkpoint_entry ON audit_checkpoint (last_entry_id DESC);

CREATE TRIGGER integrity_key_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON integrity_key
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER audit_checkpoint_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_checkpoint
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

ALTER TABLE ledger_account_snapshot ALTER COLUMN signature TYPE text;
ALTER TABLE ledger_account_snapshot ADD COLUMN signature_version smallint NOT NULL DEFAULT 1;
ALTER TABLE ledger_account_snapshot ADD COLUMN signature_key_id text;
