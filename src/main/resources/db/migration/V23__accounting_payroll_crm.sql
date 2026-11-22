-- V23: Business Accounting, Invoicing, Payroll & CRM Platform
-- Domains:
-- Domain 5: Business Accounting & Invoicing
-- Domain 6: Payroll & Supplier Payments
-- Domain 7: CRM, Loyalty & Marketing Campaigns

-- ============================================================================
-- 1. BUSINESS ACCOUNTING & INVOICING DOMAIN
-- ============================================================================

-- Chart of Accounts for Merchant General Ledger
CREATE TABLE merchant_gl_account (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL,
    account_code            varchar(32) NOT NULL,
    account_name            varchar(128) NOT NULL,
    account_category        varchar(32) NOT NULL, -- ASSET, LIABILITY, EQUITY, REVENUE, COGS, EXPENSE
    subcategory             varchar(64) NOT NULL,
    normal_balance          varchar(8) NOT NULL,  -- DEBIT, CREDIT
    balance_pesewas         bigint NOT NULL DEFAULT 0,
    currency                varchar(3) NOT NULL DEFAULT 'GHS',
    is_active               boolean NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_merchant_gl_account UNIQUE (merchant_id, account_code)
);
CREATE INDEX idx_merchant_gl_account_merchant ON merchant_gl_account(merchant_id, account_category);

-- Journal Entries (General Ledger)
CREATE TABLE merchant_gl_journal_entry (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL,
    entry_number            varchar(64) NOT NULL,
    entry_date              date NOT NULL,
    description             varchar(255) NOT NULL,
    reference_type          varchar(32) NOT NULL, -- MANUAL, INVOICE, BILL, PAYROLL, POS_SALE, TAX
    reference_id            uuid,
    currency                varchar(3) NOT NULL DEFAULT 'GHS',
    status                  varchar(32) NOT NULL DEFAULT 'POSTED', -- DRAFT, POSTED, REVERSED
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_merchant_journal_entry UNIQUE (merchant_id, entry_number)
);
CREATE INDEX idx_merchant_journal_entry_merchant ON merchant_gl_journal_entry(merchant_id, entry_date);

