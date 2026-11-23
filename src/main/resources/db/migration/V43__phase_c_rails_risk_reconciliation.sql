-- V43: Phase C — Direct Domestic Rails, Fraud Intelligence & 10-Way Daily Clearing

-- 1. Direct Carrier Session Log (MTN MoMo, Telecel Cash, AT Money)
CREATE TABLE IF NOT EXISTS direct_carrier_session (
    id UUID PRIMARY KEY,
    carrier VARCHAR(32) NOT NULL, -- 'MTN', 'TELECEL', 'AIRTELTIGO', 'GHIPSS'
    session_type VARCHAR(32) NOT NULL, -- 'COLLECTION', 'DISBURSEMENT', 'NAME_INQUIRY', 'REVERSAL'
    reference_id VARCHAR(128) NOT NULL,
    external_transaction_id VARCHAR(128),
    channel VARCHAR(32) NOT NULL,
    customer_phone VARCHAR(32),
    amount_pesewas BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'GHS',
    status VARCHAR(32) NOT NULL, -- 'INITIATED', 'SUBMITTED', 'PENDING_USER_APPROVAL', 'SUCCESS', 'FAILED', 'REVERSED'
    status_code VARCHAR(32),
    error_message TEXT,
    request_payload JSONB,
    response_payload JSONB,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_carrier_sess_ref ON direct_carrier_session (carrier, reference_id);
CREATE INDEX IF NOT EXISTS idx_carrier_sess_ext ON direct_carrier_session (carrier, external_transaction_id);

-- 2. GhIPSS Instant Pay (GIP) Interbank Clearing Session
CREATE TABLE IF NOT EXISTS ghipss_clearing_record (
    id UUID PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL UNIQUE,
    clearing_cycle VARCHAR(32) NOT NULL,
    originating_institution_code VARCHAR(16) NOT NULL,
    destination_institution_code VARCHAR(16) NOT NULL,
    destination_account_number VARCHAR(64) NOT NULL,
    destination_account_name VARCHAR(128),
    beneficiary_proxy_type VARCHAR(32), -- 'PHONE', 'EMAIL', 'GHANA_CARD'
    beneficiary_proxy_value VARCHAR(128),
    amount_pesewas BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'GHS',
    iso_message_type VARCHAR(16) NOT NULL, -- '0200', '0210', 'pacs.008', 'pacs.002'
    stan VARCHAR(12) NOT NULL, -- System Trace Audit Number
    rrn VARCHAR(12) NOT NULL, -- Retrieval Reference Number
    status VARCHAR(32) NOT NULL, -- 'PENDING', 'MATCHED', 'SETTLED', 'DISPUTED', 'RETURNED'
    ledger_tx_id UUID REFERENCES journal_transaction(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ghipss_rrn_stan ON ghipss_clearing_record (rrn, stan);

-- 3. Dynamic GhQR Payload Registry (EMVCo compliant)
CREATE TABLE IF NOT EXISTS ghqr_dynamic_intent (
    id UUID PRIMARY KEY,
    qr_reference VARCHAR(64) NOT NULL UNIQUE,
    merchant_id UUID NOT NULL,
    terminal_id VARCHAR(32),
    amount_pesewas BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'GHS',
    point_of_initiation_method VARCHAR(8) NOT NULL DEFAULT '12', -- '12' = Dynamic
    crc_checksum VARCHAR(4) NOT NULL,
    payload_text TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- 'ACTIVE', 'PAID', 'EXPIRED', 'CANCELLED'
    payment_reference VARCHAR(128),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ghqr_merchant ON ghqr_dynamic_intent (merchant_id);

-- 4. Fraud Real-Time Velocity Windows
CREATE TABLE IF NOT EXISTS fraud_velocity_window (
    id UUID PRIMARY KEY,
    dimension_type VARCHAR(32) NOT NULL, -- 'CUSTOMER', 'PHONE', 'CARD_FINGERPRINT', 'DEVICE', 'IP_SUBNET'
    dimension_key VARCHAR(128) NOT NULL,
    window_interval VARCHAR(16) NOT NULL, -- '1M', '5M', '1H', '24H', '7D'
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    transaction_count INT NOT NULL DEFAULT 1,
    accumulated_amount_pesewas BIGINT NOT NULL,
    velocity_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fraud_velocity_window UNIQUE (dimension_type, dimension_key, window_interval, window_start)
);

CREATE INDEX IF NOT EXISTS idx_fraud_velo_key ON fraud_velocity_window (dimension_type, dimension_key, window_end);

-- 5. Geolocation Checkpoints for Impossible Travel (Haversine Speed)
CREATE TABLE IF NOT EXISTS geo_velocity_checkpoint (
    id UUID PRIMARY KEY,
    entity_id VARCHAR(128) NOT NULL, -- customerId or deviceId
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    ip_address VARCHAR(64),
    city VARCHAR(64),
    country_code VARCHAR(4),
    recorded_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_geo_velo_entity ON geo_velocity_checkpoint (entity_id, recorded_at DESC);

-- 6. Pass-Through Mule Account Graph Edges
CREATE TABLE IF NOT EXISTS mule_account_graph_edge (
    id UUID PRIMARY KEY,
    source_account_id UUID NOT NULL,
    destination_account_id UUID NOT NULL,
    shared_device_id VARCHAR(128),
    shared_ip VARCHAR(64),
    flow_volume_pesewas BIGINT NOT NULL DEFAULT 0,
    transfer_count INT NOT NULL DEFAULT 1,
    turnaround_seconds INT NOT NULL, -- Duration between inbound credit and outbound debit
    is_rapid_pass_through BOOLEAN NOT NULL DEFAULT false,
    risk_weight DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_mule_edge UNIQUE (source_account_id, destination_account_id)
);

CREATE INDEX IF NOT EXISTS idx_mule_rapid ON mule_account_graph_edge (is_rapid_pass_through);

-- 7. 10-Way Bank Statement Ingestion Batch (SWIFT MT940 / MT942)
CREATE TABLE IF NOT EXISTS recon_bank_statement_batch (
    id UUID PRIMARY KEY,
    batch_reference VARCHAR(64) NOT NULL UNIQUE,
    bank_code VARCHAR(32) NOT NULL,
    account_number VARCHAR(64) NOT NULL,
    statement_number VARCHAR(32) NOT NULL,
    sequence_number VARCHAR(32),
    currency VARCHAR(3) NOT NULL DEFAULT 'GHS',
    opening_balance_pesewas BIGINT NOT NULL,
    closing_balance_pesewas BIGINT NOT NULL,
    statement_date DATE NOT NULL,
    total_lines INT NOT NULL DEFAULT 0,
    matched_lines INT NOT NULL DEFAULT 0,
    discrepancy_lines INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'INGESTED', -- 'INGESTED', 'MATCHING', 'RECONCILED', 'VARIANCE_SUSPENSE'
    raw_content_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reconciled_at TIMESTAMPTZ
);

-- 8. Bank Statement Individual Lines (Field 61 / Field 86)
CREATE TABLE IF NOT EXISTS recon_statement_line (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES recon_bank_statement_batch(id) ON DELETE CASCADE,
    line_number INT NOT NULL,
    value_date DATE NOT NULL,
    entry_date DATE NOT NULL,
    debit_credit_mark VARCHAR(2) NOT NULL, -- 'D', 'C', 'RC', 'RD'
    amount_pesewas BIGINT NOT NULL,
    transaction_type_code VARCHAR(8) NOT NULL,
    bank_reference VARCHAR(128) NOT NULL,
    customer_reference VARCHAR(128),
    supplementary_details TEXT,
    match_status VARCHAR(32) NOT NULL DEFAULT 'UNMATCHED', -- 'UNMATCHED', 'EXACT_MATCH', 'FUZZY_MATCH', 'MANUAL_VARIANCE'
    matched_ledger_tx_id UUID REFERENCES journal_transaction(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_recon_line_ref ON recon_statement_line (bank_reference, customer_reference);

-- 9. 3-Way Match Outcome and Discrepancy Tracking
CREATE TABLE IF NOT EXISTS recon_match_record (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES recon_bank_statement_batch(id),
    statement_line_id UUID REFERENCES recon_statement_line(id),
    ledger_tx_id UUID REFERENCES journal_transaction(id),
    rail_session_id UUID REFERENCES direct_carrier_session(id),
    match_type VARCHAR(32) NOT NULL, -- 'THREE_WAY_EXACT', 'TWO_WAY_INTERNAL', 'AMOUNT_MISMATCH', 'MISSING_IN_LEDGER', 'MISSING_IN_BANK'
    ledger_amount_pesewas BIGINT,
    bank_amount_pesewas BIGINT,
    rail_amount_pesewas BIGINT,
    variance_pesewas BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL, -- 'RECONCILED', 'SUSPENSE_POSTED', 'DISPUTE_FILED', 'MANUALLY_CLEARED'
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 10. Suspense Account Ledger & Aging Records
CREATE TABLE IF NOT EXISTS recon_suspense_ledger (
    id UUID PRIMARY KEY,
    match_record_id UUID NOT NULL REFERENCES recon_match_record(id),
    suspense_account_code VARCHAR(128) NOT NULL,
    variance_pesewas BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'GHS',
    aging_bucket VARCHAR(16) NOT NULL, -- '0_24H', '24_72H', 'OVER_72H'
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN', -- 'OPEN', 'UNDER_INVESTIGATION', 'WRITTEN_OFF', 'RESOLVED'
    resolution_notes TEXT,
    resolved_by VARCHAR(64),
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_recon_suspense_aging ON recon_suspense_ledger (aging_bucket, status);
