-- Platform API: scoped credentials, delegated permissions, order references and verified destinations.

ALTER TABLE api_credential ADD COLUMN scopes text[] NOT NULL DEFAULT '{view,collect,refund}';
ALTER TABLE api_credential ADD CONSTRAINT api_credential_scopes_check
    CHECK (scopes <@ ARRAY['view', 'collect', 'refund', 'withdraw', 'payouts']::text[]);

-- A merchant may let a platform's credentials act for it, per permission.
CREATE TABLE merchant_delegation (
    merchant_id uuid        NOT NULL REFERENCES merchant(id),
    delegate_id uuid        NOT NULL REFERENCES merchant(id),
    permission  text        NOT NULL CHECK (permission IN ('view', 'collect', 'refund', 'withdraw', 'payouts')),
    granted_by  text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, delegate_id, permission),
    CHECK (merchant_id <> delegate_id)
);

ALTER TABLE checkout ADD COLUMN order_reference text;
CREATE UNIQUE INDEX checkout_order_reference ON checkout (collector_id, order_reference) WHERE order_reference IS NOT NULL;

ALTER TABLE settlement_destination ADD COLUMN verification text NOT NULL DEFAULT 'unverified'
    CHECK (verification IN ('verified', 'unverified'));
ALTER TABLE settlement_destination ADD COLUMN verified_name text;
ALTER TABLE settlement_destination ADD COLUMN verified_by text;

CREATE INDEX outbox_event_merchants ON outbox_event USING gin (merchant_ids);
