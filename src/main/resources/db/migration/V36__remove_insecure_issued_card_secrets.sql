ALTER TABLE card_registry ALTER COLUMN cvv2 DROP NOT NULL;
ALTER TABLE card_registry ALTER COLUMN encrypted_pan DROP NOT NULL;
-- Remove cleartext test card credentials, not genuine encrypted issuer records.
UPDATE card_registry SET encrypted_pan = NULL, cvv2 = NULL, status = 'REVOKED', updated_at = now() WHERE encrypted_pan LIKE 'ENC:%';
-- CVV is not retained after issuance or authorization.
UPDATE card_registry SET cvv2 = NULL WHERE cvv2 IS NOT NULL;
