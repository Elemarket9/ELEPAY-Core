-- P1 #8: Financial table immutability & trigger-protection hardening.
--
-- Closes schema-level gaps identified during the DB privilege and immutability audit:
--   1. Protects `enterprise_prefunding_ledger` (missed by V49 due to table-name typo
--      `enterprise_prefunding_ledger_entry`), plus append-only financial/config history tables
--      `financial_ledger_metric_snapshot`, `checkout_config_history`, and `gra_fiscal_config_history`.
--   2. Adds `ledger_account_no_truncate` (BEFORE TRUNCATE) on `ledger_account` to complement
--      `ledger_account_guard` (which covers BEFORE UPDATE OR DELETE).
--   3. Extends `block_immutable_trigger_disable()` (V57) so ALTER TABLE ... DISABLE TRIGGER is
--      also rejected for core balance-sync and double-entry enforcement triggers
--      (`journal_entry_apply`, `journal_entry_balanced`, `journal_transaction_has_entries`,
--      `ledger_account_no_truncate`) and the newly protected history tables.
--   4. Adds an `sql_drop` event trigger (`elepay_block_immutable_trigger_drop`) scoped to
--      `WHEN TAG IN ('DROP TRIGGER')` so explicit DROP TRIGGER statements cannot remove
--      immutability or balance-integrity triggers, while leaving DROP TABLE / schema teardown unaffected.

-- 1. Append-only triggers on remaining financial & config history tables
DO $$
DECLARE
    tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'enterprise_prefunding_ledger',
        'financial_ledger_metric_snapshot',
        'checkout_config_history',
        'gra_fiscal_config_history'
    ]
    LOOP
        IF to_regclass('public.' || tbl) IS NOT NULL AND NOT EXISTS (
            SELECT 1
            FROM pg_trigger t
            JOIN pg_class c ON c.oid = t.tgrelid
            WHERE c.relname = tbl AND t.tgname = tbl || '_immutable'
        ) THEN
            EXECUTE format(
                'CREATE TRIGGER %I BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation()',
                tbl || '_immutable', tbl
            );
        END IF;
    END LOOP;
END;
$$;

-- 2. Block TRUNCATE on ledger_account (ledger_account_guard only covers BEFORE UPDATE OR DELETE)
DROP TRIGGER IF EXISTS ledger_account_no_truncate ON ledger_account;
CREATE TRIGGER ledger_account_no_truncate
    BEFORE TRUNCATE ON ledger_account
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

-- 3. Extend V57's ALTER TABLE ... DISABLE TRIGGER guard to cover balance-sync & double-entry triggers
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
        'marketplace_fee_schedule_history', 'enterprise_prefunding_ledger',
        'financial_ledger_metric_snapshot', 'checkout_config_history', 'gra_fiscal_config_history',
        'settlement_reserve_release', 'maker_checker_proposal'
    )
    AND (
        t.tgname LIKE '%_immutable'
        OR t.tgname IN (
            'ledger_account_guard',
            'ledger_account_no_truncate',
            'journal_entry_apply',
            'journal_entry_balanced',
            'journal_transaction_has_entries',
            'maker_checker_proposal_guard',
            'maker_checker_proposal_no_delete'
        )
    )
    AND t.tgenabled <> 'O';

    IF disabled_count > 0 THEN
        RAISE EXCEPTION 'Disabling immutability or balance-integrity triggers on financial ledger or audit tables is strictly forbidden'
            USING ERRCODE = '42501';
    END IF;
END;
$$;

-- 4. Block explicit DROP TRIGGER on protected immutability and balance-integrity triggers
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
                   'journal_transaction_has_entries',
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

DROP EVENT TRIGGER IF EXISTS elepay_block_immutable_trigger_drop;
CREATE EVENT TRIGGER elepay_block_immutable_trigger_drop
    ON sql_drop
    WHEN TAG IN ('DROP TRIGGER')
    EXECUTE FUNCTION block_immutable_trigger_drop();
