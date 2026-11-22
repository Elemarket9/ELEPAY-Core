-- Compliance, Sanctions, PEP, AML & Security (Algorithms 040-048, 075, 076, 078, 081-084)

-- 040: Ghana Card matching
CREATE TABLE ghana_card_verification (
    id                  uuid PRIMARY KEY,
    customer_id         uuid NOT NULL,
    id_number           varchar(64) NOT NULL,
    full_name           varchar(255) NOT NULL,
    date_of_birth       date NOT NULL,
    gender              varchar(16),
    status              varchar(32) NOT NULL, -- 'VERIFIED', 'MISMATCHED', 'UNAVAILABLE', 'FAILED'
    confidence_score    numeric(5,2) NOT NULL DEFAULT 100.00,
    simulated           boolean NOT NULL DEFAULT false,
    simulation_provider varchar(64),
    verification_response text,
    verified_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ghana_card_cust_idx ON ghana_card_verification(customer_id);
CREATE INDEX ghana_card_num_idx ON ghana_card_verification(id_number);

-- 041: Face matching and liveness
CREATE TABLE biometric_face_verification (
    id                  uuid PRIMARY KEY,
    customer_id         uuid NOT NULL,
    consent_obtained    boolean NOT NULL DEFAULT true,
    consent_timestamp   timestamptz NOT NULL DEFAULT now(),
    liveness_score      numeric(5,4) NOT NULL,
    match_score         numeric(5,4) NOT NULL,
    is_live             boolean NOT NULL,
    status              varchar(32) NOT NULL, -- 'APPROVED', 'REJECTED', 'MANUAL_REVIEW_REQUIRED'
    id_photo_hash       varchar(128) NOT NULL,
    selfie_hash         varchar(128) NOT NULL,
    reviewer_notes      text,
    reviewed_by         uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    reviewed_at         timestamptz
);

CREATE INDEX biometric_face_cust_idx ON biometric_face_verification(customer_id);
CREATE INDEX biometric_face_status_idx ON biometric_face_verification(status);

-- 042: Fuzzy sanctions screening
CREATE TABLE sanctions_list (
    id                  uuid PRIMARY KEY,
    list_code           varchar(64) NOT NULL, -- 'UN_SANCTIONS', 'OFAC_SDN', 'FIC_GHANA'
    version             varchar(64) NOT NULL,
    entity_name         varchar(255) NOT NULL,
    entity_type         varchar(32) NOT NULL, -- 'INDIVIDUAL', 'ENTITY', 'VESSEL'
    soundex_code        varchar(16) NOT NULL,
    nationality         varchar(64),
    remarks             text,
    active              boolean NOT NULL DEFAULT true,
    published_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX sanctions_list_name_idx ON sanctions_list(entity_name);
CREATE INDEX sanctions_list_soundex_idx ON sanctions_list(soundex_code);

CREATE TABLE sanctions_screening_alert (
    id                  uuid PRIMARY KEY,
    customer_id         uuid NOT NULL,
    query_name          varchar(255) NOT NULL,
    matched_list_id     uuid REFERENCES sanctions_list(id),
    match_type          varchar(32) NOT NULL, -- 'EXACT', 'JARO_WINKLER', 'SOUNDEX'
    similarity_score    numeric(5,4) NOT NULL,
    disposition         varchar(32) NOT NULL DEFAULT 'PENDING_REVIEW', -- 'CONFIRMED_MATCH', 'FALSE_POSITIVE', 'PENDING_REVIEW'
    reviewer_id         uuid,
    disposition_notes   text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    resolved_at         timestamptz
);

CREATE INDEX sanctions_alert_cust_idx ON sanctions_screening_alert(customer_id);
CREATE INDEX sanctions_alert_disp_idx ON sanctions_screening_alert(disposition);

-- 043: PEP screening
CREATE TABLE pep_directory (
    id                  uuid PRIMARY KEY,
    full_name           varchar(255) NOT NULL,
    position_title      varchar(255) NOT NULL,
    jurisdiction        varchar(64) NOT NULL DEFAULT 'GH',
    pep_tier            varchar(16) NOT NULL, -- 'TIER_1', 'TIER_2', 'TIER_3', 'TIER_4'
    soundex_code        varchar(16) NOT NULL,
    active              boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX pep_directory_name_idx ON pep_directory(full_name);
CREATE INDEX pep_directory_soundex_idx ON pep_directory(soundex_code);

CREATE TABLE pep_screening_case (
    id                  uuid PRIMARY KEY,
    customer_id         uuid NOT NULL,
    query_name          varchar(255) NOT NULL,
    matched_pep_id      uuid REFERENCES pep_directory(id),
    match_confidence    numeric(5,4) NOT NULL,
    status              varchar(32) NOT NULL DEFAULT 'SENIOR_REVIEW_REQUIRED', -- 'SENIOR_REVIEW_REQUIRED', 'APPROVED_BY_SENIOR', 'REJECTED'
    senior_reviewer_id  uuid,
    edd_notes           text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    signed_off_at       timestamptz
);

CREATE INDEX pep_case_cust_idx ON pep_screening_case(customer_id);
CREATE INDEX pep_case_status_idx ON pep_screening_case(status);

-- 044, 045, 046: Structuring, AML Monitoring, Alert Prioritization
CREATE TABLE compliance_case (
    id                      uuid PRIMARY KEY,
    case_number             varchar(64) UNIQUE NOT NULL,
    case_type               varchar(32) NOT NULL, -- 'STRUCTURING', 'AML_MONITORING', 'SANCTIONS', 'PEP'
    customer_id             uuid NOT NULL,
    priority                varchar(16) NOT NULL, -- 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW'
    priority_score          numeric(7,2) NOT NULL DEFAULT 0.00,
    exposure_amount_pesewas bigint NOT NULL DEFAULT 0,
    confidence_score        numeric(5,4) NOT NULL DEFAULT 1.0,
    triggered_rule          varchar(64) NOT NULL,
    status                  varchar(32) NOT NULL DEFAULT 'OPEN', -- 'OPEN', 'INVESTIGATING', 'ESCALATED_STR', 'CLOSED_FALSE_POSITIVE'
    evidence_payload        text,
    assigned_to             uuid,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX comp_case_cust_idx ON compliance_case(customer_id);
CREATE INDEX comp_case_status_pri_idx ON compliance_case(status, priority_score DESC);

-- 047: Regulatory report generation
CREATE TABLE regulatory_report (
    id                  uuid PRIMARY KEY,
    report_reference    varchar(64) UNIQUE NOT NULL,
    report_type         varchar(32) NOT NULL, -- 'BOG_EMI_MONTHLY', 'FIC_CTR', 'FIC_STR'
    reporting_period    varchar(32) NOT NULL,
    status              varchar(32) NOT NULL DEFAULT 'DRAFT', -- 'DRAFT', 'APPROVED_FOR_FILING', 'SUBMITTED'
    prepared_by         uuid NOT NULL,
    approved_by         uuid,
    content_payload     text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    approved_at         timestamptz
);

CREATE INDEX reg_report_type_idx ON regulatory_report(report_type, reporting_period);

-- 048: Retention and redaction
CREATE TABLE data_retention_policy (
    id                      uuid PRIMARY KEY,
    policy_name             varchar(64) UNIQUE NOT NULL,
    record_category         varchar(64) NOT NULL, -- 'TRANSACTION', 'KYC_IDENTIFICATION', 'TAX', 'OPERATIONAL_LOG'
    retention_period_months int NOT NULL,
    legal_basis             text NOT NULL,
    active                  boolean NOT NULL DEFAULT true
);

CREATE TABLE legal_hold (
    id                  uuid PRIMARY KEY,
    customer_id         uuid,
    reference_id        varchar(128),
    reason              text NOT NULL,
    placed_by           uuid NOT NULL,
    active              boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    released_at         timestamptz
);

CREATE INDEX legal_hold_cust_idx ON legal_hold(customer_id, active);

CREATE TABLE redaction_audit (
    id                  uuid PRIMARY KEY,
    target_table        varchar(64) NOT NULL,
    record_id           varchar(128) NOT NULL,
    fields_redacted     varchar(255) NOT NULL,
    reason              text NOT NULL,
    executed_by         uuid NOT NULL,
    executed_at         timestamptz NOT NULL DEFAULT now()
);

-- 075, 076: Security Key Registry & Envelope Encryption
CREATE TABLE security_key_registry (
    id                  uuid PRIMARY KEY,
    key_id              varchar(64) UNIQUE NOT NULL,
    key_version         int NOT NULL,
    algorithm           varchar(32) NOT NULL, -- 'AES-256-GCM', 'RSA-2048'
    status              varchar(32) NOT NULL, -- 'ACTIVE', 'RETIRED', 'REVOKED'
    created_at          timestamptz NOT NULL DEFAULT now(),
    retired_at          timestamptz
);

-- 081: Administrative anomaly detection
CREATE TABLE administrative_audit_event (
    id                  uuid PRIMARY KEY,
    operator_id         uuid NOT NULL,
    operator_role       varchar(64) NOT NULL,
    action              varchar(128) NOT NULL,
    resource_type       varchar(64) NOT NULL,
    resource_id         varchar(128),
    ip_address          varchar(64) NOT NULL,
    user_agent          text,
    is_anomalous        boolean NOT NULL DEFAULT false,
    anomaly_reasons     text,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX admin_audit_op_idx ON administrative_audit_event(operator_id, created_at);
CREATE INDEX admin_audit_anom_idx ON administrative_audit_event(is_anomalous);

-- 082: Session and device binding
CREATE TABLE user_session_device (
    id                          uuid PRIMARY KEY,
    session_id                  varchar(128) UNIQUE NOT NULL,
    user_id                     uuid NOT NULL,
    refresh_token_family        uuid NOT NULL,
    current_refresh_token_hash  varchar(128) NOT NULL,
    device_id                   varchar(128) NOT NULL,
    device_fingerprint          varchar(255) NOT NULL,
    is_revoked                  boolean NOT NULL DEFAULT false,
    revoked_reason              text,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    expires_at                  timestamptz NOT NULL
);

CREATE INDEX session_device_fam_idx ON user_session_device(refresh_token_family);
CREATE INDEX session_device_user_idx ON user_session_device(user_id);

-- 083: Mobile certificate pinning
CREATE TABLE mobile_certificate_pin (
    id                  uuid PRIMARY KEY,
    hostname            varchar(255) NOT NULL,
    spki_sha256_pin     varchar(128) NOT NULL,
    pin_type            varchar(32) NOT NULL, -- 'PRIMARY', 'BACKUP'
    expires_at          timestamptz NOT NULL,
    active              boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX cert_pin_host_idx ON mobile_certificate_pin(hostname, active);
