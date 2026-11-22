# E2E Test Runner and Execution Architecture Report

**Explorer:** Explorer 3 (E2E Testing Track)  
**Date:** 2026-11-22  
**Target Project:** ELEPAY Core  
**State Folder:** `/home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/e2e_exp_r1_3`  
**Recipient:** Sub-Orchestrator (`36f87fbfb73276bfc5b7a3efe94611a9`)

---

## Executive Summary

This report establishes the complete architecture and operational blueprint for the **ELEPAY Core End-to-End (E2E) Test Harness and Execution Architecture**.

Following mandatory inspection of:
1. `/home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/ORIGINAL_REQUEST.md`
2. `/home/antigravity/Elemarket9/ELEPAY-Core/PROJECT.md`
3. Existing scripts `scripts/smoke.py` and `scripts/acceptance.py`
4. The system environment (Debian/Ubuntu Python 3.11 with PEP 668 externally managed environment where `pytest` is **not** pre-installed)
5. The 81 REST endpoints declared in `docs/openapi.json` and authentication via `/dev/token`

We deliver a battle-tested, requirement-driven, **opaque-box** testing architecture across all 4 tiers that strictly tests ELEPAY externally over HTTP without coupling to internal Spring beans, database tables, or private classes.

---

## 1. Analysis of Existing Test Scripts

### 1.1 `scripts/smoke.py`
* **Target:** Legacy/transitional sandbox endpoints (`/sandbox/operations/{reference}`, `/sandbox/balances/{account}`, `/sandbox/payments/{path}`).
* **Strengths:**
  - Lightweight and fast (<1s).
  - Exercises concurrency via `concurrent.futures.ThreadPoolExecutor` testing race conditions on simultaneous checkout requests against the same balance.
  - Verifies idempotency replay and excess refund rejection (HTTP 409).
  - Pure standard library Python with zero external dependencies.
* **Limitations & Gaps:**
  - Bypasses real production REST controllers and domain workflows (does not hit `/v1/...`).
  - No authentication, token issuance, or role-based access control (RBAC) validation.
  - Does not test double-entry ledger invariant verification (`/v1/ops/ledger/integrity`).
  - Lacks modularity, structured reporting, and CLI configuration.

