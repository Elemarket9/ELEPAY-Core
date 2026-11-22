-- POS tracks only payment evidence owned by the checkout module. No duplicate journal posting.
CREATE TABLE pos_checkout_link (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES pos_order(id),
    checkout_ref varchar(128) NOT NULL UNIQUE,
    amount bigint NOT NULL CHECK (amount > 0),
    status varchar(16) NOT NULL CHECK (status IN ('open','applied','cancelled')),
    journal_tx uuid UNIQUE REFERENCES journal_transaction(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'applied') = (journal_tx IS NOT NULL))
);
CREATE UNIQUE INDEX pos_checkout_one_active ON pos_checkout_link(order_id) WHERE status IN ('open','applied');
