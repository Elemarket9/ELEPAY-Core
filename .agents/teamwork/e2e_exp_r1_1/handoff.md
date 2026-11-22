# ELEPAY Core E2E Testing Track — Explorer 1 Handoff Report
**Author:** Explorer 1 (E2E Testing Track)  
**Date:** 2026-11-22  
**Target Repository:** `Elemarket9/ELEPAY-Core`  
**State Folder:** `/home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/e2e_exp_r1_1`  
**Reference Artifacts:** `ORIGINAL_REQUEST.md`, `PROJECT.md`, `docs/product-specification.md`, `docs/openapi.json`, `docs/readiness-scorecard.md`, `scripts/smoke.py`, `scripts/acceptance.py`

---

## 1. Executive Summary & Test Architecture Philosophy

### 1.1 Mission & Scope
As Explorer 1 for the E2E Testing Track, this report surveys all **100 algorithms and capabilities** across the 10 core functional domains of ELEPAY Core:
1. **A. Ledger and Money** (Algorithms 001–010)
2. **B. Payment Processing** (Algorithms 011–022)
3. **C. Fraud and Risk** (Algorithms 023–036)
4. **D. Compliance Controls** (Algorithms 037–048)
5. **E. Reconciliation and Treasury** (Algorithms 049–058)
6. **F. FX and Cross-Border** (Algorithms 059–064)
7. **G. Credit** (Algorithms 065–072)
8. **H. Security** (Algorithms 073–084)
9. **I. Reliability and Platform** (Algorithms 085–094)
10. **J. Customer and Growth** (Algorithms 095–100)

This handoff details the test design for:
- **Tier 1: Feature Coverage (Happy-Path):** At least 5 detailed test cases per functional domain (50 test cases total).
- **Tier 2: Boundary & Corner Cases (Negative/Edge):** At least 5 detailed test cases per functional domain (50 test cases total).
- **Total Test Cases Specified:** **100 fully-specified opaque-box E2E test cases** with exact HTTP endpoints, methods, headers, JSON payloads, expected status codes, and assertion logic.

### 1.2 Opaque-Box & Financial Invariant Principles
1. **Strict Opaque-Box Execution:** All tests interact exclusively through authenticated HTTP/REST endpoints (`/v1/...`, `/dev/token`, `/sandbox/...`), webhooks, and signed gateway callbacks. No test depends on internal JVM reflection or private database writes.
2. **Deterministic Financial Invariants:**
   - Every financial operation uses integer minor units (pesewas for GHS, cents for USD/EUR/GBP).
   - Zero-sum per-currency journal posting invariant verified after test flows via `GET /v1/ops/ledger/integrity`.
   - Never allow negative balances on customer or merchant available balances (`CHECK (allow_negative OR balance >= 0)`).
   - Authoritative idempotency enforcement: identical retries return identical responses (`200 OK`); modified payloads with the same idempotency key return `409 Conflict`.
   - Single-use, cryptographically bound transaction confirmations (5-minute TTL, bound to sender, recipient, amount, currency, and rail).

---

## 2. Comprehensive 100-Algorithm Survey & Domain Mapping

| Domain | Algorithm ID & Name | Scope / Current Status | Primary Opaque-Box API Entry Point |
|---|---|---|---|
| **A. Ledger & Money** | 001. Double-entry posting | Atomic balanced postings per currency | `POST /v1/wallet/transfers`, `GET /v1/ops/ledger/integrity` |
| | 002. Ordered row locking | Deterministic lock acquisition (ID order) | `POST /v1/checkouts/{ref}/pay/wallet` |
| | 003. Idempotent request hashing | Canonicalization & fingerprint rejection | Header `Idempotency-Key` across all mutating financial POSTs |
| | 004. Integer proportional allocation | Largest remainder minor unit split | `POST /v1/merchants/{id}/checkouts/{ref}/allocations/{line}/refunds` |
| | 005. Linked reversals | Reversals linked to original, once-only | `POST /v1/merchants/{id}/checkouts/{ref}/allocations/{line}/refunds` |
| | 006. Per-currency FX clearing | Balanced cross-currency clearing | `POST /v1/fx/transfers`, `POST /v1/fx/partners/{partner}/inbound` |
| | 007. Fee schedule engine | Versioned fixed + percentage (bps) fee | `POST /v1/ops/merchants/{id}/review`, `GET /v1/public/fees/quote` |
| | 008. Interest & yield accrual | Day-count conventions (disabled live) | `POST /v1/ops/interest/accrue` (ops controlled) |
| | 009. Revenue recognition | Distinguish fees, deferred, earned | `GET /v1/ops/ledger/integrity`, `GET /v1/merchants/{id}/balances` |
| | 010. Balance snapshots | Verifiable point-in-time balance proofs | `GET /v1/wallet/statement`, `GET /v1/merchants/{id}/balances` |
| **B. Payment Processing** | 011. Duplicate-safe rail submission | Pre-submission intent & idempotency | `POST /v1/public/checkouts/{ref}/pay`, `POST /v1/wallet/topups` |
| | 012. Unknown-outcome recovery | Reconcile unresolved pending rails | `POST /v1/ops/rails/recover` |
| | 013. Callback state machine | Deduplicated, reorder-safe callbacks | `POST /v1/rails/{rail}/callbacks` |
| | 014. Smart payment routing | Route selection by cost and success | `POST /v1/public/checkouts/{ref}/pay` |
| | 015. Safe failover routing | Failover only when definitively unsubmitted | `POST /v1/public/checkouts/{ref}/pay` |
| | 016. Network health detection | Sliding window health and error tracking | `GET /v1/ops/overview` |
| | 017. Card BIN lookup | Offline versioned BIN database lookup | `POST /v1/public/checkouts/{ref}/pay` |
| | 018. Risk-based 3-D Secure | 3DS 2.x protocol challenge / frictionless | `POST /v1/public/checkouts/{ref}/pay` |
| | 019. Card tokenization boundary | Isolated token storage, PAN masking | `POST /v1/public/checkouts/{ref}/pay` |
| | 020. Recurring billing mandates | Consent, frequency, duplicate protection | `POST /v1/merchants/{id}/mandates` |
| | 021. Dunning schedules | Bounded retry schedules with smart backoff | `POST /v1/merchants/{id}/mandates/dunning/run` |
| | 022. EMVCo QR encoding | Static/dynamic QR with CRC16 validation | `GET /v1/merchants/{id}/qr` |
| **C. Fraud and Risk** | 023. Velocity rules | Sliding window (1h, 24h, 7d) limits | `POST /v1/wallet/transfers/confirmed` |
| | 024. Rules engine & risk score | Versioned declarative risk rules | `POST /v1/public/checkouts/{ref}/pay` |
| | 025. Device risk signals | Privacy-conscious device fingerprinting | Headers `X-Device-Fingerprint`, `User-Agent` |
| | 026. SIM-swap checks | IMSI change age verification | `POST /v1/wallet/transfers/confirmed` |
| | 027. Geo-velocity checks | Impossible travel detection (distance/time) | Headers `X-Forwarded-For`, `X-Geo-Location` |
| | 028. Account takeover detection | Correlated session, device, withdrawal risk | `POST /v1/merchants/{id}/withdrawals` |
| | 029. Adaptive step-up auth | Cryptographically bound challenge | `POST /v1/wallet/transfers/confirmed` |
| | 030. Merchant risk scoring | Volume, refund ratio, dispute rates | `GET /v1/ops/merchants` |
| | 031. Promotion abuse detection | Sybil accounts & circular referral farming | `POST /v1/wallet/promotions/claim` |
| | 032. Mule-account graph detection| Rapid pass-through fund flow analysis | `GET /v1/ops/queue` |
| | 033. Synthetic identity detection| Cross-referencing national ID, TIN, phone | `POST /v1/ops/customers` |
| | 034. Behavioral biometrics | Cadence/keystroke dynamics telemetry | `POST /v1/wallet/transfers/preview` |
| | 035. Gradient-boosted fraud model| ML scoring pipeline (experimental) | `POST /v1/ops/risk/evaluate` |
| | 036. Chargeback prediction | Pre-settlement dispute probability | `GET /v1/merchants/{id}/disputes` |
| **D. Compliance Controls**| 037. Rate-limited audited lookups| 10/min, 100/day, 10 misses/hr rate limit | `POST /v1/wallet/recipients/lookup` |
| | 038. Bound single-use confirms | 5-min ephemeral bound confirmation tokens | `POST /v1/wallet/transfers/confirmed` |
| | 039. KYC tier limits | Daily & per-transaction balance limits | `POST /v1/wallet/transfers/confirmed` |
| | 040. Ghana Card matching | NIA database verification simulation | `POST /v1/ops/customers/{id}/verify-id` |
| | 041. Face matching & liveness | Biometric selfie vs national ID | `POST /v1/ops/customers/{id}/verify-biometrics` |
| | 042. Fuzzy sanctions screening | Jaro-Winkler + Soundex against UN/OFAC | `POST /v1/ops/compliance/screen-sanctions` |
| | 043. PEP screening | Politically Exposed Persons screening | `POST /v1/ops/compliance/screen-pep` |
| | 044. Structuring detection | Smurfing detection under reporting caps | `GET /v1/ops/queue` |
| | 045. AML transaction monitoring | Scenario-based SAR/STR case generation | `GET /v1/ops/queue` |
| | 046. Alert prioritization | Exposure, confidence, and age ranking | `GET /v1/ops/queue` |
| | 047. Regulatory report generation| Bank of Ghana (BoG) / FIC STR export | `GET /v1/ops/reports/bog-fic` |
| | 048. Retention and redaction | Statutory retention & legal holds | `POST /v1/ops/compliance/retention/apply` |
| **E. Reconciliation & Treasury**| 049. Exact statement matching | 4-way matching of external statement lines | `POST /v1/ops/fx/reconcile/{partner}`, `/v1/ops/reconciliation/runs` |
| | 050. Ledger reconstruction checks| `projected_balance == journal_balance` | `GET /v1/ops/ledger/integrity` |
| | 051. Settlement batching | Immutable batches with delay hours | `POST /v1/ops/settlements/run` |
| | 052. Fuzzy statement matching | Heuristic matching with tolerances | `POST /v1/ops/reconciliation/fuzzy-match` |
| | 053. Partner fee verification | Contractual fee audit against statements | `POST /v1/ops/reconciliation/verify-fees` |
| | 054. Suspense aging & clearing | Tracking unallocated balances in suspense | `GET /v1/ops/queue`, `POST /v1/ops/queue/{id}/resolve` |
| | 055. Settlement netting | Bilateral net obligations within entity | `POST /v1/ops/settlements/netting` |
| | 056. Float forecasting | Predictive liquidity for MoMo/bank floats | `GET /v1/ops/treasury/float-forecast` |
| | 057. Float rebalancing | Policy-controlled inter-float rebalance | `POST /v1/ops/treasury/float-rebalance` |
| | 058. Merchant cash-flow forecast| Merchant daily proceeds projection | `GET /v1/merchants/{id}/cashflow-forecast` |
| **F. FX & Cross-Border** | 059. Rate sourcing and blending | Multi-provider FX rate ingestion | `GET /v1/fx/corridors` |
| | 060. Dynamic FX margin pricing | Tiered margin calculation with TTL quote | `POST /v1/fx/quotes` |
| | 061. Corridor routing | Route selection by cost, speed, liquidity | `GET /v1/fx/corridors`, `POST /v1/fx/transfers` |
| | 062. Exposure limits & hedging | Real-time net open foreign currency | `GET /v1/ops/fx/exposure` |
| | 063. Delivery-time estimation | Empirical delivery ETA communicated | `POST /v1/fx/quotes` |
| | 064. Multi-currency recon | Dual-currency statement verification | `POST /v1/ops/fx/reconcile/{partner}` |
| **G. Credit** | 065. Cash-flow underwriting | Underwriting based on sales consistency | `POST /v1/merchants/{id}/credit/underwrite` |
| | 066. Earned-wage access (EWA) | Accrued salary calculation & advance | `POST /v1/wallet/credit/ewa-eligibility` |
| | 067. Overdraft eligibility scoring | Wallet deposit cadence scoring | `GET /v1/wallet/credit/overdraft-score` |
| | 068. Merchant cash-advance (MCA)| Factor rate pricing & APR disclosure | `POST /v1/merchants/{id}/credit/mca-offer` |
| | 069. Repayment sweeps | Automatic daily percentage sweeps | `POST /v1/ops/credit/sweeps/run` |
| | 070. Collections prioritization | Delinquent recovery triage by exposure | `GET /v1/ops/credit/collections-queue` |
| | 071. Default prediction | Calibrated default risk prediction | `POST /v1/ops/credit/predict-default` |
| | 072. Dynamic credit limits | Real-time limit adjustment on sales health| `PUT /v1/merchants/{id}/credit/limit` |
| **H. Security** | 073. HMAC verification | Constant-time HMAC-SHA256 signature | `POST /v1/ussd/generic`, `POST /v1/merchants/{id}/webhooks` |
| | 074. PIN hashing and lockout | BCrypt PIN hashing + 30m lockout on 3 fail| `PUT /v1/wallet/ussd-pin`, `POST /v1/ussd/generic` |
| | 075. Field-level envelope encryption| AES-GCM-256 field encryption with KMS | Sensitive identity/account persistence |
| | 076. Key rotation | Re-encryption workflow with historical read| `POST /v1/ops/security/rotate-keys` |
| | 077. Request signing & replay | Client request signing with timestamp/nonce| Header `X-Elepay-Signature`, `X-Timestamp` |
| | 078. JWKS caching & rollover | OIDC public key retrieval & async rollover | `/dev/token`, OIDC JWT validation |
| | 079. Maker-checker approvals | Dual-control: 2 distinct actors required | `POST /v1/ops/merchants/{id}/review` |
| | 080. Tamper-evident audit records| Hash-chained audit logs (`prev_hash`) | `GET /v1/ops/audit` |
| | 081. Admin anomaly detection | Monitoring privileged operator actions | `GET /v1/ops/queue` |
| | 082. Session & device binding | Refresh token rotation & reuse revocation | `POST /dev/token`, `/v1/auth/session` |
| | 083. Mobile certificate pinning | TLS public key hash pinning | Client transport layer verification |
| | 084. CI security scanning | Automated static/secret analysis gate | Build & CI validation pipeline |
| **I. Reliability & Platform**| 085. Outbox & inbox deduplication| Transactional outbox with idempotent inbox | `GET /v1/merchants/{id}/events` |
| | 086. Partner circuit breakers | Sliding error rate circuit breaker | `POST /v1/public/checkouts/{ref}/pay` |
| | 087. Backoff and jitter | Exponential backoff with Full Jitter | Outbox event delivery retries |
| | 088. Token-bucket rate limiting | Distributed token bucket per actor/IP | `POST /v1/wallet/recipients/lookup` |
| | 089. Load shedding & backpressure | Priority shedding under high queue depth | High load gateway simulation |
| | 090. SLO burn-rate alerting | Multi-window burn rate on 99.95% budget | `/actuator/prometheus`, `GET /v1/ops/overview` |
| | 091. Success-rate anomaly detection| Statistical anomaly on checkout conversion| `GET /v1/ops/overview` |
| | 092. Scaling signals | Pool saturation, outbox lag metrics | `/actuator/metrics` |
| | 093. Fault injection | Chaos simulation (timeouts, drops) | Testcontainers / Network interceptor |
| | 094. Database failover recovery | Transaction retry across PG reconnection | Postgres connection bounce drill |
| **J. Customer & Growth** | 095. Exact phone normalization | E.164 normalization (`024...` -> `+233...`)| `POST /v1/wallet/recipients/lookup` |
| | 096. Spending categorization | Explainable category tags on transactions | `GET /v1/wallet/statement` |
| | 097. Savings goals and round-ups | Transaction round-up & savings sweep | `POST /v1/wallet/savings/goals` |
| | 098. Churn prediction | Survival/classification model on dormancy | `GET /v1/ops/analytics/churn-risk` |
| | 099. Notification timing | Quiet hours & delivery schedule triage | `GET /v1/merchants/{id}/events` |
| | 100. Personalized limits | Policy-constrained limits with history | `GET /v1/wallet`, `PUT /v1/ops/customers/{id}/limits` |

