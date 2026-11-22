# ELEPAY Core — End-to-End (E2E) Test Infrastructure Specification

**Document Version:** 1.0.0  
**Status:** ACTIVE  
**Track:** E2E Testing Track  
**Author:** E2E Testing Orchestrator  
**Applicability:** Core Financial Engine, REST APIs, Ledgers, Adapters, Risk, Compliance, Operations  

---

## 1. Test Philosophy & Guiding Principles

ELEPAY Core is a mission-critical, Ghana-first financial platform. The E2E test infrastructure enforces the highest standard of financial correctness, resilience, and security.

### 1.1 Pure Opaque-Box Execution
- **Strict Network Boundary:** All E2E tests interact with ELEPAY exclusively over HTTP via standard REST endpoints (`/v1/...`, `/dev/token`, `/ops/...`).
- **No Classpath Coupling:** Tests do not import internal Java beans, entities, or private module interfaces.
- **No Direct Database State Manipulation:** Tests never execute raw SQL against PostgreSQL to insert records or bypass state machines. All database state transitions must occur naturally via authentic HTTP API requests.
- **Zero Mocking of Core Logic:** Financial calculations (double-entry postings, row locking, balance deductions, fee allocations) are executed by the real running server.

### 1.2 Non-Negotiable Financial Invariants
Every test run must satisfy and verify the following non-negotiable invariants:
1. **Integer Minor Units:** All monetary amounts are handled strictly in integer minor units (e.g. Ghana Pesewas `100 pesewas = GH₵1.00`, USD/GBP Cents). Binary floating-point representation of money is strictly forbidden.
2. **Zero-Sum Per-Currency Ledger:** Every journal entry must balance independently per currency: $\sum \text{amount} = 0$. Cross-currency transactions balance independently in each currency via explicit FX clearing accounts.
3. **Zero Ledger Drift:** The authoritative balance of every account derived from journal transactions must match stored balance snapshots exactly (`accountsWithDrift == 0`, `unbalancedTransactions == 0`).
4. **Idempotency Lifecycle:** Every state-changing financial mutation requires a unique `Idempotency-Key` header:
   - Identical retry with the same key returns the exact original response (`200 OK` or `201 Created`).
   - Altered payload with the same key returns `409 Conflict`.
5. **No Unauthorized Overdraft:** Available balances cannot drop below zero unless the account is explicitly configured as `allow_negative = true` (e.g. clearing/suspense).
6. **Multi-Tenant Isolation:** Resource lookups across different merchants or customers must fail closed (`404 Not Found` or `403 Forbidden`).

---

## 2. Complete 4-Tier Test Architecture

The E2E test suite is structured into four distinct, progressive tiers designed to validate every layer of the system:

