-- V69: Auth, Access & Developer Portal Remediation
-- 1. Allow synthetic webhook ping deliveries to log without referencing an outbox_event row.
ALTER TABLE webhook_delivery_log
    ALTER COLUMN event_id DROP NOT NULL;

-- 2. Bound settlement destination out-of-band confirmation code resends.
ALTER TABLE settlement_destination
    ADD COLUMN IF NOT EXISTS oob_resend_count integer NOT NULL DEFAULT 0;