---

## 3. Tier 1: Feature Coverage Test Suite (Happy-Path)

Tier 1 specifies **50 happy-path test cases** (5 per functional module A through J). Every test case is specified with complete request/response contracts, header structures, and verification assertions.

---

### Domain A: Ledger and Money (Tier 1 Happy-Path)

#### `TC-T1-A-01`: Standard Double-Entry Internal Wallet Transfer
- **Algorithm:** Alg #001 (Double-entry posting), Alg #002 (Ordered row locking), Alg #003 (Idempotent hashing)
- **Objective:** Verify atomic, balanced debit of sender available balance and credit of recipient available balance in GHS minor units.
- **Method & Endpoint:** `POST /v1/wallet/transfers/confirmed`
- **Headers:**
  ```http
  Authorization: Bearer <sender_jwt>
  Idempotency-Key: f47ac10b-58cc-4372-a567-0e02b2c3d479
  Content-Type: application/json
  Accept: application/json
  ```
- **Payload:**
  ```json
  {
    "confirmationId": "7b9d6c12-3456-42ab-89cd-1234567890ab",
    "amount": 5000
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response body contains:
     - `status`: `"completed"`
     - `amount`: `5000`
     - `fee`: `0`
     - `total`: `5000`
     - `currency`: `"GHS"`
     - `transactionId`: valid non-null UUID
     - `reference`: non-empty string starting with `"WAL-"` or alphanumeric receipt reference
  2. Sender wallet balance queried via `GET /v1/wallet` reflects exact `5000` pesewas reduction in `available` bucket.
  3. Recipient wallet balance queried via `GET /v1/wallet` reflects exact `5000` pesewas increase in `available` bucket.
  4. Global ledger check `GET /v1/ops/ledger/integrity` returns `{"accountsWithDrift": 0, "unbalancedTransactions": 0}` with zero net across all currencies.

#### `TC-T1-A-02`: Multi-Allocation Split Payment with Fee Attribution
- **Algorithm:** Alg #001 (Double-entry posting), Alg #004 (Integer proportional allocation), Alg #007 (Fee schedule)
- **Objective:** Execute single customer checkout funding two independent sellers and delivery partner with explicit platform revenue recognition.
- **Method & Endpoint:** `POST /v1/checkouts/{reference}/pay/wallet`
- **Headers:**
  ```http
  Authorization: Bearer <sender_jwt>
  Idempotency-Key: a1b2c3d4-e5f6-7890-abcd-ef1234567890
  Content-Type: application/json
  ```
- **Payload:** `{}`
- **Preconditions:** Checkout created via `POST /v1/merchants/{mpId}/checkouts` with subtotal 15,000 pesewas (Seller A: 8,000 @ 150 bps fee = 120; Seller B: 5,000 @ 100 bps fee = 50; Delivery D: 2,000 @ 0 bps fee = 0).
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response `status` is `"paid"`.
  2. Merchant A `pending` balance increases by `7880` (8000 - 120).
  3. Merchant B `pending` balance increases by `4950` (5000 - 50).
  4. Delivery partner D `pending` balance increases by `2000`.
  5. System revenue account (`system:revenue:GHS`) balance increases by exactly `170` (120 + 50).
  6. Customer wallet debited exactly `15000`. Sum of credits equals `7880 + 4950 + 2000 + 170 = 15000`. Balanced to 0.

#### `TC-T1-A-03`: Proportional Fee-Reversing Partial Allocation Refund
- **Algorithm:** Alg #004 (Integer proportional allocation), Alg #005 (Linked reversals), Alg #009 (Revenue recognition)
- **Objective:** Perform allocation-level refund restoring customer balance and proportionally debiting platform revenue and merchant pending.
- **Method & Endpoint:** `POST /v1/merchants/{sellerB_id}/checkouts/{reference}/allocations/1/refunds`
- **Headers:**
  ```http
  Authorization: Bearer <sellerB_jwt>
  Idempotency-Key: c9d8e7f6-a5b4-3210-fedc-ba9876543210
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "amount": 5000,
    "reason": "Item out of stock"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `refundReference`, `amount`: `5000`, `refundedFee`: `50`, `status`: `"completed"`.
  2. Merchant B pending balance decreases by `4950` (returns to `0`).
  3. System revenue account decreases by `50` (fee returned).
  4. Customer wallet available balance increases by `5000`.
  5. Other allocations (Seller A, Delivery D) remain completely untouched and intact.

#### `TC-T1-A-04`: Balance Snapshot Reconstruction & Statement Export
- **Algorithm:** Alg #010 (Balance snapshots), Alg #050 (Ledger reconstruction checks)
- **Objective:** Verify customer statement accurately computes cumulative running balance matching point-in-time journal balance.
- **Method & Endpoint:** `GET /v1/wallet/statement?from=2026-11-01T00:00:00Z&to=2026-11-30T23:59:59Z`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Returns an array of journal statement items ordered chronologically.
  2. Each entry contains `transactionId`, `amount`, `currency`: `"GHS"`, `runningBalance`, `postedAt`, `description`.
  3. The `runningBalance` of the terminal entry strictly equals the `available` balance returned by `GET /v1/wallet`.
  4. `GET /v1/ops/ledger/integrity` confirms `accountsWithDrift: 0`.