```
tests/e2e/
├── framework/                          # Core test framework & opaque-box client
│   ├── client.py                       # OpaqueApiClient: HTTP client, dev token cache, idempotency manager
│   ├── config.py                       # TestConfig: base URL, dev secret, timeouts, CLI parameters
│   ├── fixtures.py                     # Data generators: random Ghana E.164 phones, UUIDs, IDs, minor units
│   ├── assertions.py                   # Financial math verifier, schema validators, ledger drift auditor
│   └── runner.py                       # Standalone discovery & execution engine, JUnit XML / JSON reporter
├── tier1_features/                     # Tier 1: Feature Coverage (>=5 happy-path tests per feature domain)
│   ├── test_ledger_features.py         # Domain A: Ledger & Money (001-010)
│   ├── test_payment_features.py        # Domain B: Payment Processing (011-022)
│   ├── test_risk_features.py           # Domain C: Fraud & Risk (023-036)
│   ├── test_compliance_features.py     # Domain D: Compliance Controls (037-048)
│   ├── test_reconciliation_features.py # Domain E: Reconciliation & Treasury (049-058)
│   ├── test_fx_features.py             # Domain F: FX & Cross-Border (059-064)
│   ├── test_credit_features.py         # Domain G: Credit (065-072)
│   ├── test_security_features.py       # Domain H: Security (073-084)
│   ├── test_platform_features.py       # Domain I: Reliability & Platform (085-094)
│   └── test_growth_features.py         # Domain J: Customer & Growth (095-100)
├── tier2_boundaries/                   # Tier 2: Boundary & Corner Cases (>=5 boundary/edge tests per domain)
│   ├── test_ledger_boundaries.py       # Domain A Boundary Cases
│   ├── test_payment_boundaries.py      # Domain B Boundary Cases
│   ├── test_risk_boundaries.py         # Domain C Boundary Cases
│   ├── test_compliance_boundaries.py   # Domain D Boundary Cases
│   ├── test_reconciliation_boundaries.py# Domain E Boundary Cases
│   ├── test_fx_boundaries.py           # Domain F Boundary Cases
│   ├── test_credit_boundaries.py       # Domain G Boundary Cases
│   ├── test_security_boundaries.py     # Domain H Boundary Cases
│   ├── test_platform_boundaries.py     # Domain I Boundary Cases
│   └── test_growth_boundaries.py       # Domain J Boundary Cases
├── tier3_combinations/                 # Tier 3: Cross-Feature Combinations (Pairwise interaction coverage)
│   ├── test_wallet_limits_combo.py     # Ledger + Wallet Transfers + Limit checks (KYC tier, velocity)
│   ├── test_checkout_split_combo.py    # Wallet + Checkout + Multi-allocation split
│   ├── test_checkout_refund_dispute.py # Checkout + Line Refunds + Reversals + Dispute holds
│   ├── test_payout_float_retry_combo.py# Payout batching + Float reservation + Rail retry deduplication
│   ├── test_fx_remittance_combo.py     # FX quotes + Locked TTL + Cross-currency clearing + Remittance
│   ├── test_outbox_inbox_combo.py      # Outbox event publishing + Inbox deduplication across consumers
│   └── test_maker_checker_combo.py     # Maker-checker dual control on privileged operations
└── tier4_scenarios/                    # Tier 4: Real-World Application Scenarios (Realistic multi-actor journeys)
    ├── test_marketplace_lifecycle.py   # Complete Ghana Marketplace Lifecycle
    ├── test_remittance_lifecycle.py    # International Remittance Lifecycle
    ├── test_network_chaos_recovery.py  # Adverse Network Recovery & Chaos Resilience
    └── test_financial_stress_suite.py  # High-Volume 10,000 Operation Financial Stress Suite
```

---

### 2.1 Tier 1: Feature Coverage (50 Happy-Path Tests)
Covers baseline happy paths across all 100 algorithms grouped by functional domain ($\ge 5$ tests per domain):

- **Domain A: Ledger and Money (Algorithms 001–010)**
  1. `TC-T1-A-01`: Balanced double-entry posting (`sum(amount) == 0`).
  2. `TC-T1-A-02`: Integer proportional fee allocation (largest remainder rule).
  3. `TC-T1-A-03`: Linked journal reversal referencing original transaction.
  4. `TC-T1-A-04`: Multi-currency cross-posting via per-currency FX clearing accounts.
  5. `TC-T1-A-05`: Point-in-time balance snapshot creation and journal consistency check.

- **Domain B: Payment Processing (Algorithms 011–022)**
  1. `TC-T1-B-01`: Top-up with durable intent and stable external reference.
  2. `TC-T1-B-02`: Callback state machine processing asynchronous provider events.
  3. `TC-T1-B-03`: Smart payment routing selecting optimal route based on health and cost.
  4. `TC-T1-B-04`: Dynamic EMVCo QR code payload generation with CRC16 checksum.
  5. `TC-T1-B-05`: Card BIN resolution returning scheme, issuer, and country metadata.

- **Domain C: Fraud and Risk (Algorithms 023–036)**
  1. `TC-T1-C-01`: Velocity window tracking permitting transactions within limits.
  2. `TC-T1-C-02`: Declarative rules engine scoring producing explainable decision trees.
  3. `TC-T1-C-03`: Device risk fingerprint evaluation during customer session.
  4. `TC-T1-C-04`: Geo-velocity check verifying plausible travel distance.
  5. `TC-T1-C-05`: Adaptive step-up authentication issuance bound to action payload.

- **Domain D: Compliance Controls (Algorithms 037–048)**
  1. `TC-T1-D-01`: Audited recipient phone lookup returning masked verified name.
  2. `TC-T1-D-02`: Ephemeral 5-minute single-use transfer confirmation token binding.
  3. `TC-T1-D-03`: KYC Tier 1-3 progressive transaction and balance ceilings.
  4. `TC-T1-D-04`: Ghana Card verification with explicit simulation tagging.
  5. `TC-T1-D-05`: Fuzzy sanctions screening using Jaro-Winkler and Soundex matching.

