-- ELEPAY financial core: signed double-entry journal.
--
-- Every journal transaction consists of two or more entries whose signed
-- amounts sum to zero per currency. A positive amount increases an account
-- balance and a negative amount decreases it. Customer, merchant and system
-- liability accounts hold non-negative balances; contra accounts that mirror
-- money held at providers or banks are allowed to go negative.
--
-- The sandbox tables from V1-V3 are left untouched.

CREATE TABLE ledger_account (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code           text        NOT NULL UNIQUE,
    owner_type     text        NOT NULL CHECK (owner_type IN ('customer', 'merchant', 'system')),
    owner_id       uuid,
    bucket         text        NOT NULL CHECK (bucket IN (
                       'available', 'pending', 'reserved',
                       'clearing', 'revenue', 'suspense', 'refunds_in_flight', 'disputes')),
    currency       char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    allow_negative boolean     NOT NULL DEFAULT false,
    balance        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CHECK (allow_negative OR balance >= 0),
    CHECK ((owner_type = 'system') = (owner_id IS NULL)),
    UNIQUE (owner_type, owner_id, bucket, currency)
);

CREATE TABLE journal_transaction (
    id                uuid PRIMARY KEY,
    idempotency_scope text        NOT NULL,
    idempotency_key   text        NOT NULL,
    request_hash      text        NOT NULL,
    kind              text        NOT NULL,
    description       text,
    reverses          uuid REFERENCES journal_transaction(id),
    related_to        uuid REFERENCES journal_transaction(id),
    metadata          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    posted_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (idempotency_scope, idempotency_key),
    CHECK (reverses IS NULL OR reverses <> id)
);

-- A transaction can be fully reversed at most once.
CREATE UNIQUE INDEX journal_transaction_single_reversal
    ON journal_transaction (reverses) WHERE reverses IS NOT NULL;
CREATE INDEX journal_transaction_related ON journal_transaction (related_to) WHERE related_to IS NOT NULL;

CREATE TABLE journal_entry (
    transaction_id uuid    NOT NULL REFERENCES journal_transaction(id),
    line           integer NOT NULL CHECK (line >= 0),
    account_id     bigint  NOT NULL REFERENCES ledger_account(id),
    currency       char(3) NOT NULL,
    amount         bigint  NOT NULL CHECK (amount <> 0),
    PRIMARY KEY (transaction_id, line)
);
CREATE INDEX journal_entry_account ON journal_entry (account_id, transaction_id);

-- Entries must use the account currency and update the balance projection
-- atomically. The ledger_account CHECK constraint rejects overdrafts even if
-- application checks are bypassed; concurrent writers serialize on the row.
CREATE FUNCTION journal_entry_apply() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    account_currency char(3);
BEGIN
    UPDATE ledger_account SET balance = balance + NEW.amount
    WHERE id = NEW.account_id
    RETURNING currency INTO account_currency;
    IF account_currency IS DISTINCT FROM NEW.currency THEN
        RAISE EXCEPTION 'Entry currency % does not match account currency %', NEW.currency, account_currency
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_entry_apply
    AFTER INSERT ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION journal_entry_apply();

-- Balanced-transaction check runs at commit so that multi-line postings can
-- be inserted one line at a time.
CREATE FUNCTION journal_transaction_check_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    tx uuid := NEW.transaction_id;
    entry_count integer;
    unbalanced integer;
BEGIN
    SELECT count(*) INTO entry_count FROM journal_entry WHERE transaction_id = tx;
    IF entry_count < 2 THEN
        RAISE EXCEPTION 'Journal transaction % needs at least two entries', tx USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO unbalanced FROM (
        SELECT currency FROM journal_entry WHERE transaction_id = tx
        GROUP BY currency HAVING sum(amount) <> 0
    ) u;
    IF unbalanced > 0 THEN
        RAISE EXCEPTION 'Journal transaction % is not balanced', tx USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT ON journal_entry DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION journal_transaction_check_balanced();

CREATE FUNCTION journal_transaction_check_has_entries() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM journal_entry WHERE transaction_id = NEW.id) THEN
        RAISE EXCEPTION 'Journal transaction % has no entries', NEW.id USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_transaction_has_entries
    AFTER INSERT ON journal_transaction DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION journal_transaction_check_has_entries();

-- Append-only journal. reject_ledger_mutation() was created in V3.
CREATE TRIGGER journal_transaction_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON journal_transaction
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER journal_entry_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

-- Accounts may be created but never deleted; only the balance projection
-- may change, and only through journal_entry_apply().
CREATE FUNCTION ledger_account_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Ledger accounts cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF NEW.id <> OLD.id OR NEW.code <> OLD.code OR NEW.owner_type <> OLD.owner_type
       OR NEW.owner_id IS DISTINCT FROM OLD.owner_id OR NEW.bucket <> OLD.bucket
       OR NEW.currency <> OLD.currency OR NEW.allow_negative <> OLD.allow_negative THEN
        RAISE EXCEPTION 'Ledger account identity is immutable' USING ERRCODE = '55000';
    END IF;
    IF pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'Balances change only through journal entries' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_account_guard
    BEFORE UPDATE OR DELETE ON ledger_account
    FOR EACH ROW EXECUTE FUNCTION ledger_account_guard();

-- Reconstruct every balance from the journal; compared against the projection
-- by reconciliation checks.
CREATE VIEW ledger_account_reconstruction AS
SELECT a.id, a.code, a.balance AS projected_balance,
       COALESCE(sum(e.amount), 0)::bigint AS journal_balance
FROM ledger_account a
LEFT JOIN journal_entry e ON e.account_id = a.id
GROUP BY a.id, a.code, a.balance;

-- Generic API idempotency: identical retries return the stored response,
-- a different request under the same key is rejected.
CREATE TABLE idempotent_request (
    scope        text        NOT NULL,
    key          text        NOT NULL,
    request_hash text        NOT NULL,
    response     jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, key)
);

INSERT INTO ledger_account (code, owner_type, bucket, currency, allow_negative) VALUES
    ('system:revenue:GHS',              'system', 'revenue',           'GHS', false),
    ('system:suspense:GHS',             'system', 'suspense',          'GHS', true),
    ('system:refunds_in_flight:GHS',    'system', 'refunds_in_flight', 'GHS', false),
    ('system:clearing:sandbox:GHS',     'system', 'clearing',          'GHS', true),
    ('system:clearing:paystack:GHS',    'system', 'clearing',          'GHS', true),
    ('system:clearing:hubtel:GHS',      'system', 'clearing',          'GHS', true);
