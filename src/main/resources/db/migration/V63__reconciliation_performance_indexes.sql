-- V63: Reconciliation hot-path performance indexes.
-- Supports 10-way clearing reconciliation (TenWayClearingReconciliationEngine)
-- and 4-way statement upload reconciliation (ReconciliationService) at 50k+ scale.

-- 1. recon_match_record(ledger_tx_id): backs NOT EXISTS anti-join in MISSING_IN_BANK detection
CREATE INDEX IF NOT EXISTS idx_recon_match_record_ledger_tx
    ON recon_match_record (ledger_tx_id)
    WHERE ledger_tx_id IS NOT NULL;

-- 2. recon_statement_line(batch_id, line_number): backs batch line fetch ordered by line_number
CREATE INDEX IF NOT EXISTS idx_recon_statement_line_batch
    ON recon_statement_line (batch_id, line_number);

-- 3. journal_transaction(posted_at), (idempotency_key), (description):
--    backs period range scan and bulk reference hash-joins in 10-way reconciliation
CREATE INDEX IF NOT EXISTS idx_journal_transaction_posted_at
    ON journal_transaction (posted_at);

CREATE INDEX IF NOT EXISTS idx_journal_transaction_idempotency_key
    ON journal_transaction (idempotency_key);

CREATE INDEX IF NOT EXISTS idx_journal_transaction_description
    ON journal_transaction (description)
    WHERE description IS NOT NULL;

-- 4. direct_carrier_session(reference_id), (external_transaction_id):
--    existing indexes lead with (carrier, ...); bulk reconciliation joins across carriers
CREATE INDEX IF NOT EXISTS idx_direct_carrier_session_ref
    ON direct_carrier_session (reference_id)
    WHERE reference_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_direct_carrier_session_ext
    ON direct_carrier_session (external_transaction_id)
    WHERE external_transaction_id IS NOT NULL;

-- 5. rail_transaction(rail, reference), (rail, rail_reference):
--    backs bulk statement upload matching in ReconciliationService
CREATE INDEX IF NOT EXISTS idx_rail_transaction_rail_ref
    ON rail_transaction (rail, reference);

CREATE INDEX IF NOT EXISTS idx_rail_transaction_rail_rail_ref
    ON rail_transaction (rail, rail_reference)
    WHERE rail_reference IS NOT NULL;

-- 6. Commercial record rail_tx foreign-key indexes for 4-way bulk verification
CREATE INDEX IF NOT EXISTS idx_payment_attempt_rail_tx
    ON payment_attempt (rail_tx)
    WHERE rail_tx IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payout_rail_tx
    ON payout (rail_tx)
    WHERE rail_tx IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_wallet_operation_rail_tx
    ON wallet_operation (rail_tx)
    WHERE rail_tx IS NOT NULL;
