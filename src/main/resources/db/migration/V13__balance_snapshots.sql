CREATE TABLE ledger_account_snapshot (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id                bigint      NOT NULL REFERENCES ledger_account(id),
    currency                  char(3)     NOT NULL,
    snapshot_balance          bigint      NOT NULL,
    journal_entry_sequence_id bigint      NOT NULL DEFAULT 0,
    snapshot_at               timestamptz NOT NULL DEFAULT now(),
    signature                 varchar(64) NOT NULL,
    created_at                timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ledger_account_snapshot_account ON ledger_account_snapshot (account_id, snapshot_at DESC);

CREATE TRIGGER ledger_account_snapshot_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_account_snapshot
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
