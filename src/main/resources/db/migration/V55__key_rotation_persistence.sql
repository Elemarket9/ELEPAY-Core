-- Key rotation metadata persistence (Algorithm 076 durability).
-- Stores KEK rotation METADATA only so rotation history and "rotation due" decisions survive restarts.
-- Secret key material is NEVER stored here: it is supplied at startup from the KMS/HSM/env backend
-- (KmsSecretProvider.KeyManagementBackend) and held only in memory.

CREATE TABLE IF NOT EXISTS key_rotation_metadata (
    key_id          varchar(128) NOT NULL,
    key_version     integer      NOT NULL,
    status          varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz  NOT NULL DEFAULT now(),
    activated_at    timestamptz,
    retired_at      timestamptz,
    rotation_due_at timestamptz,
    PRIMARY KEY (key_id, key_version),
    CONSTRAINT key_rotation_metadata_status_check
        CHECK (status IN ('ACTIVE', 'RETIRED', 'REVOKED'))
);

-- Fast lookup of the key(s) currently active and whether any is past its rotation interval.
CREATE INDEX IF NOT EXISTS idx_key_rotation_metadata_active_due
    ON key_rotation_metadata (status, rotation_due_at);