- **Domain E: Reconciliation and Treasury (Algorithms 049–058)**
  1. `TC-T1-E-01`: Exact 4-way statement matching of external statement lines to journal.
  2. `TC-T1-E-02`: Continuous ledger reconstruction proving `projected_balance == journal_balance`.
  3. `TC-T1-E-03`: Immutable settlement batch creation for mature merchant proceeds.
  4. `TC-T1-E-04`: Partner network fee verification against contractual fee schedule.
  5. `TC-T1-E-05`: Bilateral settlement netting calculating consolidated obligations.

- **Domain F: FX and Cross-Border (Algorithms 059–064)**
  1. `TC-T1-F-01`: Multi-provider FX rate ingestion and outlier filtering.
  2. `TC-T1-F-02`: Guaranteed FX quote generation with tiered margin and TTL expiry.
  3. `TC-T1-F-03`: Inbound corridor routing based on cost, liquidity, and speed.
  4. `TC-T1-F-04`: Real-time net open foreign currency exposure tracking.
  5. `TC-T1-F-05`: Multi-currency partner reconciliation of source, destination, and fees.

- **Domain G: Credit (Algorithms 065–072)**
  1. `TC-T1-G-01`: Cash-flow underwriting scoring merchant sales consistency.
  2. `TC-T1-G-02`: Earned-wage access (EWA) accrued salary eligibility calculation.
  3. `TC-T1-G-03`: Merchant cash advance pricing with transparent factor rate disclosure.
  4. `TC-T1-G-04`: Consented percentage sweeps from settled merchant proceeds.
  5. `TC-T1-G-05`: Dynamic credit limit calculation under strict regulatory policy ceiling.

- **Domain H: Security (Algorithms 073–084)**
  1. `TC-T1-H-01`: Constant-time HMAC-SHA256 webhook signature verification.
  2. `TC-T1-H-02`: BCrypt USSD PIN hashing and verification.
  3. `TC-T1-H-03`: Field-level envelope encryption for sensitive PII.
  4. `TC-T1-H-04`: Tamper-evident hash-chained audit log generation (`prev_hash + payload`).
  5. `TC-T1-H-05`: JWKS bounded public key caching and asynchronous key rotation.

- **Domain I: Reliability and Platform (Algorithms 085–094)**
  1. `TC-T1-I-01`: Transactional outbox event persistence atomic with domain mutation.
  2. `TC-T1-I-02`: Idempotent consumer inbox deduplication preventing re-processing.
  3. `TC-T1-I-03`: Distributed token-bucket rate limiter enforcing requests-per-minute.
  4. `TC-T1-I-04`: Circuit breaker health monitoring and state transitions.
  5. `TC-T1-I-05`: Bounded exponential backoff with full jitter on retryable network errors.

- **Domain J: Customer and Growth (Algorithms 095–100)**
  1. `TC-T1-J-01`: Ghana phone normalization converting local `024...` to E.164 `+23324...`.
  2. `TC-T1-J-02`: Automatic spending categorization without modifying immutable ledger.
  3. `TC-T1-J-03`: Voluntary transaction round-up sweeps to customer savings goal.
  4. `TC-T1-J-04`: Quiet-hours notification delivery scheduling.
  5. `TC-T1-J-05`: Transparent personalized limit profile evaluation.

---

### 2.2 Tier 2: Boundary & Corner Cases (50 Edge Tests)
Validates error conditions, security boundaries, race conditions, and negative cases ($\ge 5$ tests per domain):

- **Domain A: Ledger Boundaries:**
  1. `TC-T2-A-01`: Reject unbalanced journal entry (`sum(amount) != 0` -> 400).
  2. `TC-T2-A-02`: Reject negative balance overdraft on non-overdraft accounts (409/422).
  3. `TC-T2-A-03`: Idempotency collision with altered payload returns `409 Conflict`.
  4. `TC-T2-A-04`: Reject duplicate reversal of an already-reversed posting (409).
  5. `TC-T2-A-05`: Reject zero or negative monetary amount submissions (400).

- **Domain B: Payment Boundaries:**
  1. `TC-T2-B-01`: Concurrent checkout attempts spending identical balance (exact one 200, one 409).
  2. `TC-T2-B-02`: Repeat payment attempt on already-paid checkout returns 409.
  3. `TC-T2-B-03`: Invalid or tampered rail callback signature rejected (401/403).
  4. `TC-T2-B-04`: Reordered out-of-order callback delivery transitions safely without corruption.
  5. `TC-T2-B-05`: Stale callback replay ignored idempotently without duplicate balance credit.

