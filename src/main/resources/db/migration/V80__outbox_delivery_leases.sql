ALTER TABLE outbox_event ADD COLUMN claim_token uuid, ADD COLUMN dead_at timestamptz;
CREATE INDEX outbox_unpublished_aggregate ON outbox_event (aggregate_type, aggregate_id, sequence)
    WHERE published_at IS NULL;

CREATE TABLE outbox_replay_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES outbox_event(id),
    replay_at timestamptz NOT NULL DEFAULT now(),
    operator_name text NOT NULL DEFAULT session_user,
    incident_reference text NOT NULL CHECK (length(trim(incident_reference)) BETWEEN 1 AND 200)
);
CREATE TRIGGER outbox_replay_audit_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
    ON outbox_replay_audit FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

-- DBA grants EXECUTE to a dedicated recovery role; never reset the consumer inbox.
CREATE FUNCTION replay_dead_outbox(target uuid, incident text) RETURNS boolean
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    UPDATE outbox_event SET dead_at = NULL, attempts = 0, claim_token = NULL,
        next_attempt_at = now() WHERE id = target AND dead_at IS NOT NULL AND published_at IS NULL;
    IF NOT FOUND THEN RETURN false; END IF;
    INSERT INTO outbox_replay_audit(event_id, incident_reference) VALUES (target, incident);
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION replay_dead_outbox(uuid, text) FROM PUBLIC;

CREATE TABLE outbox_payload_archive (
    event_id uuid PRIMARY KEY REFERENCES outbox_event(id),
    payload jsonb NOT NULL,
    archived_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE outbox_event ADD COLUMN archived_at timestamptz;
ALTER TABLE outbox_event ALTER COLUMN payload DROP NOT NULL;
ALTER TABLE outbox_event ADD CONSTRAINT outbox_payload_location CHECK (
    (archived_at IS NULL AND payload IS NOT NULL) OR
    (archived_at IS NOT NULL AND published_at IS NOT NULL AND payload IS NULL)
);
CREATE INDEX outbox_archive_due ON outbox_event(published_at, id)
    WHERE published_at IS NOT NULL AND archived_at IS NULL;
CREATE FUNCTION archive_published_outbox(retention_days integer, batch_size integer) RETURNS bigint
LANGUAGE sql AS $$
    WITH due AS MATERIALIZED (
        SELECT id, payload FROM outbox_event WHERE archived_at IS NULL
          AND published_at < now() - make_interval(days => greatest(1, retention_days))
        ORDER BY published_at, id LIMIT greatest(1, batch_size) FOR NO KEY UPDATE SKIP LOCKED
    ), copied AS (
        INSERT INTO outbox_payload_archive(event_id, payload) SELECT id, payload FROM due RETURNING event_id
    ), moved AS (
        UPDATE outbox_event e SET payload = NULL, archived_at = now() FROM copied c
        WHERE c.event_id = e.id RETURNING e.id
    ) SELECT count(*) FROM moved
$$;
