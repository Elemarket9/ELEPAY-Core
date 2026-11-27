-- FCM is a non-authoritative notification channel. No payment or order state is changed by delivery.
CREATE TABLE push_subscription (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchant(id) ON DELETE CASCADE,
    subject text NOT NULL,
    app text NOT NULL CHECK (app IN ('WAITER','POS','KDS','MERCHANT')),
    installation_id uuid NOT NULL,
    token text NOT NULL UNIQUE CHECK (length(token) BETWEEN 20 AND 4096),
    branch_id uuid REFERENCES merchant_branch(id),
    station_id uuid REFERENCES restaurant_kitchen_station(id) ON DELETE CASCADE,
    kitchen_credential_id uuid REFERENCES api_credential(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    UNIQUE (app, installation_id),
    CHECK ((app = 'KDS') = (kitchen_credential_id IS NOT NULL)),
    CHECK (app = 'KDS' OR station_id IS NULL)
);
CREATE INDEX push_subscription_merchant_app ON push_subscription(merchant_id, app);
CREATE INDEX push_subscription_expiry ON push_subscription(expires_at);

-- Recheck live authorization on both enqueue and dispatch, not just at registration.
CREATE VIEW push_eligible_subscription AS
SELECT s.*
FROM push_subscription s
JOIN merchant m ON m.id = s.merchant_id AND m.status = 'active'
LEFT JOIN merchant_member mm ON mm.merchant_id = s.merchant_id AND mm.subject = s.subject
LEFT JOIN merchant_branch b ON b.id = s.branch_id AND b.merchant_id = s.merchant_id AND b.active
LEFT JOIN api_credential k ON k.id = s.kitchen_credential_id AND k.merchant_id = s.merchant_id
LEFT JOIN restaurant_kitchen_station st ON st.id = s.station_id AND st.merchant_id = s.merchant_id
WHERE s.expires_at > now() AND (s.branch_id IS NULL OR b.id IS NOT NULL) AND (
    (s.app = 'WAITER' AND mm.role = 'waiter' AND mm.branch_id IS NOT NULL
         AND mm.branch_id = s.branch_id)
 OR (s.app = 'POS' AND mm.role IN ('owner','admin','cashier')
         AND mm.branch_id IS NOT DISTINCT FROM s.branch_id)
 OR (s.app = 'MERCHANT' AND mm.role IN ('owner','admin','finance')
         AND mm.branch_id IS NOT DISTINCT FROM s.branch_id)
 OR (s.app = 'KDS' AND k.key_type = 'kitchen' AND k.revoked_at IS NULL
         AND (s.station_id IS NULL OR (st.active AND (st.branch_id IS NULL OR EXISTS (
             SELECT 1 FROM merchant_branch sb WHERE sb.id = st.branch_id AND sb.merchant_id = s.merchant_id AND sb.active))))
         AND EXISTS (
             SELECT 1 FROM restaurant_kitchen_pairing p
             WHERE p.credential_id = k.id AND p.merchant_id = s.merchant_id
               AND p.used_at IS NOT NULL AND (p.station_id IS NULL OR p.station_id = s.station_id)
         ))
);

CREATE TABLE push_delivery (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Immutable registration ID; no FK to avoid locking token rows in order transactions.
    subscription_id uuid NOT NULL,
    event_key text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('KITCHEN_ORDER','ORDER_READY','BILL_REQUESTED','CHECKOUT_PAID')),
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','SENT','DEAD','CANCELLED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '15 minutes'),
    sent_at timestamptz,
    last_error text,
    UNIQUE(subscription_id, event_key)
);
CREATE INDEX push_delivery_pending ON push_delivery(next_attempt_at,id) WHERE state = 'PENDING';
CREATE INDEX push_delivery_expiry ON push_delivery(expires_at);

-- Queue station-specific signals inside the same transaction as kitchen ticket creation.
CREATE FUNCTION elepay_push_kitchen_item() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.item_status = 'NEW' THEN
        INSERT INTO push_delivery(subscription_id,event_key,kind)
        SELECT s.id,'kitchen:' || t.id::text,'KITCHEN_ORDER'
        FROM restaurant_kds_ticket t
        JOIN push_eligible_subscription s ON s.merchant_id = t.merchant_id AND s.app = 'KDS'
        WHERE t.id = NEW.ticket_id
          AND (s.station_id IS NULL OR s.station_id = NEW.station_id)
        ON CONFLICT(subscription_id,event_key) DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER push_kitchen_item_insert AFTER INSERT ON restaurant_kds_item
FOR EACH ROW EXECUTE FUNCTION elepay_push_kitchen_item();

-- A ready signal belongs only to the assigned waiter, including the branch scope.
CREATE FUNCTION elepay_push_waiter_ready(p_ticket uuid) RETURNS void LANGUAGE sql AS $$
    INSERT INTO push_delivery(subscription_id,event_key,kind)
    SELECT s.id,'ready:' || t.id::text,'ORDER_READY'
    FROM restaurant_kds_ticket t
    JOIN restaurant_waiter_order w ON w.order_id = t.order_id AND w.merchant_id = t.merchant_id
    JOIN pos_order o ON o.id = w.order_id AND o.merchant_id = t.merchant_id
    JOIN push_eligible_subscription s ON s.merchant_id = t.merchant_id
        AND s.app = 'WAITER' AND s.subject = w.waiter_subject AND s.branch_id = o.branch_id
    WHERE t.id = p_ticket AND t.status = 'READY'
    ON CONFLICT(subscription_id,event_key) DO NOTHING
$$;
CREATE FUNCTION elepay_push_ticket_ready() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM elepay_push_waiter_ready(NEW.id);
    RETURN NEW;
END $$;
CREATE TRIGGER push_ticket_ready AFTER UPDATE OF status ON restaurant_kds_ticket
FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status AND NEW.status = 'READY')
EXECUTE FUNCTION elepay_push_ticket_ready();

-- Auto-ready tickets can be ready before waiter ownership is inserted.
CREATE FUNCTION elepay_push_waiter_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM elepay_push_waiter_ready(NEW.ticket_id);
    RETURN NEW;
END $$;
CREATE TRIGGER push_waiter_insert AFTER INSERT OR UPDATE OF ticket_id ON restaurant_waiter_order
FOR EACH ROW EXECUTE FUNCTION elepay_push_waiter_insert();

CREATE FUNCTION elepay_push_bill_requested() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO push_delivery(subscription_id,event_key,kind)
    SELECT s.id,'bill:' || NEW.order_id::text,'BILL_REQUESTED'
    FROM pos_order o
    JOIN push_eligible_subscription s ON s.merchant_id = o.merchant_id AND s.app = 'POS'
    WHERE o.id = NEW.order_id AND o.merchant_id = NEW.merchant_id
      AND (s.branch_id IS NULL OR s.branch_id = o.branch_id)
    ON CONFLICT(subscription_id,event_key) DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER push_bill_requested AFTER UPDATE OF bill_requested_at ON restaurant_waiter_order
FOR EACH ROW WHEN (OLD.bill_requested_at IS NULL AND NEW.bill_requested_at IS NOT NULL)
EXECUTE FUNCTION elepay_push_bill_requested();
