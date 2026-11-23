-- A change in POS payment state must refresh its kitchen ticket for KDS clients.
-- Payment is still verified by the original POS checkout and ledger; this is display-only.
CREATE OR REPLACE FUNCTION restaurant_kds_payment_status_touch() RETURNS trigger AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        UPDATE restaurant_kds_ticket SET updated_at = now() WHERE order_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_restaurant_kds_payment_status ON pos_order;
CREATE TRIGGER trg_restaurant_kds_payment_status
AFTER UPDATE OF status ON pos_order
FOR EACH ROW EXECUTE FUNCTION restaurant_kds_payment_status_touch();
