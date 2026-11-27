-- V84: BANK_TRANSFER tender support + persisted payable split bills (even, seat, item)

-- 1. Enforce valid tender types including BANK_TRANSFER and track split_key on tenders
ALTER TABLE pos_tender_payment
    ADD COLUMN IF NOT EXISTS split_key varchar(64);

ALTER TABLE pos_tender_payment
    DROP CONSTRAINT IF EXISTS chk_pos_tender_type;

ALTER TABLE pos_tender_payment
    ADD CONSTRAINT chk_pos_tender_type
    CHECK (tender_type IN ('CASH', 'MOMO', 'CARD', 'WALLET', 'GHQR', 'BANK_TRANSFER'));

CREATE INDEX IF NOT EXISTS idx_pos_tender_order_split
    ON pos_tender_payment(order_id, split_key)
    WHERE split_key IS NOT NULL;

-- 2. Persisted sub-bills for an order (split_type: EVEN, SEAT, ITEM)
CREATE TABLE IF NOT EXISTS pos_order_split (
    id              uuid PRIMARY KEY,
    order_id        uuid NOT NULL REFERENCES pos_order(id) ON DELETE CASCADE,
    split_key       varchar(64) NOT NULL,
    split_type      varchar(16) NOT NULL CHECK (split_type IN ('EVEN', 'SEAT', 'ITEM')),
    label           varchar(64) NOT NULL,
    seat_number     integer,
    amount_pesewas  bigint NOT NULL CHECK (amount_pesewas >= 0),
    paid_pesewas    bigint NOT NULL DEFAULT 0 CHECK (paid_pesewas >= 0),
    status          varchar(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PARTIAL', 'PAID')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    paid_at         timestamptz,
    CONSTRAINT uq_pos_order_split_key UNIQUE (order_id, split_key)
);

CREATE INDEX IF NOT EXISTS idx_pos_order_split_order
    ON pos_order_split(order_id);

-- 3. Item assignments for ITEM (and SEAT) sub-bills; each order item belongs to at most one sub-bill
CREATE TABLE IF NOT EXISTS pos_order_split_item (
    split_id        uuid NOT NULL REFERENCES pos_order_split(id) ON DELETE CASCADE,
    order_item_id   uuid NOT NULL REFERENCES pos_order_item(id) ON DELETE CASCADE,
    PRIMARY KEY (split_id, order_item_id),
    CONSTRAINT uq_pos_split_item_once UNIQUE (order_item_id)
);
