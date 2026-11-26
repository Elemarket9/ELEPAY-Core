-- V67: Enforce database-level uniqueness of payout_batch_reference across payroll runs.
-- Closes the concurrent TOCTOU race where two parallel /payout calls could link the same
-- payout batch reference to two different payroll runs. The unique partial index is placed
-- on payroll_run (not payroll_payslip) because multiple payslips within a single payroll run
-- legitimately share the same payout batch reference.

ALTER TABLE payroll_run
    ADD COLUMN IF NOT EXISTS payout_batch_reference VARCHAR(128);

WITH per_run AS (
    SELECT
        r.id AS run_id,
        r.executed_at,
        r.created_at,
        MIN(p.payout_reference) AS batch_ref
    FROM payroll_run r
    JOIN payroll_payslip p ON p.payroll_run_id = r.id
    WHERE r.payout_batch_reference IS NULL
      AND p.payout_reference IS NOT NULL
      AND btrim(p.payout_reference) <> ''
    GROUP BY r.id, r.executed_at, r.created_at
),
ranked AS (
    SELECT
        run_id,
        batch_ref,
        ROW_NUMBER() OVER (
            PARTITION BY batch_ref
            ORDER BY executed_at NULLS LAST, created_at, run_id
        ) AS rn
    FROM per_run
)
UPDATE payroll_run r
SET payout_batch_reference = ranked.batch_ref
FROM ranked
WHERE r.id = ranked.run_id
  AND ranked.rn = 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_payroll_run_payout_batch_ref
    ON payroll_run (payout_batch_reference)
    WHERE payout_batch_reference IS NOT NULL;
