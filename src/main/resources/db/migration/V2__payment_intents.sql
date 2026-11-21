CREATE TABLE payment_intent (
    id uuid PRIMARY KEY,
    checkout jsonb NOT NULL,
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'paid', 'cancelled'))
);

CREATE TABLE payment_outbox (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES payment_intent(id),
    event_type text NOT NULL CHECK (event_type IN ('paid', 'cancelled')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (payment_id, event_type)
);
