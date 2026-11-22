-- Modular monolith: the wallet module owns wallet_operation and no longer reads
-- the payout table to show status. The payout module publishes status changes
-- (PayoutEvents.StatusChanged) and the wallet keeps its own rows current.
ALTER TABLE wallet_operation DROP CONSTRAINT IF EXISTS wallet_operation_status_check;
ALTER TABLE wallet_operation ADD CONSTRAINT wallet_operation_status_check
    CHECK (status IN ('pending', 'completed', 'failed', 'returned'));

-- One-off backfill of history written before the event existed.
UPDATE wallet_operation o SET kind = 'transfer_out'
FROM payout p
WHERE p.id = o.related_id AND p.owner_type = 'customer' AND p.owner_id = o.customer_id
  AND o.kind = 'withdrawal' AND p.client_reference LIKE 'transfer-%';

UPDATE wallet_operation o SET status = CASE p.status
        WHEN 'succeeded' THEN 'completed'
        WHEN 'failed' THEN 'failed'
        WHEN 'rejected' THEN 'failed'
        WHEN 'returned' THEN 'returned'
        ELSE o.status END,
    updated_at = now()
FROM payout p
WHERE p.id = o.related_id AND p.owner_type = 'customer' AND p.owner_id = o.customer_id
  AND o.kind IN ('withdrawal', 'transfer_out') AND p.status IN ('succeeded', 'failed', 'rejected', 'returned');
