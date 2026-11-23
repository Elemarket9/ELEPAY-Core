# Project: ELEPAY-Core — Stage 1 Launch Readiness, Kill-Switch Matrix, Dead-End Security Gates, Custodian Bank Escrow & Benchmarking

## Product boundary
ELEPAY is strictly fintech. Core financial functions are wallet accounts, append-only double-entry ledger, domestic Ghana payments and collections, provider-neutral multi-merchant payment allocations, financial escrow, settlement, payouts and reconciliation, FX conversion, treasury, reserves, lending, virtual cards, POS payment handling, bill payment, agent banking, compliance (KYC/AML), fraud controls, security, merchant financial BackOffice, developer APIs and webhooks.

Merchant tools restored Nov 23 2026 by founder decision (`com.elepay.merchanttools`): POS held carts, inventory, product catalogs, purchasing, warehouses, restaurant/KDS, CRM, loyalty, coupons, campaigns, payroll and suppliers. These are merchant software, not regulated payment activities. Exception: gift cards and store credit hold customer value and must stay switched off for live use until BoG confirms the e-money position.

**International payouts / remittance EXCLUDED from current scope per founder decision Nov 23 2026. Only domestic settlement.** International card acceptance and foreign-currency merchant collections remain in scope; FX conversion and merchant settlement must end at authorized domestic Ghana destinations.

## External integrations and compliance status
**Live bank, MoMo, card networks: NOT YET CONNECTED. Local testing uses sandbox/test fixtures only.**

**GRA integration NOT IMPLEMENTED - manual submission required.** No generated local fiscal receipt, signature or acknowledgment may be presented as a successful agency submission.

**E-Levy repealed April 2025 - no longer charged. Historical only.** Historical audit records must not be changed or used to originate a deduction.

## Engineering
Java 25, Spring Boot 4.2.0, Spring Modulith 2.2.0, PostgreSQL 18, Flyway, Maven. Preserve the modular monolith, the append-only double-entry ledger, accounting idempotency and the separation of external providers from internal financial state. Do not edit applied Flyway migrations; introduce only new migration versions (`V49+`) for approved data structure changes, and register all new tables in `src/test/resources/table-owners.properties`.

## Architecture
- **Stage 1 Kill-Switch Matrix & Route-Level Filter (`com.elepay.identity` / `com.elepay.payout`)**:
  - `Stage1KillSwitchFilter` runs before authentication/database lookups and returns HTTP `404` (`{"code":"FEATURE_NOT_ENABLED","error":"FEATURE_NOT_ENABLED","module":"...","message":"This module is disabled in Stage 1"}`) for all default-off non-Stage-1 modules (`lending`, `virtual-cards`, `pos`, `bill-payments`, `agent-banking`, `fx-trading`, `consumer-w2w`/`consumer-wallet`, `consumer-ussd`, `consumer-mandates`, `consumer-growth`).
  - Global Kill Switch (`POST /admin/kill-switch/global` & `POST /v1/ops/kill-switch/global`) stops all payouts/withdrawals/transfers in < 1 second via Redis + atomic in-memory state.
  - Merchant Kill Switch (`POST /admin/kill-switch/merchants/{merchantId}` & `POST /v1/ops/kill-switch/merchants/{merchantId}`) stops payouts for 1 merchant only while others continue.
  - API Key Kill Switch (`ApiKeys.revoke`) propagates to Redis + in-memory cache so revoked keys fail authentication in < 1 second.
  - Emergency Payout Velocity Ceiling tracks rolling hourly and daily global GHS cedi outflow and auto-freezes outbound payouts on breach while collections/checkouts/callbacks continue uninterrupted.
- **Custodian Bank Escrow, Submerchant Accounting & Outflow Backstop (`com.elepay.rails`, `com.elepay.checkout`, `com.elepay.settlement`, `com.elepay.enterprise`)**:
  - Multi-merchant split collections, submerchant virtual ledger accounts (`pending`, `available`, `reserved`), delivery escrow holds, conditional settlement releases, and batch/payroll disbursements operate with zero dependency on Consumer W2W (`com.elepay.wallet`).
  - `CustodianBankBackstopService` in `com.elepay.rails` hooks into `RailTransactions.send` to enforce hard daily outflow caps, per-payout caps, reserve floors, and emergency custodian freeze at the physical rail boundary, plus `verifyTrustSafeguarding` (`GET /v1/ops/custodian/safeguard-report`).
