# Project: ELEPAY Core

## Architecture
ELEPAY is a Ghana-first financial platform designed as a modular monolith in Java/Spring Boot and TypeScript/React, backed by PostgreSQL as the authoritative transactional database.

### Core Modules and System Boundaries:
- `com.elepay.ledger`: Authoritative double-entry ledger with append-only immutable postings, per-currency zero-sum balance triggers, and ID-ordered row locking.
- `com.elepay.wallet`: Customer wallets, account lookups, single-use bound confirmations, KYC tier limits, transfers, statements.
- `com.elepay.checkout`: Multi-allocation commercial checkout (merchant/delivery split), payment links, dynamic EMVCo QR codes, settlement batching, line-level refunds, and disputes.
- `com.elepay.payout`: Single and batch payouts (up to 500 items), available balance reservation, safe rail retries (`retry_of`), and settlement execution.
- `com.elepay.fx`: Multi-currency corridors, locked quotes with TTL, conversion with explicit rounding, per-currency clearing accounts, and outbound/inbound remittances.
- `com.elepay.rails`: Direct partner network adapters (MTN, Telecel, AirtelTigo, GHIPSS/NIP, Card acquirers) with controlled simulators, pre-submission intent, 7-state lifecycle, and unknown-outcome recovery. (Paystack and Hubtel strictly excluded).
- `com.elepay.identity`: Authentication, API keys, scopes, merchant delegations, actor resolution, USSD PIN hashing (BCrypt) with lockout.
- `com.elepay.risk`: Velocity tracking, declarative rules engine, fraud scoring, SIM-swap checks, geo-velocity, mule graph detection, and ML risk models.
- `com.elepay.compliance`: Audited lookups, KYC limits, Ghana Card verification, fuzzy sanctions screening (Jaro-Winkler/Soundex), PEP checks, structuring detection, AML cases, and BoG/FIC reporting.
- `com.elepay.reconciliation`: Exact 4-way statement matching, fuzzy statement matching, partner fee audits, suspense aging/clearing, settlement netting, and float management.
- `com.elepay.credit`: Cash-flow underwriting, EWA eligibility, MCA pricing, repayment sweeps (disabled for live use until authorized).
- `com.elepay.outbox`: Transactional outbox pattern and idempotent consumer inbox for reliable domain event dispatch.
- `com.elepay.operations`: Operations console backend, ops items queue, maker-checker approvals, and operational health metrics.
- `com.elepay.ussd`: Telco USSD session menu state machine with signed callbacks and secure PIN verification.
- `web/`: React 19 + TypeScript frontend with Customer Wallet (`/app`), Merchant Dashboard (`/merchant`), Operations Console (`/ops`), and Checkout (`/pay/*`).

## Code Layout
- Java Backend: `src/main/java/com/elepay/`
- Java Tests: `src/test/java/com/elepay/`
- Database Migrations: `src/main/resources/db/migration/` (`V1__*.sql` through `V12__*.sql`)
- Web Frontend: `web/src/`
- Scripts & Runbooks: `scripts/`, `docs/runbooks/`
- Specification & Docs: `docs/product-specification.md`, `docs/openapi.json`, `docs/readiness-scorecard.md`

## Feature Inventory (100 Algorithms & Capabilities)

| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 001 | Double-entry posting | Atomic, balanced journal postings per currency with account & currency validation | M1 | Survey #001 |
| 002 | Ordered row locking | Deterministic lock acquisition (ID order) preventing race conditions and deadlocks | M1 | Survey #002 |
| 003 | Idempotent request hashing | Request canonicalization, SHA-256 fingerprinting, returning cached response or 409 | M1 | Survey #003 |
| 004 | Integer proportional allocation | Largest remainder / deterministic minor unit distribution without loss | M1 | Survey #004 |
| 005 | Linked reversals | Balanced reversal journal postings linked to original with duplicate protection | M1 | Survey #005 |
| 006 | Per-currency FX clearing | Balanced cross-currency movements via explicit clearing accounts | M1 | Survey #006 |
| 007 | Fee schedule engine | Versioned fixed, percentage (bps), tiered, minimum/capped fee calculation | M1 | Survey #007 |
| 008 | Interest and yield accrual | Day-count conventions, eligibility, rounding (disabled for live use) | M6 | Survey #008 |
| 009 | Revenue recognition | Distinguishes collected fees, deferred balances, earned platform revenue | M2 | Survey #009 |
| 010 | Balance snapshots | Verifiable point-in-time balance snapshots with journal consistency proof | M1 | Survey #010 |
| 011 | Duplicate-safe rail submission | Durable intent, stable references, partner idempotency, explicit submission states | M4 | Survey #011 |
| 012 | Unknown-outcome recovery | Queries unresolved transactions, reconciles evidence, prevents unsafe resubmission | M4 | Survey #012 |
| 013 | Callback state machine | Authenticates events, deduplicates callbacks, enforces valid transitions under reordering | M4 | Survey #013 |
| 014 | Smart payment routing | Selects optimal route based on cost, health, capability, and success rates | M4 | Survey #014 |
| 015 | Safe failover routing | Switches rail only when primary is definitively unsubmitted or failed without money movement | M4 | Survey #015 |
| 016 | Network health detection | Sliding window health signals, error rates, latencies, recovery probes | M4 | Survey #016 |
| 017 | Card BIN lookup | Offline versioned BIN database resolving card scheme, issuer bank, country | M4 | Survey #017 |
| 018 | Risk-based 3-D Secure | 3DS 2.x protocol challenge/frictionless flow, preserving directory server & liability tokens | M4 | Survey #018 |
| 019 | Card tokenization boundary | Token lifecycle and isolated storage preventing raw PAN exposure | M4 | Survey #019 |
| 020 | Recurring billing mandates | Mandate consent, frequency, amount limits, cancellation, duplicate charge protection | M2 | Survey #020 |
| 021 | Dunning schedules | Bounded retry schedules with smart backoff and customer notices | M2 | Survey #021 |
| 022 | EMVCo QR encoding | Static and dynamic merchant QR generation with CRC16 checksum validation | M2 | Survey #022 |
| 023 | Velocity rules | Rolling counter rules (count, amount) across sliding windows (1h, 24h, 7d) | M5 | Survey #023 |
| 024 | Rules engine and risk score | Versioned declarative rules producing numeric risk scores and auditable trees | M5 | Survey #024 |
| 025 | Device risk signals | Privacy-conscious device fingerprinting evaluating risk confidence | M5 | Survey #025 |
| 026 | SIM-swap checks | Integration with telco SIM status APIs detecting IMSI change age | M5 | Survey #026 |
| 027 | Geo-velocity checks | Great-circle distance vs time detecting impossible travel | M5 | Survey #027 |
| 028 | Account takeover detection | Correlated session, credential, device, and withdrawal risk signals | M5 | Survey #028 |
| 029 | Adaptive step-up authentication | Dynamic challenge issuance bound cryptographically to action payload | M5 | Survey #029 |
| 030 | Merchant risk scoring | Continuous evaluation of merchant volume, refund ratio, and dispute rates | M5 | Survey #020 |
| 031 | Promotion and referral abuse | Graph analysis detecting sybil accounts and circular referral farming | M5 | Survey #031 |
| 032 | Mule-account graph detection | Transaction relationship graph analysis identifying pass-through money mules | M5 | Survey #032 |
| 033 | Synthetic identity detection | Anomaly detection cross-referencing national ID, TIN, and phone history | M5 | Survey #033 |
| 034 | Behavioral biometrics | Passive interaction telemetry (keystroke dynamics, pointer cadence) | M5 | Survey #034 |
| 035 | Gradient-boosted fraud model | ML classification pipeline with versioned features (experimental on synthetic data) | M6 | Survey #035 |
| 036 | Chargeback prediction | Calibrated risk model estimating probability of dispute prior to settlement | M6 | Survey #036 |
| 037 | Rate-limited audited lookups | Sender-scoped rate limits (10/min, 100/day, 10 misses/hr) with hash-only logging | M1 | Survey #037 |
| 038 | Bound single-use confirmations | Ephemeral 5-minute tokens binding sender, recipient, amount, currency, and rail | M1 | Survey #038 |
| 039 | KYC tier limits | Configurable Tier 1-3 balance and cumulative daily/monthly transaction limits | M1 | Survey #039 |
| 040 | Ghana Card matching | Verification channel against NIA database with explicit simulation tagging | M5 | Survey #040 |
| 041 | Face matching and liveness | Biometric photo matching against national ID selfie with anti-spoofing liveness | M5 | Survey #041 |
| 042 | Fuzzy sanctions screening | Exact matching + Jaro-Winkler + Soundex against UN/OFAC/Ghana sanctions lists | M5 | Survey #042 |
| 043 | PEP screening | Politically Exposed Persons database screening with senior sign-off workflow | M5 | Survey #043 |
| 044 | Structuring detection | Smurfing detection across rolling windows for transactions under reporting thresholds | M5 | Survey #044 |
| 045 | AML transaction monitoring | Scenario-based detection rules generating auditable AML review cases | M5 | Survey #045 |
| 046 | Alert prioritization | Multi-factor queue prioritizing compliance cases by exposure, confidence, and age | M5 | Survey #046 |
| 047 | Regulatory report generation | Automated generation of Bank of Ghana (BoG) and FIC STR/CTR export filings | M5 | Survey #047 |
| 048 | Retention and redaction | Statutory retention policy engine, legal holds, and GDPR/data protection redaction | M5 | Survey #048 |
| 049 | Exact statement matching | 4-way matching of external bank/rail statement lines to internal transactions | M2 | Survey #049 |
| 050 | Ledger reconstruction checks | Rebuilding balance from journal entries proving `projected_balance == journal_balance` | M1 | Survey #050 |
| 051 | Settlement batching | Immutable batching of eligible merchant proceeds with delay hours and deduplication | M2 | Survey #051 |
| 052 | Fuzzy statement matching | Heuristic matching with timestamp windows and fee tolerances for ambiguous statements | M2 | Survey #052 |
| 053 | Partner fee verification | Reconciles deducted network fees against contractual fee matrices | M2 | Survey #053 |
| 054 | Suspense aging and clearing | Tracking unallocated balances in suspense with maker-checker clearance | M2 | Survey #054 |
| 055 | Settlement netting | Bilateral/multilateral net obligations within legal entity and currency boundaries | M2 | Survey #055 |
| 056 | Float forecasting | Predictive modeling of mobile money and bank float requirements across calendar windows | M6 | Survey #056 |
| 057 | Float rebalancing | Policy-controlled inter-float recommendations and automated rebalancing transfers | M6 | Survey #057 |
| 058 | Merchant cash-flow forecasting | Merchant analytics projecting daily available funds and payout curves | M6 | Survey #058 |
| 059 | Rate sourcing and blending | Multi-provider FX rate ingestion, outlier rejection, and mid-market rate synthesis | M3 | Survey #059 |
| 060 | Dynamic FX margin pricing | Tiered margin calculation over base rates producing locked quotes with TTL | M3 | Survey #060 |
| 061 | Corridor routing | Payout partner route selection based on corridor support, cost, liquidity, and speed | M3 | Survey #061 |
| 062 | Exposure limits and hedging triggers | Real-time net open foreign currency exposure tracking with hedging triggers | M3 | Survey #062 |
| 063 | Delivery-time estimation | Empirical delivery time estimation communicating realistic SLA to senders | M3 | Survey #063 |
| 064 | Multi-currency partner reconciliation | Dual-currency reconciliation verifying source debits, destination credits, and fees | M3 | Survey #064 |
| 065 | Cash-flow underwriting | Creditworthiness assessment based on transaction turnover, consistency, and refunds | M6 | Survey #065 |
| 066 | Earned-wage access eligibility | Accrued salary calculation, existing deductions, and advance limits | M6 | Survey #066 |
| 067 | Overdraft eligibility scoring | Wallet overdraft facility assessment based on deposit cadence | M6 | Survey #067 |
| 068 | Merchant cash-advance pricing | Fixed-fee factor rate pricing, repayment percentage, and full APR disclosure | M6 | Survey #068 |
| 069 | Repayment scheduling and sweeps | Automatic daily percentage sweeps from settled merchant checkout proceeds | M6 | Survey #069 |
| 070 | Collections prioritization | Triage of delinquent balances by recovery probability and exposure | M6 | Survey #070 |
| 071 | Default prediction | Calibrated ML model estimating loan default risk | M6 | Survey #071 |
| 072 | Dynamic credit limits | Real-time credit line adjustment based on real-time sales health | M6 | Survey #072 |
| 073 | HMAC verification | Constant-time signature verification for webhooks and partner callbacks | M1 | Survey #073 |
| 074 | PIN hashing and lockout | BCrypt PIN hashing with sequential/repeated digit rejection and 30m lockout | M1 | Survey #074 |
| 075 | Field-level envelope encryption | AES-GCM-256 field encryption using data keys wrapped by master KMS keys | M5 | Survey #075 |
| 076 | Key rotation | Re-encryption workflow supporting active key rollover while preserving historical reads | M5 | Survey #076 |
| 077 | Request signing & replay protection | Client request signature verification using timestamp windows and nonces | M2 | Survey #077 |
| 078 | JWKS caching and rollover | OIDC public key retrieval, bounded caching, and asynchronous rollover | M5 | Survey #078 |
| 079 | Maker-checker approvals | Dual-control enforcement where privileged operations require 2 distinct actors | M2 | Survey #079 |
| 080 | Tamper-evident audit records | Hash-chained audit logs (`prev_hash + payload`) with verifiable checkpoints | M1 | Survey #080 |
| 081 | Administrative anomaly detection | Real-time monitoring of privileged operator activity for anomalous behavior | M5 | Survey #081 |
| 082 | Session and device binding | Refresh token rotation and device binding with instant revocation on reuse | M5 | Survey #082 |
| 083 | Mobile certificate pinning | TLS public key hash pinning in Android and iOS with backup pins and rotation | M5 | Survey #083 |
| 084 | CI security scanning | Automated static analysis, secret scanning, dependency audit, and container gates | M5 | Survey #084 |
| 085 | Transactional outbox & inbox dedupe | Transactional persistence of domain events with idempotent message processing | M1 | Survey #085 |
| 086 | Partner circuit breakers | Resilience circuit breakers tracking error rates, opening on failures, half-open probe | M5 | Survey #086 |
| 087 | Backoff and jitter | Exponential backoff with Full Jitter for network retries | M5 | Survey #087 |
| 088 | Token-bucket rate limiting | Distributed token bucket rate limiters protecting public and merchant APIs | M1 | Survey #088 |
| 089 | Load shedding and backpressure | System prioritization shedding non-critical traffic under high load | M5 | Survey #089 |
| 090 | SLO burn-rate alerting | Multi-window multi-burn-rate alerting for 99.95% availability & latency budget | M5 | Survey #090 |
| 091 | Success-rate anomaly detection | Statistical anomaly detection on checkout/transfer conversion rates | M5 | Survey #091 |
| 092 | Scaling signals | Custom autoscaling metrics (connection pool, outbox lag, queue depth) | M5 | Survey #092 |
| 093 | Fault injection | Chaos testing harness simulating partitions, slow queries, and lost callbacks | M5 | Survey #093 |
| 094 | Database failover recovery | Transparent reconnection and transaction retry across PostgreSQL failover | M5 | Survey #094 |
| 095 | Exact phone normalization | Canonical E.164 normalization for Ghana (`024...` -> `+23324...`) and international | M1 | Survey #095 |
| 096 | Spending categorization | Explainable categorization of customer transactions without altering ledger records | M6 | Survey #096 |
| 097 | Savings goals and round-ups | Voluntary transaction round-ups and automated recurring savings sweeps | M6 | Survey #097 |
| 098 | Churn prediction | Privacy-conscious survival/classification model predicting dormancy | M6 | Survey #098 |
| 099 | Notification timing | Smart delivery queue respecting quiet hours and priority channels | M6 | Survey #099 |
| 100 | Personalized limits | Dynamic risk-based limits tailored to usage history under policy ceilings | M6 | Survey #100 |

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Core Financial Journey & Ledger Hardening | Setup toolchain / compilation fixes, algorithms 001-007, 010, 037-039, 050, 073-074, 080, 085, 088, 095. Complete 12-step wallet transfer flow. | None | DONE (Gate passed 5/5 verifiers, 18 algorithms Built, commits: 65d3279, 0884620) |
| M2 | Commercial Checkout, Refunds, Disputes & Settlement | Algorithms 009, 020-022, 049, 051-055, 077, 079. Multi-allocation checkout, payment links, QR, settlement batches, disputes, maker-checker. | M1 | DONE (12 algorithms Built, commits: 53b229b, 11b69f9) |
| M3 | FX, Cross-Border Remittances & Payouts | Algorithms 059-064, Payout batching, multi-currency clearing, quote engine, corridor routing. | M1 | DONE (6 algorithms Built, commits: b78a96a, 7b642e5) |
| M4 | Direct Partner Rail Adapters & Simulators | Algorithms 011-019. Direct MoMo (MTN, Telecel, AirtelTigo), GHIPSS/NIP, Card acquirer simulators, tokenization boundary. Paystack/Hubtel excluded. | M1 | DONE (9 algorithms Built, commits: 87832d6, 997ac14) |
| M5 | Risk, Fraud, Compliance & Platform Security | Algorithms 023-034, 040-048, 075-076, 078, 081-084, 086-087, 089-094. Sanctions, PEP, AML, Ghana Card, envelope encryption, circuit breakers, rate limiting. | M1, M2 | DONE (30 algorithms Built, M5A & M5B merged, commits: 8f88813, 708ebec) |
| M6 | Advanced Analytics, Credit Models, Growth & Final Readiness | Algorithms 008, 035-036, 056-058, 065-072, 096-100. Credit engine (disabled live), ML models, float forecasting, scorecard completion, E2E 100% pass, adversarial hardening. | M1-M5, TEST_READY | DONE (25 algorithms Built, commits: 8b61d35, 16e61d2; 100/100 Algorithms Complete) |

