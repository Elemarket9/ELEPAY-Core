-- Recipient name confirmation for send-by-phone and external transfers.
--
-- A confirmation is a short-lived, single-use authorization bound to the
-- sender, the normalized destination, the verified recipient account and the
-- selected rail. Execution re-validates it on the server.

CREATE TABLE recipient_confirmation (
    id                    uuid PRIMARY KEY,
    sender_customer_id    uuid        NOT NULL REFERENCES customer(id),
    rail                  text        NOT NULL CHECK (rail IN ('wallet', 'momo', 'bank')),
    destination           text        NOT NULL,
    network               text,
    recipient_customer_id uuid REFERENCES customer(id),
    recipient_account     text,
    verified_name         text        NOT NULL,
    verification_source   text        NOT NULL,
    status                text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'used', 'revoked')),
    created_at            timestamptz NOT NULL DEFAULT now(),
    expires_at            timestamptz NOT NULL,
    used_at               timestamptz,
    used_by_tx            uuid REFERENCES journal_transaction(id),
    CHECK (rail <> 'wallet' OR recipient_customer_id IS NOT NULL)
);
CREATE INDEX recipient_confirmation_sender ON recipient_confirmation (sender_customer_id, created_at);

-- Every lookup is recorded (destination hashed) for rate limiting and abuse review.
CREATE TABLE recipient_lookup (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sender_customer_id uuid        NOT NULL REFERENCES customer(id),
    rail               text        NOT NULL,
    destination_hash   text        NOT NULL,
    outcome            text        NOT NULL CHECK (outcome IN (
                           'verified', 'not_found', 'unavailable', 'disabled', 'self', 'rate_limited', 'failed')),
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX recipient_lookup_sender_time ON recipient_lookup (sender_customer_id, created_at);

-- Phone numbers can be reassigned by operations; keep history for investigations.
CREATE TABLE customer_phone_history (
    customer_id uuid        NOT NULL REFERENCES customer(id),
    phone       text        NOT NULL,
    changed_by  text        NOT NULL,
    changed_at  timestamptz NOT NULL DEFAULT now()
);
