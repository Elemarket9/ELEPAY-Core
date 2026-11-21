ALTER TABLE ledger_move
    ADD COLUMN currency text NOT NULL DEFAULT 'GHS' CHECK (currency = 'GHS');

CREATE FUNCTION reject_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Posted ledger records are immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER ledger_move_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_move
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER ledger_operation_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_operation
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
