-- V89__restore_ledger_guard_and_constraints.sql
--
-- 1. Restores DB-level double-entry ledger enforcement for partitioned journal_entry
--    (closing the V77 gap where journal_entry_balanced_guard() became a no-op and allowed
--    post-commit INSERT INTO journal_entry against already-committed journal_transaction rows
--    to mint balances without zero-sum validation, and where (transaction_id, line) uniqueness
--    was weakened across partitions).
-- 2. Adds missing CHECK (>= 0 / > 0) and UNIQUE invariants on core financial tables
--    (V6, V10, V22/V88, V42, V45, V49, V87) using NOT VALID + VALIDATE CONSTRAINT.

-- ============================================================================
-- 1. DB-Level Double-Entry Guard on Partitioned journal_entry
-- ============================================================================

-- Tracks journal_transaction IDs created within the CURRENT uncommitted database transaction.
-- Rows are inserted on journal_transaction INSERT and deleted by the DEFERRABLE INITIALLY DEFERRED
-- constraint trigger at COMMIT time after verifying double-entry zero-sum balance.
-- Under PostgreSQL MVCC, rows are never visible to other transactions and the table stays empty
-- after commit.
CREATE TABLE IF NOT EXISTS journal_tx_in_progress (
    transaction_id uuid PRIMARY KEY
);

REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON journal_tx_in_progress FROM PUBLIC;

-- Lock down journal_tx_in_progress against direct DML and non-owner pg_temp trigger bypasses:
--   - UPDATE and TRUNCATE are unconditionally forbidden.
--   - INSERT and DELETE are permitted ONLY when invoked from inside a SECURITY DEFINER ledger
--     trigger owned by the table owner:
--       1. pg_trigger_depth() >= 2 (depth 1 = journal_tx_open_tracker or journal_tx_close_and_check,
--          depth 2 = this guard trigger). Direct SQL INSERT/DELETE executes at depth 1 and is rejected.
--       2. current_user must equal the relation owner of journal_tx_in_progress, so even a
--          non-superuser role granted DML on journal_tx_in_progress cannot bypass via a pg_temp trigger.
CREATE OR REPLACE FUNCTION journal_tx_in_progress_guard() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'journal_tx_in_progress cannot be updated' USING ERRCODE = '55000';
    END IF;
    IF pg_trigger_depth() < 2
       OR current_user IS DISTINCT FROM (
           SELECT pg_catalog.pg_get_userbyid(c.relowner)
           FROM pg_catalog.pg_class c
           WHERE c.oid = TG_RELID
       ) THEN
        RAISE EXCEPTION 'journal_tx_in_progress is managed exclusively by ledger triggers' USING ERRCODE = '55000';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER journal_tx_in_progress_guard
    BEFORE INSERT OR UPDATE OR DELETE ON journal_tx_in_progress
    FOR EACH STATEMENT EXECUTE FUNCTION journal_tx_in_progress_guard();

REVOKE ALL ON FUNCTION public.journal_tx_in_progress_guard() FROM PUBLIC;

CREATE TRIGGER journal_tx_in_progress_no_truncate
    BEFORE TRUNCATE ON journal_tx_in_progress
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

CREATE OR REPLACE FUNCTION journal_tx_mark_open() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP <> 'INSERT' OR TG_RELID IS DISTINCT FROM 'public.journal_transaction'::pg_catalog.regclass THEN
        RAISE EXCEPTION 'journal_tx_mark_open may only be invoked by trigger on public.journal_transaction'
            USING ERRCODE = '55000';
    END IF;
    INSERT INTO public.journal_tx_in_progress (transaction_id) VALUES (NEW.id);
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION public.journal_tx_mark_open() FROM PUBLIC;

CREATE TRIGGER journal_tx_open_tracker
    AFTER INSERT ON journal_transaction
    FOR EACH ROW EXECUTE FUNCTION journal_tx_mark_open();

CREATE OR REPLACE FUNCTION journal_tx_verify_and_close() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    entry_count integer;
    unbalanced  integer;
BEGIN
    IF TG_OP <> 'INSERT' OR TG_RELID IS DISTINCT FROM 'public.journal_tx_in_progress'::pg_catalog.regclass THEN
        RAISE EXCEPTION 'journal_tx_verify_and_close may only be invoked by trigger on public.journal_tx_in_progress'
            USING ERRCODE = '55000';
    END IF;
    DELETE FROM public.journal_tx_in_progress WHERE transaction_id = NEW.transaction_id;

    SELECT count(*) INTO entry_count FROM public.journal_entry WHERE transaction_id = NEW.transaction_id;
    IF entry_count = 0 THEN
        RAISE EXCEPTION 'Journal transaction % has no entries', NEW.transaction_id USING ERRCODE = '23514';
    END IF;
    IF entry_count < 2 THEN
        RAISE EXCEPTION 'Journal transaction % needs at least two entries', NEW.transaction_id USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO unbalanced FROM (
        SELECT currency FROM public.journal_entry WHERE transaction_id = NEW.transaction_id
        GROUP BY currency HAVING sum(amount) <> 0
    ) u;
    IF unbalanced > 0 THEN
        RAISE EXCEPTION 'Journal transaction % is not balanced', NEW.transaction_id USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION public.journal_tx_verify_and_close() FROM PUBLIC;