#### `TC-T1-A-05`: Public Fee Calculation & Tiered Margin Quotation
- **Algorithm:** Alg #007 (Fee schedule engine)
- **Objective:** Query public dynamic fee quote for varying purchase amounts across payment methods (MoMo, Wallet, Card).
- **Method & Endpoint:** `GET /v1/public/fees/quote?amount=10000&method=momo`
- **Headers:**
  ```http
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains:
     - `method`: `"momo"`
     - `fee`: `>= 0`
     - `total`: `10000 + fee`
  2. Mathematical identity: `response.total == 10000 + response.fee`.
  3. Wallet method (`GET /v1/public/fees/quote?amount=10000&method=wallet`) returns `fee: 0`, `total: 10000` (zero customer fee for wallet payments).

---

### Domain B: Payment Processing (Tier 1 Happy-Path)

#### `TC-T1-B-01`: End-to-End Mobile Money Wallet Top-Up
- **Algorithm:** Alg #011 (Duplicate-safe rail submission), Alg #012 (Unknown-outcome recovery)
- **Objective:** Initiate MoMo wallet funding, submit to rail simulator, recover status, and credit wallet.
- **Method & Endpoint:** `POST /v1/wallet/topups`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Idempotency-Key: topup-seed-001
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "amount": 25000,
    "method": "momo",
    "phone": "0245550005",
    "network": "mtn"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response status is `"pending"`.
  2. Wallet `available` balance is NOT credited yet.
  3. Trigger rail recovery `POST /v1/ops/rails/recover` with ops token.
  4. Follow-up `GET /v1/wallet` shows `available` balance increased by `25000`.
  5. `rail_transaction` status transitioned to `"succeeded"`.

#### `TC-T1-B-02`: Guest Checkout Payment via Simulated Mobile Money Rail
- **Algorithm:** Alg #011 (Rail submission), Alg #013 (Callback state machine)
- **Objective:** Pay public checkout reference via MoMo guest payment and process successful signed rail callback.
- **Method & Endpoint:** `POST /v1/public/checkouts/{reference}/pay`
- **Headers:**
  ```http
  Idempotency-Key: guest-pay-002
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "method": "momo",
    "phone": "0241234567",
    "network": "mtn"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `status`: `"pending"` and a non-null `railReference`.
  2. Post signed callback to `POST /v1/rails/sandbox/callbacks` with `x-sandbox-signature` and status `"SUCCESS"`.
  3. `GET /v1/public/checkouts/{reference}/status` returns `status`: `"paid"`.
  4. Merchant pending balance reflects allocated funds.

#### `TC-T1-B-03`: Dynamic EMVCo QR Code Generation & Validation
- **Algorithm:** Alg #022 (EMVCo QR encoding)
- **Objective:** Generate dynamic merchant EMVCo QR code payload and verify valid format, merchant ID, and CRC16 checksum.
- **Method & Endpoint:** `GET /v1/merchants/{merchantId}/qr`
- **Headers:**
  ```http
  Authorization: Bearer <merchant_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `qrPayload` string and `merchantCode`.
  2. Payload starts with `"000201"` (EMVCo payload format indicator).
  3. Contains Tag 53 (Currency `"936"` or `"GHS"`), Tag 58 (Country `"GH"`), and Tag 63 (valid 4-character hex CRC16).
  4. Checksum matches CRC16-CCITT algorithm calculation.

#### `TC-T1-B-04`: Card Payment Flow with BIN Lookup Metadata
- **Algorithm:** Alg #017 (Card BIN lookup), Alg #019 (Card tokenization boundary)
- **Objective:** Submit guest card payment, verify card scheme and masked PAN resolution without exposing raw PAN.
- **Method & Endpoint:** `POST /v1/public/checkouts/{reference}/pay`
- **Headers:**
  ```http
  Idempotency-Key: card-pay-004
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "method": "card",
    "email": "customer@example.com"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `status`: `"pending"` or `"paid"` (in simulated environment).
  2. Under no circumstance is full PAN or CVV logged or returned.
  3. `checkout` transaction records show tokenized card reference and masked scheme representation.

#### `TC-T1-B-05`: Unknown-Outcome Rail Sweep and Auto-Resolution
- **Algorithm:** Alg #012 (Unknown-outcome recovery), Alg #016 (Network health detection)
- **Objective:** Exercise ops rail recovery endpoint to poll dangling rail transactions and reconcile final state.
- **Method & Endpoint:** `POST /v1/ops/rails/recover`
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `recovered` count (integer `>= 0`).
  2. All previously pending simulated transactions advance to terminal states (`succeeded` or `failed`).
  3. `GET /v1/ops/overview` indicates `rail_open: 0`.

---

### Domain C: Fraud and Risk (Tier 1 Happy-Path)

#### `TC-T1-C-01`: Compliant Transfer within Standard Velocity Limits
- **Algorithm:** Alg #023 (Velocity rules), Alg #024 (Rules engine and risk score)
- **Objective:** Execute standard peer-to-peer transfer well below velocity counters and verify low risk score.
- **Method & Endpoint:** `POST /v1/wallet/transfers/confirmed`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Idempotency-Key: velo-happy-001
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "confirmationId": "<valid_confirmation_uuid>",
    "amount": 2000
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Transfer completes with `status: "completed"`.
  2. Transaction passes through without step-up challenge or compliance holds.
  3. No fraud alert created in `GET /v1/ops/queue`.

#### `TC-T1-C-02`: Verified Recipient Name Lookup with Risk Signals
- **Algorithm:** Alg #025 (Device risk signals), Alg #037 (Audited lookups)
- **Objective:** Perform legitimate destination lookup with client device headers, returning verified recipient name.
- **Method & Endpoint:** `POST /v1/wallet/recipients/lookup`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  User-Agent: Mozilla/5.0 (Android; Mobile) ElepayApp/1.0
  X-Device-Fingerprint: fp-accra-device-9876
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "rail": "wallet",
    "phone": "0241234567"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response status is `"verified"`.
  2. `verifiedName` is properly masked/presented (e.g., `"Ama Mensah"`).
  3. `confirmationId` is a non-null UUID with `expiresAt` set ~300 seconds in the future.
  4. Hash-only destination logged in audit log (raw phone not stored in plaintext audit fields).

#### `TC-T1-C-03`: Plausible Geolocation Transfer (Normal Velocity)
- **Algorithm:** Alg #027 (Geo-velocity checks)
- **Objective:** Submit successive transactions from geographically proximate IP addresses (Accra to Tema) without trigger.
- **Method & Endpoint:** `POST /v1/wallet/transfers/confirmed`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  X-Forwarded-For: 154.160.2.10
  Idempotency-Key: geo-ok-001
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "confirmationId": "<valid_confirmation_uuid>",
    "amount": 1500
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Geo-velocity check calculates speed < 100 km/h between location coordinates.
  2. Payment processes immediately with `status: "completed"`.

#### `TC-T1-C-04`: Clean Merchant Risk Scoring Assessment
- **Algorithm:** Alg #030 (Merchant risk scoring)
- **Objective:** Verify low-risk merchant with zero disputes and low refund ratio maintains clean active status.
- **Method & Endpoint:** `GET /v1/ops/merchants`
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Returns array of merchants.
  2. Established merchant has `status: "active"`.
  3. No pending risk reviews or suspension flags in ops item queue.

#### `TC-T1-C-05`: Legitimate Referral Attribution
- **Algorithm:** Alg #031 (Promotion and referral abuse detection)
- **Objective:** Apply valid referral bonus between two distinct registered customers with independent devices and histories.
- **Method & Endpoint:** `POST /v1/wallet/promotions/claim`
- **Headers:**
  ```http
  Authorization: Bearer <new_customer_jwt>
  Idempotency-Key: promo-claim-001
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "referralCode": "REF-KOFI-123"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Claim accepted, bonus credited to pending/available.
  2. Graph analyzer verifies no circular referral loop and no shared device fingerprint.

---

### Domain D: Compliance Controls (Tier 1 Happy-Path)

#### `TC-T1-D-01`: Tier 1 KYC Transaction Compliance
- **Algorithm:** Alg #039 (KYC tier limits)
- **Objective:** Execute transfer strictly below Tier 1 daily limit (GH₵1,000 / 100,000 pesewas) for newly enrolled customer.
- **Method & Endpoint:** `POST /v1/wallet/transfers/confirmed`
- **Headers:**
  ```http
  Authorization: Bearer <tier1_customer_jwt>
  Idempotency-Key: kyc1-ok-001
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "confirmationId": "<valid_tier1_confirmation>",
    "amount": 25000
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Returns `status: "completed"`.
  2. Cumulative daily spend tracked as 25,000 pesewas against 100,000 limit.
  3. No KYC limit exception raised.

#### `TC-T1-D-02`: Bound Single-Use Confirmation Lifecycle
- **Algorithm:** Alg #038 (Bound single-use confirmations)
- **Objective:** Generate a confirmation preview, inspect exact details, and execute transaction within the 5-minute validity window.
- **Method & Endpoint:** `POST /v1/wallet/transfers/preview`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "confirmationId": "<valid_confirmation_uuid>",
    "amount": 7500
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `confirmationId`, `destination`, `verifiedName`, `amount`: `7500`, `fee`: `0`, `total`: `7500`, `currency`: `"GHS"`, `expiresAt`.
  2. Subsequent `POST /v1/wallet/transfers/confirmed` with same `confirmationId` succeeds with `status: "completed"`.

#### `TC-T1-D-03`: Clean Sanctions and PEP Screening
- **Algorithm:** Alg #042 (Fuzzy sanctions screening), Alg #043 (PEP screening)
- **Objective:** Verify a standard customer name screening returns clean clearance against UN/OFAC/Ghana consolidated lists.
- **Method & Endpoint:** `POST /v1/ops/compliance/screen-sanctions`
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "fullName": "Kwame Mensah",
    "country": "GH"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `matchFound: false`, `confidenceScore: 0.0`, `status: "passed"`.
  2. No sanctions hold placed on customer account.

#### `TC-T1-D-04`: Bank of Ghana (BoG) Regulatory Report Generation
- **Algorithm:** Alg #047 (Regulatory report generation)
- **Objective:** Generate statutory BoG/FIC reporting export covering transaction turnover, dispute counts, and reconciliation health.
- **Method & Endpoint:** `GET /v1/ops/reports/bog-fic?period=2026-Q3`
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response body provides BoG standard schema:
     - `period`: `"2026-Q3"`
     - `totalTurnoverPesewas`: numeric integer `>= 0`
     - `totalTransactions`: numeric integer `>= 0`
     - `disputeCount`: numeric integer `>= 0`
     - `signedChecksum`: non-null sha256 digest
  2. Export status marked as `"generated_for_review"` (never automatically submitted).

#### `TC-T1-D-05`: Controlled Audit Log Query by Subject
- **Algorithm:** Alg #037 (Audited lookups), Alg #080 (Tamper-evident audit records)
- **Objective:** Retrieve ops audit history for an enrolled entity, confirming audit records exist with actor, action, and timestamp.
- **Method & Endpoint:** `GET /v1/ops/audit?subject=customer-kofi-001`
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response is a JSON list of audit entries.
  2. Each entry contains `actor`, `action`, `subject`: `"customer-kofi-001"`, `details`, `created_at`.
  3. Sensitive credentials (PINs, passwords, full PANs) are strictly absent from `details`.

---

### Domain E: Reconciliation and Treasury (Tier 1 Happy-Path)

#### `TC-T1-E-01`: Exact 4-Way Statement Matching Reconciling to Zero Discrepancy
- **Algorithm:** Alg #049 (Exact statement matching), Alg #064 (Multi-currency partner recon)
- **Objective:** Reconcile partner statement lines with internal ledger journal entries matching references, currency, amount, and status.
- **Method & Endpoint:** `POST /v1/ops/fx/reconcile/sim-remit`
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "period": "2026-11-22",
    "lines": [
      {
        "partnerReference": "PARTNER-REF-1001",
        "reference": "FX-TR-001",
        "sourceAmount": 10000,
        "sourceCurrency": "USD",
        "destinationAmount": 155000,
        "destinationCurrency": "GHS",
        "status": "paid"
      }
    ]
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `matched`: `1`, `amountMismatch`: `0`, `statusMismatch`: `0`, `missingInternal`: `0`, `missingPartner`: `0`.
  2. Reconciled run recorded in `GET /v1/ops/reconciliation/runs`.

