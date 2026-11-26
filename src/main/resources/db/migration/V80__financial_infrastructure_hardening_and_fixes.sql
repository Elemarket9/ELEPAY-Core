-- V80: Financial Infrastructure Hardening & Audit Remediation
--
-- Remediates schema, immutability, partitioning, idempotency pruning, outbox archival,
-- reconciliation summary, and cell migration uniqueness gaps identified during the
-- adversarial white-box audit of V74..V79:
--   1. Extends `block_immutable_trigger_disable()` to cover `failover_audit_event` AND
--      all child partitions of protected partitioned tables via `pg_inherits`.
--   2. Adds row-level immutability trigger `failover_audit_event_row_immutable` on `failover_audit_event`.
--   3. Updates `ensure_monthly_partitions(months_ahead)` to include `outbox_event_archive`,
--      safely handle rows already in `<tbl>_default` overlapping `[month_start, month_end)`
--      (moving overlapping rows for mutable archive tables via `ATTACH PARTITION`, and skipping
--      without `23514` crash if an immutable `*_default` partition already holds rows in that window),
--      and provisions 6 months ahead (`SELECT ensure_monthly_partitions(6)`).
--   4. Backfills `expires_at` on historical `idempotent_request` rows where `expires_at > created_at + interval '89 days'`
--      and updates `prune_idempotent_requests(retention_days, batch_size)` so historical `created_at`
--      rows and caller-specified `retention_days` are honored while still protecting `IN_PROGRESS`,
--      `AMBIGUOUS_OUTCOME`, and `financial_reference IS NOT NULL` rows.
--   5. Fixes `archive_published_outbox_events(older_than_days, batch_limit)`: drops the hard FK
--      `webhook_delivery_log_event_id_fkey` on `webhook_delivery_log(event_id)` (since archived events
--      move to `outbox_event_archive` and `webhook_delivery_log` is immutable), cleans up terminal
--      (`status IN ('delivered', 'dead')`) `webhook_delivery` rows for archivable events, requires
--      `e.status = 'PUBLISHED'`, and deletes `USING moved m WHERE e.id = m.id`.
--   6. Adds partial index `outbox_event_aggregate_unpublished_seq_idx` (`WHERE published_at IS NULL`)
--      so `OutboxDispatcher`'s `NOT EXISTS` subquery uses an index scan across both `PENDING` and `DEAD` events.
--   7. Adds partial unique index `idx_cell_migration_job_active_merchant` preventing duplicate active
--      cell migrations for the same merchant.
--   8. Updates `reconciliation_split_and_rail_summary()` to include `'pending'` and `'mismatch'` in
--      `rail_transaction.status` older than 15 minutes and detect paid checkouts with missing `journal_tx`
--      or mismatched `total_paid <> subtotal + customer_fee`.

-- =============================================================================
-- 1. Harden block_immutable_trigger_disable() & failover_audit_event row trigger
-- =============================================================================
DROP TRIGGER IF EXISTS failover_audit_event_row_immutable ON failover_audit_event;
CREATE TRIGGER failover_audit_event_row_immutable
    BEFORE UPDATE OR DELETE ON failover_audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE OR REPLACE FUNCTION block_immutable_trigger_disable() RETURNS event_trigger LANGUAGE plpgsql AS $$
DECLARE
    disabled_count integer;
BEGIN
    SELECT count(*) INTO disabled_count
    FROM pg_trigger t
    JOIN pg_class c ON c.oid = t.tgrelid
    WHERE (
        c.relname IN (
            'journal_transaction', 'journal_entry', 'ledger_account', 'ledger_account_snapshot',
            'ledger_move', 'ledger_operation', 'audit_log', 'audit_checkpoint', 'integrity_key',
            'administrative_audit_event', 'redaction_audit', 'webhook_delivery_log',
            'marketplace_fee_schedule_history', 'enterprise_prefunding_ledger',
            'financial_ledger_metric_snapshot', 'checkout_config_history', 'gra_fiscal_config_history',
            'settlement_reserve_release', 'maker_checker_proposal', 'failover_audit_event'
        )
        OR EXISTS (
            SELECT 1
            FROM pg_inherits i
            JOIN pg_class p ON p.oid = i.inhparent
            WHERE i.inhrelid = c.oid
              AND p.relname IN ('journal_entry', 'audit_log', 'webhook_delivery_log')
        )
    )
    AND (
        t.tgname LIKE '%_immutable'
        OR t.tgname IN (
            'ledger_account_guard',
            'ledger_account_no_truncate',
            'journal_entry_apply',
            'journal_entry_balanced',
            'journal_transaction_has_entries',
            'maker_checker_proposal_guard',
            'maker_checker_proposal_no_delete'
        )
    )
    AND t.tgenabled <> 'O';

    IF disabled_count > 0 THEN
        RAISE EXCEPTION 'Disabling immutability triggers on financial ledger or audit tables is strictly forbidden'
            USING ERRCODE = '42501';
    END IF;