- **Domain C: Risk Boundaries:**
  1. `TC-T2-C-01`: Burst velocity exceeding hourly limit blocked (429/403).
  2. `TC-T2-C-02`: Impossible geo-velocity movement flags transaction for review.
  3. `TC-T2-C-03`: SIM-swap detected within 72 hours triggers mandatory step-up challenge.
  4. `TC-T2-C-04`: High risk transaction without step-up authentication blocked.
  5. `TC-T2-C-05`: Coordinated circular referral graph flagged as promotion abuse.

- **Domain D: Compliance Boundaries:**
  1. `TC-T2-D-01`: Recipient phone lookup rate-limit exceeded (10/min) returns 429.
  2. `TC-T2-D-02`: Expired single-use confirmation token (>5 min) rejected (409).
  3. `TC-T2-D-03`: KYC Tier 1 transaction exceeding daily maximum (GH₵1,000) rejected (409).
  4. `TC-T2-D-04`: Exact sanctions match against OFAC/UN list blocks transaction immediately.
  5. `TC-T2-D-05`: Structuring (smurfing) pattern just below reporting threshold triggers AML alert.

- **Domain E: Reconciliation Boundaries:**
  1. `TC-T2-E-01`: Statement line with amount mismatch flagged as unreconciled exception.
  2. `TC-T2-E-02`: Premature settlement attempt before `settlement_delay_hours` rejected.
  3. `TC-T2-E-03`: Cumulative line-item refunds exceeding original captured amount rejected (409).
  4. `TC-T2-E-04`: Partner deducted fee discrepancy exceeding contract matrix flagged in audit.
  5. `TC-T2-E-05`: Suspense account debit without maker-checker approval rejected (403).

- **Domain F: FX Boundaries:**
  1. `TC-T2-F-01`: Outlier FX rate deviating >5% from consensus rejected during blending.
  2. `TC-T2-F-02`: FX quote execution after TTL expiration rejected (409 Quote Expired).
  3. `TC-T2-F-03`: Currency pair mismatch in corridor routing rejected (400).
  4. `TC-T2-F-04`: Foreign currency exposure exceeding risk ceiling triggers hedging hold.
  5. `TC-T2-F-05`: Discrepant dual-currency reconciliation flagged as FX exception.

- **Domain G: Credit Boundaries:**
  1. `TC-T2-G-01`: Live loan disbursement attempt while product status is disabled returns 403.
  2. `TC-T2-G-02`: EWA advance exceeding net accrued earnings rejected.
  3. `TC-T2-G-03`: Overdraft request with poor repayment history rejected.
  4. `TC-T2-G-04`: Duplicate daily settlement sweep attempt rejected idempotently.
  5. `TC-T2-G-05`: Unauthorized manual credit limit override blocked.

- **Domain H: Security Boundaries:**
  1. `TC-T2-H-01`: Cross-tenant balance query returns 404 Not Found (no enumeration).
  2. `TC-T2-H-02`: USSD PIN entry fails 3 times triggers 30-minute account lockout (423/403).
  3. `TC-T2-H-03`: Single-actor maker-checker self-approval strictly rejected (403).
  4. `TC-T2-H-04`: Signed client request with replayed nonce or expired timestamp rejected (401).
  5. `TC-T2-H-05`: Tampered audit log record hash triggers integrity verification alarm.

- **Domain I: Reliability Boundaries:**
  1. `TC-T2-I-01`: Outbox poller skip-locked prevents duplicate consumer processing.
  2. `TC-T2-I-02`: Tripped partner circuit breaker fails fast (503) without network call.
  3. `TC-T2-I-03`: Load shedding sheds non-critical telemetry under extreme load.
  4. `TC-T2-I-04`: PostgreSQL connection drop during transaction rollback safely without leak.
  5. `TC-T2-I-05`: Maximum retry budget exhaustion terminates gracefully with DLQ dispatch.

- **Domain J: Customer Boundaries:**
  1. `TC-T2-J-01`: Malformed phone number failing E.164 regex rejected (400).
  2. `TC-T2-J-02`: Savings round-up skipped gracefully when wallet balance is insufficient.
  3. `TC-T2-J-03`: High-priority fraud notification bypasses customer quiet-hours suppression.
  4. `TC-T2-J-04`: Personalized limit reduction immediately binds without grandfathering.
  5. `TC-T2-J-05`: Non-Ghana phone lookup on Ghana-only rail rejected (400).

---

### 2.3 Tier 3: Cross-Feature Combinations (7 Integrated Suites)
Validates interactions across module boundaries:

