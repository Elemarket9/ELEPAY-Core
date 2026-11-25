-- Waiter phones pair once with a manager-issued code for a named waiter (like kitchen tablets), instead of
-- pasting identity-provider tokens that expire mid-shift. The device key is bound to that waiter and branch.
ALTER TABLE api_credential DROP CONSTRAINT IF EXISTS api_credential_key_type_check;
ALTER TABLE api_credential ADD CONSTRAINT api_credential_key_type_check
    CHECK (key_type IN ('publishable', 'secret', 'payout', 'legacy', 'kitchen', 'pos_terminal', 'waiter')) NOT VALID;

CREATE TABLE restaurant_waiter_pairing (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    waiter_subject      text NOT NULL,
    code_hash           varchar(64) NOT NULL,
    created_by          text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    expires_at          timestamptz NOT NULL,
    failed_attempts     integer NOT NULL DEFAULT 0,
    used_at             timestamptz,
    credential_id       uuid REFERENCES api_credential(id)
);
CREATE INDEX idx_waiter_pairing_open ON restaurant_waiter_pairing(merchant_id) WHERE used_at IS NULL;

CREATE TABLE restaurant_waiter_device (
    credential_id       uuid PRIMARY KEY REFERENCES api_credential(id),
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    waiter_subject      text NOT NULL,
    device_name         varchar(64) NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    revoked_at          timestamptz
);
CREATE INDEX idx_waiter_device_merchant ON restaurant_waiter_device(merchant_id, waiter_subject);

-- Tax applied to waiter (dine-in) orders, in basis points of the order subtotal (1500 = 15%).
-- Zero keeps the previous behaviour until a manager sets the restaurant's rate.
CREATE TABLE restaurant_settings (
    merchant_id         uuid PRIMARY KEY REFERENCES merchant(id),
    tax_basis_points    integer NOT NULL DEFAULT 0 CHECK (tax_basis_points BETWEEN 0 AND 5000),
    updated_by          text NOT NULL,
    updated_at          timestamptz NOT NULL DEFAULT now()
);
