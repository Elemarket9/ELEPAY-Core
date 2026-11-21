CREATE TABLE api_credential (
    id          uuid PRIMARY KEY,
    prefix      text        NOT NULL,
    secret_hash text        NOT NULL UNIQUE,
    merchant_id uuid        NOT NULL,
    label       text,
    created_by  text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz
);
CREATE INDEX api_credential_merchant ON api_credential (merchant_id);

-- Transactional outbox. Rows are written in the same transaction as the
-- business change and delivered at least once by the dispatcher.
CREATE TABLE outbox_event (
    id              uuid PRIMARY KEY,
    sequence        bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    aggregate_type  text        NOT NULL,
    aggregate_id    text        NOT NULL,
    event_type      text        NOT NULL,
    payload         jsonb       NOT NULL,
    merchant_ids    uuid[]      NOT NULL DEFAULT '{}',
    created_at      timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text
);
CREATE INDEX outbox_event_pending ON outbox_event (next_attempt_at, sequence) WHERE published_at IS NULL;

-- Consumers record processed event ids so duplicate deliveries are no-ops.
CREATE TABLE outbox_consumer_inbox (
    consumer     text        NOT NULL,
    event_id     uuid        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

CREATE TABLE webhook_endpoint (
    id          uuid PRIMARY KEY,
    merchant_id uuid        NOT NULL,
    url         text        NOT NULL CHECK (url ~ '^https?://'),
    secret      text        NOT NULL,
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX webhook_endpoint_merchant ON webhook_endpoint (merchant_id) WHERE active;

CREATE TABLE webhook_delivery (
    event_id        uuid        NOT NULL REFERENCES outbox_event(id),
    endpoint_id     uuid        NOT NULL REFERENCES webhook_endpoint(id),
    status          text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'delivered', 'dead')),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_status     integer,
    last_error      text,
    delivered_at    timestamptz,
    PRIMARY KEY (event_id, endpoint_id)
);
CREATE INDEX webhook_delivery_pending ON webhook_delivery (next_attempt_at) WHERE status = 'pending';

CREATE TABLE notification (
    id          uuid PRIMARY KEY,
    event_id    uuid        NOT NULL,
    channel     text        NOT NULL,
    recipient   text        NOT NULL,
    body        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (event_id, channel, recipient)
);

CREATE TABLE audit_log (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor      text        NOT NULL,
    action     text        NOT NULL,
    subject    text        NOT NULL,
    details    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_subject ON audit_log (subject, created_at);
CREATE TRIGGER audit_log_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
