-- V58: Network card token table + monotonic ATC replay protection, and custodian reservation_date on rail_transaction

CREATE TABLE IF NOT EXISTS network_card_token (
    id                  uuid PRIMARY KEY,
    card_id             uuid NOT NULL REFERENCES card_registry(id) ON DELETE CASCADE,
    token_pan           varchar(19) NOT NULL UNIQUE,
    token_requester_id  varchar(64) NOT NULL,
    device_id           varchar(128),
    expiry_month        integer NOT NULL,
    expiry_year         integer NOT NULL,
    status              varchar(32) NOT NULL DEFAULT 'ACTIVE',
    last_atc            bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE network_card_token ADD COLUMN IF NOT EXISTS last_atc bigint NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_net_token_card ON network_card_token(card_id);
CREATE INDEX IF NOT EXISTS idx_net_token_pan ON network_card_token(token_pan);

ALTER TABLE rail_transaction ADD COLUMN IF NOT EXISTS reservation_date date;
