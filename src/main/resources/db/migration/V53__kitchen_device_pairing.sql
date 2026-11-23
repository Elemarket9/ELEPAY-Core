-- V53: Pair kitchen tablets and printer devices once with a short code instead of a staff sign-in.

ALTER TABLE api_credential DROP CONSTRAINT IF EXISTS api_credential_key_type_check;
-- NOT VALID: existing rows are left as they are; new and updated rows must use a known key type.
ALTER TABLE api_credential ADD CONSTRAINT api_credential_key_type_check
    CHECK (key_type IN ('publishable', 'secret', 'payout', 'legacy', 'kitchen')) NOT VALID;

CREATE TABLE IF NOT EXISTS restaurant_kitchen_pairing (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    station_id          uuid REFERENCES restaurant_kitchen_station(id),
    code_hash           varchar(64) NOT NULL,
    created_by          text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    expires_at          timestamptz NOT NULL,
    failed_attempts     integer NOT NULL DEFAULT 0,
    used_at             timestamptz,
    credential_id       uuid REFERENCES api_credential(id),
    device_name         varchar(64)
);
CREATE INDEX IF NOT EXISTS idx_kitchen_pairing_open
    ON restaurant_kitchen_pairing(merchant_id) WHERE used_at IS NULL;
