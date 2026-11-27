-- V85: Indexes for Phase 6 live order listing (cursor pagination + enrichment)
-- and Phase 7 daily owner reports (Accra business day aggregations & shift cash reconciliation).

CREATE INDEX IF NOT EXISTS idx_pos_order_merchant_branch_created
    ON pos_order(merchant_id, branch_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_pos_order_merchant_status_created
    ON pos_order(merchant_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_kds_ticket_order
    ON restaurant_kds_ticket(order_id);

CREATE INDEX IF NOT EXISTS idx_restaurant_table_current_order
    ON restaurant_table(current_order_id) WHERE current_order_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pos_shift_merchant_start
    ON pos_shift(merchant_id, shift_start DESC);
