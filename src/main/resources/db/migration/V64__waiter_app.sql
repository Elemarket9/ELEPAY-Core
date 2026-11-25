ALTER TABLE merchant_member DROP CONSTRAINT merchant_member_role_check;
ALTER TABLE merchant_member ADD CONSTRAINT merchant_member_role_check
    CHECK (role IN ('owner', 'admin', 'finance', 'cashier', 'waiter'));
ALTER TABLE merchant_member DROP CONSTRAINT merchant_member_check;
ALTER TABLE merchant_member ADD CONSTRAINT merchant_member_branch_role_check
    CHECK ((role IN ('cashier', 'waiter')) OR branch_id IS NULL);
ALTER TABLE restaurant_table ADD COLUMN waiter_branch_id uuid REFERENCES merchant_branch(id);
CREATE TABLE restaurant_waiter_order (
    order_id uuid PRIMARY KEY REFERENCES pos_order(id),
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    table_id uuid NOT NULL REFERENCES restaurant_table(id),
    waiter_subject text NOT NULL,
    ticket_id uuid NOT NULL REFERENCES restaurant_kds_ticket(id),
    bill_requested_at timestamptz
);
CREATE INDEX restaurant_waiter_order_staff_idx ON restaurant_waiter_order(merchant_id, waiter_subject);
