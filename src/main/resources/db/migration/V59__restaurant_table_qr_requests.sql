-- Table QR is an untrusted customer request, NOT a payment authorization.
-- Anonymous submissions never create kitchen tickets until a merchant manager accepts.
CREATE TABLE IF NOT EXISTS restaurant_table_qr (
    table_id uuid PRIMARY KEY REFERENCES restaurant_table(id),
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    token_hash char(64) NOT NULL UNIQUE,
    enabled boolean NOT NULL DEFAULT true,
    rotated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS restaurant_table_qr_request (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    table_id uuid NOT NULL REFERENCES restaurant_table(id),
    client_key varchar(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ACCEPTED','REJECTED')),
    server_order_id uuid REFERENCES pos_order(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    decided_at timestamptz,
    CONSTRAINT uq_table_qr_request UNIQUE (table_id,client_key)
);
CREATE INDEX IF NOT EXISTS ix_qr_request_queue
    ON restaurant_table_qr_request(merchant_id,status,created_at DESC);
CREATE TABLE IF NOT EXISTS restaurant_table_qr_request_item (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES restaurant_table_qr_request(id) ON DELETE CASCADE,
    variant_id uuid NOT NULL REFERENCES inventory_variant(id),
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    quoted_price_pesewas bigint NOT NULL CHECK (quoted_price_pesewas >= 0),
    notes varchar(200),
    CONSTRAINT uq_table_qr_item UNIQUE (request_id,variant_id)
);
