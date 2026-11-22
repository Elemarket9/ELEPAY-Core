-- ============================================================================
-- V26__security_hardening.sql
-- Domain 14 & 15: Security Hardening, Defect Remediation & Production Infrastructure
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Dynamic Merchant Signing Secrets per API Credential
ALTER TABLE api_credential ADD COLUMN IF NOT EXISTS signing_secret TEXT;

UPDATE api_credential 
SET signing_secret = 'sec_' || encode(gen_random_bytes(24), 'hex')
WHERE signing_secret IS NULL OR signing_secret = '';

-- 2. Request Nonce Replay Protection (Distributed fallback when Redis is absent)
CREATE TABLE IF NOT EXISTS request_nonce (
    nonce TEXT PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_request_nonce_expires_at ON request_nonce(expires_at);

-- 3. Distributed User Session Management
CREATE TABLE IF NOT EXISTS user_session (
    session_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    last_accessed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attributes JSONB,
    revoked BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_user_session_user_id ON user_session(user_id);
CREATE INDEX IF NOT EXISTS idx_user_session_expires_at ON user_session(expires_at);

-- 4. Distributed Lock Table (Redis fallback / HA cluster lock synchronization)
CREATE TABLE IF NOT EXISTS distributed_lock (
    lock_key TEXT PRIMARY KEY,
    lock_owner TEXT NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_distributed_lock_expires_at ON distributed_lock(expires_at);

-- 5. Financial Ledger Metric Snapshots for Production Observability
CREATE TABLE IF NOT EXISTS financial_ledger_metric_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    currency TEXT NOT NULL,
    total_debits BIGINT NOT NULL,
    total_credits BIGINT NOT NULL,
    drift BIGINT NOT NULL,
    is_healthy BOOLEAN NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ledger_metric_snapshot_time ON financial_ledger_metric_snapshot(snapshot_time);
