-- V82: Phase 2 (pos_order_item KDS round/sent tracking) + Phase 3 (manager roles, void & discount logs, discount threshold).

-- 1. Track fired rounds and soft-void state on pos_order_item
ALTER TABLE pos_order_item
    ADD COLUMN IF NOT EXISTS kds_round integer,
    ADD COLUMN IF NOT EXISTS sent_at   timestamptz,
    ADD COLUMN IF NOT EXISTS voided    boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS voided_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_order_item_unfired
    ON pos_order_item(order_id) WHERE sent_at IS NULL AND NOT voided;

-- Track refunded timestamp on applied digital checkout links (preserves V35 status/journal_tx check constraint)
ALTER TABLE pos_checkout_link
    ADD COLUMN IF NOT EXISTS refunded_at timestamptz;

-- 2. Cashier roles / manager approval flag
ALTER TABLE pos_cashier
    ADD COLUMN IF NOT EXISTS role       varchar(32) NOT NULL DEFAULT 'CASHIER',
    ADD COLUMN IF NOT EXISTS is_manager boolean NOT NULL DEFAULT false;

ALTER TABLE pos_cashier DROP CONSTRAINT IF EXISTS ck_pos_cashier_role;
ALTER TABLE pos_cashier ADD CONSTRAINT ck_pos_cashier_role
    CHECK (role IN ('CASHIER', 'SUPERVISOR', 'MANAGER'));

-- 3. Per-merchant POS control settings (discount PIN threshold)
CREATE TABLE IF NOT EXISTS pos_merchant_settings (
    merchant_id                    uuid PRIMARY KEY REFERENCES merchant(id),
    discount_pin_threshold_pesewas bigint NOT NULL DEFAULT 0 CHECK (discount_pin_threshold_pesewas >= 0),
    discount_pin_threshold_bps     integer CHECK (discount_pin_threshold_bps IS NULL OR (discount_pin_threshold_bps >= 0 AND discount_pin_threshold_bps <= 10000)),
    updated_at                     timestamptz NOT NULL DEFAULT now()
);

-- 4. Void audit log (item-level and order-level)
CREATE TABLE IF NOT EXISTS pos_void_log (
    id               uuid PRIMARY KEY,
    merchant_id      uuid NOT NULL REFERENCES merchant(id),
    branch_id        uuid REFERENCES merchant_branch(id),
    order_id         uuid NOT NULL REFERENCES pos_order(id),
    order_item_id    uuid REFERENCES pos_order_item(id),
    void_scope       varchar(16) NOT NULL CHECK (void_scope IN ('ITEM', 'ORDER')),
    item_name        varchar(128),
    quantity         numeric(12, 4),
    amount_pesewas   bigint NOT NULL CHECK (amount_pesewas >= 0),
    was_fired        boolean NOT NULL DEFAULT false,
    reason           text NOT NULL,
    voided_by        varchar(128) NOT NULL,
    approved_by      uuid REFERENCES pos_cashier(id),
    approved_by_name varchar(128),
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_pos_void_log_merchant_at ON pos_void_log(merchant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_pos_void_log_order ON pos_void_log(order_id);

-- 5. Discount audit log
CREATE TABLE IF NOT EXISTS pos_discount_log (
    id               uuid PRIMARY KEY,
    merchant_id      uuid NOT NULL REFERENCES merchant(id),
    branch_id        uuid REFERENCES merchant_branch(id),
    order_id         uuid NOT NULL REFERENCES pos_order(id),
    order_item_id    uuid REFERENCES pos_order_item(id),
    discount_pesewas bigint NOT NULL CHECK (discount_pesewas > 0),
    reason           text NOT NULL,
    applied_by       varchar(128) NOT NULL,
    approved_by      uuid REFERENCES pos_cashier(id),
    approved_by_name varchar(128),
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_pos_discount_log_merchant_at ON pos_discount_log(merchant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_pos_discount_log_order ON pos_discount_log(order_id);
