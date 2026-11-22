-- V38: Authoritative Ledger Backing for Agent Banking and Bill Payments
ALTER TABLE agent_transaction ADD COLUMN journal_tx uuid REFERENCES journal_transaction(id);
CREATE UNIQUE INDEX uq_agent_txn_journal ON agent_transaction(journal_tx) WHERE journal_tx IS NOT NULL;

ALTER TABLE bill_payment_transaction ADD COLUMN journal_tx uuid REFERENCES journal_transaction(id);
CREATE UNIQUE INDEX uq_bill_payment_journal ON bill_payment_transaction(journal_tx) WHERE journal_tx IS NOT NULL;
