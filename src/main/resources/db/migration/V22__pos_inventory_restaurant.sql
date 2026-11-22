-- V22: POS, Inventory & Warehouse Management, Restaurant Management & KDS
-- Covers:
-- Domain 1: ELEPAY POS (terminals, cashiers, shifts, cart, multi-tender, split bills, receipts, offline sync)
-- Domain 3: Inventory & Warehouse Management (warehouses, suppliers, categories, products, variants, stock levels, movements, batches, transfer orders, purchase orders, low stock alerts)
-- Domain 4: Restaurant Management & KDS (floor plans, tables, dining modes, modifiers, recipes, costing, KDS live tickets, bump bar)

-- ============================================================================
-- 1. INVENTORY & WAREHOUSE MANAGEMENT DOMAIN
-- ============================================================================

CREATE TABLE IF NOT EXISTS inventory_warehouse (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    branch_id           uuid REFERENCES merchant_branch(id),
    code                varchar(32) NOT NULL,
    name                varchar(128) NOT NULL,
    address             text,
    is_active           boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_warehouse_merchant_code UNIQUE (merchant_id, code)
);
CREATE INDEX IF NOT EXISTS idx_warehouse_merchant ON inventory_warehouse(merchant_id);

CREATE TABLE IF NOT EXISTS inventory_supplier (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    name                varchar(128) NOT NULL,
    contact_email       varchar(128),
    contact_phone       varchar(32),
    address             text,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_supplier_merchant ON inventory_supplier(merchant_id);

CREATE TABLE IF NOT EXISTS inventory_category (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    name                varchar(64) NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_category_merchant_name UNIQUE (merchant_id, name)
);

CREATE TABLE IF NOT EXISTS inventory_product (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    category_id         uuid REFERENCES inventory_category(id),
    name                varchar(128) NOT NULL,
    description         text,
    brand               varchar(64),
    is_active           boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_product_merchant ON inventory_product(merchant_id);

CREATE TABLE IF NOT EXISTS inventory_variant (
    id                  uuid PRIMARY KEY,
    product_id          uuid NOT NULL REFERENCES inventory_product(id) ON DELETE CASCADE,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    sku                 varchar(64) NOT NULL,
    barcode             varchar(64),
    variant_name        varchar(128) NOT NULL,
    attributes_json     text DEFAULT '{}',
    cost_price_pesewas  bigint NOT NULL DEFAULT 0,
    retail_price_pesewas bigint NOT NULL DEFAULT 0,
    track_inventory     boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_variant_sku UNIQUE (merchant_id, sku)
);
CREATE INDEX IF NOT EXISTS idx_variant_product ON inventory_variant(product_id);
CREATE INDEX IF NOT EXISTS idx_variant_barcode ON inventory_variant(merchant_id, barcode);

CREATE TABLE IF NOT EXISTS inventory_stock (
    id                  uuid PRIMARY KEY,
    warehouse_id        uuid NOT NULL REFERENCES inventory_warehouse(id),
    variant_id          uuid NOT NULL REFERENCES inventory_variant(id),
    quantity_on_hand    numeric(12, 4) NOT NULL DEFAULT 0,
    quantity_reserved   numeric(12, 4) NOT NULL DEFAULT 0,
    reorder_point       numeric(12, 4) NOT NULL DEFAULT 10,
    reorder_quantity    numeric(12, 4) NOT NULL DEFAULT 50,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_stock_warehouse_variant UNIQUE (warehouse_id, variant_id)
);
CREATE INDEX IF NOT EXISTS idx_stock_variant ON inventory_stock(variant_id);

CREATE TABLE IF NOT EXISTS inventory_stock_movement (
    id                  uuid PRIMARY KEY,
    stock_id            uuid NOT NULL REFERENCES inventory_stock(id),
    variant_id          uuid NOT NULL REFERENCES inventory_variant(id),
    warehouse_id        uuid NOT NULL REFERENCES inventory_warehouse(id),
    quantity_change     numeric(12, 4) NOT NULL,
    quantity_after      numeric(12, 4) NOT NULL,
    movement_type       varchar(32) NOT NULL,
    reference_id        uuid,
    notes               text,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_stock_movement_stock ON inventory_stock_movement(stock_id);

CREATE TABLE IF NOT EXISTS inventory_batch (
    id                  uuid PRIMARY KEY,
    variant_id          uuid NOT NULL REFERENCES inventory_variant(id),
    warehouse_id        uuid NOT NULL REFERENCES inventory_warehouse(id),
    batch_number        varchar(64) NOT NULL,
    expiry_date         date,
    quantity            numeric(12, 4) NOT NULL DEFAULT 0,
    cost_price_pesewas  bigint NOT NULL DEFAULT 0,
    status              varchar(32) NOT NULL DEFAULT 'ACTIVE',
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_batch_variant ON inventory_batch(variant_id, warehouse_id);

CREATE TABLE IF NOT EXISTS inventory_transfer_order (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL REFERENCES merchant(id),
    source_warehouse_id     uuid NOT NULL REFERENCES inventory_warehouse(id),
    destination_warehouse_id uuid NOT NULL REFERENCES inventory_warehouse(id),
    status                  varchar(32) NOT NULL DEFAULT 'REQUESTED',
    tracking_number         varchar(64),
    notes                   text,
    dispatched_at           timestamptz,
    received_at             timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_transfer_merchant ON inventory_transfer_order(merchant_id);

CREATE TABLE IF NOT EXISTS inventory_transfer_item (
    id                  uuid PRIMARY KEY,
    transfer_id         uuid NOT NULL REFERENCES inventory_transfer_order(id) ON DELETE CASCADE,
    variant_id          uuid NOT NULL REFERENCES inventory_variant(id),
    quantity_sent       numeric(12, 4) NOT NULL,
    quantity_received   numeric(12, 4) DEFAULT 0
);

CREATE TABLE IF NOT EXISTS inventory_purchase_order (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    supplier_id         uuid NOT NULL REFERENCES inventory_supplier(id),
    warehouse_id        uuid NOT NULL REFERENCES inventory_warehouse(id),
    po_number           varchar(64) NOT NULL UNIQUE,
    status              varchar(32) NOT NULL DEFAULT 'DRAFT',
    total_cost_pesewas  bigint NOT NULL DEFAULT 0,
    issued_at           timestamptz,
    completed_at        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_po_merchant ON inventory_purchase_order(merchant_id);

CREATE TABLE IF NOT EXISTS inventory_po_item (
    id                  uuid PRIMARY KEY,
    po_id               uuid NOT NULL REFERENCES inventory_purchase_order(id) ON DELETE CASCADE,
    variant_id          uuid NOT NULL REFERENCES inventory_variant(id),
    unit_cost_pesewas   bigint NOT NULL,
    quantity_ordered    numeric(12, 4) NOT NULL,
    quantity_received   numeric(12, 4) NOT NULL DEFAULT 0
);


-- ============================================================================
-- 2. ELEPAY POS DOMAIN
-- ============================================================================

CREATE TABLE IF NOT EXISTS pos_terminal (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    branch_id           uuid REFERENCES merchant_branch(id),
    serial_number       varchar(64) NOT NULL UNIQUE,
    model               varchar(64) NOT NULL,
    pairing_code        varchar(16),
    secret_key          varchar(128) NOT NULL,
    hardware_fingerprint varchar(128),
    status              varchar(32) NOT NULL DEFAULT 'INACTIVE',
    paired_at           timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_terminal_merchant ON pos_terminal(merchant_id);

CREATE TABLE IF NOT EXISTS pos_cashier (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    name                varchar(128) NOT NULL,
    pin_hash            varchar(128) NOT NULL,
    badge_number        varchar(32),
    is_active           boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_pos_cashier_badge UNIQUE (merchant_id, badge_number)
);
CREATE INDEX IF NOT EXISTS idx_cashier_merchant ON pos_cashier(merchant_id);

CREATE TABLE IF NOT EXISTS pos_shift (
    id                          uuid PRIMARY KEY,
    terminal_id                 uuid NOT NULL REFERENCES pos_terminal(id),
    cashier_id                  uuid NOT NULL REFERENCES pos_cashier(id),
    merchant_id                 uuid NOT NULL REFERENCES merchant(id),
    opening_balance_pesewas     bigint NOT NULL DEFAULT 0,
    closing_balance_pesewas     bigint,
    expected_cash_pesewas       bigint NOT NULL DEFAULT 0,
    cash_discrepancy_pesewas    bigint,
    status                      varchar(32) NOT NULL DEFAULT 'OPEN',
    shift_start                 timestamptz NOT NULL DEFAULT now(),
    shift_end                   timestamptz,
    notes                       text
);
CREATE INDEX IF NOT EXISTS idx_shift_terminal ON pos_shift(terminal_id, status);

CREATE TABLE IF NOT EXISTS pos_cart_held (
    id                  uuid PRIMARY KEY,
    terminal_id         uuid NOT NULL REFERENCES pos_terminal(id),
    cashier_id          uuid NOT NULL REFERENCES pos_cashier(id),
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    customer_name       varchar(128),
    cart_payload_json   text NOT NULL,
    total_pesewas       bigint NOT NULL,
    held_at             timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_held_terminal ON pos_cart_held(terminal_id);

CREATE TABLE IF NOT EXISTS pos_order (
    id                  uuid PRIMARY KEY,
    order_number        varchar(64) NOT NULL UNIQUE,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    branch_id           uuid REFERENCES merchant_branch(id),
    terminal_id         uuid REFERENCES pos_terminal(id),
    cashier_id          uuid REFERENCES pos_cashier(id),
    customer_id         uuid,
    subtotal_pesewas    bigint NOT NULL,
    discount_pesewas    bigint NOT NULL DEFAULT 0,
    tax_pesewas         bigint NOT NULL DEFAULT 0,
    tip_pesewas         bigint NOT NULL DEFAULT 0,
    total_pesewas       bigint NOT NULL,
    paid_pesewas        bigint NOT NULL DEFAULT 0,
    change_pesewas      bigint NOT NULL DEFAULT 0,
    status              varchar(32) NOT NULL DEFAULT 'PENDING',
    order_type          varchar(32) NOT NULL DEFAULT 'STANDARD',
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_pos_order_merchant ON pos_order(merchant_id, created_at);

CREATE TABLE IF NOT EXISTS pos_order_item (
    id                  uuid PRIMARY KEY,
    order_id            uuid NOT NULL REFERENCES pos_order(id) ON DELETE CASCADE,
    variant_id          uuid REFERENCES inventory_variant(id),
    item_name           varchar(128) NOT NULL,
    quantity            numeric(12, 4) NOT NULL,
    unit_price_pesewas  bigint NOT NULL,
    discount_pesewas    bigint NOT NULL DEFAULT 0,
    tax_pesewas         bigint NOT NULL DEFAULT 0,
    total_pesewas       bigint NOT NULL,
    modifiers_json      text DEFAULT '[]',
    seat_number         integer,
    course              varchar(32),
    notes               text
);
CREATE INDEX IF NOT EXISTS idx_order_item_order ON pos_order_item(order_id);

CREATE TABLE IF NOT EXISTS pos_tender_payment (
    id                  uuid PRIMARY KEY,
    order_id            uuid NOT NULL REFERENCES pos_order(id) ON DELETE CASCADE,
    tender_type         varchar(32) NOT NULL,
    amount_pesewas      bigint NOT NULL,
    reference           varchar(128),
    status              varchar(32) NOT NULL DEFAULT 'SUCCESS',
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tender_order ON pos_tender_payment(order_id);

CREATE TABLE IF NOT EXISTS pos_receipt (
    id                  uuid PRIMARY KEY,
    receipt_number      varchar(64) NOT NULL UNIQUE,
    order_id            uuid NOT NULL REFERENCES pos_order(id),
    digital_receipt_url varchar(256),
    qr_payload          text,
    escpos_formatted_text text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_receipt_order ON pos_receipt(order_id);

CREATE TABLE IF NOT EXISTS pos_offline_sync_log (
    id                  uuid PRIMARY KEY,
    terminal_id         uuid NOT NULL REFERENCES pos_terminal(id),
    offline_tx_id       varchar(128) NOT NULL,
    idempotency_key     varchar(128) NOT NULL UNIQUE,
    sync_status         varchar(32) NOT NULL,
    conflict_reason     text,
    resolution_applied  varchar(64),
    synced_at           timestamptz NOT NULL DEFAULT now()
);


-- ============================================================================
-- 3. RESTAURANT MANAGEMENT & KDS DOMAIN
-- ============================================================================

CREATE TABLE IF NOT EXISTS restaurant_floor_plan (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    name                varchar(64) NOT NULL,
    layout_json         text DEFAULT '{}',
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_floor_plan_merchant ON restaurant_floor_plan(merchant_id);

CREATE TABLE IF NOT EXISTS restaurant_table (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    section_id          uuid NOT NULL REFERENCES restaurant_floor_plan(id) ON DELETE CASCADE,
    table_number        varchar(32) NOT NULL,
    capacity            integer NOT NULL DEFAULT 4,
    status              varchar(32) NOT NULL DEFAULT 'AVAILABLE',
    current_order_id    uuid REFERENCES pos_order(id),
    CONSTRAINT uq_restaurant_table UNIQUE (merchant_id, table_number)
);
CREATE INDEX IF NOT EXISTS idx_restaurant_table_section ON restaurant_table(section_id);

CREATE TABLE IF NOT EXISTS restaurant_modifier_group (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    name                varchar(64) NOT NULL,
    min_selection       integer NOT NULL DEFAULT 0,
    max_selection       integer NOT NULL DEFAULT 1,
    is_required         boolean NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS restaurant_modifier_option (
    id                      uuid PRIMARY KEY,
    group_id                uuid NOT NULL REFERENCES restaurant_modifier_group(id) ON DELETE CASCADE,
    name                    varchar(64) NOT NULL,
    additional_price_pesewas bigint NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_modifier_option_group ON restaurant_modifier_option(group_id);

CREATE TABLE IF NOT EXISTS restaurant_recipe (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL REFERENCES merchant(id),
    menu_item_variant_id    uuid NOT NULL REFERENCES inventory_variant(id),
    recipe_name             varchar(128) NOT NULL,
    yield_quantity          numeric(12, 4) NOT NULL DEFAULT 1,
    preparation_instructions text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_recipe_variant UNIQUE (merchant_id, menu_item_variant_id)
);

CREATE TABLE IF NOT EXISTS restaurant_recipe_ingredient (
    id                      uuid PRIMARY KEY,
    recipe_id               uuid NOT NULL REFERENCES restaurant_recipe(id) ON DELETE CASCADE,
    ingredient_variant_id   uuid NOT NULL REFERENCES inventory_variant(id),
    quantity_required       numeric(12, 4) NOT NULL,
    unit_of_measure         varchar(32) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_recipe_ingredient_recipe ON restaurant_recipe_ingredient(recipe_id);

CREATE TABLE IF NOT EXISTS restaurant_kds_ticket (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    order_id            uuid NOT NULL REFERENCES pos_order(id),
    table_id            uuid REFERENCES restaurant_table(id),
    dining_mode         varchar(32) NOT NULL DEFAULT 'DINE_IN',
    server_name         varchar(64),
    status              varchar(32) NOT NULL DEFAULT 'NEW',
    course              varchar(32) DEFAULT 'ALL',
    notes               text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    started_at          timestamptz,
    ready_at            timestamptz,
    served_at           timestamptz
);
CREATE INDEX IF NOT EXISTS idx_kds_ticket_status ON restaurant_kds_ticket(merchant_id, status);

CREATE TABLE IF NOT EXISTS restaurant_kds_item (
    id                  uuid PRIMARY KEY,
    ticket_id           uuid NOT NULL REFERENCES restaurant_kds_ticket(id) ON DELETE CASCADE,
    order_item_id       uuid NOT NULL REFERENCES pos_order_item(id),
    item_name           varchar(128) NOT NULL,
    quantity            numeric(12, 4) NOT NULL,
    modifiers           text,
    course              varchar(32) NOT NULL DEFAULT 'MAIN',
    item_status         varchar(32) NOT NULL DEFAULT 'NEW'
);
CREATE INDEX IF NOT EXISTS idx_kds_item_ticket ON restaurant_kds_item(ticket_id);
