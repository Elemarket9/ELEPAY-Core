-- V66: Scope mandate_charge and pos_offline_sync_log idempotency keys per mandate/terminal
-- so different merchants/mandates/terminals can never collide or leak across tenants.

ALTER TABLE mandate_charge DROP CONSTRAINT IF EXISTS mandate_charge_idempotency_key_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_mandate_charge_mandate_idem
    ON mandate_charge (mandate_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ALTER TABLE pos_offline_sync_log DROP CONSTRAINT IF EXISTS pos_offline_sync_log_idempotency_key_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_pos_offline_sync_terminal_idem
    ON pos_offline_sync_log (terminal_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
