-- A snapshot is an immutable observation, not a posting or a source of spendable balances.
CREATE TABLE ledger_snapshot_run (
    snapshot_day date PRIMARY KEY,
    captured_at timestamptz NOT NULL DEFAULT statement_timestamp()
);
CREATE TABLE ledger_balance_snapshot (
    snapshot_day date NOT NULL REFERENCES ledger_snapshot_run(snapshot_day),
    account_id bigint NOT NULL REFERENCES ledger_account(id),
    currency char(3) NOT NULL,
    balance bigint NOT NULL,
    journal_balance bigint NOT NULL,
    PRIMARY KEY (snapshot_day, account_id)
);
CREATE TRIGGER ledger_snapshot_run_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_snapshot_run
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER ledger_balance_snapshot_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_balance_snapshot
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

-- Bounded 80–100% exponential jitter. Retry only queries/notifications, never ambiguous money submission.
CREATE FUNCTION elepay_retry_seconds(base_seconds integer, cap_seconds integer, attempt integer, sample double precision)
RETURNS double precision LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT least(cap_seconds::double precision, base_seconds * power(2::double precision, least(30, greatest(attempt, 0))))
        * (0.8 + 0.2 * greatest(0::double precision, least(1::double precision, sample)))
$$;
