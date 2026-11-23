CREATE TABLE IF NOT EXISTS restaurant_kds_recall_event (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    ticket_id uuid NOT NULL REFERENCES restaurant_kds_ticket(id),
    action_id varchar(64) NOT NULL,
    from_status varchar(16) NOT NULL CHECK (from_status IN ('READY','SERVED')),
    to_status varchar(16) NOT NULL CHECK (to_status IN ('PREPARING','READY')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_kds_recall_action UNIQUE (merchant_id,action_id)
);
CREATE INDEX IF NOT EXISTS ix_kds_recall_ticket ON restaurant_kds_recall_event(ticket_id,created_at);
