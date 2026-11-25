-- Native user authentication accounts and dual-channel (Email + SMS) OTP verification challenges.

CREATE TABLE IF NOT EXISTS auth_account (
    id                    UUID PRIMARY KEY,
    email                 TEXT NOT NULL UNIQUE,
    phone                 TEXT NOT NULL UNIQUE,
    full_name             TEXT NOT NULL,
    password_hash         TEXT NOT NULL,
    roles                 TEXT NOT NULL DEFAULT 'user',
    email_verified_at     TIMESTAMPTZ,
    phone_verified_at     TIMESTAMPTZ,
    failed_login_attempts INT NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    status                TEXT NOT NULL DEFAULT 'pending_verification',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_auth_account_status CHECK (status IN ('pending_verification', 'active', 'suspended'))
);

CREATE INDEX IF NOT EXISTS idx_auth_account_email ON auth_account (email);
CREATE INDEX IF NOT EXISTS idx_auth_account_phone ON auth_account (phone);

CREATE TABLE IF NOT EXISTS auth_verification_challenge (
    id                UUID PRIMARY KEY,
    account_id        UUID NOT NULL REFERENCES auth_account(id) ON DELETE CASCADE,
    purpose           TEXT NOT NULL,
    email_code_hash   TEXT NOT NULL,
    sms_code_hash     TEXT NOT NULL,
    email_verified_at TIMESTAMPTZ,
    sms_verified_at   TIMESTAMPTZ,
    attempts          INT NOT NULL DEFAULT 0,
    max_attempts      INT NOT NULL DEFAULT 5,
    resend_count      INT NOT NULL DEFAULT 0,
    last_sent_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL,
    consumed_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_auth_challenge_purpose CHECK (purpose IN ('register', 'login', 'reset_password'))
);

CREATE INDEX IF NOT EXISTS idx_auth_challenge_account ON auth_verification_challenge (account_id, created_at DESC);
