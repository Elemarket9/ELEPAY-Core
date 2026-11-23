-- V52: Kitchen Display System built for Ghanaian kitchens.
-- Stations with item routing, daily order numbers, duplicate-safe ticket creation and bumps,
-- a change feed that devices sync from after internet drops, and kitchen printer jobs.

-- Kitchen stations (Kitchen, Grill, Drinks...). Output decides screen, printer or both.
CREATE TABLE IF NOT EXISTS restaurant_kitchen_station (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    branch_id           uuid REFERENCES merchant_branch(id),
    name                varchar(64) NOT NULL,
    is_default          boolean NOT NULL DEFAULT false,
    output              varchar(16) NOT NULL DEFAULT 'SCREEN' CHECK (output IN ('SCREEN', 'PRINTER', 'BOTH')),
    printer_address     varchar(128),
    paper_width         smallint NOT NULL DEFAULT 42 CHECK (paper_width IN (32, 42, 48)),
    active              boolean NOT NULL DEFAULT true,
    last_seen_at        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_kitchen_station_name UNIQUE (merchant_id, name),
    CONSTRAINT ck_station_printer CHECK (output = 'SCREEN' OR printer_address IS NOT NULL)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_kitchen_station_default
    ON restaurant_kitchen_station(merchant_id) WHERE is_default AND active;

-- Which station prepares which product (by variant, or by whole category).
CREATE TABLE IF NOT EXISTS restaurant_station_route (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    station_id          uuid NOT NULL REFERENCES restaurant_kitchen_station(id) ON DELETE CASCADE,
    category_id         uuid REFERENCES inventory_category(id),
    variant_id          uuid REFERENCES inventory_variant(id),
    CONSTRAINT ck_route_target CHECK ((category_id IS NULL) <> (variant_id IS NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_route_variant
    ON restaurant_station_route(merchant_id, variant_id) WHERE variant_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_route_category
    ON restaurant_station_route(merchant_id, category_id) WHERE category_id IS NOT NULL;

-- Short order numbers that reset daily (Accra time), for calling out pickups.
CREATE TABLE IF NOT EXISTS restaurant_kds_counter (
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    business_day        date NOT NULL,
    last_number         integer NOT NULL,
    PRIMARY KEY (merchant_id, business_day)
);

CREATE SEQUENCE IF NOT EXISTS restaurant_kds_change_seq;

ALTER TABLE restaurant_kds_ticket ADD COLUMN IF NOT EXISTS ticket_number integer;
ALTER TABLE restaurant_kds_ticket ADD COLUMN IF NOT EXISTS client_ref varchar(64);
ALTER TABLE restaurant_kds_ticket ADD COLUMN IF NOT EXISTS cancelled_at timestamptz;
ALTER TABLE restaurant_kds_ticket ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE restaurant_kds_ticket ADD COLUMN IF NOT EXISTS change_seq bigint NOT NULL DEFAULT nextval('restaurant_kds_change_seq');
ALTER TABLE restaurant_kds_ticket DROP CONSTRAINT IF EXISTS ck_kds_ticket_status;
ALTER TABLE restaurant_kds_ticket ADD CONSTRAINT ck_kds_ticket_status
    CHECK (status IN ('NEW', 'PREPARING', 'READY', 'SERVED', 'CANCELLED'));
CREATE UNIQUE INDEX IF NOT EXISTS uq_kds_ticket_client_ref
    ON restaurant_kds_ticket(merchant_id, client_ref) WHERE client_ref IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_kds_ticket_sync ON restaurant_kds_ticket(merchant_id, change_seq);

ALTER TABLE restaurant_kds_item ADD COLUMN IF NOT EXISTS station_id uuid REFERENCES restaurant_kitchen_station(id);
ALTER TABLE restaurant_kds_item DROP CONSTRAINT IF EXISTS ck_kds_item_status;
ALTER TABLE restaurant_kds_item ADD CONSTRAINT ck_kds_item_status
    CHECK (item_status IN ('NEW', 'PREPARING', 'READY', 'SERVED', 'CANCELLED'));
CREATE INDEX IF NOT EXISTS idx_kds_item_station ON restaurant_kds_item(station_id);

-- Every change to a ticket or its items moves it forward in the change feed, whatever code path made it.
CREATE OR REPLACE FUNCTION restaurant_kds_ticket_touch() RETURNS trigger AS $$
BEGIN
    NEW.change_seq := nextval('restaurant_kds_change_seq');
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_kds_ticket_touch ON restaurant_kds_ticket;
CREATE TRIGGER trg_kds_ticket_touch BEFORE UPDATE ON restaurant_kds_ticket
    FOR EACH ROW EXECUTE FUNCTION restaurant_kds_ticket_touch();

CREATE OR REPLACE FUNCTION restaurant_kds_item_touch() RETURNS trigger AS $$
BEGIN
    UPDATE restaurant_kds_ticket SET updated_at = now()
    WHERE id = COALESCE(NEW.ticket_id, OLD.ticket_id);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_kds_item_touch ON restaurant_kds_item;
CREATE TRIGGER trg_kds_item_touch AFTER INSERT OR UPDATE OR DELETE ON restaurant_kds_item
    FOR EACH ROW EXECUTE FUNCTION restaurant_kds_item_touch();

-- Actions sent by devices, so a bump retried after a network drop is applied exactly once.
CREATE TABLE IF NOT EXISTS restaurant_kds_action (
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    action_id           varchar(64) NOT NULL,
    ticket_id           uuid NOT NULL REFERENCES restaurant_kds_ticket(id),
    action              varchar(32) NOT NULL,
    applied_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, action_id)
);

-- Kitchen printer jobs, claimed by an Android device on the restaurant's network.
CREATE TABLE IF NOT EXISTS restaurant_kds_print_job (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    station_id          uuid NOT NULL REFERENCES restaurant_kitchen_station(id),
    ticket_id           uuid NOT NULL REFERENCES restaurant_kds_ticket(id),
    kind                varchar(16) NOT NULL CHECK (kind IN ('NEW', 'REPRINT', 'VOID', 'FALLBACK')),
    content             text NOT NULL,
    status              varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CLAIMED', 'PRINTED', 'FAILED')),
    attempts            integer NOT NULL DEFAULT 0,
    claimed_by          varchar(64),
    claimed_at          timestamptz,
    printed_at          timestamptz,
    last_error          text,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_kds_print_job_queue ON restaurant_kds_print_job(merchant_id, status, created_at);
