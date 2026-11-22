-- Balance snapshots need an exact cut-off. posted_at is the transaction's start time, so a posting that
-- waited on the account lock can commit after a snapshot yet carry an earlier timestamp. entry_seq is
-- assigned at insert, which happens only after the posting holds the account lock, so it orders entries
-- against a snapshot of that account exactly.
ALTER TABLE journal_entry ADD COLUMN entry_seq bigint GENERATED ALWAYS AS IDENTITY;
CREATE INDEX journal_entry_account_seq ON journal_entry (account_id, entry_seq);
ALTER TABLE ledger_account_snapshot ADD COLUMN entry_seq_cutoff bigint;