#### `TC-T1-E-02`: Scheduled Merchant Settlement Batch Execution
- **Algorithm:** Alg #051 (Settlement batching), Alg #009 (Revenue recognition)
- **Objective:** Trigger scheduled settlement moving due pending proceeds into merchant available balance after maturity delay.
- **Method & Endpoint:** `POST /v1/ops/settlements/run`
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "ignoreDelay": true,
    "merchantId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `settledCount`: `>= 1`, `settledAmount`: `> 0`.
  2. Merchant balance queried via `GET /v1/merchants/{id}/balances` shows `pending` decremented and `available` incremented by the exact net settled amount.
  3. `GET /v1/ops/ledger/integrity` confirms 0 drift.

#### `TC-T1-E-03`: Full Ledger Mathematical Reconstruction Proof
- **Algorithm:** Alg #050 (Ledger reconstruction checks)
- **Objective:** Rebuild account balances from the genesis block of journal entries and verify zero discrepancy against stored projections.
- **Method & Endpoint:** `GET /v1/ops/ledger/integrity`
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. `accountsWithDrift`: `0`.
  2. `unbalancedTransactions`: `0`.
  3. `netByCurrency`: Map where `"GHS": 0`, and any active foreign currency (`"USD": 0`, `"EUR": 0`, `"GBP": 0`) is exactly `0`.

#### `TC-T1-E-04`: Settlement Netting Across Marketplace Sellers
- **Algorithm:** Alg #055 (Settlement netting)
- **Objective:** Compute bilateral netting of gross checkout sales against refunds, deductions, and delivery fees.
- **Method & Endpoint:** `GET /v1/merchants/{marketplaceId}/settlements`
- **Headers:**
  ```http
  Authorization: Bearer <mp_owner_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Returns itemized list of settlement allocations.
  2. Sum of net payouts + sum of platform fees equals gross checkout volumes.
  3. Every individual allocation links to its original checkout line reference.

#### `TC-T1-E-05`: Suspense Account Maker-Checker Resolution
- **Algorithm:** Alg #054 (Suspense aging and clearing), Alg #079 (Maker-checker approvals)
- **Objective:** Resolve an unallocated payment trapped in suspense via authorized ops resolution workflow.
- **Method & Endpoint:** `POST /v1/ops/queue/{itemId}/resolve`
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "resolution": "Reconciled to Merchant 9b1deb4d after bank MT940 statement confirmation"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response returns `status: "resolved"`.
  2. Ops queue item status changes from `"open"` to `"resolved"`.
  3. Resolver subject recorded in audit log.

---

### Domain F: FX and Cross-Border (Tier 1 Happy-Path)

#### `TC-T1-F-01`: Query Active Cross-Border Corridors
- **Algorithm:** Alg #059 (Rate sourcing and blending), Alg #061 (Corridor routing)
- **Objective:** Retrieve supported remittance corridors with source/destination currencies, fee structures, and partner metadata.
- **Method & Endpoint:** `GET /v1/fx/corridors`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Returns array of corridor objects.
  2. Includes default `"USD-GHS"` corridor with `sourceCurrency: "USD"`, `destinationCurrency: "GHS"`, `active: true`.
  3. Contains configuration fields: `minAmount`, `maxAmount`, `quoteTtlSeconds`, `estDeliveryMinutes`.

#### `TC-T1-F-02`: Generate Guaranteed Dynamic FX Quote with Locked TTL
- **Algorithm:** Alg #060 (Dynamic FX margin pricing), Alg #063 (Delivery-time estimation)
- **Objective:** Request locked exchange quote for cross-border remittance, verifying rate, fees, expiration, and destination payout.
- **Method & Endpoint:** `POST /v1/fx/quotes`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "corridor": "USD-GHS-SIM",
    "sourceAmount": 10000
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains:
     - `quoteId`: valid UUID
     - `sourceAmount`: `10000`
     - `rate`: decimal string / numeric rate (e.g. `15.50`)
     - `destinationAmount`: calculated integer minor units (e.g. `155000`)
     - `fee`: calculated fee
     - `expiresAt`: timestamp ~600 seconds in the future
     - `estimatedDeliveryMinutes`: integer `> 0`

#### `TC-T1-F-03`: Execute FX Remittance Transfer with Per-Currency Balanced Entries
- **Algorithm:** Alg #006 (Per-currency FX clearing), Alg #060 (Dynamic margin), Alg #061 (Corridor routing)
- **Objective:** Accept active quote and execute international transfer, validating balanced ledger entries in both source and destination currencies.
- **Method & Endpoint:** `POST /v1/fx/transfers`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Idempotency-Key: fx-xfer-happy-003
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "quoteId": "<valid_quote_uuid>",
    "recipient": {
      "name": "Kwame Asante",
      "accountNumber": "0245550005",
      "network": "mtn"
    }
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `reference`, `status`: `"pending"` or `"paid"`, `destinationAmount`.
  2. Ledger check proves zero-sum balance independently in USD and in GHS via FX clearing accounts:
     - GHS legs sum to 0.
     - USD legs sum to 0.
  3. `GET /v1/ops/ledger/integrity` passes unconditionally.

#### `TC-T1-F-04`: Inbound Cross-Border Remittance Delivery
- **Algorithm:** Alg #006 (Per-currency clearing), Alg #013 (Callback state machine)
- **Objective:** Process partner-signed inbound remittance credit directly to customer GHS wallet.
- **Method & Endpoint:** `POST /v1/fx/partners/sim-remit/inbound`
- **Headers:**
  ```http
  x-partner-signature: <valid_hmac_sig>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "externalReference": "EXT-REM-999",
    "sourceCurrency": "USD",
    "sourceAmount": 5000,
    "destinationCurrency": "GHS",
    "destinationAmount": 77500,
    "recipientPhone": "0241234567"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response status is `"credited"`.
  2. Recipient GHS wallet available balance credited with `77500` pesewas.
  3. Source USD partner clearing balance debited `5000`.

#### `TC-T1-F-05`: FX Transfer Status Tracking via Traceable Reference
- **Algorithm:** Alg #063 (Delivery-time estimation)
- **Objective:** Track status and receipt details of executed remittance using its public tracking reference.
- **Method & Endpoint:** `GET /v1/fx/transfers/{reference}`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Returns `reference`, `status`, `sourceAmount`, `destinationAmount`, `rate`, `recipientName`.
  2. Status is one of `pending`, `paid`, `failed`, `returned`.

---

### Domain G: Credit (Tier 1 Happy-Path)

#### `TC-T1-G-01`: Merchant Cash-Flow Underwriting Assessment
- **Algorithm:** Alg #065 (Cash-flow underwriting)
- **Objective:** Evaluate merchant turnover, daily transaction volume, and refund consistency to produce transparent credit scoring.
- **Method & Endpoint:** `POST /v1/merchants/{merchantId}/credit/underwrite`
- **Headers:**
  ```http
  Authorization: Bearer <merchant_owner_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "requestedFacility": "mca",
    "lookbackDays": 90
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `decision`: `"eligible"`, `maxFacilityPesewas`: `> 0`, `repaymentRateBps`: `1000` (10%), `explanation`: non-empty string.
  2. Assessment logs data provenance and turnover features.

#### `TC-T1-G-02`: Merchant Cash Advance (MCA) Transparent Offer Generation
- **Algorithm:** Alg #068 (Merchant cash-advance pricing)
- **Objective:** Generate fixed-fee factor rate MCA offer with full disclosure of total repayment obligation and APR.
- **Method & Endpoint:** `POST /v1/merchants/{merchantId}/credit/mca-offer`
- **Headers:**
  ```http
  Authorization: Bearer <merchant_owner_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "advanceAmount": 500000
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains:
     - `advanceAmount`: `500000` (GH₵5,000)
     - `factorRate`: `1.15`
     - `totalRepayment`: `575000` (GH₵5,750)
     - `fixedCost`: `75000` (GH₵750)
     - `dailySweepPercentage`: `10.0`
     - `estimatedDurationDays`: integer `> 0`

#### `TC-T1-G-03`: Daily Settlement Percentage Repayment Sweep
- **Algorithm:** Alg #069 (Repayment scheduling and sweeps)
- **Objective:** Automatically deduct agreed repayment sweep percentage from merchant settled proceeds prior to crediting available balance.
- **Method & Endpoint:** `POST /v1/ops/credit/sweeps/run`
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "merchantId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `sweptAmount`: `> 0`, `outstandingBalance`: decremented.
  2. Ledger journal entries record balanced debit to merchant proceeds and credit to credit-repayment clearing.
  3. No double debit occurs.

#### `TC-T1-G-04`: Earned-Wage Access (EWA) Eligibility Verification
- **Algorithm:** Alg #066 (Earned-wage access eligibility)
- **Objective:** Calculate verified accrued earnings, existing obligations, and available advance limit for salaried customer.
- **Method & Endpoint:** `POST /v1/wallet/credit/ewa-eligibility`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "employerId": "EMP-GH-001"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `accruedGross`: `> 0`, `deductions`: `>= 0`, `eligibleAdvance`: `> 0`, `maxCapPercentage`: `50`.

#### `TC-T1-G-05`: Overdraft Facility Eligibility Scoring
- **Algorithm:** Alg #067 (Overdraft eligibility scoring)
- **Objective:** Score customer wallet deposit cadence and turnover to determine overdraft buffer eligibility.
- **Method & Endpoint:** `GET /v1/wallet/credit/overdraft-score`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `score`: integer `[0-100]`, `tier`: `"Tier-A"` or `"Tier-B"`, `offeredOverdraftLimit`: integer `>= 0`.

---

### Domain H: Security (Tier 1 Happy-Path)

#### `TC-T1-H-01`: Valid HMAC-SHA256 Webhook Verification
- **Algorithm:** Alg #073 (HMAC verification)
- **Objective:** Verify valid constant-time signature calculation for partner/merchant webhooks.
- **Method & Endpoint:** `POST /v1/merchants/{id}/webhooks`
- **Headers:**
  ```http
  Authorization: Bearer <merchant_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "url": "https://webhook.site/elepay-test-endpoint"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response returns `id`, `secret` (shown once), `url`.
  2. Webhook deliveries sent with header `Elepay-Signature: t=<timestamp>,v1=<hmac_sha256>`.
  3. Signature verifies using `HMAC-SHA256(secret, t + "." + payload)`.

#### `TC-T1-H-02`: USSD PIN Setup with Strong Entropy and BCrypt Hashing
- **Algorithm:** Alg #074 (PIN hashing and lockout)
- **Objective:** Set 4-digit USSD wallet PIN meeting complexity rules (not sequential, not repeated).
- **Method & Endpoint:** `PUT /v1/wallet/ussd-pin`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "pin": "7392"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Returns `{"status": "pin_set"}`.
  2. Database records verify PIN is persisted solely as a BCrypt hash (`$2a$...`).
  3. Plaintext PIN is never stored, echoed, or logged.

#### `TC-T1-H-03`: Signed USSD Session Navigation & Balance Inquiry
- **Algorithm:** Alg #073 (HMAC verification), Alg #074 (PIN hashing)
- **Objective:** Submit valid HMAC-signed generic USSD request, verify PIN, and read available balance.
- **Method & Endpoint:** `POST /v1/ussd/generic`
- **Headers:**
  ```http
  X-Ussd-Signature: <hmac_sha256_of_body>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "sessionId": "USSD-SESS-9001",
    "msisdn": "233241234567",
    "type": "continue",
    "input": "1"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response returns `{"message": "Enter your 4-digit PIN:", "end": false}`.
  2. Follow-up request with input `"7392"` returns `{"message": "Your available balance is GH₵...", "end": true}`.

