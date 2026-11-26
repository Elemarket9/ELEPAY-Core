-- V68: Persist canonical payload SHA-256 hash and server_order_id on pos_offline_sync_log
-- to support deterministic idempotency replay and 409 idempotency_conflict detection.

ALTER TABLE pos_offline_sync_log
    ADD COLUMN IF NOT EXISTS payload_hash varchar(64),
    ADD COLUMN IF NOT EXISTS server_order_id uuid REFERENCES pos_order(id);
