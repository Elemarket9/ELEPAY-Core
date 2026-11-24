-- V61: Allow 'pos_terminal' in api_credential_key_type_check and widen pos_terminal_dukpt_key.terminal_id for UUIDs.

ALTER TABLE api_credential DROP CONSTRAINT IF EXISTS api_credential_key_type_check;
ALTER TABLE api_credential ADD CONSTRAINT api_credential_key_type_check
    CHECK (key_type IN ('publishable', 'secret', 'payout', 'legacy', 'kitchen', 'pos_terminal')) NOT VALID;

ALTER TABLE pos_terminal_dukpt_key
    ALTER COLUMN terminal_id TYPE varchar(64);