CREATE CONSTRAINT TRIGGER journal_tx_close_and_check
    AFTER INSERT ON journal_tx_in_progress DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION journal_tx_verify_and_close();

-- Enforces on EVERY journal_entry INSERT (propagated to all partitions in PG 18):
--   1. Rejects any append to an already-committed journal_transaction (prevents post-commit
--      unbalanced balance minting and retroactive tampering).
--   2. Rejects duplicate (transaction_id, line) across partitions (restores V4 PK invariant).
-- Marked SECURITY DEFINER so least-privilege non-superuser app roles do not require SELECT on
-- journal_tx_in_progress to post valid journal entries.
CREATE OR REPLACE FUNCTION journal_entry_enforce_open_tx_and_unique_line() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP <> 'INSERT' OR NOT (
        TG_RELID = 'public.journal_entry'::pg_catalog.regclass
        OR EXISTS (
            SELECT 1
            FROM pg_catalog.pg_inherits i
            WHERE i.inhrelid = TG_RELID
              AND i.inhparent = 'public.journal_entry'::pg_catalog.regclass
        )
    ) THEN
        RAISE EXCEPTION 'journal_entry_enforce_open_tx_and_unique_line may only be invoked by trigger on public.journal_entry'
            USING ERRCODE = '55000';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.journal_tx_in_progress WHERE transaction_id = NEW.transaction_id) THEN
        RAISE EXCEPTION 'Cannot insert journal_entry for committed or unknown journal_transaction % (double-entry guard)',
            NEW.transaction_id USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM public.journal_entry WHERE transaction_id = NEW.transaction_id AND line = NEW.line) THEN
        RAISE EXCEPTION 'Duplicate journal_entry (transaction_id=%, line=%)',
            NEW.transaction_id, NEW.line USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.journal_entry_enforce_open_tx_and_unique_line() FROM PUBLIC;

CREATE TRIGGER journal_entry_open_tx_guard
    BEFORE INSERT ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION journal_entry_enforce_open_tx_and_unique_line();

-- Extend V74/V80's trigger-tampering event trigger functions to also protect journal_tx_in_progress
-- and all V89 double-entry guard triggers against DISABLE/DROP TRIGGER (preserving V80's
-- failover_audit_event and pg_inherits child-partition protections).
CREATE OR REPLACE FUNCTION block_immutable_trigger_disable() RETURNS event_trigger LANGUAGE plpgsql AS $$
DECLARE
    disabled_count integer;
BEGIN
    SELECT count(*) INTO disabled_count
    FROM pg_trigger t
    JOIN pg_class c ON c.oid = t.tgrelid
    WHERE (
        c.relname IN (
            'journal_transaction', 'journal_entry', 'journal_tx_in_progress',
            'ledger_account', 'ledger_account_snapshot',
            'ledger_move', 'ledger_operation', 'audit_log', 'audit_checkpoint', 'integrity_key',
            'administrative_audit_event', 'redaction_audit', 'webhook_delivery_log',
            'marketplace_fee_schedule_history', 'enterprise_prefunding_ledger',
            'financial_ledger_metric_snapshot', 'checkout_config_history', 'gra_fiscal_config_history',
            'settlement_reserve_release', 'maker_checker_proposal', 'failover_audit_event'
        )
        OR EXISTS (
            SELECT 1
            FROM pg_inherits i
            JOIN pg_class p ON p.oid = i.inhparent
            WHERE i.inhrelid = c.oid
              AND p.relname IN ('journal_entry', 'audit_log', 'webhook_delivery_log')
        )
    )
    AND (
        t.tgname LIKE '%_immutable'
        OR t.tgname IN (
            'ledger_account_guard',
            'ledger_account_no_truncate',
            'journal_entry_apply',
            'journal_entry_balanced',
            'journal_entry_open_tx_guard',
            'journal_transaction_has_entries',
            'journal_tx_open_tracker',
            'journal_tx_close_and_check',
            'journal_tx_in_progress_guard',
            'journal_tx_in_progress_no_truncate',
            'maker_checker_proposal_guard',
            'maker_checker_proposal_no_delete'
        )
    )
    AND t.tgenabled <> 'O';

    IF disabled_count > 0 THEN
        RAISE EXCEPTION 'Disabling immutability triggers on financial ledger or audit tables is strictly forbidden'
            USING ERRCODE = '42501';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION block_immutable_trigger_drop() RETURNS event_trigger LANGUAGE plpgsql AS $$