- **Dead-End Security Gates (`com.elepay.identity`, `com.elepay.merchant`, `com.elepay.payout`, `com.elepay.security`, `com.elepay.common`)**:
  - **Gate 1 (Stolen Merchant API Key / Login -> Dead End)**:
    - Tiered API key prefixes & strict scopes: `pk_live_*` (can ONLY create payment intent/checkout), `sk_live_*` (can create + read + refund, CANNOT initiate payouts/withdrawals alone), `payout_live_*` (required for API-key payouts/withdrawals).
    - IP Allowlist (`allowed_ips` on `api_credential` enforced via `CidrBlock` for `payout_live_*` keys; unregistered IP = 403 `ip_not_allowlisted`).
    - Payout Destination Allowlist + 24-Hour Cooling-Off Lock + Out-of-Band Confirmation: Payouts (`withdrawals` and `payout-batches` when destination-allowlist enforced) only allowed to pre-approved, verified `settlement_destination` accounts after the 24-hour cooling-off window (`cooling_off_until`).
  - **Gate 2 (Compromised Ops/Admin Account -> Dead End)**:
    - Mandatory Maker-Checker Dual Control (`MakerCheckerService` + `maker_checker_proposal` table with DB trigger `reviewed_by <> proposed_by`): no single admin can approve large payouts, override escrow/reserve holds, force settlements, or change settlement rules / unfreeze kill switches alone.
  - **Gate 3 (Compromised App Server -> Dead End)**:
    - `KmsSecretProvider` wrapping `EnvelopeEncryption` + `KeyRotationService` (refusing disk file secrets in `ProductionSafetyCheck`).
    - Strict Egress Firewall / Outbound Allowlist in `OutboundUrlGuard` (allowing only Custodian Bank, MTN, Telecel, AT, GhIPSS) + `deploy/edge/egress-network-policy.yaml`.
    - Append-only DB permissions in `V49`: immutability triggers on all ledger & audit tables + PostgreSQL `EVENT TRIGGER` (`elepay_block_immutable_trigger_disable`) blocking `ALTER TABLE ... DISABLE TRIGGER`.
- **Stage 1 Benchmarking, Adversarial Pentest & CI/CD OWASP Remediation**:
  - Batch hash-join optimization in `TenWayClearingReconciliationEngine`, `Mt940StatementParser`, and `ReconciliationService` to reconcile 50k–100k transactions in seconds.
  - `Stage1BenchmarkAndPentestIT` verifying concurrent split/callback/payout zero-drift, 50k+ reconciliation speed, and all Proof 1 & Proof 2 adversarial pentest gates, plus live curl proof execution.
  - `pom.xml` (`security-scan` profile) & `.github/workflows/sandbox.yml` fix using the OWASP NVD 2.0 cached datafeed mirror so `mvn -B -Psecurity-scan -DskipTests verify` passes 100% green without failing on empty `NVD_API_KEY`.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Stage 1 Route-Level Kill-Switch Matrix (`404 FEATURE_NOT_ENABLED`) | Default-off server-side filter returning 404 with `FEATURE_NOT_ENABLED` before DB/business logic for `lending`, `virtual-cards`, `pos`, `bill-payments`, `agent-banking`, `fx-trading`, and consumer W2W when disabled | M1 | survey_1, survey_3 |
| 2 | Global, Merchant & API-Key (<1s) Kill Switches + Velocity Ceiling | `POST /admin/kill-switch/global`, merchant-scoped payout freeze, <1s Redis API key revocation cache, and automatic hourly/daily global cedi velocity ceiling | M1 | survey_1, survey_3 |
| 3 | Custodian Bank Escrow, Submerchant Splits Without W2W & Outflow Backstop | Filter `"wallet"` when consumer W2W is off; `CustodianBankBackstopService` at `RailTransactions` boundary enforcing hard daily outflow caps, per-payout caps, reserve floor, and trust safeguarding report | M1 | survey_2 |
| 4 | Dead-End Gate 1: `pk_live` / `sk_live` / `payout_live` Scopes, IP Allowlist, Destination Allowlist & 24h Cooling-Off Lock | Tiered key prefixes/scopes, IP allowlist for `payout_live`, pre-approved verified destination allowlist, OOB confirmation, and 24-hour cooling-off lock | M1 | survey_3 |
| 5 | Dead-End Gate 2: Mandatory Maker-Checker Dual Control | `MakerCheckerService` + DB trigger enforcing `proposed_by <> reviewed_by` for large payouts, escrow/reserve hold releases, settlement rule changes, and kill-switch unfreezes | M1 | survey_3 |
| 6 | Dead-End Gate 3: KMS/HSM Secret Abstraction, Strict Egress Firewall & Append-Only DB Event Trigger | `KmsSecretProvider` envelope encryption, `OutboundUrlGuard` strict partner allowlist, and `V49` append-only triggers + `elepay_block_immutable_trigger_disable` event trigger | M1 | survey_3 |
| 7 | CI/CD OWASP Security Scan Remediation | Fix `pom.xml` `security-scan` profile and `.github/workflows/sandbox.yml` NVD datafeed configuration so OWASP Dependency-Check passes cleanly | M1 | survey_3 |
| 8 | 50k–100k Fast Reconciliation Optimization, Stage 1 Concurrency Benchmark & Focused Pentest + Live Curl Proofs | Batch hash-join reconciliation engine refactor (<15s for 50k txns), `Stage1BenchmarkAndPentestIT` covering all proofs, and live curl output verification | M1 | survey_3 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | M1: Stage 1 Kill-Switch Matrix, Custodian Bank Backstop, Dead-End Security Gates, OWASP Fix & Stage 1 Benchmarking/Pentest | Complete implementation and verification of Features 1–8 across `V49` migration, identity, merchant, payout, rails, security, common, reconciliation, CI/CD config, and integration/benchmark/pentest tests | none | IN_PROGRESS |

