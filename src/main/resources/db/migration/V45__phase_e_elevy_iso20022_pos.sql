-- ============================================================================
-- ELEPAY Core — Flyway Migration V44: Phase E Financial Infrastructure
-- 1. Ghana Revenue Authority (GRA) Electronic Transfer Levy (e-Levy) Compliance
-- 2. ISO 20022 Financial Messaging Engine (pain.001, pacs.008, camt.053)
-- 3. POS Terminal Fleet Infrastructure & ANSI X9.24 DUKPT Key Management
-- ============================================================================

-- 1. GRA e-Levy Exemption & Identity Mapping
CREATE TABLE elevy_exemption_profile (
    id                      uuid PRIMARY KEY,
    customer_id             uuid NOT NULL UNIQUE,
    tin                     varchar(32),
    ghana_card_number       varchar(32),
    primary_phone           varchar(32) NOT NULL,
    is_tax_exempt           boolean NOT NULL DEFAULT false,
    exemption_category      varchar(64),
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_elevy_profile_cust ON elevy_exemption_profile(customer_id);
CREATE INDEX idx_elevy_profile_tin ON elevy_exemption_profile(tin);
CREATE INDEX idx_elevy_profile_ghcard ON elevy_exemption_profile(ghana_card_number);

-- 2. GRA e-Levy Daily Exemption Accumulator (Pessimistic Row Locking Target)
CREATE TABLE elevy_daily_accumulator (
    id                      uuid PRIMARY KEY,
    customer_id             uuid NOT NULL,
    tax_date                date NOT NULL,
    cumulative_amount_pesewas bigint NOT NULL DEFAULT 0,
    exempt_amount_pesewas   bigint NOT NULL DEFAULT 0,
    taxable_amount_pesewas  bigint NOT NULL DEFAULT 0,
    total_levy_pesewas      bigint NOT NULL DEFAULT 0,
    transaction_count       integer NOT NULL DEFAULT 0,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_elevy_customer_date UNIQUE (customer_id, tax_date)
);
CREATE INDEX idx_elevy_accum_lookup ON elevy_daily_accumulator(customer_id, tax_date);

-- 3. GRA e-Levy Transaction Audit Log
CREATE TABLE elevy_transaction_log (
    id                      uuid PRIMARY KEY,
    transaction_reference   varchar(64) NOT NULL UNIQUE,
    customer_id             uuid NOT NULL,
    recipient_identifier    varchar(64) NOT NULL,
    transfer_amount_pesewas bigint NOT NULL,
    exempt_portion_pesewas  bigint NOT NULL,
    taxable_portion_pesewas bigint NOT NULL,
    levy_rate_bps           integer NOT NULL DEFAULT 100,
    levy_amount_pesewas     bigint NOT NULL,
    is_exempt               boolean NOT NULL DEFAULT false,
    exemption_reason        varchar(64),
    status                  varchar(32) NOT NULL DEFAULT 'CHARGED',
    gra_clearance_code      varchar(64),
    ledger_tx_id            uuid,
    created_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_elevy_tx_cust ON elevy_transaction_log(customer_id, created_at DESC);
CREATE INDEX idx_elevy_tx_ref ON elevy_transaction_log(transaction_reference);

-- 4. GRA e-Levy Statutory Batch Submission Log
CREATE TABLE elevy_statutory_batch (
    id                      uuid PRIMARY KEY,
    batch_reference         varchar(64) NOT NULL UNIQUE,
    reporting_date          date NOT NULL,
    total_transactions      integer NOT NULL,
    total_volume_pesewas    bigint NOT NULL,
    total_levy_pesewas      bigint NOT NULL,
    batch_sha256            varchar(64) NOT NULL,
    status                  varchar(32) NOT NULL DEFAULT 'PENDING_SUBMISSION',
    gra_ack_code            varchar(64),
    submitted_at            timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now()
);

-- 5. ISO 20022 Financial Messaging Engine Audit Log
CREATE TABLE iso20022_message_log (
    id                      uuid PRIMARY KEY,
    message_id              varchar(64) NOT NULL UNIQUE,
    message_type            varchar(32) NOT NULL,
    direction               varchar(16) NOT NULL,
    sender_bic              varchar(32),
    receiver_bic            varchar(32),
    transaction_count       integer NOT NULL DEFAULT 1,
    control_sum_pesewas     bigint NOT NULL DEFAULT 0,
    payload_xml             text NOT NULL,
    payload_sha256          varchar(64) NOT NULL,
    status                  varchar(32) NOT NULL DEFAULT 'PROCESSED',
    created_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_iso20022_msg_id ON iso20022_message_log(message_id);
CREATE INDEX idx_iso20022_type ON iso20022_message_log(message_type, created_at DESC);

-- 6. POS Terminal ANSI X9.24 DUKPT Key State
CREATE TABLE pos_terminal_dukpt_key (
    id                      uuid PRIMARY KEY,
    terminal_id             varchar(32) NOT NULL UNIQUE,
    merchant_id             uuid NOT NULL,
    bdk_id                  varchar(32) NOT NULL DEFAULT 'BDK-PRIMARY-01',
    initial_ksn             varchar(32) NOT NULL,
    current_ksn             varchar(32) NOT NULL,
    transaction_counter     bigint NOT NULL DEFAULT 0,
    encrypted_ipek          text NOT NULL,
    status                  varchar(32) NOT NULL DEFAULT 'ACTIVE',
    last_derived_at         timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_dukpt_terminal ON pos_terminal_dukpt_key(terminal_id);

-- 7. POS Offline Transaction Store-and-Forward Queue
CREATE TABLE pos_offline_transaction_queue (
    id                      uuid PRIMARY KEY,
    terminal_id             varchar(32) NOT NULL,
    merchant_id             uuid NOT NULL,
    offline_stan            varchar(12) NOT NULL,
    auth_code               varchar(32) NOT NULL,
    amount_pesewas          bigint NOT NULL,
    currency                varchar(3) NOT NULL DEFAULT 'GHS',
    mcc                     varchar(8) NOT NULL,
    masked_pan              varchar(24) NOT NULL,
    entry_mode              varchar(8) NOT NULL DEFAULT '051',
    stored_at               timestamptz NOT NULL,
    sync_status             varchar(32) NOT NULL DEFAULT 'PENDING_SYNC',
    ledger_tx_id            uuid,
    decline_reason          text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_pos_offline_stan UNIQUE (terminal_id, offline_stan)
);
CREATE INDEX idx_pos_offline_sync ON pos_offline_transaction_queue(terminal_id, sync_status);