DECLARE
    obj record;
    trig_name text;
BEGIN
    FOR obj IN SELECT object_type, object_identity FROM pg_event_trigger_dropped_objects()
    LOOP
        IF obj.object_type = 'trigger' THEN
            trig_name := split_part(obj.object_identity, ' on ', 1);
            IF trig_name LIKE '%_immutable'
               OR trig_name IN (
                   'ledger_account_guard',
                   'ledger_account_no_truncate',
                   'journal_entry_apply',
                   'journal_entry_balanced',
                   'journal_entry_open_tx_guard',
                   'journal_transaction_has_entries',
                   'journal_tx_open_tracker',
                   'journal_tx_close_and_check',
                   'journal_tx_in_progress_guard',
                   'journal_tx_in_progress_no_truncate',
                   'maker_checker_proposal_guard',
                   'maker_checker_proposal_no_delete'
               ) THEN
                RAISE EXCEPTION 'Dropping immutability or balance-integrity trigger % is strictly forbidden', obj.object_identity
                    USING ERRCODE = '42501';
            END IF;
        END IF;
    END LOOP;
END;
$$;

-- ============================================================================
-- 2. Missing CHECK & UNIQUE Constraints on Core Financial Tables
-- ============================================================================

-- V49: payout_safety_control
ALTER TABLE payout_safety_control
    ADD CONSTRAINT chk_psc_hourly_ceiling_nonneg CHECK (hourly_ceiling_pesewas >= 0) NOT VALID,
    ADD CONSTRAINT chk_psc_daily_ceiling_nonneg CHECK (daily_ceiling_pesewas >= 0) NOT VALID,
    ADD CONSTRAINT chk_psc_max_single_nonneg CHECK (max_single_payout_pesewas >= 0) NOT VALID;
ALTER TABLE payout_safety_control VALIDATE CONSTRAINT chk_psc_hourly_ceiling_nonneg;
ALTER TABLE payout_safety_control VALIDATE CONSTRAINT chk_psc_daily_ceiling_nonneg;
ALTER TABLE payout_safety_control VALIDATE CONSTRAINT chk_psc_max_single_nonneg;

-- V49: payout_global_velocity_window
ALTER TABLE payout_global_velocity_window
    ADD CONSTRAINT chk_pgvw_outflow_nonneg CHECK (total_outflow_pesewas >= 0) NOT VALID,
    ADD CONSTRAINT chk_pgvw_count_nonneg CHECK (payout_count >= 0) NOT VALID;
ALTER TABLE payout_global_velocity_window VALIDATE CONSTRAINT chk_pgvw_outflow_nonneg;
ALTER TABLE payout_global_velocity_window VALIDATE CONSTRAINT chk_pgvw_count_nonneg;

-- V49: custodian_trust_account
ALTER TABLE custodian_trust_account
    ADD CONSTRAINT chk_cta_daily_cap_nonneg CHECK (daily_outflow_cap_pesewas >= 0) NOT VALID,
    ADD CONSTRAINT chk_cta_per_payout_cap_nonneg CHECK (per_payout_cap_pesewas >= 0) NOT VALID,
    ADD CONSTRAINT chk_cta_min_reserve_nonneg CHECK (min_reserve_floor_pesewas >= 0) NOT VALID,
    ADD CONSTRAINT chk_cta_bank_balance_nonneg CHECK (last_reconciled_bank_balance_pesewas IS NULL OR last_reconciled_bank_balance_pesewas >= 0) NOT VALID,
    ADD CONSTRAINT chk_cta_per_payout_le_daily CHECK (per_payout_cap_pesewas <= daily_outflow_cap_pesewas) NOT VALID;
ALTER TABLE custodian_trust_account VALIDATE CONSTRAINT chk_cta_daily_cap_nonneg;
ALTER TABLE custodian_trust_account VALIDATE CONSTRAINT chk_cta_per_payout_cap_nonneg;
ALTER TABLE custodian_trust_account VALIDATE CONSTRAINT chk_cta_min_reserve_nonneg;
ALTER TABLE custodian_trust_account VALIDATE CONSTRAINT chk_cta_bank_balance_nonneg;
ALTER TABLE custodian_trust_account VALIDATE CONSTRAINT chk_cta_per_payout_le_daily;