#### `TC-T1-H-04`: Maker-Checker Two-Operator Approval Flow
- **Algorithm:** Alg #079 (Maker-checker approvals)
- **Objective:** Enforce dual-control where operator 1 proposes an action and distinct operator 2 approves it.
- **Method & Endpoint:** `POST /v1/ops/merchants/{id}/review`
- **Headers:**
  ```http
  Authorization: Bearer <ops_operator2_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "decision": "approve",
    "feeBps": 150,
    "feeFixed": 0,
    "settlementDelayHours": 24
  }
  ```
- **Preconditions:** Merchant onboarding initiated by applicant (`operator1` != `operator2`).
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Status updates to `"active"`.
  2. Audit log records both maker and checker subjects.

#### `TC-T1-H-05`: Merchant Scoped API Key Generation and Invocation
- **Algorithm:** Alg #077 (Request signing and replay), Alg #084 (CI security)
- **Objective:** Generate a merchant API key with `collect` and `refund` scopes, and verify authenticated resource access via `X-Elepay-Api-Key`.
- **Method & Endpoint:** `POST /v1/merchants/{merchantId}/api-keys`
- **Headers:**
  ```http
  Authorization: Bearer <merchant_owner_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "label": "Backend Checkout Key",
    "scopes": ["collect", "refund", "view"]
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `apiKey` (starts with `"ep_live_"` or `"ep_test_"`), `keyId`, `scopes`.
  2. Calling `GET /v1/merchants/{id}/balances` with header `X-Elepay-Api-Key: <apiKey>` succeeds with `200 OK`.

---

### Domain I: Reliability and Platform (Tier 1 Happy-Path)

#### `TC-T1-I-01`: Transactional Outbox Event Emission on Checkout Payment
- **Algorithm:** Alg #085 (Transactional outbox and inbox deduplication)
- **Objective:** Confirm completing a payment atomically writes domain event to `outbox_event` table in the same DB transaction.
- **Method & Endpoint:** `GET /v1/merchants/{merchantId}/events`
- **Headers:**
  ```http
  Authorization: Bearer <merchant_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Returns array of events ordered by monotonic `sequence`.
  2. Event `type: "checkout.paid"` exists with stable `eventId`.
  3. Payload contains `reference`, `subtotal`, `allocations`.

#### `TC-T1-I-02`: Partner Circuit Breaker Normal Healthy Operation
- **Algorithm:** Alg #086 (Partner circuit breakers)
- **Objective:** Execute consecutive successful rail transactions confirming circuit breaker remains in CLOSED state.
- **Method & Endpoint:** `POST /v1/public/checkouts/{reference}/pay`
- **Headers:**
  ```http
  Idempotency-Key: cb-healthy-001
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "method": "momo",
    "phone": "0245550005",
    "network": "mtn"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Rail transaction submitted successfully.
  2. Circuit breaker metric `resilience4j_circuitbreaker_state{name="mtn",state="closed"}` remains `1`.

#### `TC-T1-I-03`: Token-Bucket Rate Limiter Clean Concurrency Under Limit
- **Algorithm:** Alg #088 (Token-bucket rate limiting)
- **Objective:** Issue 5 lookup requests within 10 seconds (under the 10/min threshold) and verify all return `200 OK`.
- **Method & Endpoint:** `POST /v1/wallet/recipients/lookup`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "rail": "wallet",
    "phone": "0241234567"
  }
  ```
- **Expected Status:** `200 OK` across all 5 sequential invocations.
- **Assertions:**
  1. All 5 requests succeed with HTTP `200`.
  2. No `429 Too Many Requests` returned.

#### `TC-T1-I-04`: Prometheus Metrics & Operational SLO Burn-Rate Export
- **Algorithm:** Alg #090 (SLO burn-rate alerting), Alg #092 (Scaling signals)
- **Objective:** Scrape platform metrics endpoint and verify publication of user-visible availability, latency, and DB pool gauges.
- **Method & Endpoint:** `GET /actuator/prometheus`
- **Headers:**
  ```http
  Accept: text/plain
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response body contains:
     - `http_server_requests_seconds_count`
     - `hikaricp_connections_active`
     - `ledger_integrity_drift_count`
     - `outbox_backlog_count`

#### `TC-T1-I-05`: Controlled Chaos Fault Recovery Simulation
- **Algorithm:** Alg #093 (Fault injection), Alg #094 (Database failover recovery)
- **Objective:** Simulate transient network delay on payment rail, confirming transaction safely holds in `pending` without money loss.
- **Method & Endpoint:** `POST /v1/ops/rails/recover`
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Engine polls delayed partner responses.
  2. Resumes pending transactions cleanly to terminal state without ledger discrepancies.

---

### Domain J: Customer and Growth (Tier 1 Happy-Path)

#### `TC-T1-J-01`: Canonical Ghana Phone Normalization Across Varied Formats
- **Algorithm:** Alg #095 (Exact phone normalization)
- **Objective:** Prove local (`0241234567`), international format (`+233241234567`), and spaced format (`024 123 4567`) normalize to the identical recipient.
- **Method & Endpoint:** `POST /v1/wallet/recipients/lookup`
- **Headers:**
  ```http
  Authorization: Bearer <sender_jwt>
  Content-Type: application/json
  ```
- **Payload 1:** `{"rail": "wallet", "phone": "0241234567"}`
- **Payload 2:** `{"rail": "wallet", "phone": "+233241234567"}`
- **Expected Status:** `200 OK` for both.
- **Assertions:**
  1. Both payloads return identical `verifiedName`: `"Ama Mensah"`.
  2. Both normalize internally to canonical destination format `+233241234567`.

#### `TC-T1-J-02`: Explainable Transaction Spending Categorization
- **Algorithm:** Alg #096 (Spending categorization)
- **Objective:** Query transaction statement and verify automated category tagging (e.g. `"groceries"`, `"utilities"`, `"transfer"`).
- **Method & Endpoint:** `GET /v1/wallet/statement?from=2026-11-01T00:00:00Z&to=2026-11-30T23:59:59Z`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Transaction items include metadata tag `category` (e.g. `"merchant_payment"`).
  2. Underlying ledger journal balance remains immutable.

#### `TC-T1-J-03`: Voluntary Savings Goal Creation and Round-Up Sweep
- **Algorithm:** Alg #097 (Savings goals and round-ups)
- **Objective:** Configure voluntary round-up rule on wallet spend, sweeping spare change into dedicated savings bucket.
- **Method & Endpoint:** `POST /v1/wallet/savings/goals`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "title": "Emergency Fund",
    "targetAmount": 100000,
    "roundUpEnabled": true
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Goal created with `roundUpEnabled: true`.
  2. Dedicated savings ledger sub-account opened for customer.

#### `TC-T1-J-04`: Customer Notification Delivery Preferences
- **Algorithm:** Alg #099 (Notification timing)
- **Objective:** Set customer quiet hours and SMS/Push channel delivery preferences.
- **Method & Endpoint:** `PUT /v1/wallet/notifications/preferences`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "quietHoursStart": "22:00",
    "quietHoursEnd": "06:00",
    "smsEnabled": true,
    "pushEnabled": true
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Returns `status: "updated"`.
  2. Non-urgent marketing notifications suppressed during quiet hours.

#### `TC-T1-J-05`: Personalized Customer Limit Adjustment
- **Algorithm:** Alg #100 (Personalized limits)
- **Objective:** Retrieve customer risk-evaluated personal daily limit based on verified tenure.
- **Method & Endpoint:** `GET /v1/wallet`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `limits` object with `dailyTransferLimit`, `maxSingleTransaction`.
  2. Limits conform to customer KYC tier ceilings.

---

## 4. Tier 2: Boundary, Corner & Negative Test Suite

Tier 2 specifies **50 boundary, edge, and negative test cases** (5 per functional module A through J). Every test case is specified with complete request/response contracts, invalid/edge payloads, expected HTTP error status codes (400, 403, 404, 409, 422, 429), and assertion criteria.

---

### Domain A: Ledger and Money (Tier 2 Boundary & Negative)

#### `TC-T2-A-01`: Replay of Idempotency Key with Modified Amount (Idempotency Conflict)
- **Algorithm:** Alg #003 (Idempotent request hashing)
- **Objective:** Submit identical idempotency key with modified transfer amount, verifying immediate `409 Conflict` rejection.
- **Method & Endpoint:** `POST /v1/wallet/transfers`
- **Headers:**
  ```http
  Authorization: Bearer <sender_jwt>
  Idempotency-Key: static-idem-key-001
  Content-Type: application/json
  ```
- **Payload Step 1:** `{"recipient": "<recipient_uuid>", "amount": 5000}` -> Returns `200 OK`.
- **Payload Step 2 (Attack/Tamper):** `{"recipient": "<recipient_uuid>", "amount": 9999}` -> With SAME `Idempotency-Key: static-idem-key-001`.
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Error response body contains `code: "idempotency_conflict"`.
  2. Sender balance debited exactly once (by `5000`), never by `9999` or `14999`.
  3. Ledger integrity check confirms zero drift.

#### `TC-T2-A-02`: Overdraft Attempt on Available Balance (Insufficient Funds)
- **Algorithm:** Alg #001 (Double-entry posting), Alg #002 (Ordered row locking)
- **Objective:** Attempt to spend more funds than available in customer wallet, verifying DB constraint trigger prevents negative balance.
- **Method & Endpoint:** `POST /v1/wallet/transfers`
- **Headers:**
  ```http
  Authorization: Bearer <sender_jwt>
  Idempotency-Key: overdraft-attack-002
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "recipient": "<recipient_uuid>",
    "amount": 999999999
  }
  ```
- **Expected Status:** `409 Conflict` (or `422 Unprocessable`)
- **Assertions:**
  1. Response body contains `code: "insufficient_funds"`.
  2. Sender balance remains completely unchanged.
  3. No journal transaction or entries persisted.

#### `TC-T2-A-03`: Excessive Refund Exceeding Original Allocation Subtotal
- **Algorithm:** Alg #004 (Proportional split), Alg #005 (Linked reversals)
- **Objective:** Submit a refund request for an allocation line that exceeds the captured amount (or repeat refund after full refund).
- **Method & Endpoint:** `POST /v1/merchants/{id}/checkouts/{reference}/allocations/0/refunds`
- **Headers:**
  ```http
  Authorization: Bearer <merchant_jwt>
  Idempotency-Key: refund-overflow-003
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "amount": 8001,
    "reason": "Exceeding original 8000 allocation"
  }
  ```
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Response body contains error code `"refund_exceeds_allocation"`.
  2. Merchant balance and revenue accounts remain intact.

#### `TC-T2-A-04`: Double Reversal of Already Reversed Journal Transaction
- **Algorithm:** Alg #005 (Linked reversals)
- **Objective:** Attempt to reverse a transaction that has already been fully reversed, testing PostgreSQL partial index `journal_transaction_single_reversal`.
- **Method & Endpoint:** `POST /v1/merchants/{id}/checkouts/{reference}/allocations/0/refunds`
- **Headers:**
  ```http
  Authorization: Bearer <merchant_jwt>
  Idempotency-Key: second-refund-attempt-004
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "amount": 8000,
    "reason": "Second full refund attempt"
  }
  ```
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Response body contains `code: "already_reversed"` or `"refund_exceeds_allocation"`.
  2. Single reversal invariant preserved.

#### `TC-T2-A-05`: Zero and Negative Amount Submission Rejection
- **Algorithm:** Alg #001 (Double-entry posting)
- **Objective:** Submit zero or negative amount in financial command, verifying boundary validation rejects invalid input.
- **Method & Endpoint:** `POST /v1/wallet/transfers`
- **Headers:**
  ```http
  Authorization: Bearer <sender_jwt>
  Idempotency-Key: neg-amount-005
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "recipient": "<recipient_uuid>",
    "amount": -500
  }
  ```
- **Expected Status:** `400 Bad Request`
- **Assertions:**
  1. Response contains validation failure on `amount`: `"must be greater than 0"`.
  2. Zero ledger mutations.

---

### Domain B: Payment Processing (Tier 2 Boundary & Negative)

#### `TC-T2-B-01`: Concurrent Spending Race Condition on Same Available Balance
- **Algorithm:** Alg #002 (Ordered row locking), Alg #011 (Rail submission)
- **Objective:** Fire 2 simultaneous concurrent payments competing for the exact same wallet balance using thread pool.
- **Method & Endpoint:** `POST /v1/checkouts/{ref1}/pay/wallet` and `POST /v1/checkouts/{ref2}/pay/wallet`
- **Preconditions:** Customer wallet available balance is exactly 15,000 pesewas. Ref 1 requests 15,000; Ref 2 requests 15,000.
- **Expected Status:** Exactly ONE returns `200 OK`; the other returns `409 Conflict`.
- **Assertions:**
  1. `[status1, status2]` sorted is `[200, 409]`.
  2. Final wallet balance is `0` (never negative).
  3. Total merchant credits equal `15000` (no double spending).

#### `TC-T2-B-02`: Repeating Payment on Already Paid Checkout
- **Algorithm:** Alg #011 (Rail submission)
- **Objective:** Attempt to pay an already settled checkout reference with a fresh idempotency key.
- **Method & Endpoint:** `POST /v1/checkouts/{reference}/pay/wallet`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Idempotency-Key: fresh-key-on-paid-checkout
  Content-Type: application/json
  ```
