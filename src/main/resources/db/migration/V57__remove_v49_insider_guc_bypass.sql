-- Remove the elepay.test_allow_insider_simulation GUC escape hatch from V49's
-- block_immutable_trigger_disable() event trigger function so no session can
-- bypass immutable ledger/audit trigger protection via ALTER TABLE ... DISABLE TRIGGER.

CREATE OR REPLACE FUNCTION block_immutable_trigger_disable() RETURNS event_trigger LANGUAGE plpgsql AS $$
DECLARE
    disabled_count integer;
BEGIN
    SELECT count(*) INTO disabled_count
    FROM pg_trigger t
    JOIN pg_class c ON c.oid = t.tgrelid
    WHERE c.relname IN (
        'journal_transaction', 'journal_entry', 'ledger_account', 'ledger_account_snapshot',
        'ledger_move', 'ledger_operation', 'audit_log', 'audit_checkpoint', 'integrity_key',
        'administrative_audit_event', 'redaction_audit', 'webhook_delivery_log',
        'marketplace_fee_schedule_history', 'enterprise_prefunding_ledger_entry',
        'settlement_reserve_release', 'maker_checker_proposal'
    )
    AND (t.tgname LIKE '%_immutable' OR t.tgname IN ('ledger_account_guard', 'maker_checker_proposal_guard', 'maker_checker_proposal_no_delete'))
    AND t.tgenabled <> 'O';

    IF disabled_count > 0 THEN
        RAISE EXCEPTION 'Disabling immutability triggers on financial ledger or audit tables is strictly forbidden'
            USING ERRCODE = '42501';
    END IF;
END;
$$;
