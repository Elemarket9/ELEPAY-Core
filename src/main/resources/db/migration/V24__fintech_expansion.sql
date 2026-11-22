-- V24: Stream 3 Fintech Expansion
-- Domains:
--   Domain 8: Complete Lending Lifecycle
--   Domain 9: Everyday Financial Services & Bill Payments
--   Domain 10: Complete Card Infrastructure
--   Domain 11: Authorized Agent Banking Platform

-- ============================================================================
-- 1. DOMAIN 8: LENDING LIFECYCLE
-- ============================================================================

CREATE TABLE lending_config (
    id                      uuid PRIMARY KEY,
    live_lending_enabled    boolean NOT NULL DEFAULT false,
    updated_at              timestamptz NOT NULL DEFAULT now()
);

INSERT INTO lending_config (id, live_lending_enabled)
VALUES ('00000000-0000-0000-0000-000000000008', false)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE loan_product (
    id                      uuid PRIMARY KEY,
    code                    varchar(32) NOT NULL UNIQUE,
    name                    varchar(128) NOT NULL,
    product_type            varchar(32) NOT NULL,
    min_amount_pesewas      bigint NOT NULL,
    max_amount_pesewas      bigint NOT NULL,
    min_tenor_days          integer NOT NULL,
    max_tenor_days          integer NOT NULL,
    interest_rate_bps       integer NOT NULL,
    origination_fee_bps     integer NOT NULL DEFAULT 0,
    penalty_rate_bps        integer NOT NULL DEFAULT 0,
    daily_sweep_bps         integer NOT NULL DEFAULT 0,
    status                  varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at              timestamptz NOT NULL DEFAULT now()
);

INSERT INTO loan_product (id, code, name, product_type, min_amount_pesewas, max_amount_pesewas, min_tenor_days, max_tenor_days, interest_rate_bps, origination_fee_bps, penalty_rate_bps, daily_sweep_bps, status)
VALUES
    ('a0000001-0000-0000-0000-000000000001', 'SME_WC_01', 'SME Working Capital Term Loan', 'SME_WORKING_CAPITAL', 500000, 50000000, 30, 365, 2400, 200, 500, 0, 'ACTIVE'),
    ('a0000001-0000-0000-0000-000000000002', 'MCA_01', 'Merchant Cash Advance', 'MERCHANT_CASH_ADVANCE', 200000, 20000000, 30, 180, 1500, 150, 300, 1500, 'ACTIVE'),
    ('a0000001-0000-0000-0000-000000000003', 'FACTOR_01', 'Invoice Factoring Facility', 'INVOICE_FACTORING', 1000000, 100000000, 15, 90, 1800, 100, 400, 0, 'ACTIVE')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE loan_application (
    id                          uuid PRIMARY KEY,
    applicant_id                uuid NOT NULL,
    applicant_type              varchar(32) NOT NULL DEFAULT 'MERCHANT',
    product_id                  uuid NOT NULL REFERENCES loan_product(id),
    requested_amount_pesewas    bigint NOT NULL,
    tenor_days                  integer NOT NULL,
    stated_purpose              text NOT NULL,
    monthly_turnover_pesewas    bigint NOT NULL,
    invoice_reference           varchar(128),
    status                      varchar(32) NOT NULL DEFAULT 'SUBMITTED',
    credit_score                integer,
    max_approved_pesewas        bigint,
    risk_band                   varchar(16),
    decision_reason             text,
    submitted_at                timestamptz NOT NULL DEFAULT now(),
    decided_at                  timestamptz
);
CREATE INDEX idx_loan_app_applicant ON loan_application(applicant_id);