END;
$$;

-- =============================================================================
-- 2. Harden ensure_monthly_partitions(months_ahead) with outbox_event_archive
--    and default-partition overlap handling
-- =============================================================================
CREATE OR REPLACE FUNCTION ensure_monthly_partitions(months_ahead integer DEFAULT 3)
RETURNS integer LANGUAGE plpgsql AS $$
DECLARE
    tbl            text;
    is_immutable   boolean;
    part_col       text;
    base_month     timestamp;
    m              integer;
    month_start    timestamptz;
    month_end      timestamptz;
    part_name      text;
    default_name   text;
    overlap_count  bigint;
    created_count  integer := 0;
BEGIN
    base_month := date_trunc('month', now() AT TIME ZONE 'UTC');

    FOREACH tbl IN ARRAY ARRAY[
        'journal_entry',
        'audit_log',
        'webhook_delivery_log',
        'journal_transaction_archive',
        'checkout_archive',
        'outbox_event_archive'
    ]
    LOOP
        IF to_regclass('public.' || tbl) IS NULL THEN
            CONTINUE;
        END IF;

        is_immutable := tbl IN ('journal_entry', 'audit_log', 'webhook_delivery_log');
        part_col := CASE WHEN tbl = 'journal_transaction_archive' THEN 'posted_at' ELSE 'created_at' END;
        default_name := tbl || '_default';

        IF to_regclass('public.' || default_name) IS NULL THEN
            EXECUTE format('CREATE TABLE %I PARTITION OF %I DEFAULT', default_name, tbl);
            created_count := created_count + 1;
        END IF;

        IF is_immutable AND NOT EXISTS (
            SELECT 1
            FROM pg_trigger t
            JOIN pg_class c ON c.oid = t.tgrelid
            WHERE c.relname = default_name AND t.tgname = default_name || '_truncate_immutable'
        ) THEN
            EXECUTE format(
                'CREATE TRIGGER %I BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation()',
                default_name || '_truncate_immutable', default_name
            );
        END IF;

        FOR m IN -1..GREATEST(months_ahead, 1) LOOP
            month_start := (base_month + make_interval(months => m)) AT TIME ZONE 'UTC';
            month_end   := (base_month + make_interval(months => m + 1)) AT TIME ZONE 'UTC';
            part_name   := tbl || '_' || to_char(base_month + make_interval(months => m), 'YYYY_MM');

            IF to_regclass('public.' || part_name) IS NULL THEN
                overlap_count := 0;
                IF to_regclass('public.' || default_name) IS NOT NULL THEN
                    EXECUTE format(
                        'SELECT count(*) FROM ONLY %I WHERE %I >= %L AND %I < %L',
                        default_name, part_col, month_start, part_col, month_end
                    ) INTO overlap_count;
                END IF;

                IF overlap_count = 0 THEN
                    EXECUTE format(
                        'CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                        part_name, tbl, month_start, month_end
                    );
                    created_count := created_count + 1;
                ELSIF NOT is_immutable THEN
                    EXECUTE format(
                        'CREATE TABLE %I (LIKE %I INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES)',
                        part_name, tbl
                    );
                    EXECUTE format(
                        'WITH moved AS (DELETE FROM ONLY %I WHERE %I >= %L AND %I < %L RETURNING *) INSERT INTO %I SELECT * FROM moved',
                        default_name, part_col, month_start, part_col, month_end, part_name
                    );
                    EXECUTE format(
                        'ALTER TABLE %I ATTACH PARTITION %I FOR VALUES FROM (%L) TO (%L)',
                        tbl, part_name, month_start, month_end
                    );
                    created_count := created_count + 1;
                ELSE
                    RAISE NOTICE 'Skipping partition % creation because immutable default partition % contains % overlapping rows',
                        part_name, default_name, overlap_count;
                END IF;
            END IF;

            IF is_immutable AND to_regclass('public.' || part_name) IS NOT NULL AND NOT EXISTS (
                SELECT 1
                FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid
                WHERE c.relname = part_name AND t.tgname = part_name || '_truncate_immutable'
            ) THEN
                EXECUTE format(
                    'CREATE TRIGGER %I BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation()',
                    part_name || '_truncate_immutable', part_name
                );
            END IF;
        END LOOP;
    END LOOP;

    RETURN created_count;
