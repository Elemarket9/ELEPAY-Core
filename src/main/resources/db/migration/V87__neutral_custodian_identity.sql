-- The safeguarding control row can exist before a custodian is selected.
ALTER TABLE custodian_trust_account
    ALTER COLUMN bank_code DROP NOT NULL,
    ALTER COLUMN bank_account_number DROP NOT NULL;

-- Clear only the original, system-owned seed identity. Retain caps and balances.
UPDATE custodian_trust_account
SET bank_code = NULL, bank_account_number = NULL
WHERE id = '00000000-0000-0000-0000-000000000049'
  AND account_code = 'CUSTODIAN_TRUST_PRIMARY_GHS'
  AND bank_code = 'GCB'
  AND bank_account_number = '10102030405060'
  AND updated_by = 'system';

ALTER TABLE custodian_trust_account
    ADD CONSTRAINT custodian_bank_identity_pair
    CHECK ((bank_code IS NULL) = (bank_account_number IS NULL));
