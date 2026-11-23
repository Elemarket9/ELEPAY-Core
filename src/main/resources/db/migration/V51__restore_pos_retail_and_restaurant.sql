-- V51: Restore POS retail and restaurant/KDS merchant tools (founder decision, Nov 23 2026).
-- Forward-only: V27 and V48 stay in history untouched. This migration re-adds what they
-- dropped. Inventory, CRM, payroll and supplier tables were never dropped and are reused as-is.

-- Undo V48: POS line-item retail/restaurant fields.
ALTER TABLE pos_order_item ADD COLUMN IF NOT EXISTS variant_id uuid REFERENCES inventory_variant(id);
ALTER TABLE pos_order_item ADD COLUMN IF NOT EXISTS modifiers_json text DEFAULT '[]';
ALTER TABLE pos_order_item ADD COLUMN IF NOT EXISTS seat_number integer;
ALTER TABLE pos_order_item ADD COLUMN IF NOT EXISTS course varchar(32);

-- Undo V27: restaurant management and kitchen display tables (definitions as in V22).
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
