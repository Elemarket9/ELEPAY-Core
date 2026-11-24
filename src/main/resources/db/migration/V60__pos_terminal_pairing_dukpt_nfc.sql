-- V60: POS Terminal Pairing, Attestation, DUKPT Hardening & NFC Tap-to-Pay Authorization

ALTER TABLE pos_terminal
    ADD COLUMN IF NOT EXISTS pairing_code_hash varchar(128),
    ADD COLUMN IF NOT EXISTS pairing_expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS failed_pairing_attempts int NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS attestation_public_key text,
    ADD COLUMN IF NOT EXISTS attestation_format varchar(32),
    ADD COLUMN IF NOT EXISTS credential_id uuid REFERENCES api_credential(id),
    ADD COLUMN IF NOT EXISTS dukpt_ksn_prefix varchar(16);

ALTER TABLE pos_terminal_dukpt_key
    ADD COLUMN IF NOT EXISTS terminal_uuid uuid REFERENCES pos_terminal(id),
    ADD COLUMN IF NOT EXISTS last_ksn_counter bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS key_version int NOT NULL DEFAULT 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_dukpt_terminal_uuid
    ON pos_terminal_dukpt_key(terminal_uuid)
    WHERE terminal_uuid IS NOT NULL;

CREATE TABLE IF NOT EXISTS pos_nfc_authorization (
    id                  uuid PRIMARY KEY,
    order_id            uuid REFERENCES pos_order(id),
    terminal_id         uuid REFERENCES pos_terminal(id),
    ksn                 varchar(32) NOT NULL,
    ksn_counter         bigint NOT NULL,
    emv_tags_redacted   jsonb,
    masked_pan          varchar(24),
    card_scheme         varchar(32),
    cvm_method          varchar(32),
    auth_code           varchar(16),
    checkout_ref        varchar(64),
    status              varchar(32),
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_pos_nfc_terminal_ksn UNIQUE (terminal_id, ksn)
);

CREATE INDEX IF NOT EXISTS idx_pos_nfc_auth_order ON pos_nfc_authorization(order_id);
CREATE INDEX IF NOT EXISTS idx_pos_nfc_auth_terminal ON pos_nfc_authorization(terminal_id, created_at DESC);
