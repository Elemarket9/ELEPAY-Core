-- V86: Receipt printing via KDS print pipeline, branch-aware default stations & routes,
-- and auto-ready (no-prep) route flag for fridge/grab-and-go items.

-- 1. Print pipeline: allow RECEIPT jobs without a KDS ticket or kitchen station.
ALTER TABLE restaurant_kds_print_job DROP CONSTRAINT IF EXISTS restaurant_kds_print_job_kind_check;
ALTER TABLE restaurant_kds_print_job
    ADD CONSTRAINT restaurant_kds_print_job_kind_check
    CHECK (kind IN ('NEW', 'REPRINT', 'VOID', 'FALLBACK', 'RECEIPT'));

ALTER TABLE restaurant_kds_print_job ALTER COLUMN station_id DROP NOT NULL;
ALTER TABLE restaurant_kds_print_job ALTER COLUMN ticket_id DROP NOT NULL;
ALTER TABLE restaurant_kds_print_job ADD COLUMN IF NOT EXISTS order_id uuid REFERENCES pos_order(id);
ALTER TABLE restaurant_kds_print_job ADD COLUMN IF NOT EXISTS branch_id uuid REFERENCES merchant_branch(id);
ALTER TABLE restaurant_kds_print_job ADD COLUMN IF NOT EXISTS paper_width smallint NOT NULL DEFAULT 42
    CHECK (paper_width IN (32, 42, 48));

ALTER TABLE restaurant_kds_print_job DROP CONSTRAINT IF EXISTS ck_kds_print_job_target;
ALTER TABLE restaurant_kds_print_job
    ADD CONSTRAINT ck_kds_print_job_target CHECK (
        (kind = 'RECEIPT' AND order_id IS NOT NULL)
        OR (kind <> 'RECEIPT' AND station_id IS NOT NULL AND ticket_id IS NOT NULL)
    );

CREATE INDEX IF NOT EXISTS idx_kds_print_job_kind_queue
    ON restaurant_kds_print_job(merchant_id, kind, status, created_at);

-- 2. Branch-aware default stations & station names.
DROP INDEX IF EXISTS uq_kitchen_station_default;
CREATE UNIQUE INDEX IF NOT EXISTS uq_kitchen_station_default_branch
    ON restaurant_kitchen_station(merchant_id, COALESCE(branch_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE is_default AND active;

ALTER TABLE restaurant_kitchen_station DROP CONSTRAINT IF EXISTS uq_kitchen_station_name;
CREATE UNIQUE INDEX IF NOT EXISTS uq_kitchen_station_name_branch
    ON restaurant_kitchen_station(merchant_id, COALESCE(branch_id, '00000000-0000-0000-0000-000000000000'::uuid), name);

-- 3. Branch-aware station routes + auto_ready flag.
ALTER TABLE restaurant_station_route ADD COLUMN IF NOT EXISTS branch_id uuid REFERENCES merchant_branch(id);
ALTER TABLE restaurant_station_route ADD COLUMN IF NOT EXISTS auto_ready boolean NOT NULL DEFAULT false;

UPDATE restaurant_station_route r
SET branch_id = s.branch_id
FROM restaurant_kitchen_station s
WHERE s.id = r.station_id AND r.branch_id IS NULL AND s.branch_id IS NOT NULL;

DROP INDEX IF EXISTS uq_route_variant;
CREATE UNIQUE INDEX IF NOT EXISTS uq_route_variant_branch
    ON restaurant_station_route(merchant_id, COALESCE(branch_id, '00000000-0000-0000-0000-000000000000'::uuid), variant_id)
    WHERE variant_id IS NOT NULL;

DROP INDEX IF EXISTS uq_route_category;
CREATE UNIQUE INDEX IF NOT EXISTS uq_route_category_branch
    ON restaurant_station_route(merchant_id, COALESCE(branch_id, '00000000-0000-0000-0000-000000000000'::uuid), category_id)
    WHERE category_id IS NOT NULL;

-- 4. Track auto_ready on KDS items so station screens display fridge items as READY until served.
ALTER TABLE restaurant_kds_item ADD COLUMN IF NOT EXISTS auto_ready boolean NOT NULL DEFAULT false;