## Interface Contracts
### `Stage1KillSwitchFilter` & `KillSwitchController` ↔ `Payouts` / `ApiKeys`
- `POST /admin/kill-switch/global` & `POST /v1/ops/kill-switch/global`: trips global payout/withdrawal/transfer freeze in <1s; `Payouts.create`, `Payouts.submit`, `MerchantPayouts.withdraw`, `MerchantPayouts.batch` reject with `GLOBAL_KILL_SWITCH_ACTIVE`.
- `POST /admin/kill-switch/merchants/{merchantId}` & `POST /v1/ops/kill-switch/merchants/{merchantId}`: freezes payouts for `merchantId` only (`MERCHANT_KILL_SWITCH_ACTIVE`).
- Non-Stage-1 disabled routes (`/v1/lending/**`, `/v1/virtual-cards/**`, `/v1/cards/**`, `/v1/pos/**`, `/v1/bills/**`, `/v1/bill-payments/**`, `/v1/agents/**`, `/v1/agent-banking/**`, `/v1/fx/**`, `/v1/fx-trading/**`, etc.) return HTTP `404` with `{"code":"FEATURE_NOT_ENABLED","error":"FEATURE_NOT_ENABLED",...}`.

### `ApiKeys` / `SecurityConfig` ↔ `MerchantPayouts` / `Merchants`
- `pk_live_*` -> `key_type = 'publishable'`, scope restricted to payment intent / checkout creation (`POST /v1/merchants/{id}/checkouts`, `POST /v1/checkouts`).
- `sk_live_*` -> `key_type = 'secret'`, scopes `view`, `collect`, `refund`; rejected with `403 Forbidden` (`payout_key_required`) on payout/withdrawal endpoints.
- `payout_live_*` -> `key_type = 'payout'`, requires caller IP to match `api_credential.allowed_ips` (`403 Forbidden` `ip_not_allowlisted` if unregistered/mismatched IP) and destination to be pre-approved, verified, and past `cooling_off_until` (`403 Forbidden` `destination_not_allowlisted` / `destination_cooling_off_active`).

### `CustodianBankBackstopService` (`com.elepay.rails`) ↔ `RailTransactions`
- `authorizeOutboundDisbursement(UUID txId, String reference, String rail, String direction, long amountPesewas, String currency)` enforced inside `RailTransactions.send` before external rail invocation.

## Code Layout
- `src/main/resources/db/migration/V49__stage1_kill_switches_custodian_and_dead_end_gates.sql` — Consolidated Stage 1 Flyway migration
- `src/test/resources/table-owners.properties` — Register all new V49 tables to their owning modules (`identity`, `payout`, `rails`, `security`)
- `src/main/java/com/elepay/identity/*` — `Stage1KillSwitchFilter`, `KillSwitchService`, `KillSwitchController`, `ApiKeys`, `ApiKeyAuthentication`, `SecurityConfig`, `Actor`
- `src/main/java/com/elepay/merchant/*` — `Merchants`, `MerchantAccess` (24h cooling-off lock & OOB verification)
- `src/main/java/com/elepay/payout/*` — `Payouts`, `MerchantPayouts`, `PayoutPolicies`, `PayoutPolicyApi`, `PayoutVelocityService`
- `src/main/java/com/elepay/rails/*` — `CustodianBankBackstopService`, `RailTransactions`, `RailEndpoints`
- `src/main/java/com/elepay/security/*` — `MakerCheckerService`, `MakerCheckerController`, `KmsSecretProvider`, `ProductionSafetyCheck`
- `src/main/java/com/elepay/common/*` — `OutboundUrlGuard` (strict egress allowlist)
- `src/main/java/com/elepay/reconciliation/*` & `src/main/java/com/elepay/operations/*` — `TenWayClearingReconciliationEngine`, `Mt940StatementParser`, `ReconciliationService` (50k–100k batch hash-join optimization)
- `pom.xml` & `.github/workflows/sandbox.yml` — OWASP Dependency-Check NVD cache datafeed configuration
- `src/test/java/com/elepay/stage1/Stage1BenchmarkAndPentestIT.java` — End-to-end Proofs 1–6 verification, concurrency benchmark, 50k reconciliation speed benchmark, and adversarial pentest suite

## Readiness and evidence
The original 100-algorithm inventory describes source implementations, **implemented in code, not yet proven in production**. Re-run backend, frontend, integration, Flyway, security and ledger integrity tests at the exact commit. Mark CI green only when a successful run is observed; simulated/test payment rails do not imply live partner connection.
