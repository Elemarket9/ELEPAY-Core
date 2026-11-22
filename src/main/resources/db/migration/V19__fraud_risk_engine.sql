-- ============================================================================
-- V19: Fraud and Risk Engine (Algorithms #023 - #036)
-- ============================================================================

-- 023: Velocity rules (sliding window transaction counters across 1h, 24h, 7d)
CREATE TABLE risk_velocity_events (
    id UUID PRIMARY KEY,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(128) NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_velocity_lookup ON risk_velocity_events(entity_type, entity_id, created_at);

-- 024: Rules engine and risk score (versioned declarative rule evaluation)
CREATE TABLE risk_evaluations (
    id UUID PRIMARY KEY,
    actor_id VARCHAR(128) NOT NULL,
    reference_id VARCHAR(128),
    action_type VARCHAR(64) NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    risk_score INT NOT NULL,
    decision VARCHAR(32) NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    triggered_rules_json TEXT NOT NULL,
    explanation_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_eval_actor ON risk_evaluations(actor_id, created_at);

-- 025: Device risk signals (privacy-conscious client fingerprinting and risk scoring)
CREATE TABLE risk_device_fingerprints (
    id UUID PRIMARY KEY,
    device_id_hash VARCHAR(128) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    ip_address VARCHAR(64),
    user_agent TEXT,
    platform VARCHAR(64),
    os VARCHAR(64),
    is_emulator BOOLEAN DEFAULT false,
    is_rooted BOOLEAN DEFAULT false,
    is_vpn_proxy BOOLEAN DEFAULT false,
    confidence_score INT NOT NULL,
    risk_score INT NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    seen_count INT NOT NULL DEFAULT 1,
    CONSTRAINT uq_device_actor UNIQUE(device_id_hash, actor_id)
);
CREATE INDEX idx_device_hash ON risk_device_fingerprints(device_id_hash);

-- 026: SIM-swap checks (IMSI swap age detection triggering holds/step-up)
CREATE TABLE risk_sim_swap_checks (
    id UUID PRIMARY KEY,
    phone_number VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128),
    telco_provider VARCHAR(32) NOT NULL,
    sim_swap_detected BOOLEAN NOT NULL,
    imsi_last_swapped_at TIMESTAMPTZ,
    swap_age_hours INT,
    signal_status VARCHAR(32) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sim_swap_phone ON risk_sim_swap_checks(phone_number, created_at);

-- 027: Geo-velocity checks (great-circle distance / impossible travel detection)
CREATE TABLE risk_geo_locations (
    id UUID PRIMARY KEY,
    actor_id VARCHAR(128) NOT NULL,
    reference_id VARCHAR(128),
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    accuracy_radius_meters DOUBLE PRECISION,
    city VARCHAR(128),
    country VARCHAR(3),
    ip_address VARCHAR(64),
    is_vpn BOOLEAN DEFAULT false,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_geo_actor ON risk_geo_locations(actor_id, recorded_at);

-- 028: Account takeover detection (session/IP/withdrawal anomaly correlation)
CREATE TABLE risk_ato_assessments (
    id UUID PRIMARY KEY,
    actor_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128),
    device_id_hash VARCHAR(128),
    ip_address VARCHAR(64),
    recent_credential_change BOOLEAN DEFAULT false,
    dormancy_broken BOOLEAN DEFAULT false,
    withdrawal_anomaly BOOLEAN DEFAULT false,
    sim_swap_flag BOOLEAN DEFAULT false,
    ato_score INT NOT NULL,
    decision VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_ato_actor ON risk_ato_assessments(actor_id, created_at);

-- 029: Adaptive step-up authentication (dynamic action-bound challenge issuance)
CREATE TABLE risk_step_up_challenges (
    id UUID PRIMARY KEY,
    challenge_token VARCHAR(256) NOT NULL UNIQUE,
    actor_id VARCHAR(128) NOT NULL,
    bound_action VARCHAR(64) NOT NULL,
    bound_payload_hash VARCHAR(128) NOT NULL,
    challenge_type VARCHAR(32) NOT NULL,
    secret_code_hash VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    expires_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_stepup_token ON risk_step_up_challenges(challenge_token);

-- 030: Merchant risk scoring (continuous monitoring of dispute and refund velocity)
CREATE TABLE risk_merchant_scores (
    id UUID PRIMARY KEY,
    merchant_id VARCHAR(128) NOT NULL,
    rolling_window_days INT NOT NULL DEFAULT 30,
    sales_volume_minor_units BIGINT NOT NULL,
    refund_volume_minor_units BIGINT NOT NULL,
    refund_ratio DOUBLE PRECISION NOT NULL,
    dispute_count INT NOT NULL,
    dispute_ratio DOUBLE PRECISION NOT NULL,
    volume_surge_factor DOUBLE PRECISION NOT NULL,
    merchant_risk_score INT NOT NULL,
    risk_tier VARCHAR(32) NOT NULL,
    review_status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    assessed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_merchant_score ON risk_merchant_scores(merchant_id, assessed_at);

-- 031: Promotion and referral abuse detection (sybil and circular referral graph detection)
CREATE TABLE risk_referral_graph (
    id UUID PRIMARY KEY,
    referrer_id VARCHAR(128) NOT NULL,
    referee_id VARCHAR(128) NOT NULL,
    campaign_id VARCHAR(64) NOT NULL,
    device_id_hash VARCHAR(128),
    ip_address VARCHAR(64),
    reward_amount_minor_units BIGINT NOT NULL,
    is_circular BOOLEAN NOT NULL DEFAULT false,
    is_sybil_cluster BOOLEAN NOT NULL DEFAULT false,
    abuse_score INT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ALLOWED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_ref_referrer ON risk_referral_graph(referrer_id);
CREATE INDEX idx_risk_ref_referee ON risk_referral_graph(referee_id);

-- 032: Mule-account graph detection (transaction flow graph identifying pass-through mules)
CREATE TABLE risk_mule_nodes (
    id UUID PRIMARY KEY,
    account_id VARCHAR(128) NOT NULL UNIQUE,
    in_degree INT NOT NULL DEFAULT 0,
    out_degree INT NOT NULL DEFAULT 0,
    total_inflow_minor_units BIGINT NOT NULL DEFAULT 0,
    total_outflow_minor_units BIGINT NOT NULL DEFAULT 0,
    drain_ratio DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    rapid_passthrough_count INT NOT NULL DEFAULT 0,
    mule_score INT NOT NULL DEFAULT 0,
    is_mule_suspect BOOLEAN NOT NULL DEFAULT false,
    last_assessed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE risk_mule_edges (
    id UUID PRIMARY KEY,
    source_account_id VARCHAR(128) NOT NULL,
    destination_account_id VARCHAR(128) NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    transferred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_mule_src ON risk_mule_edges(source_account_id);
CREATE INDEX idx_risk_mule_dst ON risk_mule_edges(destination_account_id);

-- 033: Synthetic identity detection (cross-referencing inconsistencies in ID/phone data)
CREATE TABLE risk_synthetic_identities (
    id UUID PRIMARY KEY,
    actor_id VARCHAR(128) NOT NULL,
    national_id_hash VARCHAR(128) NOT NULL,
    tin_hash VARCHAR(128),
    phone_number VARCHAR(32) NOT NULL,
    full_name VARCHAR(256) NOT NULL,
    date_of_birth DATE NOT NULL,
    address_hash VARCHAR(128),
    inconsistencies_json TEXT NOT NULL,
    synthetic_risk_score INT NOT NULL,
    risk_verdict VARCHAR(32) NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_synth_actor ON risk_synthetic_identities(actor_id);
CREATE INDEX idx_risk_synth_nid ON risk_synthetic_identities(national_id_hash);
CREATE INDEX idx_risk_synth_phone ON risk_synthetic_identities(phone_number);

-- 034: Behavioral biometrics (keystroke dynamics and pointer cadence telemetry)
CREATE TABLE risk_behavioral_profiles (
    id UUID PRIMARY KEY,
    actor_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    mean_dwell_time_ms DOUBLE PRECISION NOT NULL,
    mean_flight_time_ms DOUBLE PRECISION NOT NULL,
    dwell_time_variance DOUBLE PRECISION NOT NULL,
    pointer_velocity_avg DOUBLE PRECISION NOT NULL,
    pointer_acceleration_avg DOUBLE PRECISION NOT NULL,
    pointer_curvature_score DOUBLE PRECISION NOT NULL,
    is_bot_cadence BOOLEAN NOT NULL DEFAULT false,
    biometric_anomaly_score INT NOT NULL,
    accessibility_flag VARCHAR(64),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_biometric_actor ON risk_behavioral_profiles(actor_id, recorded_at);

-- 035: Gradient-boosted fraud model (LightGBM/XGBoost style feature vector scoring)
CREATE TABLE risk_model_predictions (
    id UUID PRIMARY KEY,
    transaction_id VARCHAR(128) NOT NULL,
    model_name VARCHAR(64) NOT NULL,
    model_version VARCHAR(32) NOT NULL,
    raw_score DOUBLE PRECISION NOT NULL,
    fraud_probability DOUBLE PRECISION NOT NULL,
    feature_vector_json TEXT NOT NULL,
    top_features_json TEXT NOT NULL,
    is_experimental BOOLEAN NOT NULL DEFAULT true,
    predicted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_model_tx ON risk_model_predictions(transaction_id);

-- 036: Chargeback prediction (calibrated dispute probability scoring)
CREATE TABLE risk_chargeback_predictions (
    id UUID PRIMARY KEY,
    payment_id VARCHAR(128) NOT NULL,
    merchant_id VARCHAR(128) NOT NULL,
    mcc VARCHAR(8) NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    calibrated_probability DOUBLE PRECISION NOT NULL,
    expected_loss_minor_units BIGINT NOT NULL,
    recommended_action VARCHAR(32) NOT NULL,
    decision_explanation TEXT NOT NULL,
    predicted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_chargeback_pmt ON risk_chargeback_predictions(payment_id);
CREATE INDEX idx_risk_chargeback_merch ON risk_chargeback_predictions(merchant_id);
