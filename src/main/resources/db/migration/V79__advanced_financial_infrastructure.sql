-- V79: Advanced Infrastructure & Financial Reliability Engineering
-- Covers:
--  Area 2: Transactional Outbox (schema_version, status, max_attempts, dead_at, outbox_event_archive, replay & archive functions)
--  Area 3: Durable Message Broker (ordering_key, broker_provider, broker_message_id, acked_at on ingestion_event)
--  Area 4: Financial Idempotency Registry (operation_type, tenant_id, financial_reference, status, expires_at, locked_until)
--  Area 7: Global Cell Directory (fencing_epoch, version, heartbeat_at on cell_registry; account_cell_directory; cross_cell_transfer)
--  Area 11: Automated Failover & Fencing (failover_fencing_epoch, failover_audit_event)

-- =============================================================================
-- 1. Transactional Outbox Enhancements (outbox_event & outbox_event_archive)
-- =============================================================================
ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS schema_version INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD')),
    ADD COLUMN IF NOT EXISTS max_attempts INT NOT NULL DEFAULT 8,
    ADD COLUMN IF NOT EXISTS dead_at TIMESTAMPTZ;

UPDATE outbox_event SET status = 'PUBLISHED' WHERE published_at IS NOT NULL AND status = 'PENDING';

CREATE INDEX IF NOT EXISTS outbox_event_status_pending_idx
    ON outbox_event (next_attempt_at, sequence)
    WHERE status = 'PENDING' AND published_at IS NULL;

CREATE INDEX IF NOT EXISTS outbox_event_aggregate_seq_idx
    ON outbox_event (aggregate_type, aggregate_id, sequence)
    WHERE status = 'PENDING' AND published_at IS NULL;

CREATE INDEX IF NOT EXISTS outbox_event_dead_idx
    ON outbox_event (dead_at DESC, sequence DESC)
    WHERE status = 'DEAD';

CREATE TABLE IF NOT EXISTS outbox_event_archive (
    id              UUID        NOT NULL,
    sequence        BIGINT      NOT NULL,
    aggregate_type  TEXT        NOT NULL,
    aggregate_id    TEXT        NOT NULL,
    event_type      TEXT        NOT NULL,
    schema_version  INT         NOT NULL DEFAULT 1,
    payload         JSONB       NOT NULL,
    merchant_ids    UUID[]      NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ NOT NULL,
    attempts        INTEGER     NOT NULL DEFAULT 1,
    archived_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE IF NOT EXISTS outbox_event_archive_default
    PARTITION OF outbox_event_archive DEFAULT;

CREATE OR REPLACE FUNCTION archive_published_outbox_events(
    older_than_days INT DEFAULT 30,
    batch_limit     INT DEFAULT 500
) RETURNS INT LANGUAGE plpgsql AS $$
DECLARE
    archived_count INT := 0;
BEGIN
    WITH archivable AS (
        SELECT e.id
        FROM outbox_event e
        WHERE e.published_at IS NOT NULL
          AND e.published_at <= now() - make_interval(days => GREATEST(0, older_than_days))
          AND NOT EXISTS (SELECT 1 FROM webhook_delivery wd WHERE wd.event_id = e.id)
          AND NOT EXISTS (SELECT 1 FROM webhook_delivery_log wdl WHERE wdl.event_id = e.id)
        ORDER BY e.sequence
        LIMIT GREATEST(1, batch_limit)
        FOR UPDATE SKIP LOCKED
    ),
    moved AS (
        INSERT INTO outbox_event_archive
            (id, sequence, aggregate_type, aggregate_id, event_type, schema_version,
             payload, merchant_ids, created_at, published_at, attempts, archived_at)
        SELECT e.id, e.sequence, e.aggregate_type, e.aggregate_id, e.event_type, e.schema_version,
               e.payload, e.merchant_ids, e.created_at, e.published_at, e.attempts, now()
        FROM outbox_event e
        JOIN archivable a ON a.id = e.id
        ON CONFLICT (id, created_at) DO NOTHING
        RETURNING id
    ),
    deleted AS (
        DELETE FROM outbox_event e
        USING archivable a
        WHERE e.id = a.id
        RETURNING e.id
    )
    SELECT count(*)::int INTO archived_count FROM deleted;

    RETURN COALESCE(archived_count, 0);
END;
$$;

CREATE OR REPLACE Function reconciliation_split_and_rail_summary()
RETURNS TABLE(split_violations BIGINT, unresolved_rails BIGINT) LANGUAGE sql STABLE AS $$
    SELECT
        (SELECT count(*)
         FROM checkout c
         JOIN (
             SELECT checkout_id, sum(amount) AS alloc_sum
             FROM checkout_allocation
             GROUP BY checkout_id
         ) a ON a.checkout_id = c.id
         WHERE a.alloc_sum <> c.subtotal) AS split_violations,
        (SELECT count(*)
         FROM rail_transaction
         WHERE status IN ('unknown', 'created')
           AND created_at < now() - interval '15 minutes') AS unresolved_rails;
$$;

-- =============================================================================
-- 2. Durable Message Broker Enhancements (ingestion_event)
-- =============================================================================
ALTER TABLE ingestion_event
    ADD COLUMN IF NOT EXISTS ordering_key TEXT,
    ADD COLUMN IF NOT EXISTS broker_provider TEXT NOT NULL DEFAULT 'postgres',
    ADD COLUMN IF NOT EXISTS broker_message_id TEXT,
    ADD COLUMN IF NOT EXISTS acked_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_ingestion_event_ordering_key
    ON ingestion_event (ordering_key, created_at ASC)
    WHERE ordering_key IS NOT NULL AND status IN ('PENDING', 'PROCESSING');

-- =============================================================================
-- 3. Financial Idempotency Registry Enhancements (idempotent_request)
-- =============================================================================
ALTER TABLE idempotent_request
    ADD COLUMN IF NOT EXISTS tenant_id UUID,
    ADD COLUMN IF NOT EXISTS operation_type TEXT NOT NULL DEFAULT 'API_REQUEST',
    ADD COLUMN IF NOT EXISTS financial_reference TEXT,
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'COMPLETED'
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'AMBIGUOUS_OUTCOME', 'FAILED')),
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '90 days'),
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_idempotent_request_fin_ref
    ON idempotent_request (financial_reference)
    WHERE financial_reference IS NOT NULL;

