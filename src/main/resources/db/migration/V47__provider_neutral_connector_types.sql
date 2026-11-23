-- ELEPAY is provider-neutral: no connector type is named after a specific enterprise or partner.
-- Replaces the partner-specific 'JUMIAPAY' connector type with the generic 'EXTERNAL_WALLET'.
-- Applied migrations (V41, V42) are left unchanged so Flyway checksums stay valid.

UPDATE enterprise_connector_config SET connector_type = 'EXTERNAL_WALLET' WHERE connector_type = 'JUMIAPAY';

ALTER TABLE enterprise_connector_config DROP CONSTRAINT IF EXISTS enterprise_connector_config_connector_type_check;

ALTER TABLE enterprise_connector_config
    ADD CONSTRAINT enterprise_connector_config_connector_type_check
    CHECK (connector_type IN ('EXTERNAL_WALLET', 'REST_WEBHOOK', 'SFTP_RECON'));