CREATE TABLE loan_contract (
    id                              uuid PRIMARY KEY,
    application_id                  uuid REFERENCES loan_application(id),
    contract_reference              varchar(64) NOT NULL UNIQUE,
    borrower_id                     uuid NOT NULL,
    product_id                      uuid NOT NULL REFERENCES loan_product(id),
    principal_pesewas               bigint NOT NULL,
    origination_fee_pesewas         bigint NOT NULL DEFAULT 0,
    total_interest_pesewas          bigint NOT NULL DEFAULT 0,
    total_repayment_pesewas         bigint NOT NULL,
    outstanding_principal_pesewas   bigint NOT NULL,
    outstanding_interest_pesewas    bigint NOT NULL,
    outstanding_fees_pesewas        bigint NOT NULL DEFAULT 0,
    outstanding_penalties_pesewas   bigint NOT NULL DEFAULT 0,
    tenor_days                      integer NOT NULL,
    daily_sweep_bps                 integer NOT NULL DEFAULT 0,
    disbursed_at                    timestamptz,
    start_date                      date NOT NULL,
    maturity_date                   date NOT NULL,
    status                          varchar(32) NOT NULL DEFAULT 'ACTIVE',
    delinquency_state               varchar(32) NOT NULL DEFAULT 'PERFORMING',
    days_past_due                   integer NOT NULL DEFAULT 0,
    created_at                      timestamptz NOT NULL DEFAULT now(),
    updated_at                      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_loan_contract_borrower ON loan_contract(borrower_id);

CREATE TABLE loan_repayment_schedule (
    id                          uuid PRIMARY KEY,
    contract_id                 uuid NOT NULL REFERENCES loan_contract(id),
    installment_number          integer NOT NULL,
    due_date                    date NOT NULL,
    principal_due_pesewas       bigint NOT NULL,
    interest_due_pesewas        bigint NOT NULL,
    fees_due_pesewas            bigint NOT NULL DEFAULT 0,
    principal_paid_pesewas      bigint NOT NULL DEFAULT 0,
    interest_paid_pesewas       bigint NOT NULL DEFAULT 0,
    fees_paid_pesewas           bigint NOT NULL DEFAULT 0,
    penalty_paid_pesewas        bigint NOT NULL DEFAULT 0,
    status                      varchar(32) NOT NULL DEFAULT 'PENDING',
    paid_at                     timestamptz,
    CONSTRAINT uq_contract_installment UNIQUE (contract_id, installment_number)
);
CREATE INDEX idx_loan_schedule_contract ON loan_repayment_schedule(contract_id, due_date);

CREATE TABLE loan_transaction (
    id                              uuid PRIMARY KEY,
    contract_id                     uuid NOT NULL REFERENCES loan_contract(id),
    transaction_type                varchar(32) NOT NULL,
    amount_pesewas                  bigint NOT NULL,
    principal_component_pesewas     bigint NOT NULL DEFAULT 0,
    interest_component_pesewas      bigint NOT NULL DEFAULT 0,
    fee_component_pesewas           bigint NOT NULL DEFAULT 0,
    penalty_component_pesewas       bigint NOT NULL DEFAULT 0,
    reference                       varchar(64) NOT NULL UNIQUE,
    notes                           text,
    created_at                      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_loan_txn_contract ON loan_transaction(contract_id, created_at DESC);

-- ============================================================================
-- 2. DOMAIN 9: EVERYDAY FINANCIAL SERVICES & BILL PAYMENTS
-- ============================================================================

CREATE TABLE bill_provider (
    id              uuid PRIMARY KEY,
    provider_code   varchar(32) NOT NULL UNIQUE,
    name            varchar(128) NOT NULL,
    category        varchar(32) NOT NULL,
    is_active       boolean NOT NULL DEFAULT true
);

INSERT INTO bill_provider (id, provider_code, name, category, is_active)
VALUES
    ('b0000001-0000-0000-0000-000000000001', 'MTN', 'MTN Ghana', 'AIRTIME_DATA', true),
    ('b0000001-0000-0000-0000-000000000002', 'TELECEL', 'Telecel Ghana', 'AIRTIME_DATA', true),
    ('b0000001-0000-0000-0000-000000000003', 'AIRTELTIGO', 'AT (AirtelTigo)', 'AIRTIME_DATA', true),
    ('b0000001-0000-0000-0000-000000000004', 'ECG', 'Electricity Company of Ghana', 'UTILITY_POWER', true),
    ('b0000001-0000-0000-0000-000000000005', 'GWCL', 'Ghana Water Company Limited', 'UTILITY_WATER', true),
    ('b0000001-0000-0000-0000-000000000006', 'SCHOOL', 'Elepay School Fee Portal', 'EDUCATION', true)
ON CONFLICT (provider_code) DO NOTHING;

CREATE TABLE bill_product (
    id              uuid PRIMARY KEY,
    provider_code   varchar(32) NOT NULL REFERENCES bill_provider(provider_code),
    product_code    varchar(64) NOT NULL UNIQUE,
    name            varchar(128) NOT NULL,
    category        varchar(32) NOT NULL,
    amount_pesewas  bigint NOT NULL DEFAULT 0,
    data_mb         integer,
    validity_days   integer,
    is_active       boolean NOT NULL DEFAULT true
);

INSERT INTO bill_product (id, provider_code, product_code, name, category, amount_pesewas, data_mb, validity_days, is_active)
VALUES
    ('b0000002-0000-0000-0000-000000000001', 'MTN', 'MTN_AIRTIME', 'MTN Top-up Airtime', 'AIRTIME_DATA', 0, NULL, NULL, true),
    ('b0000002-0000-0000-0000-000000000002', 'MTN', 'MTN_DATA_1GB', 'MTN 1GB Data Bundle', 'AIRTIME_DATA', 1000, 1000, 30, true),
    ('b0000002-0000-0000-0000-000000000003', 'MTN', 'MTN_DATA_5GB', 'MTN 5GB Data Bundle', 'AIRTIME_DATA', 4000, 5000, 30, true),
    ('b0000002-0000-0000-0000-000000000004', 'TELECEL', 'TELECEL_AIRTIME', 'Telecel Top-up Airtime', 'AIRTIME_DATA', 0, NULL, NULL, true),
    ('b0000002-0000-0000-0000-000000000005', 'TELECEL', 'TELECEL_DATA_2GB', 'Telecel 2GB Data Bundle', 'AIRTIME_DATA', 1500, 2000, 30, true),
    ('b0000002-0000-0000-0000-000000000006', 'AIRTELTIGO', 'AIRTELTIGO_AIRTIME', 'AT Top-up Airtime', 'AIRTIME_DATA', 0, NULL, NULL, true),
    ('b0000002-0000-0000-0000-000000000007', 'AIRTELTIGO', 'AIRTELTIGO_DATA_2GB', 'AT 2GB Big Time Data', 'AIRTIME_DATA', 1200, 2000, 30, true),
    ('b0000002-0000-0000-0000-000000000008', 'ECG', 'ECG_PREPAID', 'ECG Electricity Prepayment', 'UTILITY_POWER', 0, NULL, NULL, true),
    ('b0000002-0000-0000-0000-000000000009', 'ECG', 'ECG_POSTPAID', 'ECG Electricity Postpaid Bill', 'UTILITY_POWER', 0, NULL, NULL, true),
    ('b0000002-0000-0000-0000-000000000010', 'GWCL', 'GWCL_WATER', 'Ghana Water Bill Payment', 'UTILITY_WATER', 0, NULL, NULL, true),
    ('b0000002-0000-0000-0000-000000000011', 'SCHOOL', 'SCHOOL_FEES', 'Tuition & School Fee Payment', 'EDUCATION', 0, NULL, NULL, true)
ON CONFLICT (product_code) DO NOTHING;

CREATE TABLE institution_registry (
    id                  uuid PRIMARY KEY,
    institution_code    varchar(32) NOT NULL UNIQUE,
    name                varchar(128) NOT NULL,
    category            varchar(32) NOT NULL,
    is_active           boolean NOT NULL DEFAULT true
);

INSERT INTO institution_registry (id, institution_code, name, category, is_active)
VALUES
    ('b0000003-0000-0000-0000-000000000001', 'UG_LEGON', 'University of Ghana - Legon', 'UNIVERSITY', true),
    ('b0000003-0000-0000-0000-000000000002', 'KNUST', 'Kwame Nkrumah University of Science and Technology', 'UNIVERSITY', true),
    ('b0000003-0000-0000-0000-000000000003', 'UCC', 'University of Cape Coast', 'UNIVERSITY', true),
    ('b0000003-0000-0000-0000-000000000004', 'PRESEC', 'Presbyterian Boys Secondary School', 'SECONDARY', true),
    ('b0000003-0000-0000-0000-000000000005', 'ACHIMOTA', 'Achimota School', 'SECONDARY', true)
ON CONFLICT (institution_code) DO NOTHING;

CREATE TABLE student_fee_record (
    id                          uuid PRIMARY KEY,
    institution_code            varchar(32) NOT NULL REFERENCES institution_registry(institution_code),
    student_id                  varchar(64) NOT NULL,
    student_name                varchar(128) NOT NULL,
    program_name                varchar(128) NOT NULL,
    total_fee_pesewas           bigint NOT NULL,
    paid_fee_pesewas            bigint NOT NULL DEFAULT 0,
    outstanding_fee_pesewas     bigint NOT NULL,
    academic_term               varchar(32) NOT NULL,
    is_active                   boolean NOT NULL DEFAULT true,
    CONSTRAINT uq_institution_student UNIQUE (institution_code, student_id)
);

INSERT INTO student_fee_record (id, institution_code, student_id, student_name, program_name, total_fee_pesewas, paid_fee_pesewas, outstanding_fee_pesewas, academic_term, is_active)
VALUES
    ('b0000004-0000-0000-0000-000000000001', 'UG_LEGON', '10982341', 'Kwame Mensah', 'BSc Computer Science', 500000, 250000, 250000, '2026/2027 Sem 1', true),
    ('b0000004-0000-0000-0000-000000000002', 'KNUST', '20874112', 'Ama Serwaa', 'BSc Electrical Engineering', 600000, 300000, 300000, '2026/2027 Sem 1', true),
    ('b0000004-0000-0000-0000-000000000003', 'PRESEC', 'PR-2026-08', 'Kofi Boateng', 'General Science', 200000, 100000, 100000, '2026 Term 1', true)
ON CONFLICT (institution_code, student_id) DO NOTHING;

CREATE TABLE bill_payment_transaction (
    id                  uuid PRIMARY KEY,
    customer_id         uuid NOT NULL,
    provider_code       varchar(32) NOT NULL,
    product_code        varchar(64) NOT NULL,
    biller_account      varchar(128) NOT NULL,
    amount_pesewas      bigint NOT NULL,
    fee_pesewas         bigint NOT NULL DEFAULT 0,
    total_pesewas       bigint NOT NULL,
    reference           varchar(64) NOT NULL UNIQUE,
    partner_reference   varchar(128),
    status              varchar(32) NOT NULL DEFAULT 'INITIATED',
    token_voucher       varchar(128),
    metadata_json       text,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_bill_payment_customer ON bill_payment_transaction(customer_id, created_at DESC);

CREATE TABLE scheduled_bill_payment (
    id                      uuid PRIMARY KEY,
    customer_id             uuid NOT NULL,
    provider_code           varchar(32) NOT NULL,
    product_code            varchar(64) NOT NULL,
    biller_account          varchar(128) NOT NULL,
    amount_pesewas          bigint NOT NULL,
    frequency               varchar(32) NOT NULL,
    day_of_month            integer,
    next_run_date           date NOT NULL,
    status                  varchar(32) NOT NULL DEFAULT 'ACTIVE',
    last_executed_at        timestamptz,
    last_notification_at    timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_scheduled_bill_due ON scheduled_bill_payment(status, next_run_date);

-- ============================================================================
-- 3. DOMAIN 10: COMPLETE CARD INFRASTRUCTURE
-- ============================================================================

CREATE TABLE card_registry (
    id                          uuid PRIMARY KEY,
    customer_id                 uuid,
    merchant_id                 uuid,
    card_type                   varchar(32) NOT NULL,
    scheme                      varchar(32) NOT NULL,
    masked_pan                  varchar(24) NOT NULL,
    pan_hash                    varchar(64) NOT NULL UNIQUE,
    encrypted_pan               text NOT NULL,
    cvv2                        varchar(4) NOT NULL,
    expiry_month                integer NOT NULL,
    expiry_year                 integer NOT NULL,
    status                      varchar(32) NOT NULL DEFAULT 'PENDING_ACTIVATION',
    pin_hash                    varchar(128),
    per_tx_limit_pesewas        bigint NOT NULL DEFAULT 500000,
    daily_limit_pesewas         bigint NOT NULL DEFAULT 2000000,
    monthly_limit_pesewas       bigint NOT NULL DEFAULT 10000000,
    current_day_spent_pesewas   bigint NOT NULL DEFAULT 0,
    current_month_spent_pesewas bigint NOT NULL DEFAULT 0,
    last_spend_date             date,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_card_customer ON card_registry(customer_id);
CREATE INDEX idx_card_merchant ON card_registry(merchant_id);

CREATE TABLE card_mcc_rule (
    id              uuid PRIMARY KEY,
    card_id         uuid NOT NULL REFERENCES card_registry(id) ON DELETE CASCADE,
    rule_type       varchar(16) NOT NULL,
    mcc_code        varchar(8) NOT NULL,
    description     varchar(128),
    is_active       boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_card_mcc_rule UNIQUE (card_id, rule_type, mcc_code)
);

CREATE TABLE company_expense_card (
    id                          uuid PRIMARY KEY,
    card_id                     uuid NOT NULL UNIQUE REFERENCES card_registry(id) ON DELETE CASCADE,
    merchant_id                 uuid NOT NULL,
    employee_id                 uuid NOT NULL,
    department                  varchar(64) NOT NULL,
    monthly_sublimit_pesewas    bigint NOT NULL,
    current_month_spent_pesewas bigint NOT NULL DEFAULT 0,
    manager_approval_required   boolean NOT NULL DEFAULT false,
    is_active                   boolean NOT NULL DEFAULT true,
    created_at                  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_expense_card_merchant ON company_expense_card(merchant_id, employee_id);

CREATE TABLE card_transaction (
    id              uuid PRIMARY KEY,
    card_id         uuid NOT NULL REFERENCES card_registry(id),
    merchant_name   varchar(128) NOT NULL,
    merchant_city   varchar(64),
    mcc             varchar(8) NOT NULL,
    amount_pesewas  bigint NOT NULL,
    currency        varchar(3) NOT NULL DEFAULT 'GHS',
    auth_code       varchar(32),
    status          varchar(32) NOT NULL,
    decline_reason  text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_card_txn_card ON card_transaction(card_id, created_at DESC);

-- ============================================================================
-- 4. DOMAIN 11: AUTHORIZED AGENT BANKING PLATFORM
-- ============================================================================

CREATE TABLE agent_config (
    id                      uuid PRIMARY KEY,
    live_agent_enabled      boolean NOT NULL DEFAULT false,
    updated_at              timestamptz NOT NULL DEFAULT now()
);

INSERT INTO agent_config (id, live_agent_enabled)
VALUES ('00000000-0000-0000-0000-000000000011', false)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE agent_profile (
    id                              uuid PRIMARY KEY,
    agent_code                      varchar(32) NOT NULL UNIQUE,
    business_name                   varchar(128) NOT NULL,
    proprietor_name                 varchar(128) NOT NULL,
    national_id                     varchar(64) NOT NULL,
    phone                           varchar(32) NOT NULL UNIQUE,
    address                         text NOT NULL,
    city                            varchar(64) NOT NULL,
    region                          varchar(64) NOT NULL,
    latitude                        numeric(10,6),
    longitude                       numeric(10,6),
    kyc_tier                        varchar(16) NOT NULL DEFAULT 'TIER_1',
    max_single_cash_in_pesewas      bigint NOT NULL DEFAULT 100000,
    max_single_cash_out_pesewas     bigint NOT NULL DEFAULT 100000,
    daily_volume_limit_pesewas      bigint NOT NULL DEFAULT 1000000,
    pin_hash                        varchar(128) NOT NULL,
    status                          varchar(32) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    created_at                      timestamptz NOT NULL DEFAULT now(),
    updated_at                      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_phone ON agent_profile(phone);
CREATE INDEX idx_agent_region ON agent_profile(region);

CREATE TABLE agent_float_account (
    id                                  uuid PRIMARY KEY,
    agent_id                            uuid NOT NULL UNIQUE REFERENCES agent_profile(id) ON DELETE CASCADE,
    available_float_pesewas             bigint NOT NULL DEFAULT 0,
    reserved_float_pesewas              bigint NOT NULL DEFAULT 0,
    accrued_commission_pesewas          bigint NOT NULL DEFAULT 0,
    total_lifetime_commission_pesewas   bigint NOT NULL DEFAULT 0,
    currency                            varchar(3) NOT NULL DEFAULT 'GHS',
    updated_at                          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE agent_commission_tier (
    id                      uuid PRIMARY KEY,
    kyc_tier                varchar(16) NOT NULL,
    transaction_type        varchar(32) NOT NULL,
    min_amount_pesewas      bigint NOT NULL,
    max_amount_pesewas      bigint NOT NULL,
    commission_rate_bps     integer NOT NULL,
    fixed_commission_pesewas bigint NOT NULL DEFAULT 0,
    platform_share_bps      integer NOT NULL DEFAULT 2000
);

INSERT INTO agent_commission_tier (id, kyc_tier, transaction_type, min_amount_pesewas, max_amount_pesewas, commission_rate_bps, fixed_commission_pesewas, platform_share_bps)
VALUES
    ('c0000001-0000-0000-0000-000000000001', 'TIER_1', 'CASH_IN', 100, 1000000, 50, 50, 2000),
    ('c0000001-0000-0000-0000-000000000002', 'TIER_1', 'CASH_OUT', 100, 1000000, 75, 75, 2000),
    ('c0000001-0000-0000-0000-000000000003', 'TIER_1', 'BILL_PAY', 100, 1000000, 60, 50, 2000),
    ('c0000001-0000-0000-0000-000000000004', 'TIER_2', 'CASH_IN', 100, 5000000, 60, 50, 2000),
    ('c0000001-0000-0000-0000-000000000005', 'TIER_2', 'CASH_OUT', 100, 5000000, 85, 75, 2000),
    ('c0000001-0000-0000-0000-000000000006', 'TIER_2', 'BILL_PAY', 100, 5000000, 70, 50, 2000),
    ('c0000001-0000-0000-0000-000000000007', 'TIER_3', 'CASH_IN', 100, 20000000, 75, 50, 2000),
    ('c0000001-0000-0000-0000-000000000008', 'TIER_3', 'CASH_OUT', 100, 20000000, 100, 75, 2000),
    ('c0000001-0000-0000-0000-000000000009', 'TIER_3', 'BILL_PAY', 100, 20000000, 80, 50, 2000);

CREATE TABLE agent_cash_out_otp (
    id                  uuid PRIMARY KEY,
    agent_id            uuid NOT NULL REFERENCES agent_profile(id),
    customer_phone      varchar(32) NOT NULL,
    customer_wallet_id  uuid,
    amount_pesewas      bigint NOT NULL,
    otp_code            varchar(8) NOT NULL,
    expires_at          timestamptz NOT NULL,
    status              varchar(32) NOT NULL DEFAULT 'PENDING',
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_cash_out_phone ON agent_cash_out_otp(customer_phone, status);

CREATE TABLE agent_transaction (
    id                          uuid PRIMARY KEY,
    agent_id                    uuid NOT NULL REFERENCES agent_profile(id),
    transaction_type            varchar(32) NOT NULL,
    customer_identifier         varchar(128),
    amount_pesewas              bigint NOT NULL,
    float_before_pesewas        bigint NOT NULL,
    float_after_pesewas         bigint NOT NULL,
    agent_commission_pesewas    bigint NOT NULL DEFAULT 0,
    platform_fee_pesewas        bigint NOT NULL DEFAULT 0,
    reference                   varchar(64) NOT NULL UNIQUE,
    status                      varchar(32) NOT NULL DEFAULT 'COMPLETED',
    created_at                  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_txn ON agent_transaction(agent_id, created_at DESC);
