-- Keep the unique claim for the lifetime of a financial reference. Only the
-- result moves to cold storage; retries still return the original response.
CREATE TABLE idempotency_result_archive (
    scope text NOT NULL,
    key text NOT NULL,
    response jsonb NOT NULL,
    archived_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, key),
    FOREIGN KEY (scope, key) REFERENCES idempotent_request (scope, key)
);

CREATE INDEX idempotent_request_archive_due ON idempotent_request (created_at)
    WHERE response IS NOT NULL;

CREATE OR REPLACE FUNCTION prune_idempotent_requests(
    retention_days integer DEFAULT 30, batch_size integer DEFAULT 10000
) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE archived bigint;
BEGIN
    WITH due AS MATERIALIZED (
        SELECT scope, key, response FROM idempotent_request
        WHERE response IS NOT NULL
          AND created_at < now() - make_interval(days => greatest(retention_days, 1))
        ORDER BY created_at, scope, key LIMIT greatest(batch_size, 1)
        FOR UPDATE SKIP LOCKED
    ), copied AS (
        INSERT INTO idempotency_result_archive (scope, key, response)
        SELECT scope, key, response FROM due RETURNING scope, key
    )
    UPDATE idempotent_request r SET response = NULL
    FROM copied c WHERE (r.scope, r.key) = (c.scope, c.key);
    GET DIAGNOSTICS archived = ROW_COUNT;
    RETURN archived;
END;
$$;