- **Payload:** `{}`
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Response contains `code: "checkout_already_paid"`.
  2. No duplicate debit.

#### `TC-T2-B-03`: Payment Confirmation Attempt on Cancelled Order
- **Algorithm:** Alg #011 (Rail submission)
- **Objective:** Cancel an unpaid checkout order, then attempt to execute a payment on it.
- **Method & Endpoint:** `POST /v1/checkouts/{cancelledRef}/pay/wallet`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Idempotency-Key: pay-cancelled-order
  Content-Type: application/json
  ```
- **Payload:** `{}`
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Response contains `code: "order_cancelled"`.
  2. Order remains in cancelled status.

#### `TC-T2-B-04`: Invalid Rail Callback Signature Rejection
- **Algorithm:** Alg #013 (Callback state machine), Alg #073 (HMAC verification)
- **Objective:** Post forged rail callback with bogus signature header, verifying rejection and no ledger execution.
- **Method & Endpoint:** `POST /v1/rails/sandbox/callbacks`
- **Headers:**
  ```http
  x-sandbox-signature: deadbeef010203040506070809
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "railReference": "RAIL-REF-12345",
    "status": "SUCCESS",
    "amount": 50000
  }
  ```
- **Expected Status:** `401 Unauthorized` (or `403 Forbidden`)
- **Assertions:**
  1. Forged callback rejected.
  2. Associated transaction status remains unchanged.

#### `TC-T2-B-05`: Unknown Network Operator in Mobile Money Payment
- **Algorithm:** Alg #014 (Smart routing), Alg #015 (Safe failover)
- **Objective:** Supply unsupported or uncontracted network operator name (e.g., `vodafone_uk`, `paystack`).
- **Method & Endpoint:** `POST /v1/public/checkouts/{reference}/pay`
- **Headers:**
  ```http
  Idempotency-Key: bad-network-005
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "method": "momo",
    "phone": "0241234567",
    "network": "unsupported_network"
  }
  ```
- **Expected Status:** `400 Bad Request`
- **Assertions:**
  1. Response contains `code: "invalid_network"`, message indicates only `mtn`, `telecel`, `airteltigo` permitted.

---

### Domain C: Fraud and Risk (Tier 2 Boundary & Negative)

#### `TC-T2-C-01`: Rapid Velocity Threshold Breached (Rate-Lockout)
- **Algorithm:** Alg #023 (Velocity rules)
- **Objective:** Rapidly fire 15 transactions in 60 seconds from the same customer, breaching the 10/min velocity limit.
- **Method & Endpoint:** `POST /v1/wallet/transfers/confirmed` (11th request)
- **Expected Status:** `429 Too Many Requests` (or `409 velocity_limit_exceeded`)
- **Assertions:**
  1. 11th request is blocked.
  2. Audit item written to `ops_item` with reason `velocity_exceeded`.

#### `TC-T2-C-02`: Impossible Geo-Velocity Travel Distance
- **Algorithm:** Alg #027 (Geo-velocity checks)
- **Objective:** Fire transaction from Accra, Ghana IP, followed 3 minutes later by transaction from Tokyo, Japan IP.
- **Method & Endpoint:** `POST /v1/wallet/transfers/confirmed`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  X-Forwarded-For: 133.242.0.1
  Idempotency-Key: geo-fraud-002
  Content-Type: application/json
  ```
- **Payload:** `{"confirmationId": "<uuid>", "amount": 50000}`
- **Expected Status:** `403 Forbidden` (or `409 step_up_required`)
- **Assertions:**
  1. Transaction blocked or queued for adaptive step-up authentication.
  2. Ops queue records `impossible_travel_anomaly`.

#### `TC-T2-C-03`: Recent SIM-Swap Lockout
- **Algorithm:** Alg #026 (SIM-swap checks)
- **Objective:** Attempt high-value transfer immediately following a SIM card swap (<24 hours old).
- **Method & Endpoint:** `POST /v1/wallet/transfers/confirmed`
- **Headers:**
  ```http
  Authorization: Bearer <sim_swapped_customer_jwt>
  Idempotency-Key: sim-swap-003
  Content-Type: application/json
  ```
- **Payload:** `{"confirmationId": "<uuid>", "amount": 200000}`
- **Expected Status:** `403 Forbidden` (or `409 sim_swap_review`)
- **Assertions:**
  1. Response contains `code: "sim_swap_detected"`.
  2. Action halted pending customer step-up verification.

#### `TC-T2-C-04`: Circular Money-Mule Graph Loop Detection
- **Algorithm:** Alg #032 (Mule-account graph detection)
- **Objective:** Cycle funds rapidly across 3 newly enrolled accounts (A -> B -> C -> A) within 5 minutes.
- **Method & Endpoint:** `POST /v1/wallet/transfers/confirmed` (Closing transfer C -> A)
- **Expected Status:** `403 Forbidden` (or placed under `compliance_hold`)
- **Assertions:**
  1. Transfer suspended.
  2. AML alert generated in `GET /v1/ops/queue` with type `mule_cycle_detected`.

#### `TC-T2-C-05`: Sybil Referral Self-Promotion Exploitation
- **Algorithm:** Alg #031 (Promotion and referral abuse detection)
- **Objective:** Attempt to claim a referral reward on an account sharing the exact device fingerprint and IP of the referrer.
- **Method & Endpoint:** `POST /v1/wallet/promotions/claim`
- **Headers:**
  ```http
  Authorization: Bearer <sybil_jwt>
  X-Device-Fingerprint: fp-same-device-123
  Content-Type: application/json
  ```
- **Payload:** `{"referralCode": "REF-ORIGINAL-123"}`
- **Expected Status:** `409 Conflict` (or `403 Forbidden`)
- **Assertions:**
  1. Response returns `code: "referral_abuse_detected"`.
  2. No referral bonus credited.

---

### Domain D: Compliance Controls (Tier 2 Boundary & Negative)

#### `TC-T2-D-01`: Recipient Lookup Rate Limit Abuse (11th Request Rejection)
- **Algorithm:** Alg #037 (Rate-limited audited lookups)
- **Objective:** Submit 11 recipient lookups in a 60-second window, exceeding the default 10 per minute rate limit.
- **Method & Endpoint:** `POST /v1/wallet/recipients/lookup` (11th invocation)
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:** `{"rail": "wallet", "phone": "0240000011"}`
- **Expected Status:** `429 Too Many Requests`
- **Assertions:**
  1. 11th call returns HTTP 429.
  2. Response header contains `Retry-After`.
  3. Audit record records rate limit breach.

