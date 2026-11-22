-- V25: Developer Platform, Integrations & Web Portals
-- Domains:
--   Domain 12: Developer & Partner Portals
--   Domain 2: Business BackOffice Integrations
--   Domain 13: Customer, Merchant & Operations Web Extensions

-- 1. Extend api_credential for live/test environments, key types, and usage telemetry
ALTER TABLE api_credential ADD COLUMN IF NOT EXISTS environment varchar(16) NOT NULL DEFAULT 'live'
    CHECK (environment IN ('live', 'test'));
ALTER TABLE api_credential ADD COLUMN IF NOT EXISTS key_type varchar(32) NOT NULL DEFAULT 'api_key';
ALTER TABLE api_credential ADD COLUMN IF NOT EXISTS last_used_at timestamptz;
ALTER TABLE api_credential ADD COLUMN IF NOT EXISTS expires_at timestamptz;

-- 2. Developer Applications / Integrations
CREATE TABLE developer_app (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    name                varchar(128) NOT NULL,
    description         text,
    app_type            varchar(32) NOT NULL DEFAULT 'MERCHANT_INTEGRATION', -- ECOMMERCE_PLUGIN, MOBILE_APP, SAAS, CUSTOM
    environment         varchar(16) NOT NULL DEFAULT 'live' CHECK (environment IN ('live', 'test')),
    status              varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    client_id           varchar(64) UNIQUE,
    created_by          text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_developer_app_merchant ON developer_app (merchant_id);

-- 3. Extend webhook_endpoint for multi-environment, custom subscribed events, failure tracking
ALTER TABLE webhook_endpoint ADD COLUMN IF NOT EXISTS environment varchar(16) NOT NULL DEFAULT 'live'
    CHECK (environment IN ('live', 'test'));
ALTER TABLE webhook_endpoint ADD COLUMN IF NOT EXISTS description text;
ALTER TABLE webhook_endpoint ADD COLUMN IF NOT EXISTS event_types text[] NOT NULL DEFAULT '{checkout.paid,payment.failed,payout.completed,refund.created,mandate.active}';
ALTER TABLE webhook_endpoint ADD COLUMN IF NOT EXISTS failure_count integer NOT NULL DEFAULT 0;
ALTER TABLE webhook_endpoint ADD COLUMN IF NOT EXISTS last_delivery_at timestamptz;
ALTER TABLE webhook_endpoint ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

-- 4. Real-time Webhook Delivery Logs for Inspection and Manual Retry Replay
CREATE TABLE webhook_delivery_log (
    id                  uuid PRIMARY KEY,
    delivery_id         uuid NOT NULL,
    endpoint_id         uuid NOT NULL REFERENCES webhook_endpoint(id),
    event_id            uuid NOT NULL REFERENCES outbox_event(id),
    merchant_id         uuid NOT NULL,
    event_type          varchar(64) NOT NULL,
    request_url         text NOT NULL,
    request_headers     jsonb NOT NULL DEFAULT '{}',
    request_payload     jsonb NOT NULL,
    response_code       integer,
    response_headers    jsonb,
    response_body       text,
    duration_ms         integer NOT NULL DEFAULT 0,
    status              varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED', 'RETRYING', 'DEAD')),
    attempt_number      integer NOT NULL DEFAULT 1,
    is_manual_retry     boolean NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_webhook_delivery_log_endpoint ON webhook_delivery_log (endpoint_id, created_at DESC);
CREATE INDEX idx_webhook_delivery_log_merchant ON webhook_delivery_log (merchant_id, created_at DESC);
CREATE INDEX idx_webhook_delivery_log_event ON webhook_delivery_log (event_id);

-- 5. Partner Certification & Pre-built Integrations
CREATE TABLE partner_certification (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    partner_name        varchar(128) NOT NULL,
    partner_type        varchar(64) NOT NULL, -- WOOCOMMERCE, SHOPIFY, MAGENTO, ERP, POS_INTEGRATOR, CUSTOM
    certification_level varchar(32) NOT NULL DEFAULT 'SANDBOX_VERIFIED' CHECK (certification_level IN ('SANDBOX_VERIFIED', 'STAGING_TESTED', 'PRODUCTION_CERTIFIED')),
    test_runs_passed    integer NOT NULL DEFAULT 0,
    compliance_checklist jsonb NOT NULL DEFAULT '{}',
    certified_at        timestamptz,
    expires_at          timestamptz,
    status              varchar(32) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'APPROVED', 'REJECTED', 'EXPIRED')),
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_partner_cert_merchant ON partner_certification (merchant_id);

-- 6. Developer SDK Telemetry & Client Analytics
CREATE TABLE developer_sdk_telemetry (
    id                  uuid PRIMARY KEY,
    merchant_id         uuid NOT NULL,
    language            varchar(32) NOT NULL, -- nodejs, python, php, java, curl, csharp, go
    sdk_version         varchar(32) NOT NULL,
    endpoint_called     varchar(128) NOT NULL,
    status_code         integer NOT NULL,
    duration_ms         integer NOT NULL,
    recorded_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_sdk_telemetry_merchant ON developer_sdk_telemetry (merchant_id, recorded_at DESC);
