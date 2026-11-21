-- USSD channel: per-customer PIN with lockout, session expiry.

ALTER TABLE customer ADD COLUMN ussd_pin_hash text;
ALTER TABLE customer ADD COLUMN ussd_pin_failures integer NOT NULL DEFAULT 0;
ALTER TABLE customer ADD COLUMN ussd_locked_until timestamptz;

ALTER TABLE ussd_session ADD COLUMN gateway text NOT NULL DEFAULT 'generic';
ALTER TABLE ussd_session ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX ussd_session_updated ON ussd_session (updated_at);
