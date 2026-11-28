-- Preserve BIN lookup metadata and independently maintained issuer records.
ALTER TABLE card_bin ALTER COLUMN issuer_bank DROP NOT NULL;

UPDATE card_bin
SET issuer_bank = NULL, updated_at = now()
WHERE version = '2026.1'
  AND created_at = updated_at
  AND (bin, issuer_bank) IN (
      ('411111', 'GCB Bank PLC'), ('512345', 'GCB Bank PLC'),
      ('402234', 'Ecobank Ghana PLC'), ('539983', 'Ecobank Ghana PLC'),
      ('428421', 'Stanbic Bank Ghana'), ('524128', 'Stanbic Bank Ghana'),
      ('456789', 'Absa Bank Ghana PLC'), ('541234', 'Absa Bank Ghana PLC'),
      ('489012', 'Zenith Bank Ghana'), ('434567', 'Fidelity Bank Ghana'),
      ('476543', 'Standard Chartered Bank Ghana'), ('400000', 'Chase Bank USA'),
      ('555555', 'Barclays Bank UK')
  );
