-- Stage 2: Async Ingestion Buffer schema.
-- Durable, idempotent ingestion queue with partial indexes for SKIP LOCKED
-- worker polling, stale lease recovery, and dead-letter queue inspection.

CREATE TABLE IF NOT EXISTS ingestion_event (
    id              UUID PRIMARY KEY,
    tenant_id       UUID,
    event_type      TEXT NOT NULL,
    scope           TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    payload_hash    TEXT NOT NULL,
    payload         JSONB NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD')),
    attempts        INT NOT NULL DEFAULT 0,
    max_attempts    INT NOT NULL DEFAULT 5,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_by       TEXT,
    locked_at       TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    result          JSONB,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    UNIQUE (scope, idempotency_key)
);

-- Hot-path index for FOR UPDATE SKIP LOCKED worker claiming of due PENDING events.
CREATE INDEX IF NOT EXISTS idx_ingestion_event_pending_poll
    ON ingestion_event (next_attempt_at, created_at)
    WHERE status = 'PENDING';

-- Partial index for stale lease recovery of stuck PROCESSING events.
CREATE INDEX IF NOT EXISTS idx_ingestion_event_processing_lease
    ON ingestion_event (lease_expires_at)
    WHERE status = 'PROCESSING';

-- Partial index for fast ops DLQ listing.
CREATE INDEX IF NOT EXISTS idx_ingestion_event_dead
    ON ingestion_event (updated_at DESC)
    WHERE status = 'DEAD';

-- Tenant-scoped lookup index.
CREATE INDEX IF NOT EXISTS idx_ingestion_event_tenant_created
    ON ingestion_event (tenant_id, created_at DESC)
    WHERE tenant_id IS NOT NULL;
