-- Bind recipient confirmations to explicit transfer amount and currency (Algorithm 038).
ALTER TABLE recipient_confirmation
    ADD COLUMN IF NOT EXISTS amount bigint CHECK (amount IS NULL OR amount > 0),
    ADD COLUMN IF NOT EXISTS currency text CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$');

-- Allow mandate_charge in wallet_operation.kind so KYC daily/monthly debit limits include mandate charges.
ALTER TABLE wallet_operation DROP CONSTRAINT IF EXISTS wallet_operation_kind_check;
ALTER TABLE wallet_operation ADD CONSTRAINT wallet_operation_kind_check
    CHECK (kind IN ('topup', 'transfer_out', 'transfer_in', 'withdrawal', 'payment', 'refund', 'mandate_charge'));
