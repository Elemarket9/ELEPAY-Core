-- Production correction for historical seed-only records. Applied Flyway migrations
-- V18 and V24 must remain immutable to preserve checksum integrity.
-- Never remove genuine financial transactions or user-created customer data.

-- A simulated adapter must never be an active or fallback production route.
UPDATE rail_route_config
SET secondary_rail = NULL
WHERE secondary_rail = 'sandbox';

UPDATE rail_route_config
SET enabled = false
WHERE primary_rail = 'sandbox';

-- The original card BIN catalog used illustrative, unverified bank/BIN pairings.
-- Remove only the exact unchanged seed rows. Real operator-verified revisions
-- (different version or issuer) are preserved.
DELETE FROM card_bin
WHERE (bin, issuer_bank, version) IN (
    ('411111', 'GCB Bank PLC', '2026.1'),
    ('512345', 'GCB Bank PLC', '2026.1'),
    ('402234', 'Ecobank Ghana PLC', '2026.1'),
    ('539983', 'Ecobank Ghana PLC', '2026.1'),
    ('428421', 'Stanbic Bank Ghana', '2026.1'),
    ('524128', 'Stanbic Bank Ghana', '2026.1'),
    ('456789', 'Absa Bank Ghana PLC', '2026.1'),
    ('541234', 'Absa Bank Ghana PLC', '2026.1'),
    ('489012', 'Zenith Bank Ghana', '2026.1'),
    ('434567', 'Fidelity Bank Ghana', '2026.1'),
    ('476543', 'Standard Chartered Bank Ghana', '2026.1'),
    ('900101', 'GhIPSS Gh-Link', '2026.1'),
    ('400000', 'Chase Bank USA', '2026.1'),
    ('555555', 'Barclays Bank UK', '2026.1')
);

-- The following three student-fee records were illustrative seed data, not
-- evidence of university-issued invoices. Match original balances exactly
-- so a row modified after initial migration is kept for operator review.
DELETE FROM student_fee_record
WHERE (id, institution_code, student_id, paid_fee_pesewas, outstanding_fee_pesewas) IN (
    ('b0000004-0000-0000-0000-000000000001'::uuid, 'UG_LEGON', '10982341', 250000, 250000),
    ('b0000004-0000-0000-0000-000000000002'::uuid, 'KNUST', '20874112', 300000, 300000),
    ('b0000004-0000-0000-0000-000000000003'::uuid, 'PRESEC', 'PR-2026-08', 100000, 100000)
);