#### `TC-T2-D-02`: Expired Confirmation Token Rejection
- **Algorithm:** Alg #038 (Bound single-use confirmations)
- **Objective:** Attempt to execute transfer using a confirmation ID whose 5-minute TTL has elapsed.
- **Method & Endpoint:** `POST /v1/wallet/transfers/confirmed`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Idempotency-Key: exp-confirm-002
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "confirmationId": "<expired_confirmation_uuid>",
    "amount": 5000
  }
  ```
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Response contains `code: "confirmation_expired"`.
  2. Zero funds debited.

#### `TC-T2-D-03`: Cross-Sender Confirmation Hijacking Attempt
- **Algorithm:** Alg #038 (Bound single-use confirmations)
- **Objective:** Customer B attempts to submit confirmation token generated by Customer A.
- **Method & Endpoint:** `POST /v1/wallet/transfers/confirmed`
- **Headers:**
  ```http
  Authorization: Bearer <customerB_jwt>
  Idempotency-Key: hijack-confirm-003
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "confirmationId": "<customerA_confirmation_uuid>",
    "amount": 5000
  }
  ```
- **Expected Status:** `404 Not Found` (or `403 Forbidden`)
- **Assertions:**
  1. Token not found under Customer B scope.
  2. Confirmation isolation enforced.

#### `TC-T2-D-04`: Tier 1 Cumulative Daily Limit Breach
- **Algorithm:** Alg #039 (KYC tier limits)
- **Objective:** Attempt transfer that would push Tier 1 customer daily cumulative transfer over 100,000 pesewas.
- **Method & Endpoint:** `POST /v1/wallet/transfers/confirmed`
- **Headers:**
  ```http
  Authorization: Bearer <tier1_customer_jwt>
  Idempotency-Key: kyc-over-limit-004
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "confirmationId": "<valid_confirmation_uuid>",
    "amount": 100001
  }
  ```
- **Expected Status:** `409 Conflict` (or `422 Unprocessable`)
- **Assertions:**
  1. Response contains `code: "kyc_limit_exceeded"`.
  2. Guidance returned advising user to upgrade KYC Tier.

#### `TC-T2-D-05`: High-Confidence Fuzzy Sanctions Match Block
- **Algorithm:** Alg #042 (Fuzzy sanctions screening)
- **Objective:** Attempt transaction involving recipient whose name has high Jaro-Winkler match (>0.92) to an OFAC SDN entity.
- **Method & Endpoint:** `POST /v1/ops/compliance/screen-sanctions`
- **Payload:**
  ```json
  {
    "fullName": "Usama Bin Laden",
    "country": "AF"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `matchFound: true`, `confidenceScore: > 0.90`, `status: "flagged"`.
  2. Transaction blocked and routed to senior compliance officer queue.

---

### Domain E: Reconciliation and Treasury (Tier 2 Boundary & Negative)

#### `TC-T2-E-01`: Statement Reconciliation with Amount Discrepancy
- **Algorithm:** Alg #049 (Exact statement matching), Alg #053 (Partner fee verification)
- **Objective:** Feed partner statement record where recorded amount does not match internal transaction.
- **Method & Endpoint:** `POST /v1/ops/fx/reconcile/sim-remit`
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "period": "2026-11-22",
    "lines": [
      {
        "partnerReference": "PARTNER-REF-ERR",
        "reference": "FX-TR-001",
        "sourceAmount": 9999,
        "sourceCurrency": "USD",
        "destinationAmount": 155000,
        "destinationCurrency": "GHS",
        "status": "paid"
      }
    ]
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response returns `amountMismatch: 1`.
  2. System creates an active exception item in `ops_item` queue.
  3. No automatic silent balance repair.

#### `TC-T2-E-02`: Withdrawal Attempt to Unverified Settlement Destination
- **Algorithm:** Alg #051 (Settlement batching)
- **Objective:** Request merchant withdrawal to a destination whose status is `pending_verification` or `rejected`.
- **Method & Endpoint:** `POST /v1/merchants/{id}/withdrawals`
- **Headers:**
  ```http
  Authorization: Bearer <merchant_jwt>
  Idempotency-Key: unverified-withdraw-002
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "destinationId": "<unverified_destination_uuid>",
    "amount": 10000
  }
  ```
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Response contains `code: "destination_unverified"`.
  2. Zero funds reserved.

#### `TC-T2-E-03`: Payout Retry on Still-Pending Rail Transaction
- **Algorithm:** Alg #011 (Rail submission), Alg #012 (Unknown recovery)
- **Objective:** Submit retry request for a payout batch item that is currently in `submitted` or `unknown` state.
- **Method & Endpoint:** `POST /v1/merchants/{id}/payouts/{reference}/retry`
- **Headers:**
  ```http
  Authorization: Bearer <merchant_jwt>
  Content-Type: application/json
  ```
- **Payload:** `{}`
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Response contains `code: "payout_not_retryable"`.
  2. Item cannot be retried until definitively `failed` or `returned`.

#### `TC-T2-E-04`: Duplicate Payout Item Reference in Batch Submission
- **Algorithm:** Alg #051 (Settlement batching)
- **Objective:** Submit payout batch containing two identical item references within the same batch.
- **Method & Endpoint:** `POST /v1/merchants/{id}/payout-batches`
- **Headers:**
  ```http
  Authorization: Bearer <merchant_jwt>
  Idempotency-Key: dup-payout-ref-004
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "items": [
      {"reference": "ITEM-DUP-01", "amount": 1000, "type": "momo", "network": "mtn", "accountNumber": "024111", "accountName": "A"},
      {"reference": "ITEM-DUP-01", "amount": 2000, "type": "momo", "network": "mtn", "accountNumber": "024222", "accountName": "B"}
    ]
  }
  ```
- **Expected Status:** `400 Bad Request`
- **Assertions:**
  1. Response rejects duplicate item reference within batch.

#### `TC-T2-E-05`: Over-Withdrawal Exceeding Available Balance
- **Algorithm:** Alg #001 (Double entry), Alg #051 (Settlement)
- **Objective:** Request withdrawal of 50,000 pesewas when merchant available balance is only 10,000.
- **Method & Endpoint:** `POST /v1/merchants/{id}/withdrawals`
- **Headers:**
  ```http
  Authorization: Bearer <merchant_jwt>
  Idempotency-Key: overdraw-merchant-005
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "destinationId": "<verified_destination_uuid>",
    "amount": 50000
  }
  ```
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Response contains `code: "insufficient_funds"`.
  2. Reserved balance remains `0`.

---

### Domain F: FX and Cross-Border (Tier 2 Boundary & Negative)

#### `TC-T2-F-01`: Execution of Stale or Expired FX Quote
- **Algorithm:** Alg #060 (Dynamic FX margin pricing)
- **Objective:** Submit remittance transfer using a quote ID whose `expiresAt` timestamp has elapsed.
- **Method & Endpoint:** `POST /v1/fx/transfers`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Idempotency-Key: exp-fx-quote-001
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "quoteId": "<expired_quote_uuid>",
    "recipient": {"name": "Kwame", "accountNumber": "0245550005", "network": "mtn"}
  }
  ```
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Response contains `code: "quote_expired"`.
  2. Customer must request fresh quote.

#### `TC-T2-F-02`: Re-Execution of Already Consumed FX Quote
- **Algorithm:** Alg #060 (Dynamic FX margin)
- **Objective:** Attempt to execute a second transfer using a single-use quote ID that has already been executed.
- **Method & Endpoint:** `POST /v1/fx/transfers`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Idempotency-Key: fx-quote-reuse-002
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "quoteId": "<already_executed_quote_uuid>",
    "recipient": {"name": "Kwame", "accountNumber": "0245550005", "network": "mtn"}
  }
  ```
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Response contains `code: "quote_already_used"`.
  2. Exactly one transfer allowed per quote.

#### `TC-T2-F-03`: Inbound Remittance Destination Amount Mismatch
- **Algorithm:** Alg #006 (Per-currency clearing), Alg #064 (Multi-currency recon)
- **Objective:** Submit inbound remittance callback where destination amount deviates from calculated rate product.
- **Method & Endpoint:** `POST /v1/fx/partners/sim-remit/inbound`
- **Headers:**
  ```http
  x-partner-signature: <valid_sig>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "externalReference": "EXT-ERR-003",
    "sourceCurrency": "USD",
    "sourceAmount": 1000,
    "destinationCurrency": "GHS",
    "destinationAmount": 999999,
    "recipientPhone": "0241234567"
  }
  ```
- **Expected Status:** `400 Bad Request`
- **Assertions:**
  1. Response returns `code: "amount_mismatch"`.
  2. Instruction rejected before ledger entry.

#### `TC-T2-F-04`: Inactive or Non-Existent FX Corridor Quote Request
- **Algorithm:** Alg #061 (Corridor routing)
- **Objective:** Request FX quote for non-existent or deactivated corridor (e.g., `JPY-GHS`).
- **Method & Endpoint:** `POST /v1/fx/quotes`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "corridor": "JPY-GHS",
    "sourceAmount": 100000
  }
  ```
- **Expected Status:** `404 Not Found` (or `400 Bad Request`)
- **Assertions:**
  1. Response indicates corridor not supported or inactive.

#### `TC-T2-F-05`: Amount Below Corridor Minimum Limit
- **Algorithm:** Alg #060 (Dynamic FX margin)
- **Objective:** Request quote for amount lower than corridor configured `minAmount`.
- **Method & Endpoint:** `POST /v1/fx/quotes`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:**
  ```json
  {
    "corridor": "USD-GHS-SIM",
    "sourceAmount": 10
  }
  ```
- **Expected Status:** `400 Bad Request`
- **Assertions:**
  1. Response indicates amount below minimum corridor limit.

---

### Domain G: Credit (Tier 2 Boundary & Negative)

#### `TC-T2-G-01`: Execution of Credit Products When Feature Flag Disabled
- **Algorithm:** Alg #065-#072 (Credit module lifecycle)
- **Objective:** Attempt to disburse live credit facility when product flag is set to disabled for live operation.
- **Method & Endpoint:** `POST /v1/merchants/{id}/credit/disburse`
- **Headers:**
  ```http
  Authorization: Bearer <merchant_jwt>
  Content-Type: application/json
  ```
- **Payload:** `{"facilityId": "<uuid>", "amount": 100000}`
- **Expected Status:** `403 Forbidden` (or `503 Service Unavailable`)
- **Assertions:**
  1. Response contains `code: "credit_product_disabled"`.
  2. Live disbursement completely blocked per regulatory rule.

#### `TC-T2-G-02`: MCA Offer Request for Non-Qualifying Merchant
- **Algorithm:** Alg #065 (Underwriting), Alg #068 (MCA pricing)
- **Objective:** Request MCA pricing for merchant with less than 30 days active history or zero transaction turnover.
- **Method & Endpoint:** `POST /v1/merchants/{newMerchantId}/credit/mca-offer`
- **Headers:**
  ```http
  Authorization: Bearer <new_merchant_jwt>
  Content-Type: application/json
  ```
- **Payload:** `{"advanceAmount": 500000}`
- **Expected Status:** `409 Conflict` (or `422 Unprocessable`)
- **Assertions:**
  1. Response contains `code: "merchant_ineligible"`, with explanation of insufficient sales history.

#### `TC-T2-G-03`: Duplicate Sweep Execution on Same Settled Proceeds
- **Algorithm:** Alg #069 (Repayment scheduling and sweeps)
- **Objective:** Re-run sweep job on the exact same settlement batch, verifying idempotency prevents duplicate debit.
- **Method & Endpoint:** `POST /v1/ops/credit/sweeps/run` (Second run)
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  Content-Type: application/json
  ```
- **Payload:** `{"merchantId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"}`
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response returns `sweptAmount: 0` (no new funds swept).
  2. Ledger integrity maintained.

#### `TC-T2-G-04`: EWA Advance Exceeding Salary Accrual Cap
- **Algorithm:** Alg #066 (Earned-wage access eligibility)
- **Objective:** Request EWA advance of GH₵2,000 when verified accrued earnings only permit GH₵1,000.
- **Method & Endpoint:** `POST /v1/wallet/credit/ewa-advance`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:** `{"amount": 200000}`
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Response returns `code: "advance_exceeds_accrual_limit"`.

#### `TC-T2-G-05`: Overdraft Protection Against Unsupported Sub-Accounts
- **Algorithm:** Alg #067 (Overdraft scoring)
- **Objective:** Attempt to overdraw a wallet bucket not enrolled in overdraft protection.
- **Method & Endpoint:** `POST /v1/wallet/transfers`
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Overdraft denied for unapproved accounts.

---

### Domain H: Security (Tier 2 Boundary & Negative)

#### `TC-T2-H-01`: Three Consecutive USSD Wrong PIN Entries Trigger 30-Minute Lockout
- **Algorithm:** Alg #074 (PIN hashing and lockout)
- **Objective:** Enter incorrect USSD PIN 3 consecutive times, verifying the 3rd attempt triggers a 30-minute account lockout.
- **Method & Endpoint:** `POST /v1/ussd/generic` (3 successive calls with wrong PIN)
- **Headers:**
  ```http
  X-Ussd-Signature: <valid_hmac_of_body>
  Content-Type: application/json
  ```