## Interface Contracts

### Ledger ↔ Wallet
- `Ledger.post(Posting posting)`: Atomically validates per-currency balance = 0, checks overdraft constraints, acquires ordered row locks, inserts journal entries, and updates balances.
- `Wallets.send(UUID senderId, SendRequest req)`: Verifies recipient confirmation token, locks sender/recipient, calls `Ledger.post()`, inserts `wallet_operation`, returns `ConfirmedReceipt`.

### Wallet ↔ Compliance
- `RecipientConfirmations.lookup(UUID senderId, String phone)`: Rate limits (10/min, 100/day, 10 misses/hr), hashes destination, checks KYC status, returns masked name + ephemeral 5-min `confirmationId`.
- `TransferLimits.check(UUID customerId, int kycTier, long amountPesewas)`: Enforces per-transaction, daily rolling, and velocity limits per KYC tier.

### Checkout ↔ Ledger & Merchant
- `CheckoutService.payFromWallet(UUID checkoutId, UUID customerId)`: Debits customer available, credits merchant pending/delivery pending, records customer fee to system revenue.
- `CheckoutService.settleBatch(UUID merchantId)`: Batch transfers due funds from merchant pending to merchant available after `settlement_delay_hours`.

### Rails ↔ Adapters
- `PaymentRail`: Abstract contract for `submitCharge`, `submitPayout`, `submitRefund`, `queryStatus`, `verifyCallback`.
- Strict exclusion: No Paystack or Hubtel adapters allowed in registry.