1. **`test_wallet_limits_combo.py` (Ledger + Wallet + Limits):**
   - Customer KYC Tier 2 limits + velocity tracking + transfer execution + ledger journal balancing.
   - Tests bound single-use confirmation tokens, single-tx limits, daily rolling ceiling against `Africa/Accra` midnight, and rapid burst token-bucket throttling.

2. **`test_checkout_split_combo.py` (Wallet + Checkout + Multi-Allocation Split):**
   - Single customer wallet debit of GH₵150.00 splits into Seller A (GH₵80.00), Seller B (GH₵50.00), Delivery D (GH₵20.00), and Platform Revenue.
   - Verifies fee deduction matrices (`bps + fixed`), merchant pending accounts, and duplicate checkout protection.

3. **`test_checkout_refund_dispute.py` (Checkout + Partial Refunds + Ledger Reversals + Dispute Holds):**
   - Checkout completed -> partial line refund issued -> merchant fee proportionally restored -> customer disputes remaining line -> funds locked into dispute hold bucket -> dispute resolution.

4. **`test_payout_float_retry_combo.py` (Payout Batching + Float Reservation + Rail Retry Deduplication):**
   - Up to 500 payout items batched -> available balance atomically moved to reserved -> independent item lifecycle (success, fail, delayed return) -> safe retry enforcement using `retry_of` constraint.

5. **`test_fx_remittance_combo.py` (FX Quotes + Locked TTL + Cross-Currency Clearing + Remittance):**
   - UK GBP quote generated with TTL -> accepted before expiry -> cross-currency balanced journal entries in GBP and GHS -> clearing account net zero -> recipient MoMo wallet delivery.

6. **`test_outbox_inbox_combo.py` (Transactional Outbox + Multi-Consumer Inbox Deduplication):**
   - Domain mutations publish outbox events in same DB transaction -> concurrent consumers with `SKIP LOCKED` process events -> inbox deduplication prevents double processing on network retry.

7. **`test_maker_checker_combo.py` (Maker-Checker Dual Control on Privileged Ops):**
   - Proposing operator creates ops item -> same operator attempt to approve fails with 403 -> distinct authorized operator approves -> action executes and records tamper-evident audit trail.

---

### 2.4 Tier 4: Real-World Application Scenarios (4 Multi-Actor Lifecycles)
Simulates end-to-end user journeys mirroring actual production financial lifecycles:

1. **`test_marketplace_lifecycle.py` (Complete Ghana Marketplace Lifecycle):**
   - Multi-actor workflow: Marketplace onboarding -> Ops review -> Seller invitations -> Dynamic payment link generation -> Customer wallet checkout -> Delayed settlement window -> Dispute resolution -> Merchant MoMo withdrawal -> Ledger drift verification.

2. **`test_remittance_lifecycle.py` (International Remittance Lifecycle):**
   - UK sender requests GBP-to-GHS quote -> Guaranteed rate locked with TTL -> Inbound corridor routing -> Recipient MTN MoMo payout -> Dual-currency journal posting -> Treasury float drawdown & rebalance alert.

3. **`test_network_chaos_recovery.py` (Adverse Network Recovery & Chaos Resilience):**
   - Rail submission experiences 504 Gateway Timeout -> Charge placed in `unknown` status without duplicate submission -> Customer repeat attempt returns same pending attempt -> Status query recovery confirms provider outcome -> Callback replay assault handled safely -> 4-way statement reconciliation matches.

4. **`test_financial_stress_suite.py` (High-Volume Financial Stress Suite):**
   - 10,000 pseudo-random operations executed with reproducible seed -> Top-ups, transfers, checkouts, line refunds, disputes, payouts, FX -> Continuous assertion of zero ledger drift, zero unbalanced transactions, and net-zero per currency.

---

## 3. Test Runner Invocation (`scripts/run_e2e.py`)

The test suite includes a zero-dependency, modular test runner built exclusively on Python 3 standard library modules (`unittest`, `urllib`, `dataclasses`, `argparse`, `concurrent.futures`, `json`, `uuid`). It executes out-of-the-box in restricted container environments without requiring `pip install` or virtual environments.

### 3.1 Command-Line Interface (CLI)
```bash
python3 scripts/run_e2e.py [OPTIONS]
```

