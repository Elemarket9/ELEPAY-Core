-- Separately seal append-only audit events without holding a global lock in financial transactions.
-- The chain order is sealing order, not audit sequence allocation or transaction commit order.
CREATE TABLE audit_chain (
    position bigint PRIMARY KEY CHECK (position > 0),
    audit_id bigint NOT NULL UNIQUE REFERENCES audit_log(id),
    previous_hash bytea NOT NULL CHECK (octet_length(previous_hash) = 32),
    event_hash bytea NOT NULL CHECK (octet_length(event_hash) = 32),
    sealed_at timestamptz NOT NULL DEFAULT statement_timestamp()
);
CREATE TRIGGER audit_chain_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_chain
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
CREATE INDEX audit_log_created_at ON audit_log (created_at);

-- Canonical digest: UTF-8 of a JSON array, with exact UTC epoch and previous digest.
-- Never export personal details to metrics or logs. This is tamper-evidence, not a signature.
CREATE FUNCTION elepay_audit_digest(
    p_position bigint, p_id bigint, p_actor text, p_action text, p_subject text,
    p_details jsonb, p_created timestamptz, p_previous bytea
) RETURNS bytea LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT sha256(convert_to(jsonb_build_array(
        'elepay-audit-v1', p_position, p_id, p_actor, p_action, p_subject,
        p_details, extract(epoch FROM p_created), encode(p_previous, 'hex')
    )::text, 'UTF8'))
$$;

CREATE FUNCTION elepay_seal_audit(p_limit integer) RETURNS integer LANGUAGE plpgsql AS $$
DECLARE
    v_position bigint := 0;
    v_previous bytea := decode(repeat('00', 32), 'hex');
    v_current bytea;
    v_count integer := 0;
    v_event record;
BEGIN
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 5000 THEN
        RAISE EXCEPTION 'Invalid audit sealing batch size';
    END IF;
    -- Lock is held only by this scheduled worker, not by writers of financial audit events.
    IF NOT pg_try_advisory_xact_lock(520, 80) THEN
        RETURN 0;
    END IF;
    SELECT position, event_hash INTO v_position, v_previous FROM audit_chain ORDER BY position DESC LIMIT 1;
    IF NOT FOUND THEN
        v_position := 0;
        v_previous := decode(repeat('00', 32), 'hex');
    END IF;
    -- Anti-join also catches older identity values committed after a previous sealing batch.
    FOR v_event IN
        SELECT a.id, a.actor, a.action, a.subject, a.details, a.created_at
        FROM audit_log a WHERE NOT EXISTS (SELECT 1 FROM audit_chain c WHERE c.audit_id = a.id)
        ORDER BY a.id LIMIT p_limit
    LOOP
        v_position := v_position + 1;
        v_current := elepay_audit_digest(v_position, v_event.id, v_event.actor, v_event.action,
                        v_event.subject, v_event.details, v_event.created_at, v_previous);
        INSERT INTO audit_chain(position, audit_id, previous_hash, event_hash)
            VALUES (v_position, v_event.id, v_previous, v_current);
        v_previous := v_current;
        v_count := v_count + 1;
    END LOOP;
    RETURN v_count;
END;
$$;

CREATE VIEW audit_chain_violations AS
WITH linked AS (
    SELECT c.*, lag(c.position, 1, 0::bigint) OVER (ORDER BY c.position) AS predecessor,
           lag(c.event_hash, 1, decode(repeat('00', 32), 'hex')) OVER (ORDER BY c.position) AS expected_previous
    FROM audit_chain c
)
SELECT l.position, l.audit_id FROM linked l JOIN audit_log a ON a.id = l.audit_id
WHERE l.position <> l.predecessor + 1
   OR l.previous_hash <> l.expected_previous
   OR l.event_hash <> elepay_audit_digest(l.position, a.id, a.actor, a.action, a.subject, a.details, a.created_at, l.previous_hash);
