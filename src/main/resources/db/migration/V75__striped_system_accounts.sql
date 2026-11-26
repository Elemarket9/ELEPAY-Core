-- Striped system ledger accounts to eliminate global hot-row lock serialization.
-- Pre-seeds 64 stripes (:s00..:s63) for hot GHS clearing, deferred_revenue, and revenue
-- system accounts, and adds a text_pattern_ops index for fast prefix aggregation via starts_with().

CREATE INDEX IF NOT EXISTS idx_ledger_account_code_pattern
    ON ledger_account (code text_pattern_ops);

INSERT INTO ledger_account (code, owner_type, bucket, currency, allow_negative)
SELECT 'system:clearing:' || rail || ':GHS:s' || lpad(idx::text, 2, '0'),
       'system',
       'clearing',
       'GHS',
       true
FROM unnest(ARRAY['sandbox', 'mtn', 'telecel', 'airteltigo', 'ghipss', 'card']) AS rail
CROSS JOIN generate_series(0, 63) AS idx
ON CONFLICT (code) DO NOTHING;

INSERT INTO ledger_account (code, owner_type, bucket, currency, allow_negative)
SELECT 'system:deferred_revenue:GHS:s' || lpad(idx::text, 2, '0'),
       'system',
       'deferred_revenue',
       'GHS',
       true
FROM generate_series(0, 63) AS idx
ON CONFLICT (code) DO NOTHING;

INSERT INTO ledger_account (code, owner_type, bucket, currency, allow_negative)
SELECT 'system:revenue:GHS:s' || lpad(idx::text, 2, '0'),
       'system',
       'revenue',
       'GHS',
       false
FROM generate_series(0, 63) AS idx
ON CONFLICT (code) DO NOTHING;