-- Journal Entry Lines (Double-Entry Debit / Credit)
CREATE TABLE merchant_gl_journal_line (
    id                      uuid PRIMARY KEY,
    journal_entry_id        uuid NOT NULL REFERENCES merchant_gl_journal_entry(id) ON DELETE CASCADE,
    account_id              uuid NOT NULL REFERENCES merchant_gl_account(id),
    debit_pesewas           bigint NOT NULL DEFAULT 0,
    credit_pesewas          bigint NOT NULL DEFAULT 0,
    description             varchar(255),
    created_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_merchant_journal_line_entry ON merchant_gl_journal_line(journal_entry_id);
CREATE INDEX idx_merchant_journal_line_account ON merchant_gl_journal_line(account_id);

-- Invoices
CREATE TABLE merchant_invoice (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL,
    invoice_number          varchar(64) NOT NULL,
    quote_id                uuid,
    customer_id             uuid,
    customer_name           varchar(128) NOT NULL,
    customer_email          varchar(128),
    customer_phone          varchar(32),
    customer_tin            varchar(32),
    issue_date              date NOT NULL,
    due_date                date NOT NULL,
    currency                varchar(3) NOT NULL DEFAULT 'GHS',
    subtotal_pesewas        bigint NOT NULL,
    tax_pesewas             bigint NOT NULL DEFAULT 0,
    discount_pesewas        bigint NOT NULL DEFAULT 0,
    total_pesewas           bigint NOT NULL,
    paid_amount_pesewas     bigint NOT NULL DEFAULT 0,
    status                  varchar(32) NOT NULL DEFAULT 'DRAFT', -- DRAFT, SENT, PAID, PARTIALLY_PAID, OVERDUE, VOID
    payment_link_token      varchar(64) UNIQUE,
    gra_sdc_receipt_number  varchar(64),
    gra_qr_code             text,
    gra_signature           text,
    notes                   text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_merchant_invoice_num UNIQUE (merchant_id, invoice_number)
);
CREATE INDEX idx_merchant_invoice_merchant ON merchant_invoice(merchant_id, status, due_date);
CREATE INDEX idx_merchant_invoice_customer ON merchant_invoice(customer_id);

-- Invoice Line Items
CREATE TABLE merchant_invoice_item (
    id                      uuid PRIMARY KEY,
    invoice_id              uuid NOT NULL REFERENCES merchant_invoice(id) ON DELETE CASCADE,
    item_description        varchar(255) NOT NULL,
    quantity                numeric(12,4) NOT NULL DEFAULT 1,
    unit_price_pesewas      bigint NOT NULL,
    tax_rate_bps            integer NOT NULL DEFAULT 0,
    discount_pesewas        bigint NOT NULL DEFAULT 0,
    total_pesewas           bigint NOT NULL
);
CREATE INDEX idx_merchant_invoice_item_invoice ON merchant_invoice_item(invoice_id);

-- Quotes / Estimates
CREATE TABLE merchant_quote (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL,
    quote_number            varchar(64) NOT NULL,
    customer_id             uuid,
    customer_name           varchar(128) NOT NULL,
    customer_email          varchar(128),
    customer_phone          varchar(32),
    issue_date              date NOT NULL,
    expiry_date             date NOT NULL,
    currency                varchar(3) NOT NULL DEFAULT 'GHS',
    subtotal_pesewas        bigint NOT NULL,
    tax_pesewas             bigint NOT NULL DEFAULT 0,
    discount_pesewas        bigint NOT NULL DEFAULT 0,
    total_pesewas           bigint NOT NULL,
    status                  varchar(32) NOT NULL DEFAULT 'DRAFT', -- DRAFT, SENT, ACCEPTED, DECLINED, CONVERTED, EXPIRED
    converted_invoice_id    uuid REFERENCES merchant_invoice(id),
    notes                   text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_merchant_quote_num UNIQUE (merchant_id, quote_number)
);
CREATE INDEX idx_merchant_quote_merchant ON merchant_quote(merchant_id, status);

-- Quote Line Items
CREATE TABLE merchant_quote_item (
    id                      uuid PRIMARY KEY,
    quote_id                uuid NOT NULL REFERENCES merchant_quote(id) ON DELETE CASCADE,
    item_description        varchar(255) NOT NULL,
    quantity                numeric(12,4) NOT NULL DEFAULT 1,
    unit_price_pesewas      bigint NOT NULL,
    tax_rate_bps            integer NOT NULL DEFAULT 0,
    total_pesewas           bigint NOT NULL
);
CREATE INDEX idx_merchant_quote_item_quote ON merchant_quote_item(quote_id);

-- Credit Notes
CREATE TABLE merchant_credit_note (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL,
    credit_note_number      varchar(64) NOT NULL,
    invoice_id              uuid NOT NULL REFERENCES merchant_invoice(id),
    reason                  varchar(255) NOT NULL,
    amount_pesewas          bigint NOT NULL,
    status                  varchar(32) NOT NULL DEFAULT 'ISSUED', -- ISSUED, APPLIED, VOID
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_merchant_credit_note UNIQUE (merchant_id, credit_note_number)
);
CREATE INDEX idx_merchant_credit_note_invoice ON merchant_credit_note(invoice_id);


-- ============================================================================
-- 2. PAYROLL & SUPPLIER PAYMENTS DOMAIN
-- ============================================================================

-- Employee Master Records
CREATE TABLE payroll_employee (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL,
    employee_code           varchar(32) NOT NULL,
    full_name               varchar(128) NOT NULL,
    national_id             varchar(32),
    tin                     varchar(32),
    ssnit_number            varchar(32),
    phone                   varchar(32),
    email                   varchar(128),
    bank_code               varchar(32),
    bank_account_number     varchar(64),
    momo_network            varchar(32),
    momo_number             varchar(32),
    employment_type         varchar(32) NOT NULL DEFAULT 'FULL_TIME', -- FULL_TIME, PART_TIME, CONTRACT
    base_salary_pesewas     bigint NOT NULL,
    allowances_pesewas      bigint NOT NULL DEFAULT 0,
    tier3_voluntary_rate_bps integer NOT NULL DEFAULT 0,
    reliefs_pesewas         bigint NOT NULL DEFAULT 0,
    is_active               boolean NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_payroll_employee UNIQUE (merchant_id, employee_code)
);
CREATE INDEX idx_payroll_employee_merchant ON payroll_employee(merchant_id, is_active);

-- Monthly Payroll Run
CREATE TABLE payroll_run (
    id                              uuid PRIMARY KEY,
    merchant_id                     uuid NOT NULL,
    period_month                    integer NOT NULL,
    period_year                     integer NOT NULL,
    total_gross_pesewas             bigint NOT NULL DEFAULT 0,
    total_employee_ssnit_pesewas    bigint NOT NULL DEFAULT 0,
    total_employer_ssnit_pesewas    bigint NOT NULL DEFAULT 0,
    total_tier3_pesewas             bigint NOT NULL DEFAULT 0,
    total_paye_tax_pesewas          bigint NOT NULL DEFAULT 0,
    total_net_pay_pesewas           bigint NOT NULL DEFAULT 0,
    status                          varchar(32) NOT NULL DEFAULT 'DRAFT', -- DRAFT, SUBMITTED, APPROVED, EXECUTED, CANCELLED
    initiated_by                    varchar(64) NOT NULL,
    approved_by                     varchar(64),
    executed_at                     timestamptz,
    created_at                      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_payroll_period UNIQUE (merchant_id, period_month, period_year)
);
CREATE INDEX idx_payroll_run_merchant ON payroll_run(merchant_id, status);

-- Itemized Payslips
CREATE TABLE payroll_payslip (
    id                              uuid PRIMARY KEY,
    payroll_run_id                  uuid NOT NULL REFERENCES payroll_run(id) ON DELETE CASCADE,
    employee_id                     uuid NOT NULL REFERENCES payroll_employee(id),
    base_salary_pesewas             bigint NOT NULL,
    allowances_pesewas              bigint NOT NULL DEFAULT 0,
    gross_pay_pesewas               bigint NOT NULL,
    ssnit_tier2_pesewas             bigint NOT NULL DEFAULT 0,
    ssnit_tier3_voluntary_pesewas   bigint NOT NULL DEFAULT 0,
    taxable_income_pesewas          bigint NOT NULL,
    paye_tax_pesewas                bigint NOT NULL DEFAULT 0,
    other_deductions_pesewas        bigint NOT NULL DEFAULT 0,
    net_pay_pesewas                 bigint NOT NULL,
    ssnit_tier1_employer_pesewas    bigint NOT NULL DEFAULT 0,
    payment_status                  varchar(32) NOT NULL DEFAULT 'PENDING', -- PENDING, PAID, FAILED
    payment_method                  varchar(32) NOT NULL, -- MOMO, BANK
    destination_account             varchar(64) NOT NULL,
    payout_reference                varchar(64),
    created_at                      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_payroll_payslip_run ON payroll_payslip(payroll_run_id);
CREATE INDEX idx_payroll_payslip_employee ON payroll_payslip(employee_id);

-- Supplier Master Records
CREATE TABLE supplier_master (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL,
    supplier_code           varchar(32) NOT NULL,
    name                    varchar(128) NOT NULL,
    contact_person          varchar(128),
    email                   varchar(128),
    phone                   varchar(32),
    tin                     varchar(32),
    payment_terms_days      integer NOT NULL DEFAULT 30,
    default_payment_method  varchar(32) NOT NULL DEFAULT 'BANK', -- BANK, MOMO
    bank_code               varchar(32),
    bank_account_number     varchar(64),
    momo_network            varchar(32),
    momo_number             varchar(32),
    is_active               boolean NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_supplier_code UNIQUE (merchant_id, supplier_code)
);
CREATE INDEX idx_supplier_master_merchant ON supplier_master(merchant_id, is_active);

-- Supplier Bills (Accounts Payable)
CREATE TABLE supplier_bill (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL,
    supplier_id             uuid NOT NULL REFERENCES supplier_master(id),
    bill_number             varchar(64) NOT NULL,
    bill_date               date NOT NULL,
    due_date                date NOT NULL,
    currency                varchar(3) NOT NULL DEFAULT 'GHS',
    subtotal_pesewas        bigint NOT NULL,
    tax_pesewas             bigint NOT NULL DEFAULT 0,
    total_amount_pesewas    bigint NOT NULL,
    paid_amount_pesewas     bigint NOT NULL DEFAULT 0,
    status                  varchar(32) NOT NULL DEFAULT 'PENDING_APPROVAL', -- PENDING_APPROVAL, APPROVED, REJECTED, PARTIALLY_PAID, PAID, CANCELLED
    approved_by             varchar(64),
    approved_at             timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_supplier_bill_num UNIQUE (merchant_id, bill_number)
);
CREATE INDEX idx_supplier_bill_merchant ON supplier_bill(merchant_id, status, due_date);
CREATE INDEX idx_supplier_bill_supplier ON supplier_bill(supplier_id);

-- Supplier Bill Line Items
CREATE TABLE supplier_bill_item (
    id                      uuid PRIMARY KEY,
    bill_id                 uuid NOT NULL REFERENCES supplier_bill(id) ON DELETE CASCADE,
    description             varchar(255) NOT NULL,
    quantity                numeric(12,4) NOT NULL DEFAULT 1,
    unit_price_pesewas      bigint NOT NULL,
    total_pesewas           bigint NOT NULL
);
CREATE INDEX idx_supplier_bill_item_bill ON supplier_bill_item(bill_id);

-- Supplier Payment Runs (Automated Batch Payment)
CREATE TABLE supplier_payment_run (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL,
    run_number              varchar(64) NOT NULL,
    total_amount_pesewas    bigint NOT NULL,
    bill_count              integer NOT NULL,
    status                  varchar(32) NOT NULL DEFAULT 'INITIATED', -- INITIATED, APPROVED, EXECUTED, FAILED
    executed_at             timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_supplier_pay_run UNIQUE (merchant_id, run_number)
);
CREATE INDEX idx_supplier_payment_run_merchant ON supplier_payment_run(merchant_id, status);

-- Supplier Payment Allocations
CREATE TABLE supplier_payment_allocation (
    id                      uuid PRIMARY KEY,
    payment_run_id          uuid NOT NULL REFERENCES supplier_payment_run(id) ON DELETE CASCADE,
    bill_id                 uuid NOT NULL REFERENCES supplier_bill(id),
    amount_pesewas          bigint NOT NULL,
    payout_reference        varchar(64),
    status                  varchar(32) NOT NULL DEFAULT 'PENDING', -- PENDING, SUCCESS, FAILED
    created_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_supplier_pay_alloc_run ON supplier_payment_allocation(payment_run_id);
CREATE INDEX idx_supplier_pay_alloc_bill ON supplier_payment_allocation(bill_id);


-- ============================================================================
-- 3. CRM, LOYALTY & MARKETING CAMPAIGNS DOMAIN
-- ============================================================================

-- Merchant Customer Profiles
CREATE TABLE crm_customer (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL,
    customer_code           varchar(32) NOT NULL,
    full_name               varchar(128) NOT NULL,
    phone                   varchar(32) NOT NULL,
    email                   varchar(128),
    address                 varchar(255),
    total_spend_pesewas     bigint NOT NULL DEFAULT 0,
    transaction_count       integer NOT NULL DEFAULT 0,
    last_purchase_at        timestamptz,
    rfm_recency_score       integer DEFAULT 1,
    rfm_frequency_score     integer DEFAULT 1,
    rfm_monetary_score      integer DEFAULT 1,
    rfm_segment             varchar(32) NOT NULL DEFAULT 'NEW_CUSTOMERS', -- CHAMPIONS, LOYAL_CUSTOMERS, POTENTIAL_LOYALISTS, NEW_CUSTOMERS, AT_RISK, CANNOT_LOSE_THEM, HIBERNATING
    loyalty_tier            varchar(32) NOT NULL DEFAULT 'BRONZE',        -- BRONZE, SILVER, GOLD, PLATINUM
    notes                   text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_crm_customer_code UNIQUE (merchant_id, customer_code)
);
CREATE INDEX idx_crm_customer_merchant ON crm_customer(merchant_id, rfm_segment, loyalty_tier);
CREATE INDEX idx_crm_customer_phone ON crm_customer(merchant_id, phone);

-- Customer Loyalty Accounts (Points & Tier)
CREATE TABLE crm_loyalty_account (
    id                          uuid PRIMARY KEY,
    merchant_id                 uuid NOT NULL,
    customer_id                 uuid NOT NULL REFERENCES crm_customer(id) ON DELETE CASCADE,
    points_balance              bigint NOT NULL DEFAULT 0,
    lifetime_points_earned      bigint NOT NULL DEFAULT 0,
    lifetime_points_redeemed    bigint NOT NULL DEFAULT 0,
    current_tier                varchar(32) NOT NULL DEFAULT 'BRONZE',
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_crm_loyalty_customer UNIQUE (merchant_id, customer_id)
);
CREATE INDEX idx_crm_loyalty_account_merchant ON crm_loyalty_account(merchant_id);

-- Loyalty Points Ledger (Earn / Redeem / Expire)
CREATE TABLE crm_loyalty_ledger (
    id                      uuid PRIMARY KEY,
    loyalty_account_id      uuid NOT NULL REFERENCES crm_loyalty_account(id) ON DELETE CASCADE,
    points                  bigint NOT NULL, -- Positive for earned, negative for redeemed
    transaction_type        varchar(32) NOT NULL, -- EARNED, REDEEMED, ADJUSTED, EXPIRED
    reference_type          varchar(32), -- INVOICE, POS_ORDER, MANUAL
    reference_id            uuid,
    description             varchar(255),
    created_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_crm_loyalty_ledger_account ON crm_loyalty_ledger(loyalty_account_id);

-- Gift Cards with Hashed PIN
CREATE TABLE crm_gift_card (
    id                          uuid PRIMARY KEY,
    merchant_id                 uuid NOT NULL,
    card_code                   varchar(32) NOT NULL,
    pin_hash                    varchar(255) NOT NULL,
    initial_balance_pesewas     bigint NOT NULL,
    current_balance_pesewas     bigint NOT NULL,
    currency                    varchar(3) NOT NULL DEFAULT 'GHS',
    recipient_name              varchar(128),
    recipient_phone             varchar(32),
    status                      varchar(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, REDEEMED, EXPIRED, CANCELLED
    expires_at                  timestamptz NOT NULL,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_crm_gift_card UNIQUE (merchant_id, card_code)
);
CREATE INDEX idx_crm_gift_card_merchant ON crm_gift_card(merchant_id, status);

-- Dynamic Coupons & Promotion Rules
CREATE TABLE crm_coupon (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL,
    code                    varchar(32) NOT NULL,
    discount_type           varchar(32) NOT NULL, -- PERCENTAGE, FIXED_AMOUNT
    discount_value          bigint NOT NULL,      -- bps for percentage or pesewas for fixed
    min_order_pesewas       bigint NOT NULL DEFAULT 0,
    max_discount_pesewas    bigint NOT NULL DEFAULT 0, -- 0 = no cap
    usage_limit             integer NOT NULL DEFAULT 0, -- 0 = unlimited
    times_used              integer NOT NULL DEFAULT 0,
    per_customer_limit      integer NOT NULL DEFAULT 1,
    starts_at               timestamptz NOT NULL DEFAULT now(),
    expires_at              timestamptz NOT NULL,
    is_active               boolean NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_crm_coupon_code UNIQUE (merchant_id, code)
);
CREATE INDEX idx_crm_coupon_merchant ON crm_coupon(merchant_id, is_active, expires_at);

-- Customer Store Credit Balance Account
CREATE TABLE crm_store_credit_account (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL,
    customer_id             uuid NOT NULL REFERENCES crm_customer(id) ON DELETE CASCADE,
    balance_pesewas         bigint NOT NULL DEFAULT 0,
    currency                varchar(3) NOT NULL DEFAULT 'GHS',
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_crm_store_credit_customer UNIQUE (merchant_id, customer_id)
);
CREATE INDEX idx_crm_store_credit_account_merchant ON crm_store_credit_account(merchant_id);

-- Customer Store Credit Balance Ledger
CREATE TABLE crm_store_credit_ledger (
    id                          uuid PRIMARY KEY,
    store_credit_account_id     uuid NOT NULL REFERENCES crm_store_credit_account(id) ON DELETE CASCADE,
    amount_pesewas              bigint NOT NULL, -- Positive for credit, negative for debit
    entry_type                  varchar(32) NOT NULL, -- CREDIT_ADDED, CREDIT_USED, REFUND_DEPOSIT, EXPIRED
    reference                   varchar(64),
    description                 varchar(255),
    balance_after_pesewas       bigint NOT NULL,
    created_at                  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_crm_store_credit_ledger_account ON crm_store_credit_ledger(store_credit_account_id);

-- Marketing Campaigns & Dispatch
CREATE TABLE crm_campaign (
    id                      uuid PRIMARY KEY,
    merchant_id             uuid NOT NULL,
    name                    varchar(128) NOT NULL,
    channel                 varchar(32) NOT NULL, -- SMS, WHATSAPP
    target_segment          varchar(32) NOT NULL, -- ALL, CHAMPIONS, LOYAL_CUSTOMERS, AT_RISK, NEW_CUSTOMERS, HIBERNATING
    message_template        text NOT NULL,
    scheduled_at            timestamptz,
    status                  varchar(32) NOT NULL DEFAULT 'DRAFT', -- DRAFT, SCHEDULED, DISPATCHED, CANCELLED
    sent_count              integer NOT NULL DEFAULT 0,
    failed_count            integer NOT NULL DEFAULT 0,
    created_at              timestamptz NOT NULL DEFAULT now(),
    dispatched_at           timestamptz
);
CREATE INDEX idx_crm_campaign_merchant ON crm_campaign(merchant_id, status);