### 1.2 `scripts/acceptance.py`
* **Target:** Production REST API (`/v1/...`) on `http://127.0.0.1:8080`.
* **Strengths:**
  - Authenticates via `/dev/token` with roles (`ops`, customer, merchant owner).
  - Implements a genuine 12-step commercial acceptance flow:
    1. Customer creation via `/v1/ops/customers`
    2. Recipient phone lookup & name normalization via `/v1/wallet/recipients/lookup`
    3. MoMo top-up with `Idempotency-Key` via `/v1/wallet/topups`
    4. Rail recovery trigger via `/v1/ops/rails/recover`
    5. Multi-merchant marketplace checkout with allocations (seller A, seller B, delivery D)
    6. Payment confirmation with idempotency replay & duplicate rejection (409)
    7. Multi-tenant isolation verification (Seller B cannot view Seller A's balances -> 404)
    8. Line-item refund, settlement run (`/v1/ops/settlements/run`), and merchant withdrawal
    9. Authoritative ledger drift verification (`/v1/ops/ledger/integrity`: zero drift, zero unbalanced transactions, net-zero by currency).
* **Limitations & Gaps:**
  - Monolithic linear script with failure cascading: if step 1 fails, the entire script aborts without running subsequent features.
  - No tier separation: does not distinguish between unit feature tests, boundary checks, pairwise combinations, or complex end-to-end scenarios.
  - No CLI flags (`--tier`, `--filter`, `--report`, `--seed`, `--fast`).
  - Only covers a single happy-path slice; misses FX corridors, USSD pin lockouts, disputes, sanctions/PEP compliance, and fee schedules.

---

## 2. E2E Test Harness Architecture

### 2.1 Directory Structure
The test harness is organized under `tests/e2e/`, completely decoupled from JVM internal code:

```
tests/e2e/
├── __init__.py
├── framework/                          # Core test framework & opaque-box client
│   ├── __init__.py
│   ├── client.py                       # OpaqueApiClient: HTTP client, dev token cache, idempotency manager
│   ├── config.py                       # TestConfig: base URL, dev secret, timeouts, CLI parameters
│   ├── fixtures.py                     # Data generators: random Ghana E.164 phones, UUIDs, IDs, minor units
│   ├── assertions.py                   # Financial math verifier, schema validators, ledger drift auditor
│   └── runner.py                       # Standalone discovery & execution engine, JUnit XML / JSON reporter
├── tier1_features/                     # Tier 1: Feature Coverage (>=5 happy-path tests per feature domain)
│   ├── __init__.py
│   ├── test_wallet_features.py         # Wallet enrollment, topups, transfers, receipts, statements
│   ├── test_checkout_features.py       # Payment links, QR codes, allocations, wallet payment
│   ├── test_payout_features.py         # Single payout, batch payout (up to 500), rail status
│   ├── test_fx_features.py             # Corridors, quotes with TTL, conversions, FX transfers
│   ├── test_compliance_features.py     # Recipient lookup, rate limiting, KYC limits, sanctions
│   ├── test_ledger_features.py         # Double-entry posting verification, balance reconciliation
│   └── test_ussd_features.py           # USSD menu session, PIN hashing, balance inquiry
├── tier2_boundaries/                   # Tier 2: Boundary & Corner Cases (>=5 boundary/edge tests per feature)
│   ├── __init__.py
│   ├── test_monetary_boundaries.py     # 0 pesewas, negative amounts, INT64 limits, decimal rejection
│   ├── test_idempotency_boundaries.py  # Key collision with altered payload (409), whitespace/missing keys
│   ├── test_kyc_limits_boundaries.py   # Tier 1-3 balance caps, daily velocity caps, single tx thresholds
│   ├── test_security_boundaries.py     # Cross-tenant balance reading (404), expired tokens, tampering
│   └── test_rail_boundaries.py         # Unknown outcome recovery, simulated timeout, double callback
├── tier3_combinations/                 # Tier 3: Cross-Feature Combinations (Pairwise interaction coverage)
│   ├── __init__.py
│   ├── test_checkout_refund_settle.py  # Checkout split + partial allocation refund + net settlement
│   ├── test_fx_and_payout.py           # Locked FX quote + cross-border remittance + rail retry
│   ├── test_dispute_and_settlement.py  # Merchant dispute hold + partial settlement exclusion
│   └── test_rate_limit_and_lookup.py   # Lookup velocity limit + USSD PIN lockout interaction
└── tier4_scenarios/                    # Tier 4: Real-World Application Scenarios (Realistic multi-actor journeys)
    ├── __init__.py
    ├── test_marketplace_lifecycle.py   # Marketplace onboarding -> 3 sellers -> split checkout -> refund -> payout -> zero drift
    ├── test_creator_remittance_flow.py # International creator payout: USD quote -> GHS MoMo wallet -> statement audit
    └── test_momo_agent_cash_cycle.py   # High-velocity agent cash-in/cash-out -> drift verification under load
```

### 2.2 Test Runner Design (`scripts/run_e2e.py`)
Because the Debian/Ubuntu Python environment has PEP 668 enabled (pip is restricted and `pytest` is not installed system-wide), the runner is designed as a **zero-dependency modular test runner** built on Python's standard library (`unittest`, `urllib`, `dataclasses`, `argparse`, `concurrent.futures`, `json`, `uuid`):
1. **Zero External Dependencies:** Runs natively on standard Python 3.11+ without virtual environments or `pip install`.
2. **Dual Execution Compatibility:**
   - Run via `python3 scripts/run_e2e.py` for automated orchestration and CI.
   - Run via `pytest tests/e2e/` if an engineer chooses to install pytest in a virtual environment.
3. **CLI Arguments & Flags:**
   ```bash
   python3 scripts/run_e2e.py [OPTIONS]

   Options:
     --base-url URL        Target ELEPAY server URL (default: http://127.0.0.1:8080 or $ELEPAY_URL)
     --tier TIERS          Comma-separated list of tiers to run: 1, 2, 3, 4 (default: all)
     --filter PATTERN      Regex or substring pattern to filter test classes/methods (-k)
     --fast                Fast mode: skip slow recovery probes, run smoke/essential subset
     --seed INT            Reproducible random seed for test data generators
     --verify-ledger       Perform GET /v1/ops/ledger/integrity check after every test/suite
     --report PATH         Generate machine-readable JSON execution report
     --junit-xml PATH      Generate standard JUnit XML report for CI artifact publishing
     --fail-fast           Abort suite execution immediately upon first failure (-x)
     --concurrency INT     Number of worker threads for parallel test execution (default: 1)
     -v, --verbose         Verbose HTTP trace output on assertion failure
   ```

---

## 3. Server Interaction & Opaque-Box Protocol

### 3.1 Strict Opaque-Box Boundary
The test suite operates strictly as an external API consumer over HTTP:
- **No Classpath Imports:** Never imports com.elepay.* classes.
- **No Direct Database Access:** Never executes raw SQL against PostgreSQL.
- **No Reflection / In-Memory Mocking:** Interacts solely via REST HTTP requests.

### 3.2 Authentication & Role Matrix
Authentication uses the server's `/dev/token` endpoint (enabled under `dev` and `test` Spring profiles):
- **Ops Persona:** `TestClient.mint_token("ops-admin", roles=["ops"])`
  - Authorized for `/v1/ops/...` endpoints (customer onboarding, merchant review, settlements, rail recovery, ledger integrity).
- **Merchant Persona:** `TestClient.mint_token("merchant-owner", roles=[])`
  - Authorized for `/v1/merchants/{id}/...` endpoints (balances, payouts, checkouts, webhooks, API keys).
- **Customer Persona:** `TestClient.mint_token("customer-user", roles=[])`
  - Authorized for `/v1/wallet/...` endpoints (recipients lookup, transfers, topups, statements).
- **Public Persona:** Unauthenticated requests with standard headers for `/v1/public/...` endpoints.

### 3.3 Mandatory Idempotency Lifecycle
Every state-changing financial command (POST/PUT/DELETE on `/v1/wallet/transfers`, `/v1/wallet/topups`, `/v1/checkouts/*/pay/wallet`, `/v1/merchants/*/refunds`, `/v1/merchants/*/withdrawals`) must:
1. Include an `Idempotency-Key: <UUID>` header.
2. Verify that resending the exact same payload with the same key returns the **identical cached response (HTTP 200/201)**.
3. Verify that sending a different payload with the same key returns **HTTP 409 Conflict**.

### 3.4 Ledger Invariant & Drift Verification
At the end of financial operations or test suites, the test runner invokes:
```http
GET /v1/ops/ledger/integrity
Authorization: Bearer <ops-token>
```
The test harness verifies:
1. `accountsWithDrift == 0`
2. `unbalancedTransactions == 0`
3. `netByCurrency`: all currencies (e.g. `GHS`, `USD`) must balance to `0`.
Any deviation raises an immediate `LedgerIntegrityViolationError` which fails the test run.

---

## 4. Multi-Environment Execution Strategy

### 4.1 Local Development Environment
A developer can start the backend and execute the suite locally:
```bash
# Terminal 1: Launch ELEPAY with sandbox & dev profiles
java -jar target/elepay-core-0.1.0-SNAPSHOT.jar --spring.profiles.active=sandbox,dev

# Terminal 2: Run all 4 tiers with ledger verification
python3 scripts/run_e2e.py --tier 1,2,3,4 --verify-ledger
```

### 4.2 CI Environment (GitHub Actions `.github/workflows/sandbox.yml`)
The E2E test runner integrates smoothly into the existing CI pipeline without requiring root or external package installs:
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

## 5. Outline and Structure for `TEST_INFRA.md`

We propose the following complete structure for `/home/antigravity/Elemarket9/ELEPAY-Core/TEST_INFRA.md`:

```markdown
# ELEPAY Core — E2E Test Infrastructure Specification

## 1. Testing Philosophy & Invariant Guarantees
- Opaque-box principle: pure HTTP client interaction, zero database backdoor mutations
- Financial correctness: integer minor units, per-currency balance triggers, atomic double-entry
- Strict idempotency contract: replay guarantees and 409 collision enforcement
- Zero-drift ledger invariant: continuous verification via /v1/ops/ledger/integrity

## 2. Directory Layout & Architecture
- Detailed tree of tests/e2e/ (framework/, tier1_features/, tier2_boundaries/, tier3_combinations/, tier4_scenarios/)
- Component responsibilities (client, config, fixtures, assertions, runner)

## 3. The 4-Tier Test Suite Specification
- **Tier 1: Feature Coverage (>=5 happy-path tests per domain)**
  * Wallet (lookup, top-up, transfer, preview, receipt)
  * Checkout (merchant checkout, split allocations, payment links, QR, fee calculation)
  * Payout (single payout, batch payout, retry, status query)
  * FX & Remittances (corridors, locked quotes with TTL, conversion, transfer)
  * Compliance & Operations (customer review, KYC tier limits, BoG/FIC reporting)
- **Tier 2: Boundary & Corner Cases (>=5 tests per domain)**
  * Monetary boundaries (0 amount, negative amount, MAX_INT64, float/string injection)
  * Idempotency edge cases (re-use with altered body -> 409, empty key, duplicate callbacks)
  * KYC tier caps & velocity breaches (daily rolling limit, single tx limit)
  * Multi-tenant security boundaries (cross-merchant balance inspection -> 404, invalid auth)
- **Tier 3: Cross-Feature Combinations (Pairwise coverage)**
  * Checkout + allocation refund + settlement netting
  * FX quote expiration + corridor failover + payout retry
  * Dispute hold + merchant withdrawal lock + partial settlement
  * Rapid lookup velocity + step-up authentication challenge
- **Tier 4: Real-World Application Scenarios (End-to-end holistic flows)**
  * Marketplace lifecycle: Onboarding -> 3 vendors -> split checkout -> partial return -> withdrawal
  * International remittance: USD quote -> GHS MoMo delivery -> dual-currency reconciliation
  * High-velocity MoMo agent cash cycle with balance auditing

## 4. Standalone Test Runner (`scripts/run_e2e.py`)
- Zero external dependencies rationale (PEP 668 compliance)
- Full CLI reference manual and options table
- Dynamic test discovery mechanism (`unittest.defaultTestLoader`)
- JSON and JUnit XML reporting schemas

## 5. Opaque HTTP Client & Framework Reference
- `OpaqueApiClient` API: `get()`, `post()`, `put()`, `delete()`, `mint_token()`
- Automatic bearer token injection & idempotency key management
- Standardized error parsing and response assertion methods

## 6. Ledger Drift & Invariant Verification Protocol
- Detailed contract of `/v1/ops/ledger/integrity`
- Invariant assertion formulas (`drift == 0`, `unbalanced == 0`, `netByCurrency == 0`)
- Audit trigger hooks (post-suite, post-scenario, and failure diagnostics)

## 7. Execution Environments & CI/CD Integration
- Local execution runbook
- GitHub Actions workflow step definitions
- Docker container testing integration
- Diagnostics and triage procedures for test failures
```

---

## 6. Recommendations & Next Steps for Sub-Orchestrator

1. **Adopt Zero-Dependency Python Architecture:** Keep `scripts/run_e2e.py` and `tests/e2e/` strictly using standard library Python 3.11 (`unittest`, `urllib.request`). This eliminates external pip installation failures in the containerized Debian environment.
2. **Standardize Test Base Class:** All tier tests inherit from `OpaqueTestCase` which provides self-contained test isolation, a unique run suffix (e.g. `uuid4().hex[:8]`), automatic customer/merchant enrollment helpers, and automated ledger verification in `tearDown()`.
3. **Approve `TEST_INFRA.md` Creation:** Authorize Worker 1 to draft `/home/antigravity/Elemarket9/ELEPAY-Core/TEST_INFRA.md` following this structure.
4. **Schedule Suite Implementation:** Authorize implementation of the test harness framework and Tier 1-4 suites under `tests/e2e/` and `scripts/run_e2e.py`.
