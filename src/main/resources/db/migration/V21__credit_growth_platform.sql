-- V21: Credit, Reliability & Customer Growth Platform
-- Algorithms: 008, 056, 057, 058, 065, 066, 067, 068, 069, 070, 071, 072, 086, 089, 090, 091, 092, 093, 096, 097, 098, 099, 100

-- ============================================================================
-- 1. CREDIT & TREASURY FORECASTING DOMAIN
-- ============================================================================

-- Algorithm 008: Interest and yield accrual
CREATE TABLE interest_accrual_policy (
    id                      uuid PRIMARY KEY,
    product_name            varchar(64) NOT NULL UNIQUE,
    rate_bps                integer NOT NULL,
    day_count_convention    varchar(32) NOT NULL,
    posting_schedule        varchar(32) NOT NULL,
    live_enabled            boolean NOT NULL DEFAULT false,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE interest_accrual_record (
    id                      uuid PRIMARY KEY,
    account_id              uuid NOT NULL,
    calculation_date        date NOT NULL,
    principal_pesewas       bigint NOT NULL,
    rate_bps                integer NOT NULL,
    day_count_convention    varchar(32) NOT NULL,
    accrued_pesewas         bigint NOT NULL,
    cumulative_pesewas      bigint NOT NULL,
    status                  varchar(32) NOT NULL,
    posted_journal_id       uuid,
    created_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_interest_accrual_account ON interest_accrual_record(account_id, calculation_date);

-- Algorithm 056: Float forecasting
CREATE TABLE float_account (
    id                      uuid PRIMARY KEY,
    rail                    varchar(32) NOT NULL UNIQUE,
    account_name            varchar(128) NOT NULL,
    current_balance_pesewas bigint NOT NULL DEFAULT 0,
    min_target_pesewas      bigint NOT NULL DEFAULT 0,
    max_target_pesewas      bigint NOT NULL DEFAULT 0,
    currency                varchar(3) NOT NULL DEFAULT 'GHS',
    updated_at              timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE float_forecast (
    id                          uuid PRIMARY KEY,
    rail                        varchar(32) NOT NULL,
    forecast_date               date NOT NULL,
    expected_inflow_pesewas     bigint NOT NULL,
    expected_outflow_pesewas    bigint NOT NULL,
    projected_eod_pesewas       bigint NOT NULL,
    buffer_pesewas              bigint NOT NULL,
    confidence_lower_pesewas    bigint NOT NULL,
    confidence_upper_pesewas    bigint NOT NULL,
    model_version               varchar(32) NOT NULL,
    created_at                  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_float_forecast_rail ON float_forecast(rail, forecast_date);

-- Algorithm 057: Float rebalancing
CREATE TABLE float_rebalance_recommendation (
    id                          uuid PRIMARY KEY,
    source_rail                 varchar(32) NOT NULL,
    target_rail                 varchar(32) NOT NULL,
    recommended_amount_pesewas  bigint NOT NULL,
    urgency                     varchar(16) NOT NULL,
    reason                      text NOT NULL,
    status                      varchar(32) NOT NULL DEFAULT 'RECOMMENDED',
    executed_by                 uuid,
    executed_at                 timestamptz,
    created_at                  timestamptz NOT NULL DEFAULT now()
);

-- Algorithm 058: Merchant cash-flow forecasting
CREATE TABLE merchant_cash_flow_forecast (
    id                              uuid PRIMARY KEY,
    merchant_id                     uuid NOT NULL,
    forecast_date                   date NOT NULL,
    projected_gross_pesewas         bigint NOT NULL,
    projected_settled_pesewas       bigint NOT NULL,
    projected_available_pesewas     bigint NOT NULL,
    confidence_lower_pesewas        bigint NOT NULL,
    confidence_upper_pesewas        bigint NOT NULL,
    backtest_mape                   numeric(6,4),
    model_version                   varchar(32) NOT NULL,
    created_at                      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_merchant_forecast_date ON merchant_cash_flow_forecast(merchant_id, forecast_date);

-- Algorithm 065: Cash-flow underwriting
CREATE TABLE credit_underwriting_assessment (
    id                          uuid PRIMARY KEY,
    merchant_id                 uuid NOT NULL,
    evaluated_at                timestamptz NOT NULL DEFAULT now(),
    monthly_turnover_pesewas    bigint NOT NULL,
    refund_rate_bps             integer NOT NULL,
    volatility_score            numeric(5,4) NOT NULL,
    max_credit_pesewas          bigint NOT NULL,
    recommended_factor_rate     numeric(5,4) NOT NULL,
    decision                    varchar(32) NOT NULL,
    reason                      text NOT NULL,
    features_json               text
);
CREATE INDEX idx_credit_underwriting_merchant ON credit_underwriting_assessment(merchant_id);

-- Algorithm 066: Earned-wage access (EWA) eligibility
CREATE TABLE ewa_eligibility (
    id                          uuid PRIMARY KEY,
    employee_id                 uuid NOT NULL,
    employer_id                 uuid NOT NULL,
    monthly_salary_pesewas      bigint NOT NULL,
    days_worked                 integer NOT NULL,
    accrued_salary_pesewas      bigint NOT NULL,
    max_advance_limit_pesewas   bigint NOT NULL,
    active_advances_pesewas     bigint NOT NULL DEFAULT 0,
    eligible_amount_pesewas     bigint NOT NULL,
    status                      varchar(32) NOT NULL,
    evaluated_at                timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ewa_employee ON ewa_eligibility(employee_id);

-- Algorithm 067: Overdraft eligibility scoring
CREATE TABLE overdraft_facility (
    id                              uuid PRIMARY KEY,
    customer_id                     uuid NOT NULL UNIQUE,
    credit_score                    integer NOT NULL,
    qualified_limit_pesewas         bigint NOT NULL,
    current_utilization_pesewas     bigint NOT NULL DEFAULT 0,
    monthly_deposit_cadence_score   numeric(5,2) NOT NULL,
    status                          varchar(32) NOT NULL,
    live_enabled                    boolean NOT NULL DEFAULT false,
    evaluated_at                    timestamptz NOT NULL DEFAULT now()
);

-- Algorithm 068: Merchant cash-advance pricing
CREATE TABLE mca_offer (
    id                          uuid PRIMARY KEY,
    merchant_id                 uuid NOT NULL,
    advance_amount_pesewas      bigint NOT NULL,
    factor_rate                 numeric(5,4) NOT NULL,
    fixed_fee_pesewas           bigint NOT NULL,
    total_repayment_pesewas     bigint NOT NULL,
    holdback_percentage_bps     integer NOT NULL,
    estimated_duration_days     integer NOT NULL,
    apr_equivalent_bps          integer NOT NULL,
    status                      varchar(32) NOT NULL DEFAULT 'OFFERED',
    created_at                  timestamptz NOT NULL DEFAULT now(),
    accepted_at                 timestamptz
);
CREATE INDEX idx_mca_offer_merchant ON mca_offer(merchant_id);

-- Algorithm 069: Repayment scheduling and sweeps
CREATE TABLE repayment_sweep_plan (
    id                          uuid PRIMARY KEY,
    mca_offer_id                uuid NOT NULL REFERENCES mca_offer(id),
    merchant_id                 uuid NOT NULL,
    target_amount_pesewas       bigint NOT NULL,
    collected_amount_pesewas    bigint NOT NULL DEFAULT 0,
    sweep_percentage_bps        integer NOT NULL,
    status                      varchar(32) NOT NULL DEFAULT 'ACTIVE',
    start_date                  date NOT NULL,
    completion_date             date,
    created_at                  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE repayment_sweep_execution (
    id                          uuid PRIMARY KEY,
    plan_id                     uuid NOT NULL REFERENCES repayment_sweep_plan(id),
    settlement_batch_id         uuid NOT NULL,
    settlement_amount_pesewas   bigint NOT NULL,
    swept_amount_pesewas        bigint NOT NULL,
    status                      varchar(32) NOT NULL,
    executed_at                 timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_sweep_batch UNIQUE (plan_id, settlement_batch_id)
);

-- Algorithm 070: Collections prioritization
CREATE TABLE collections_case (
    id                          uuid PRIMARY KEY,
    debtor_id                   uuid NOT NULL,
    debtor_type                 varchar(32) NOT NULL,
    overdue_amount_pesewas      bigint NOT NULL,
    days_overdue                integer NOT NULL,
    recovery_probability        numeric(5,4) NOT NULL,
    priority_score              numeric(12,4) NOT NULL,
    tier                        varchar(32) NOT NULL,
    status                      varchar(32) NOT NULL DEFAULT 'OPEN',
    assigned_agent              uuid,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_collections_priority ON collections_case(priority_score DESC);

-- Algorithm 071: Default prediction
CREATE TABLE credit_default_prediction (
    id                          uuid PRIMARY KEY,
    entity_id                   uuid NOT NULL,
    entity_type                 varchar(32) NOT NULL,
    probability_of_default      numeric(6,5) NOT NULL,
    risk_band                   varchar(16) NOT NULL,
    model_version               varchar(32) NOT NULL,
    calibrated                  boolean NOT NULL DEFAULT true,
    evaluated_at                timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_default_pred_entity ON credit_default_prediction(entity_id);

-- Algorithm 072: Dynamic credit limits
CREATE TABLE dynamic_credit_limit (
    id                          uuid PRIMARY KEY,
    entity_id                   uuid NOT NULL,
    entity_type                 varchar(32) NOT NULL,
    baseline_limit_pesewas      bigint NOT NULL,
    current_limit_pesewas       bigint NOT NULL,
    utilization_pesewas         bigint NOT NULL DEFAULT 0,
    health_score                numeric(5,2) NOT NULL,
    reason                      text NOT NULL,
    last_adjusted_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dynamic_credit_entity UNIQUE (entity_id, entity_type)
);

-- ============================================================================
-- 2. RELIABILITY DOMAIN
-- ============================================================================

-- Algorithm 086: Partner circuit breakers
CREATE TABLE circuit_breaker_event (
    id                          uuid PRIMARY KEY,
    partner_id                  varchar(64) NOT NULL,
    from_state                  varchar(16) NOT NULL,
    to_state                    varchar(16) NOT NULL,
    failure_rate_pct            numeric(5,2) NOT NULL,
    slow_call_rate_pct          numeric(5,2) NOT NULL,
    transition_reason           text NOT NULL,
    event_time                  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_cb_event_partner ON circuit_breaker_event(partner_id, event_time DESC);

-- Algorithm 089: Load shedding
CREATE TABLE load_shedding_rule (
    id                          uuid PRIMARY KEY,
    service_name                varchar(64) NOT NULL,
    priority_tier               varchar(16) NOT NULL,
    active                      boolean NOT NULL DEFAULT true,
    cpu_threshold_pct           integer NOT NULL DEFAULT 85,
    queue_threshold             integer NOT NULL DEFAULT 100,
    drop_count                  bigint NOT NULL DEFAULT 0,
    updated_at                  timestamptz NOT NULL DEFAULT now()
);

-- Algorithm 090: SLO burn-rate alerts
CREATE TABLE slo_burn_alert (
    id                          uuid PRIMARY KEY,
    service_name                varchar(64) NOT NULL,
    slo_name                    varchar(64) NOT NULL,
    window_name                 varchar(16) NOT NULL,
    burn_rate                   numeric(8,4) NOT NULL,
    threshold                   numeric(8,4) NOT NULL,
    severity                    varchar(16) NOT NULL,
    status                      varchar(16) NOT NULL DEFAULT 'FIRING',
    triggered_at                timestamptz NOT NULL DEFAULT now(),
    resolved_at                 timestamptz
);
CREATE INDEX idx_slo_burn_status ON slo_burn_alert(status, triggered_at);

-- Algorithm 091: Success-rate anomaly detection
CREATE TABLE conversion_anomaly_record (
    id                          uuid PRIMARY KEY,
    route_or_rail               varchar(64) NOT NULL,
    baseline_conversion_pct     numeric(5,2) NOT NULL,
    current_conversion_pct      numeric(5,2) NOT NULL,
    sample_size                 integer NOT NULL,
    z_score                     numeric(6,3) NOT NULL,
    is_anomaly                  boolean NOT NULL DEFAULT false,
    detected_at                 timestamptz NOT NULL DEFAULT now()
);

-- Algorithm 092: Scaling signals
CREATE TABLE scaling_signal_snapshot (
    id                          uuid PRIMARY KEY,
    component                   varchar(64) NOT NULL,
    pool_utilization_pct        numeric(5,2) NOT NULL,
    queue_depth                 integer NOT NULL,
    outbox_lag_ms               bigint NOT NULL,
    p99_latency_ms              bigint NOT NULL,
    scale_recommendation        varchar(16) NOT NULL,
    captured_at                 timestamptz NOT NULL DEFAULT now()
);

-- Algorithm 093: Fault injection
CREATE TABLE chaos_experiment (
    id                          uuid PRIMARY KEY,
    experiment_name             varchar(128) NOT NULL,
    target_rail                 varchar(64) NOT NULL,
    failure_mode                varchar(32) NOT NULL,
    delay_ms                    integer NOT NULL DEFAULT 0,
    error_rate_pct              numeric(5,2) NOT NULL DEFAULT 0,
    status                      varchar(16) NOT NULL DEFAULT 'CREATED',
    start_time                  timestamptz,
    end_time                    timestamptz,
    created_at                  timestamptz NOT NULL DEFAULT now()
);

-- ============================================================================
-- 3. CUSTOMER & GROWTH DOMAIN
-- ============================================================================

-- Algorithm 096: Spending categorization
CREATE TABLE transaction_category (
    id                          uuid PRIMARY KEY,
    transaction_id              uuid NOT NULL,
    customer_id                 uuid NOT NULL,
    category                    varchar(64) NOT NULL,
    subcategory                 varchar(64),
    confidence                  numeric(4,3) NOT NULL,
    source                      varchar(32) NOT NULL,
    corrected_by_user           boolean NOT NULL DEFAULT false,
    assigned_at                 timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_txn_category UNIQUE (transaction_id)
);
CREATE INDEX idx_txn_category_customer ON transaction_category(customer_id, category);

-- Algorithm 097: Savings goals and round-ups
CREATE TABLE savings_goal (
    id                          uuid PRIMARY KEY,
    customer_id                 uuid NOT NULL,
    goal_name                   varchar(128) NOT NULL,
    target_amount_pesewas       bigint NOT NULL,
    current_amount_pesewas      bigint NOT NULL DEFAULT 0,
    currency                    varchar(3) NOT NULL DEFAULT 'GHS',
    round_up_enabled            boolean NOT NULL DEFAULT true,
    round_up_unit_pesewas       integer NOT NULL DEFAULT 100,
    status                      varchar(16) NOT NULL DEFAULT 'ACTIVE',
    target_date                 date,
    created_at                  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_savings_goal_customer ON savings_goal(customer_id);

CREATE TABLE savings_round_up_sweep (
    id                          uuid PRIMARY KEY,
    goal_id                     uuid NOT NULL REFERENCES savings_goal(id),
    customer_id                 uuid NOT NULL,
    transaction_id              uuid NOT NULL,
    transaction_amount_pesewas  bigint NOT NULL,
    round_up_amount_pesewas     bigint NOT NULL,
    status                      varchar(16) NOT NULL DEFAULT 'COMPLETED',
    executed_at                 timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_round_up_txn UNIQUE (transaction_id)
);

-- Algorithm 098: Churn prediction
CREATE TABLE customer_churn_score (
    id                          uuid PRIMARY KEY,
    customer_id                 uuid NOT NULL,
    dormancy_probability        numeric(5,4) NOT NULL,
    days_since_last_txn         integer NOT NULL,
    hazard_rate                 numeric(6,5) NOT NULL,
    churn_risk_tier             varchar(16) NOT NULL,
    evaluated_at                timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_churn_score_cust ON customer_churn_score(customer_id);

-- Algorithm 099: Notification timing
CREATE TABLE notification_delivery_queue (
    id                          uuid PRIMARY KEY,
    customer_id                 uuid NOT NULL,
    title                       varchar(255) NOT NULL,
    message                     text NOT NULL,
    urgency                     varchar(16) NOT NULL,
    quiet_hours_allowed         boolean NOT NULL DEFAULT false,
    scheduled_delivery_time     timestamptz NOT NULL,
    status                      varchar(16) NOT NULL DEFAULT 'QUEUED',
    created_at                  timestamptz NOT NULL DEFAULT now(),
    sent_at                     timestamptz
);
CREATE INDEX idx_notif_queue_status ON notification_delivery_queue(status, scheduled_delivery_time);

-- Algorithm 100: Personalized limits
CREATE TABLE personalized_limit_profile (
    id                              uuid PRIMARY KEY,
    customer_id                     uuid NOT NULL UNIQUE,
    base_tier                       integer NOT NULL,
    tier_multiplier                 numeric(4,2) NOT NULL DEFAULT 1.00,
    max_daily_pesewas               bigint NOT NULL,
    max_transaction_pesewas         bigint NOT NULL,
    policy_ceiling_daily_pesewas    bigint NOT NULL,
    risk_score                      integer NOT NULL,
    reason                          text NOT NULL,
    status                          varchar(16) NOT NULL DEFAULT 'ACTIVE',
    updated_at                      timestamptz NOT NULL DEFAULT now()
);