### 3.2 CLI Options & Flags
| Flag | Description | Default |
|------|-------------|---------|
| `--base-url URL` | Base URL of running ELEPAY server | `http://127.0.0.1:8080` (or `$ELEPAY_URL`) |
| `--tier TIERS` | Comma-separated list of tiers to run (`1`, `2`, `3`, `4`) | `all` (1,2,3,4) |
| `--filter PATTERN` | Regex / substring pattern to filter test cases (`-k`) | `None` (run all) |
| `--fast` | Fast mode: skip slow recovery probes, run essential tests | `False` |
| `--seed INT` | Seed for reproducible randomized test data generators | `42` |
| `--verify-ledger` | Enforce `GET /v1/ops/ledger/integrity` after every suite | `True` |
| `--report PATH` | Path to export detailed JSON execution report | `target/e2e-report.json` |
| `--junit-xml PATH` | Path to export standard JUnit XML test report for CI | `target/junit-e2e.xml` |
| `--fail-fast` | Abort test execution immediately upon first assertion failure (`-x`) | `False` |
| `--concurrency INT` | Number of worker threads for parallel test execution | `1` |
| `-v, --verbose` | Verbose HTTP trace output on assertion failures | `False` |

### 3.3 Example Invocations
```bash
# Run entire E2E test suite across all 4 tiers with ledger verification
python3 scripts/run_e2e.py --tier 1,2,3,4 --verify-ledger

# Run only Tier 1 Feature tests in fast mode
python3 scripts/run_e2e.py --tier 1 --fast

# Run Tier 3 combination tests with JUnit XML output for CI
python3 scripts/run_e2e.py --tier 3 --junit-xml target/junit-e2e.xml

# Run specific scenario matching filter
python3 scripts/run_e2e.py --filter "marketplace"
```

---

## 4. Pass/Fail Semantics & Invariant Checks

A test run is considered **PASSED** if and only if **ALL** of the following conditions hold:

1. **HTTP Status Code Conformity:**
   - Happy path mutations return `200 OK` or `201 Created`.
   - Idempotency collisions return `409 Conflict`.
   - Unauthorized access returns `401 Unauthorized` or `403 Forbidden`.
   - Cross-tenant lookups return `404 Not Found`.
   - Rate limit violations return `429 Too Many Requests`.
2. **Schema & Contract Conformance:**
   - Response payloads match required fields and structures defined in `docs/openapi.json`.
3. **Idempotency Conservation:**
   - Resending any mutation with identical payload and key returns exact cached response without re-executing money movement.
4. **Authoritative Ledger Integrity Verification:**
   - At the completion of each suite, `GET /v1/ops/ledger/integrity` is invoked:
     ```json
     {
       "accountsWithDrift": 0,
       "unbalancedTransactions": 0,
       "netByCurrency": {
         "GHS": 0,
         "USD": 0,
         "GBP": 0,
         "EUR": 0
       }
     }
     ```
   - If `accountsWithDrift > 0` or `unbalancedTransactions > 0` or any currency net is non-zero, the test suite **FAILS IMMEDIATELY**.

---

## 5. Multi-Environment & CI Integration

### 5.1 Local Development
Developers launch the server with `sandbox` and `dev` profiles:
```bash
java -jar target/elepay-core-0.1.0-SNAPSHOT.jar --spring.profiles.active=sandbox,dev
python3 scripts/run_e2e.py --tier 1,2,3,4
```

### 5.2 CI Pipeline (`.github/workflows/sandbox.yml`)
Integrated into the GitHub Actions workflow following backend compilation:
```yaml
      - name: Run E2E Test Suite
        env:
          ELEPAY_URL: http://127.0.0.1:8080
        run: |
          # Wait for server readiness probe
          for attempt in {1..60}; do
            if curl -fsS http://127.0.0.1:8080/v1/ops/ledger/integrity >/dev/null 2>&1; then
              python3 scripts/smoke.py
              python3 scripts/acceptance.py
              python3 scripts/run_e2e.py --tier 1,2,3,4 --report target/e2e-report.json --junit-xml target/junit-e2e.xml --verify-ledger
              exit 0
            fi
            sleep 1
          done
          echo "Server failed to start" && exit 1
```

---

## 6. Verification and Readiness Sign-Off

Upon full test suite implementation, execution, and verification by the Parallel Verification Wave (2 Reviewers, 2 Challengers, 1 Forensic Auditor), the E2E Testing Track publishes:
`/home/antigravity/Elemarket9/ELEPAY-Core/TEST_READY.md`

Declaring that ELEPAY Core has achieved 100% test readiness across all 4 tiers.
