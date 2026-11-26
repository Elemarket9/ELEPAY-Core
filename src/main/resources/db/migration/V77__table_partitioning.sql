-- ============================================================================
-- V77__table_partitioning.sql
-- Stage 3A: Declarative Table Partitioning & Lifecycle Management (PG 18)
--
-- 1. Declarative RANGE (created_at) monthly partitioning + default partition for:
--      - journal_entry        (adds created_at; folds balance check into
--                              journal_transaction_has_entries; preserves
--                              journal_entry_apply, journal_entry_balanced,
--                              journal_entry_immutable, ledger_account_reconstruction)
--      - audit_log            (preserves audit_log_id_seq, GENERATED ALWAYS AS IDENTITY,
--                              audit_log_immutable, hash-chain columns & indexes)
--      - webhook_delivery_log (preserves webhook_delivery_log_immutable & indexes)
-- 2. Declarative HASH (scope, key) partitioning (16 partitions) for:
--      - idempotent_request   (preserves PK (scope, key) and ON CONFLICT semantics)
--      - prune_idempotent_requests(retention_days, batch_size) helper for TTL cleanup
-- 3. BRIN indexes on FK-anchored high-volume tables:
--      - journal_transaction(posted_at)
--      - checkout(created_at)
--      - checkout_allocation(created_at) (column added + backfilled)
-- 4. Partitioned archive tables:
--      - journal_transaction_archive PARTITION BY RANGE (posted_at)
--      - checkout_archive            PARTITION BY RANGE (created_at)
-- 5. Idempotent partition pre-provisioning function:
--      - ensure_monthly_partitions(months_ahead integer DEFAULT 3)
--
-- V74 Safety: Never executes ALTER TABLE ... DISABLE TRIGGER or DROP TRIGGER.
-- Uses RENAME -> CREATE PARTITIONED -> PROVISION -> COPY -> ATTACH TRIGGERS -> DROP LEGACY.
-- ============================================================================

-- 0. Drop dependent view before swapping journal_entry (recreated identically below)
DROP VIEW IF EXISTS ledger_account_reconstruction;

-- ============================================================================
-- 1. Partitioned journal_entry (RANGE by created_at)
-- ============================================================================
ALTER TABLE journal_entry RENAME TO journal_entry_legacy;
ALTER INDEX IF EXISTS journal_entry_pkey RENAME TO journal_entry_legacy_pkey;
ALTER INDEX IF EXISTS journal_entry_account RENAME TO journal_entry_legacy_account;
ALTER INDEX IF EXISTS journal_entry_account_seq RENAME TO journal_entry_legacy_account_seq;
ALTER SEQUENCE IF EXISTS journal_entry_entry_seq_seq RENAME TO journal_entry_legacy_entry_seq_seq;

