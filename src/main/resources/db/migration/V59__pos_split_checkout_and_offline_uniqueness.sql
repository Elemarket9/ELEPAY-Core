-- V59: Support split-bill digital checkouts via split_key and enforce (terminal_id, offline_tx_id) uniqueness
ALTER TABLE pos_checkout_link
    ADD COLUMN IF NOT EXISTS split_key varchar(64);

DROP INDEX IF EXISTS pos_checkout_one_active;
CREATE UNIQUE INDEX pos_checkout_one_active
    ON pos_checkout_link(order_id, COALESCE(split_key, ''))
    WHERE status IN ('open', 'applied');

ALTER TABLE pos_offline_sync_log
    ADD CONSTRAINT uq_pos_offline_sync_terminal_tx UNIQUE (terminal_id, offline_tx_id);
