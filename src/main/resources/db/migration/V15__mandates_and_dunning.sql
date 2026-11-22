-- Recurring billing mandates and dunning schedules (Algorithms 020 & 021)

CREATE TABLE mandate (
    id                  uuid PRIMARY KEY,
    customer_id         uuid NOT NULL REFERENCES customer(id),
    merchant_id         uuid NOT NULL REFERENCES merchant(id),
    currency            varchar(3) NOT NULL DEFAULT 'GHS',
    max_amount_pesewas  bigint NOT NULL CHECK (max_amount_pesewas > 0),
    frequency           varchar(32) NOT NULL CHECK (frequency IN ('daily', 'weekly', 'monthly', 'ad_hoc')),
    status              varchar(32) NOT NULL CHECK (status IN ('active', 'cancelled', 'suspended', 'exhausted')),
    consent_reference   varchar(128) NOT NULL,
    idempotency_key     varchar(128),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX mandate_customer_idx ON mandate(customer_id, created_at DESC);
CREATE INDEX mandate_merchant_idx ON mandate(merchant_id, created_at DESC);
CREATE UNIQUE INDEX mandate_idempotency_idx ON mandate(customer_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE mandate_charge (
    id                  uuid PRIMARY KEY,
    mandate_id         uuid NOT NULL REFERENCES mandate(id),
    amount_pesewas      bigint NOT NULL CHECK (amount_pesewas > 0),
    idempotency_key     varchar(128) UNIQUE,
    status              varchar(32) NOT NULL CHECK (status IN ('pending', 'succeeded', 'failed', 'retrying')),
    failure_reason      text,
    attempt_count       int NOT NULL DEFAULT 1,
    next_retry_at       timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX mandate_charge_mandate_idx ON mandate_charge(mandate_id, created_at DESC);

CREATE TABLE dunning_schedule (
    id                  uuid PRIMARY KEY,
    charge_id           uuid NOT NULL REFERENCES mandate_charge(id),
    schedule_step       int NOT NULL DEFAULT 1,
    max_steps           int NOT NULL DEFAULT 3,
    backoff_hours       int NOT NULL DEFAULT 24,
    status              varchar(32) NOT NULL CHECK (status IN ('active', 'retried', 'exhausted', 'abandoned')),
    last_attempt_at     timestamptz,
    next_attempt_at     timestamptz
);

CREATE INDEX dunning_schedule_charge_idx ON dunning_schedule(charge_id);
CREATE INDEX dunning_schedule_due_idx ON dunning_schedule(next_attempt_at) WHERE status = 'active';
