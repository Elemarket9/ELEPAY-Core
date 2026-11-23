-- Retire only out-of-scope POS retail/catalog fields in a forward-only migration.
-- Preserve POS payments, receipts, payment evidence and all financial journal records.
-- Historic standalone retail/CRM tables remain untouched for archival and audit purposes.
ALTER TABLE pos_order_item DROP COLUMN IF EXISTS variant_id;
ALTER TABLE pos_order_item DROP COLUMN IF EXISTS modifiers_json;
ALTER TABLE pos_order_item DROP COLUMN IF EXISTS seat_number;
ALTER TABLE pos_order_item DROP COLUMN IF EXISTS course;
