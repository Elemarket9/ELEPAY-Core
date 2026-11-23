-- Stage 3: durable POS order replay identity and authoritative sync receipt.
-- Historic rows remain intact; no destructive reset of existing offline records.
ALTER TABLE pos_offline_sync_log ADD COLUMN IF NOT EXISTS merchant_id uuid REFERENCES merchant(id);
ALTER TABLE pos_offline_sync_log ADD COLUMN IF NOT EXISTS server_order_id uuid REFERENCES pos_order(id);
ALTER TABLE pos_offline_sync_log ADD COLUMN IF NOT EXISTS payload_hash varchar(64);
ALTER TABLE pos_offline_sync_log ALTER COLUMN terminal_id DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_pos_offline_merchant_tx
    ON pos_offline_sync_log (merchant_id, offline_tx_id)
    WHERE merchant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_pos_offline_reconciled
    ON pos_offline_sync_log (merchant_id, synced_at DESC);