CREATE TABLE journal_entry (
    transaction_id uuid        NOT NULL REFERENCES journal_transaction(id),
    line           integer     NOT NULL CHECK (line >= 0),
    account_id     bigint      NOT NULL REFERENCES ledger_account(id),
    currency       char(3)     NOT NULL,
    amount         bigint      NOT NULL CHECK (amount <> 0),
    entry_seq      bigint      GENERATED ALWAYS AS IDENTITY,
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (transaction_id, line, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX journal_entry_account ON journal_entry (account_id, transaction_id);
CREATE INDEX journal_entry_account_seq ON journal_entry (account_id, entry_seq);

-- ============================================================================
-- 2. Partitioned audit_log (RANGE by created_at)
-- ============================================================================
ALTER TABLE audit_log RENAME TO audit_log_legacy;
ALTER INDEX IF EXISTS audit_log_pkey RENAME TO audit_log_legacy_pkey;
ALTER INDEX IF EXISTS audit_log_subject RENAME TO audit_log_legacy_subject;
ALTER INDEX IF EXISTS audit_log_entry_hash RENAME TO audit_log_legacy_entry_hash;
ALTER SEQUENCE IF EXISTS audit_log_id_seq RENAME TO audit_log_legacy_id_seq;

CREATE TABLE audit_log (
    id           bigint      GENERATED ALWAYS AS IDENTITY,
    actor        text        NOT NULL,
    action       text        NOT NULL,
    subject      text        NOT NULL,
    details      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    prev_hash    text,
    entry_hash   text,
    hash_version smallint    NOT NULL DEFAULT 1,
    hash_key_id  text,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX audit_log_subject ON audit_log (subject, created_at);
CREATE INDEX audit_log_entry_hash ON audit_log (entry_hash);
CREATE INDEX audit_log_created_at_idx ON audit_log (created_at);
CREATE INDEX audit_log_id_desc_idx ON audit_log (id DESC) WHERE entry_hash IS NOT NULL;

-- ============================================================================
-- 3. Partitioned webhook_delivery_log (RANGE by created_at)
-- ============================================================================
ALTER TABLE webhook_delivery_log RENAME TO webhook_delivery_log_legacy;
ALTER INDEX IF EXISTS webhook_delivery_log_pkey RENAME TO webhook_delivery_log_legacy_pkey;
ALTER INDEX IF EXISTS idx_webhook_delivery_log_endpoint RENAME TO idx_webhook_delivery_log_legacy_endpoint;
ALTER INDEX IF EXISTS idx_webhook_delivery_log_merchant RENAME TO idx_webhook_delivery_log_legacy_merchant;
ALTER INDEX IF EXISTS idx_webhook_delivery_log_event RENAME TO idx_webhook_delivery_log_legacy_event;

CREATE TABLE webhook_delivery_log (
    id               uuid        NOT NULL,
    delivery_id      uuid        NOT NULL,
    endpoint_id      uuid        NOT NULL REFERENCES webhook_endpoint(id),
    event_id         uuid        REFERENCES outbox_event(id),
    merchant_id      uuid        NOT NULL,
    event_type       varchar(64) NOT NULL,
    request_url      text        NOT NULL,
    request_headers  jsonb       NOT NULL DEFAULT '{}',
    request_payload  jsonb       NOT NULL,
    response_code    integer,
    response_headers jsonb,
    response_body    text,
    duration_ms      integer     NOT NULL DEFAULT 0,
    status           varchar(32) NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED', 'RETRYING', 'DEAD')),
    attempt_number   integer     NOT NULL DEFAULT 1,
    is_manual_retry  boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX idx_webhook_delivery_log_endpoint ON webhook_delivery_log (endpoint_id, created_at DESC);
CREATE INDEX idx_webhook_delivery_log_merchant ON webhook_delivery_log (merchant_id, created_at DESC);
CREATE INDEX idx_webhook_delivery_log_event ON webhook_delivery_log (event_id);

-- ============================================================================
-- 4. Hash-Partitioned idempotent_request (16 partitions by (scope, key))
-- ============================================================================
ALTER TABLE idempotent_request RENAME TO idempotent_request_legacy;
ALTER INDEX IF EXISTS idempotent_request_pkey RENAME TO idempotent_request_legacy_pkey;

CREATE TABLE idempotent_request (
    scope        text        NOT NULL,
    key          text        NOT NULL,
    request_hash text        NOT NULL,
    response     jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, key)
) PARTITION BY HASH (scope, key);

DO $$
DECLARE
    i integer;
BEGIN
    FOR i IN 0..15 LOOP
        EXECUTE format(
            'CREATE TABLE idempotent_request_p%s PARTITION OF idempotent_request FOR VALUES WITH (MODULUS 16, REMAINDER %s)',
            lpad(i::text, 2, '0'), i
        );
    END LOOP;
END;
$$;

CREATE INDEX idempotent_request_created_at_idx ON idempotent_request (created_at);

INSERT INTO idempotent_request (scope, key, request_hash, response, created_at)
SELECT scope, key, request_hash, response, created_at
FROM idempotent_request_legacy;

DROP TABLE IF EXISTS idempotent_request_legacy;

-- Bounded-batch TTL pruning helper for idempotent_request
CREATE OR REPLACE FUNCTION prune_idempotent_requests(
    retention_days integer DEFAULT 30,
    batch_size integer DEFAULT 10000
) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE
    deleted_rows bigint;
BEGIN
    WITH doomed AS (
        SELECT scope, key
        FROM idempotent_request
        WHERE created_at < now() - make_interval(days => GREATEST(retention_days, 1))
        LIMIT GREATEST(batch_size, 1)
    )
    DELETE FROM idempotent_request t
    USING doomed d
    WHERE t.scope = d.scope AND t.key = d.key;

    GET DIAGNOSTICS deleted_rows = ROW_COUNT;
    RETURN deleted_rows;
END;
$$;

-- ============================================================================
-- 5. BRIN Indexes, checkout_allocation.created_at, and Archive Tables
-- ============================================================================
CREATE INDEX IF NOT EXISTS journal_transaction_posted_at_brin
    ON journal_transaction USING brin (posted_at);

CREATE INDEX IF NOT EXISTS checkout_created_at_brin
    ON checkout USING brin (created_at);

ALTER TABLE checkout_allocation
    ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now();

UPDATE checkout_allocation a
SET created_at = c.created_at
FROM checkout c
WHERE c.id = a.checkout_id;

CREATE INDEX IF NOT EXISTS checkout_allocation_created_at_brin
    ON checkout_allocation USING brin (created_at);

CREATE TABLE IF NOT EXISTS journal_transaction_archive (
    id                uuid        NOT NULL,
    idempotency_scope text        NOT NULL,
    idempotency_key   text        NOT NULL,
    request_hash      text        NOT NULL,
    kind              text        NOT NULL,
    description       text,
    reverses          uuid,
    related_to        uuid,
    metadata          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    posted_at         timestamptz NOT NULL,
    archived_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, posted_at)
) PARTITION BY RANGE (posted_at);

CREATE TABLE IF NOT EXISTS checkout_archive (
    id                uuid        NOT NULL,
    reference         text        NOT NULL,
    collector_id      uuid        NOT NULL,
    branch_id         uuid,
    payment_link_id   uuid,
    customer_id       uuid,
    status            text        NOT NULL,
    currency          char(3)     NOT NULL,
    subtotal          bigint      NOT NULL,
    description       text,
    payment_method    text,
    customer_fee      bigint      NOT NULL DEFAULT 0,
    total_paid        bigint,
    journal_tx        uuid,
    expires_at        timestamptz NOT NULL,
    paid_at           timestamptz,
    created_by        text        NOT NULL,
    created_at        timestamptz NOT NULL,
    order_reference   text,
    delivery_deadline timestamptz,
    channel           text,
    archived_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- ============================================================================
-- 6. Idempotent Monthly Partition Provisioning Function
-- ============================================================================
CREATE OR REPLACE FUNCTION ensure_monthly_partitions(months_ahead integer DEFAULT 3)
RETURNS integer LANGUAGE plpgsql AS $$
DECLARE
    tbl           text;
    is_immutable  boolean;
    base_month    timestamp;
    m             integer;
    month_start   timestamptz;
    month_end     timestamptz;
    part_name     text;
    default_name  text;
    created_count integer := 0;
BEGIN
    base_month := date_trunc('month', now() AT TIME ZONE 'UTC');

    FOREACH tbl IN ARRAY ARRAY[
        'journal_entry',
        'audit_log',
        'webhook_delivery_log',
        'journal_transaction_archive',
        'checkout_archive'
    ]
    LOOP
        IF to_regclass('public.' || tbl) IS NULL THEN
            CONTINUE;
        END IF;

        is_immutable := tbl IN ('journal_entry', 'audit_log', 'webhook_delivery_log');
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
                EXECUTE format(
                    'CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                    part_name, tbl, month_start, month_end
                );
                created_count := created_count + 1;
            END IF;

            IF is_immutable AND NOT EXISTS (
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

-- Provision default + current + 6 months ahead before migrating legacy rows
SELECT ensure_monthly_partitions(6);

-- ============================================================================
-- 7. Migrate Existing Rows Without Re-Triggering Balance Application
-- ============================================================================
INSERT INTO journal_entry (transaction_id, line, account_id, currency, amount, entry_seq, created_at)
OVERRIDING SYSTEM VALUE
SELECT e.transaction_id, e.line, e.account_id, e.currency, e.amount, e.entry_seq, COALESCE(t.posted_at, now())
FROM journal_entry_legacy e
LEFT JOIN journal_transaction t ON t.id = e.transaction_id;

DO $$
DECLARE
    max_seq       bigint;
    legacy_last   bigint;
    legacy_called boolean;
    seq_name      text := pg_get_serial_sequence('journal_entry', 'entry_seq');
BEGIN
    IF seq_name IS NOT NULL THEN
        SELECT max(entry_seq) INTO max_seq FROM journal_entry;
        IF to_regclass('public.journal_entry_legacy_entry_seq_seq') IS NOT NULL THEN
            SELECT last_value, is_called INTO legacy_last, legacy_called FROM journal_entry_legacy_entry_seq_seq;
        ELSE
            legacy_last := 1;
            legacy_called := false;
        END IF;
        IF max_seq IS NOT NULL THEN
            PERFORM setval(seq_name, GREATEST(max_seq, legacy_last), true);
        ELSE
            PERFORM setval(seq_name, legacy_last, legacy_called);
        END IF;
    END IF;
END;
$$;

INSERT INTO audit_log (id, actor, action, subject, details, created_at, prev_hash, entry_hash, hash_version, hash_key_id)
OVERRIDING SYSTEM VALUE
SELECT id, actor, action, subject, details, created_at, prev_hash, entry_hash, hash_version, hash_key_id
FROM audit_log_legacy;

DO $$
DECLARE
    max_id        bigint;
    legacy_last   bigint;
    legacy_called boolean;
BEGIN
    SELECT max(id) INTO max_id FROM audit_log;
    IF to_regclass('public.audit_log_legacy_id_seq') IS NOT NULL THEN
        SELECT last_value, is_called INTO legacy_last, legacy_called FROM audit_log_legacy_id_seq;
    ELSE
        legacy_last := 1;
        legacy_called := false;
    END IF;
    IF max_id IS NOT NULL THEN
        PERFORM setval('audit_log_id_seq', GREATEST(max_id, legacy_last), true);
    ELSE
        PERFORM setval('audit_log_id_seq', legacy_last, legacy_called);
    END IF;
END;
$$;

INSERT INTO webhook_delivery_log (
    id, delivery_id, endpoint_id, event_id, merchant_id, event_type,
    request_url, request_headers, request_payload, response_code,
    response_headers, response_body, duration_ms, status,
    attempt_number, is_manual_retry, created_at
)
SELECT
    id, delivery_id, endpoint_id, event_id, merchant_id, event_type,
    request_url, request_headers, request_payload, response_code,
    response_headers, response_body, duration_ms, status,
    attempt_number, is_manual_retry, created_at
FROM webhook_delivery_log_legacy;

-- ============================================================================
-- 8. Fold Double-Entry Balance Check Into journal_transaction_has_entries &
--    Re-Attach All Immutability and Balance-Sync Triggers
-- ============================================================================
CREATE OR REPLACE FUNCTION journal_transaction_check_has_entries() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    entry_count integer;
    unbalanced  integer;
BEGIN
    SELECT count(*) INTO entry_count FROM journal_entry WHERE transaction_id = NEW.id;
    IF entry_count = 0 THEN
        RAISE EXCEPTION 'Journal transaction % has no entries', NEW.id USING ERRCODE = '23514';
    END IF;
    IF entry_count < 2 THEN
        RAISE EXCEPTION 'Journal transaction % needs at least two entries', NEW.id USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO unbalanced FROM (
        SELECT currency FROM journal_entry WHERE transaction_id = NEW.id
        GROUP BY currency HAVING sum(amount) <> 0
    ) u;
    IF unbalanced > 0 THEN
        RAISE EXCEPTION 'Journal transaction % is not balanced', NEW.id USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION journal_entry_balanced_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Full double-entry balance enforcement is folded into journal_transaction_has_entries
    -- (DEFERRABLE INITIALLY DEFERRED on journal_transaction) for PostgreSQL 18 partitioned-table
    -- compatibility and single-check-per-transaction performance.
    RETURN NULL;
END;
$$;

-- journal_entry triggers (row-level triggers automatically propagate to all partitions in PG 18)
CREATE TRIGGER journal_entry_apply
    AFTER INSERT ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION journal_entry_apply();

CREATE TRIGGER journal_entry_balanced
    AFTER INSERT ON journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION journal_entry_balanced_guard();

CREATE TRIGGER journal_entry_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER journal_entry_row_immutable
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- audit_log triggers
CREATE TRIGGER audit_log_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER audit_log_row_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- webhook_delivery_log triggers
CREATE TRIGGER webhook_delivery_log_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON webhook_delivery_log
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER webhook_delivery_log_row_immutable
    BEFORE UPDATE OR DELETE ON webhook_delivery_log
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- ============================================================================
-- 9. Recreate ledger_account_reconstruction View & Drop Legacy Tables
-- ============================================================================
CREATE VIEW ledger_account_reconstruction AS
SELECT a.id, a.code, a.balance AS projected_balance,
       COALESCE(sum(e.amount), 0)::bigint AS journal_balance
FROM ledger_account a
LEFT JOIN journal_entry e ON e.account_id = a.id
GROUP BY a.id, a.code, a.balance;

DROP TABLE IF EXISTS journal_entry_legacy;
DROP TABLE IF EXISTS audit_log_legacy;
DROP TABLE IF EXISTS webhook_delivery_log_legacy;