END;
$$;

SELECT ensure_monthly_partitions(6);

-- =============================================================================
-- 3. Fix prune_idempotent_requests & backfill historical expires_at
-- =============================================================================
UPDATE idempotent_request
SET expires_at = created_at + interval '30 days'
WHERE expires_at > created_at + interval '89 days'
  AND created_at < now() - interval '1 minute';

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
        WHERE (
            (created_at < now() - make_interval(days => GREATEST(1, retention_days))
             AND (operation_type = 'API_REQUEST'
                  OR expires_at <= created_at + interval '91 days'
                  OR expires_at < now()))
            OR expires_at < now()
        )
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
-- 4. Fix archive_published_outbox_events & add unpublished aggregate sequence index
-- =============================================================================
ALTER TABLE webhook_delivery_log
    DROP CONSTRAINT IF EXISTS webhook_delivery_log_event_id_fkey;

CREATE INDEX IF NOT EXISTS outbox_event_aggregate_unpublished_seq_idx
    ON outbox_event (aggregate_type, aggregate_id, sequence)
    WHERE published_at IS NULL;

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
          AND e.status = 'PUBLISHED'
          AND e.published_at <= now() - make_interval(days => GREATEST(0, older_than_days))
          AND NOT EXISTS (
              SELECT 1
              FROM webhook_delivery wd
              WHERE wd.event_id = e.id
                AND wd.status NOT IN ('delivered', 'dead')
          )
        ORDER BY e.sequence
        LIMIT GREATEST(1, batch_limit)
        FOR UPDATE SKIP LOCKED
    ),
    cleaned_webhooks AS (
        DELETE FROM webhook_delivery wd
        USING archivable a
        WHERE wd.event_id = a.id
          AND wd.status IN ('delivered', 'dead')
        RETURNING wd.event_id
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
        USING moved m
        WHERE e.id = m.id
        RETURNING e.id
    )
    SELECT count(*)::int INTO archived_count FROM deleted;

    RETURN COALESCE(archived_count, 0);
END;
$$;

-- =============================================================================
-- 5. Prevent duplicate active cell migration jobs per merchant
-- =============================================================================
CREATE UNIQUE INDEX IF NOT EXISTS idx_cell_migration_job_active_merchant
    ON cell_migration_job (merchant_id)
    WHERE state NOT IN ('COMPLETED', 'ROLLED_BACK', 'FAILED');

-- =============================================================================
-- 6. Harden reconciliation_split_and_rail_summary()
-- =============================================================================
CREATE OR REPLACE FUNCTION reconciliation_split_and_rail_summary()
RETURNS TABLE(split_violations BIGINT, unresolved_rails BIGINT) LANGUAGE sql STABLE AS $$
    SELECT
        (SELECT count(*)
         FROM checkout c
         LEFT JOIN (
             SELECT checkout_id, sum(amount) AS alloc_sum
             FROM checkout_allocation
             GROUP BY checkout_id
         ) a ON a.checkout_id = c.id
         WHERE COALESCE(a.alloc_sum, 0) <> c.subtotal
            OR (c.status = 'paid'
                AND (c.journal_tx IS NULL
                     OR (c.total_paid IS NOT NULL AND c.total_paid <> c.subtotal + c.customer_fee)))) AS split_violations,
        (SELECT count(*)
         FROM rail_transaction
         WHERE status IN ('unknown', 'created', 'pending', 'mismatch')
           AND created_at < now() - interval '15 minutes') AS unresolved_rails;
$$;
