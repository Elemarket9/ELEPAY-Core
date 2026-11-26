-- Every new journal header creates one internal event. The deferred journal
-- balance checks and the outbox insert commit or roll back together.
CREATE FUNCTION emit_journal_commit_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE event_id uuid := gen_random_uuid();
BEGIN
    INSERT INTO outbox_event(id, aggregate_type, aggregate_id, event_type, payload)
    VALUES (event_id, 'journal_transaction', NEW.id::text, 'ledger.posted',
        jsonb_build_object('schemaVersion', 1, 'eventId', event_id,
            'type', 'ledger.posted', 'data', jsonb_build_object('transactionId', NEW.id, 'kind', NEW.kind)));
    RETURN NEW;
END;
$$;
CREATE TRIGGER journal_commit_event AFTER INSERT ON journal_transaction
    FOR EACH ROW EXECUTE FUNCTION emit_journal_commit_event();