-- Harden prune_idempotent_requests so it NEVER deletes IN_PROGRESS or AMBIGUOUS_OUTCOME records
CREATE OR REPLACE FUNCTION prune_idempotent_requests(
    retention_days INT DEFAULT 30,
    batch_size     INT DEFAULT 10000
) RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE
    deleted_count BIGINT := 0;
BEGIN
    WITH doomed AS (
        SELECT scope, key
        FROM idempotent_request
        WHERE created_at < now() - make_interval(days => GREATEST(1, retention_days))
          AND expires_at < now()
          AND status = 'COMPLETED'
          AND financial_reference IS NULL
        LIMIT GREATEST(1, batch_size)
    )
    DELETE FROM idempotent_request t
    USING doomed d
    WHERE t.scope = d.scope AND t.key = d.key;

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$;

-- =============================================================================
-- 4. Global Cell Directory, Fencing & Cross-Cell In-Transit Protocol
-- =============================================================================
ALTER TABLE cell_registry
    ADD COLUMN IF NOT EXISTS fencing_epoch BIGINT NOT NULL DEFAULT 1 CHECK (fencing_epoch >= 1),
    ADD COLUMN IF NOT EXISTS routing_version BIGINT NOT NULL DEFAULT 1 CHECK (routing_version >= 1),
    ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE IF NOT EXISTS account_cell_directory (
    account_id        TEXT        PRIMARY KEY,
    account_type      TEXT        NOT NULL CHECK (account_type IN ('CUSTOMER', 'MERCHANT', 'WALLET', 'LEDGER_ACCOUNT', 'ESCROW')),
    owner_id          UUID        NOT NULL,
    home_cell_id      TEXT        NOT NULL REFERENCES cell_registry (cell_id),
    fencing_token     BIGINT      NOT NULL DEFAULT 1 CHECK (fencing_token >= 1),
    routing_version   BIGINT      NOT NULL DEFAULT 1 CHECK (routing_version >= 1),
    state             TEXT        NOT NULL DEFAULT 'ACTIVE'
                                  CHECK (state IN ('ACTIVE', 'MIGRATING_FROZEN', 'MIGRATED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_account_cell_directory_owner
    ON account_cell_directory (owner_id, account_type);

CREATE INDEX IF NOT EXISTS idx_account_cell_directory_cell
    ON account_cell_directory (home_cell_id);

CREATE TABLE IF NOT EXISTS cross_cell_transfer (
    id                  UUID        PRIMARY KEY,
    idempotency_key     TEXT        NOT NULL UNIQUE,
    source_cell_id      TEXT        NOT NULL REFERENCES cell_registry (cell_id),
    target_cell_id      TEXT        NOT NULL REFERENCES cell_registry (cell_id),
    source_account_code TEXT        NOT NULL,
    target_account_code TEXT        NOT NULL,
    currency            TEXT        NOT NULL DEFAULT 'GHS',
    amount_minor        BIGINT      NOT NULL CHECK (amount_minor > 0),
    fencing_token       BIGINT      NOT NULL CHECK (fencing_token >= 1),
    state               TEXT        NOT NULL DEFAULT 'RESERVED_IN_TRANSIT'
                                    CHECK (state IN (
                                        'RESERVED_IN_TRANSIT',
                                        'COMMITTED_TARGET',
                                        'SETTLED',
                                        'COMPENSATED',
                                        'RECONCILIATION_REQUIRED'
                                    )),
    debit_journal_tx    UUID,
    credit_journal_tx   UUID,
    compensation_tx     UUID,
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_cross_cell_transfer_state
    ON cross_cell_transfer (state, created_at);

-- Extend ledger_account_bucket_check to include 'cross_cell_in_transit' and pre-seed holding account
ALTER TABLE ledger_account DROP CONSTRAINT IF EXISTS ledger_account_bucket_check;
ALTER TABLE ledger_account ADD CONSTRAINT ledger_account_bucket_check CHECK (bucket IN (
    'available', 'pending', 'reserved', 'clearing', 'revenue', 'suspense', 'refunds_in_flight', 'disputes',
    'rail_fees', 'fx_clearing', 'fx_in_flight', 'deferred_revenue', 'revenue_earned',
    'operating', 'holding', 'rewards', 'cross_cell_in_transit'
));

INSERT INTO ledger_account (code, owner_type, bucket, currency, allow_negative, balance)
VALUES ('system:cross_cell_in_transit:GHS', 'system', 'cross_cell_in_transit', 'GHS', false, 0)
ON CONFLICT (code) DO NOTHING;

-- =============================================================================
-- 5. Automated Failover, Split-Brain Fencing & Immutable Audit Records
-- =============================================================================
CREATE TABLE IF NOT EXISTS failover_fencing_epoch (
    cluster_id        TEXT        PRIMARY KEY,
    active_primary_id TEXT        NOT NULL,
    fencing_epoch     BIGINT      NOT NULL DEFAULT 1 CHECK (fencing_epoch >= 1),
    quorum_size       INT         NOT NULL DEFAULT 2 CHECK (quorum_size >= 1),
    quorum_votes      INT         NOT NULL DEFAULT 2 CHECK (quorum_votes >= 1),
    replication_mode  TEXT        NOT NULL DEFAULT 'SYNCHRONOUS'
                                  CHECK (replication_mode IN ('SYNCHRONOUS', 'SAFE_READ_ONLY_DEGRADED', 'LOCAL_DEV')),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO failover_fencing_epoch (cluster_id, active_primary_id, fencing_epoch, quorum_size, quorum_votes, replication_mode)
VALUES ('elepay-core-cluster', 'primary-cell-0', 1, 2, 2, 'SYNCHRONOUS')
ON CONFLICT (cluster_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS failover_audit_event (
    id                UUID        PRIMARY KEY,
    cluster_id        TEXT        NOT NULL,
    event_type        TEXT        NOT NULL
                                  CHECK (event_type IN (
                                      'PRIMARY_FAILURE_DETECTED',
                                      'QUORUM_PROMOTION_DECIDED',
                                      'OBSOLETE_PRIMARY_FENCED',
                                      'STANDBY_PROMOTED',
                                      'WORKERS_RECOVERED',
                                      'CONTROLLED_FAILBACK',
                                      'SYNC_REPLICA_DEGRADED_FENCE'
                                  )),
    old_primary_id    TEXT,
    new_primary_id    TEXT,
    old_epoch         BIGINT      NOT NULL,
    new_epoch         BIGINT      NOT NULL,
    quorum_votes      INT         NOT NULL,
    actor             TEXT        NOT NULL,
    details           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_failover_audit_event_cluster_created
    ON failover_audit_event (cluster_id, created_at DESC);

DROP TRIGGER IF EXISTS failover_audit_event_immutable ON failover_audit_event;
CREATE TRIGGER failover_audit_event_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON failover_audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
