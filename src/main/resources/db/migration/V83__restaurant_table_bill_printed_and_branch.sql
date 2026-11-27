-- V83: Table BILL_PRINTED status check + branch_id column & backfill (Phase 4)
ALTER TABLE restaurant_table
    DROP CONSTRAINT IF EXISTS ck_restaurant_table_status;
ALTER TABLE restaurant_table
    ADD CONSTRAINT ck_restaurant_table_status
    CHECK (status IN ('AVAILABLE', 'OCCUPIED', 'BILL_PRINTED', 'RESERVED', 'DIRTY'));

ALTER TABLE restaurant_table
    ADD COLUMN IF NOT EXISTS branch_id uuid REFERENCES merchant_branch(id);

UPDATE restaurant_table t
SET branch_id = COALESCE(
    t.waiter_branch_id,
    (SELECT o.branch_id FROM pos_order o WHERE o.id = t.current_order_id)
)
WHERE t.branch_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_restaurant_table_merchant_branch
    ON restaurant_table(merchant_id, branch_id);