-- V49: custodian_daily_outflow
ALTER TABLE custodian_daily_outflow
    ADD CONSTRAINT chk_cdo_reserved_nonneg CHECK (reserved_outflow_pesewas >= 0) NOT VALID,
    ADD CONSTRAINT chk_cdo_settled_nonneg CHECK (settled_outflow_pesewas >= 0) NOT VALID,
    ADD CONSTRAINT chk_cdo_rejected_nonneg CHECK (rejected_count >= 0) NOT VALID;
ALTER TABLE custodian_daily_outflow VALIDATE CONSTRAINT chk_cdo_reserved_nonneg;
ALTER TABLE custodian_daily_outflow VALIDATE CONSTRAINT chk_cdo_settled_nonneg;
ALTER TABLE custodian_daily_outflow VALIDATE CONSTRAINT chk_cdo_rejected_nonneg;

-- V87: platform_revenue_sweep UNIQUE & FK/in-flight indexes
CREATE UNIQUE INDEX IF NOT EXISTS uq_prs_rail_tx
    ON platform_revenue_sweep (rail_tx);
CREATE UNIQUE INDEX IF NOT EXISTS uq_prs_reservation_tx
    ON platform_revenue_sweep (reservation_tx);
CREATE UNIQUE INDEX IF NOT EXISTS uq_prs_proposal_id
    ON platform_revenue_sweep (proposal_id) WHERE proposal_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_prs_operating_account
    ON platform_revenue_sweep (operating_account_id);
CREATE INDEX IF NOT EXISTS idx_prs_inflight_currency
    ON platform_revenue_sweep (currency) WHERE status IN ('reserved', 'submitted');

-- V42: enterprise_treasury_account, enterprise_prefunding_ledger, enterprise_settlement_batch_item
ALTER TABLE enterprise_treasury_account
    ADD CONSTRAINT chk_eta_balance_nonneg CHECK (balance_pesewas >= 0) NOT VALID;
ALTER TABLE enterprise_treasury_account VALIDATE CONSTRAINT chk_eta_balance_nonneg;

ALTER TABLE enterprise_prefunding_ledger
    ADD CONSTRAINT chk_epl_amount_nonzero CHECK (amount_pesewas <> 0) NOT VALID,
    ADD CONSTRAINT chk_epl_balance_after_nonneg CHECK (balance_after_pesewas >= 0) NOT VALID;
ALTER TABLE enterprise_prefunding_ledger VALIDATE CONSTRAINT chk_epl_amount_nonzero;
ALTER TABLE enterprise_prefunding_ledger VALIDATE CONSTRAINT chk_epl_balance_after_nonneg;

CREATE UNIQUE INDEX IF NOT EXISTS uq_esbi_escrow_active
    ON enterprise_settlement_batch_item (escrow_id) WHERE escrow_id IS NOT NULL AND status <> 'EXCLUDED';

-- V22 / V45 / V88: POS tender payment & offline queue
ALTER TABLE pos_tender_payment
    ADD CONSTRAINT chk_ptp_amount_pos CHECK (amount_pesewas > 0) NOT VALID;
ALTER TABLE pos_tender_payment VALIDATE CONSTRAINT chk_ptp_amount_pos;

ALTER TABLE pos_offline_transaction_queue
    ADD CONSTRAINT chk_potq_amount_pos CHECK (amount_pesewas > 0) NOT VALID;
ALTER TABLE pos_offline_transaction_queue VALIDATE CONSTRAINT chk_potq_amount_pos;

-- V10: fx_transfer
ALTER TABLE fx_transfer
    ADD CONSTRAINT chk_fxt_source_amount_pos CHECK (source_amount > 0) NOT VALID,
    ADD CONSTRAINT chk_fxt_fee_nonneg CHECK (fee >= 0) NOT VALID,
    ADD CONSTRAINT chk_fxt_dest_amount_pos CHECK (destination_amount > 0) NOT VALID,
    ADD CONSTRAINT chk_fxt_rate_pos CHECK (rate > 0) NOT VALID;
ALTER TABLE fx_transfer VALIDATE CONSTRAINT chk_fxt_source_amount_pos;
ALTER TABLE fx_transfer VALIDATE CONSTRAINT chk_fxt_fee_nonneg;
ALTER TABLE fx_transfer VALIDATE CONSTRAINT chk_fxt_dest_amount_pos;
ALTER TABLE fx_transfer VALIDATE CONSTRAINT chk_fxt_rate_pos;

-- V6: rail_transaction
ALTER TABLE rail_transaction
    ADD CONSTRAINT chk_rt_rail_fee_nonneg CHECK (rail_fee IS NULL OR rail_fee >= 0) NOT VALID;
ALTER TABLE rail_transaction VALIDATE CONSTRAINT chk_rt_rail_fee_nonneg;

CREATE UNIQUE INDEX IF NOT EXISTS uq_rail_tx_rail_ref
    ON rail_transaction (rail, rail_reference) WHERE rail_reference IS NOT NULL;