- **Payload (Attempt 3):**
  ```json
  {
    "sessionId": "USSD-LOCKOUT-001",
    "msisdn": "233241234567",
    "type": "continue",
    "input": "0000"
  }
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response message contains: `"PIN locked for 30 minutes due to repeated failed attempts"`, `end: true`.
  2. 4th attempt with CORRECT PIN immediately returns locked message.
  3. Security incident written to `audit_log`.

#### `TC-T2-H-02`: Weak USSD PIN Complexity Validation Rejection
- **Algorithm:** Alg #074 (PIN hashing)
- **Objective:** Attempt to set sequential (`"1234"`, `"4321"`) or repeated (`"1111"`, `"5555"`) PIN.
- **Method & Endpoint:** `PUT /v1/wallet/ussd-pin`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:** `{"pin": "1234"}`
- **Expected Status:** `400 Bad Request`
- **Assertions:**
  1. Response contains `code: "weak_pin"`, message specifies sequential or repeated digits disallowed.

#### `TC-T2-H-03`: Single-Operator Maker-Checker Self-Approval Rejection
- **Algorithm:** Alg #079 (Maker-checker approvals)
- **Objective:** Operator attempts to approve a sensitive merchant onboarding review that they themselves submitted.
- **Method & Endpoint:** `POST /v1/ops/merchants/{id}/review`
- **Headers:**
  ```http
  Authorization: Bearer <same_operator_jwt>
  Content-Type: application/json
  ```
- **Payload:** `{"decision": "approve", "feeBps": 150}`
- **Expected Status:** `403 Forbidden` (or `409 Conflict`)
- **Assertions:**
  1. Response returns `code: "maker_checker_violation"`.
  2. Maker cannot act as checker.

#### `TC-T2-H-04`: Cross-Tenant Merchant Resource Access Enforces HTTP 404
- **Algorithm:** Alg #084 (Security boundaries)
- **Objective:** Merchant B uses their valid Bearer token to read Merchant A's balances.
- **Method & Endpoint:** `GET /v1/merchants/{merchantA_id}/balances`
- **Headers:**
  ```http
  Authorization: Bearer <merchantB_jwt>
  Accept: application/json
  ```
- **Payload:** None
- **Expected Status:** `404 Not Found` (Explicit design requirement: Return 404 to prevent merchant ID enumeration).
- **Assertions:**
  1. Server returns HTTP 404.
  2. No balance or metadata leaked.

#### `TC-T2-H-05`: Replayed Signed Request Timestamp Outside Tolerance Window
- **Algorithm:** Alg #077 (Request signing and replay protection)
- **Objective:** Send request with timestamp older than 300 seconds (5 minutes).
- **Method & Endpoint:** `POST /v1/merchants/{id}/checkouts`
- **Headers:**
  ```http
  X-Elepay-Signature: <valid_sig_of_old_timestamp>
  X-Timestamp: 1600000000
  Content-Type: application/json
  ```
- **Expected Status:** `401 Unauthorized` (or `400 Bad Request`)
- **Assertions:**
  1. Response contains `code: "timestamp_out_of_bounds"`.

---

### Domain I: Reliability and Platform (Tier 2 Boundary & Negative)

#### `TC-T2-I-01`: Circuit Breaker Opens Under High Upstream Error Rate
- **Algorithm:** Alg #086 (Partner circuit breakers)
- **Objective:** Inject 10 consecutive upstream 500 errors from a rail partner simulator, verifying circuit transitions to OPEN.
- **Method & Endpoint:** `POST /v1/public/checkouts/{reference}/pay` (11th call)
- **Expected Status:** `503 Service Unavailable`
- **Assertions:**
  1. 11th call immediately short-circuits with `503 Service Unavailable` without attempting external connection.
  2. Metric `circuitbreaker_state` reports `OPEN`.

#### `TC-T2-I-02`: Outbox Dispatcher Dead-Letter Handling on Terminal Webhook Failure
- **Algorithm:** Alg #085 (Outbox), Alg #087 (Backoff and jitter)
- **Objective:** Configure merchant webhook endpoint to return `500 Internal Error` indefinitely, verifying max retries and dead status.
- **Method & Endpoint:** `GET /v1/ops/overview`
- **Headers:**
  ```http
  Authorization: Bearer <ops_jwt>
  ```
- **Expected Status:** `200 OK`
- **Assertions:**
  1. After exponential backoff exhausts max retries, `webhook_delivery` row status transitions to `"dead"`.
  2. `webhooks_dead` count in ops overview increments by 1.

#### `TC-T2-I-03`: Load Shedding Under Simulated DB Connection Exhaustion
- **Algorithm:** Alg #089 (Load shedding and backpressure)
- **Objective:** Saturate worker thread queues, confirming non-critical read traffic is shed while critical financial postings are preserved.
- **Method & Endpoint:** `GET /v1/public/fees/quote` (Under artificial heavy load)
- **Expected Status:** `503 Service Unavailable` (with `Retry-After` header)
- **Assertions:**
  1. System protects core ledger operations from starvation.

#### `TC-T2-I-04`: Duplicate Webhook Event Ingestion Idempotency
- **Algorithm:** Alg #085 (Inbox deduplication)
- **Objective:** Submit identical event ID twice to webhook receiver endpoint.
- **Method & Endpoint:** `POST /v1/rails/sandbox/callbacks`
- **Headers:**
  ```http
  x-sandbox-signature: <valid_sig>
  Content-Type: application/json
  ```
- **Payload:** `{"railReference": "REF-DUP-004", "status": "SUCCESS"}` (Identical retry)
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Second execution recognized as duplicate in `rail_callback` table.
  2. Event applied exactly once to financial ledger.

#### `TC-T2-I-05`: Graceful Recovery Across Database Reconnection Bounce
- **Algorithm:** Alg #094 (Database failover recovery)
- **Objective:** Temporarily pause PostgreSQL container for 3 seconds during API call, verifying Hikari pool reconnects cleanly without corrupted ledger state.
- **Method & Endpoint:** `GET /v1/ops/ledger/integrity` (Post-reconnect)
- **Expected Status:** `200 OK`
- **Assertions:**
  1. HikariCP restores healthy active connection.
  2. Zero corrupted or partially committed transactions.

---

### Domain J: Customer and Growth (Tier 2 Boundary & Negative)

#### `TC-T2-J-01`: Invalid Destination Phone Format Rejection
- **Algorithm:** Alg #095 (Exact phone normalization)
- **Objective:** Submit malformed, short, or invalid country-code phone numbers during lookup.
- **Method & Endpoint:** `POST /v1/wallet/recipients/lookup`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:** `{"rail": "wallet", "phone": "12345"}`
- **Expected Status:** `400 Bad Request`
- **Assertions:**
  1. Response returns validation error on phone format.

#### `TC-T2-J-02`: Recipient Phone Number Not Found in Wallet Directory
- **Algorithm:** Alg #095 (Phone normalization), Alg #037 (Audited lookups)
- **Objective:** Look up valid phone number that has no registered wallet account.
- **Method & Endpoint:** `POST /v1/wallet/recipients/lookup`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:** `{"rail": "wallet", "phone": "0249999999"}`
- **Expected Status:** `200 OK`
- **Assertions:**
  1. Response contains `status: "not_found"`.
  2. `verifiedName` and `confirmationId` are strictly `null`.
  3. Increments misses counter for sender rate limiting.

#### `TC-T2-J-03`: Stale Recipient Phone Reassigned to Different Customer
- **Algorithm:** Alg #095 (Normalization), Alg #038 (Bound confirmations)
- **Objective:** Generate confirmation for Customer A; ops updates Customer A phone number; attempt transfer with original confirmation.
- **Method & Endpoint:** `POST /v1/wallet/transfers/confirmed`
- **Headers:**
  ```http
  Authorization: Bearer <sender_jwt>
  Idempotency-Key: stale-reassign-003
  Content-Type: application/json
  ```
- **Payload:** `{"confirmationId": "<uuid_generated_before_reassign>", "amount": 5000}`
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Response returns `code: "confirmation_stale"`.
  2. Stale binding rejected.

#### `TC-T2-J-04`: Savings Goal Withdrawal Exceeding Available Goal Balance
- **Algorithm:** Alg #097 (Savings goals and round-ups)
- **Objective:** Attempt withdrawal from savings goal bucket greater than accrued savings balance.
- **Method & Endpoint:** `POST /v1/wallet/savings/goals/{id}/withdraw`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:** `{"amount": 500000}`
- **Expected Status:** `409 Conflict`
- **Assertions:**
  1. Returns `code: "insufficient_savings_funds"`.

#### `TC-T2-J-05`: Unauthorized Override of Personalized Policy Ceilings
- **Algorithm:** Alg #100 (Personalized limits)
- **Objective:** Customer attempts to unilaterally update their own personal transfer limit above approved policy ceiling.
- **Method & Endpoint:** `PUT /v1/wallet/limits`
- **Headers:**
  ```http
  Authorization: Bearer <customer_jwt>
  Content-Type: application/json
  ```
- **Payload:** `{"dailyLimit": 99999999}`
- **Expected Status:** `403 Forbidden` (or `404 Not Found`)
- **Assertions:**
  1. Customer cannot override compliance limit ceilings.

---

## 5. Standalone E2E Test Runner & Execution Recommendations

### 5.1 Test Execution Harness Architecture
To provide reproducible, automated opaque-box verification across CI and local environments, we recommend a dual-runner approach:
1. **JUnit 5 / Spring Boot Test Harness (`src/test/java/com/elepay/e2e/`):**
   - Inherits from `PostgresTestBase` with PostgreSQL 18 container.
   - Bootstraps real Spring Boot server on random port (`@SpringBootTest(webEnvironment = RANDOM_PORT)`).
   - Uses `TestApi` / `RestClient` for pure HTTP dispatch.
   - Organized into clean test classes matching the 10 domains:
     - `LedgerMoneyE2ETest.java` (Domain A)
     - `PaymentProcessingE2ETest.java` (Domain B)
     - `FraudRiskE2ETest.java` (Domain C)
     - `ComplianceControlsE2ETest.java` (Domain D)
     - `ReconTreasuryE2ETest.java` (Domain E)
     - `FxCrossBorderE2ETest.java` (Domain F)
     - `CreditE2ETest.java` (Domain G)
     - `SecurityE2ETest.java` (Domain H)
     - `ReliabilityPlatformE2ETest.java` (Domain I)
     - `CustomerGrowthE2ETest.java` (Domain J)
2. **Python Standalone CLI Runner (`scripts/run_e2e.py`):**
   - Extends the pattern established in `scripts/smoke.py` and `scripts/acceptance.py`.
   - Accepts `--url`, `--token`, `--tier=1,2,3,4`, `--domain=A,B,C...` arguments.
   - Can run against any live, staging, or local environment without requiring a Java development toolchain.

### 5.2 Test Data Isolation Strategy
- Every test run generates a unique hex run prefix (`UUID.randomUUID().hex[:8]`).
- Customer subjects, phone numbers (`TestApi.uniquePhone()`), merchant trading names, and order references include this run prefix.
- Zero cross-test state leakage.
- Post-condition check: Every test suite execution terminates with a call to `GET /v1/ops/ledger/integrity` asserting `accountsWithDrift == 0`, `unbalancedTransactions == 0`, and `netByCurrency == 0` across all currencies.

---

## 6. Summary of Deliverables & Hand-Off Status

| Metric | Target | Designed & Documented | Status |
|---|---|---|---|
| **Tier 1 Feature Coverage (Happy-Path)** | >= 5 per domain (>= 50 total) | **50 Test Cases** (5 in each of A–J) | **COMPLETE** |
| **Tier 2 Boundary & Negative Cases** | >= 5 per domain (>= 50 total) | **50 Test Cases** (5 in each of A–J) | **COMPLETE** |
| **Total Test Cases Detailed** | >= 100 cases | **100 Concrete Test Cases** | **COMPLETE** |
| **100 Algorithms Surveyed** | 100 algorithms | **All 100 Algorithms Surveyed (001–100)** | **COMPLETE** |
| **Opaque-Box Compliance** | 100% HTTP / REST | **100% Contract & HTTP Driven** | **COMPLETE** |

This handoff report is saved to `/home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/e2e_exp_r1_1/handoff.md` and submitted to the E2E Testing Track Orchestrator.
