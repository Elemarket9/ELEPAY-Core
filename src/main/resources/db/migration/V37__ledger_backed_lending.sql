ALTER TABLE loan_application ADD COLUMN reviewed_by text, ADD COLUMN review_evidence varchar(256);
ALTER TABLE loan_contract ADD COLUMN sweep_mandate varchar(256), ADD COLUMN sweep_authorized_by text;
ALTER TABLE loan_transaction ADD COLUMN journal_tx uuid REFERENCES journal_transaction(id), ADD COLUMN settlement_evidence uuid REFERENCES journal_transaction(id);
CREATE UNIQUE INDEX uq_loan_journal ON loan_transaction(journal_tx) WHERE journal_tx IS NOT NULL;
CREATE UNIQUE INDEX uq_loan_settlement_evidence ON loan_transaction(settlement_evidence) WHERE settlement_evidence IS NOT NULL;
