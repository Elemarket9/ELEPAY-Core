-- Firebase Cloud Messaging (FCM HTTP v1) device push token registry.
-- Owned by com.elepay.common (FirebasePushService).

CREATE TABLE push_device_token (
    id           UUID PRIMARY KEY,
    subject      TEXT NOT NULL,
    merchant_id  UUID,
    platform     TEXT NOT NULL CHECK (platform IN ('android', 'ios', 'web')),
    fcm_token    TEXT NOT NULL UNIQUE,
    app_id       TEXT NOT NULL DEFAULT 'merchant-app',
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_push_device_token_subject_active
    ON push_device_token (subject)
    WHERE active = TRUE;

CREATE INDEX idx_push_device_token_merchant_active
    ON push_device_token (merchant_id)
    WHERE active = TRUE AND merchant_id IS NOT NULL;