# Phase 2: Gap Closure and Fintech Expansion

## Architecture Expansion
Extending ELEPAY into a comprehensive, independent financial and business management platform across 15 critical enterprise domains while preserving core double-entry accounting and ledger immutability.

## Expansion Streams

| Stream | Name | Domains Covered | Scope / Package | Migration | Status |
|---|---|---|---|---|---|
| S1 | In-Store Commerce & POS | Domains 1, 3, 4: ELEPAY POS, Inventory & Warehouses, Restaurant & KDS | `com.elepay.pos`, `com.elepay.inventory`, `com.elepay.restaurant` | `V22__pos_inventory_restaurant.sql` | IN_PROGRESS |
| S2 | Business Accounting, Invoicing, Payroll & CRM | Domains 5, 6, 7: Merchant GL, Invoices, AR/AP, Payroll, Statutory Deductions, CRM, Loyalty, Gift Cards | `com.elepay.accounting`, `com.elepay.payroll`, `com.elepay.crm` | `V23__accounting_payroll_crm.sql` | IN_PROGRESS |
| S3 | Fintech Expansion | Domains 8, 9, 10, 11: Lending Lifecycle, Bill Payments (Airtime/Utilities), Card Issuing, Agent Banking | `com.elepay.lending`, `com.elepay.bills`, `com.elepay.cards`, `com.elepay.agents` | `V24__fintech_expansion.sql` | IN_PROGRESS |
| S4 | Developer Platform & Responsive Web | Domains 2, 12, 13: BackOffice, Developer Portal, Webhook Replay, WooCommerce Plugin, Wallet/Merchant/Ops/Checkout UI | `com.elepay.developer`, `web/src/` | `V25__developer_portal.sql` | IN_PROGRESS |
| S5 | Financial Hardening & Production Infra | Domains 14, 15: Security Defect Fixes, Request Signing, Signing Secrets, Distributed Controls, Redis, OTEL, CI/CD | `com.elepay.common`, `com.elepay.identity`, `com.elepay.reliability` | `V26__security_hardening.sql` | IN_PROGRESS |
