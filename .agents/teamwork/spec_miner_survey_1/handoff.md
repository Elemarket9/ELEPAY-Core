# Spec Miner Survey Handoff Report — ELEPAY Core

**Agent**: `spec_miner_survey_1`  
**Milestone**: Step 0 Survey  
**Date**: 2026-11-22  
**Target Branch**: `main`  
**Authoritative Sources**:
- `ORIGINAL_REQUEST.md`
- `docs/product-specification.md`
- `docs/readiness-scorecard.md`
- `docs/openapi.json`
- `docs/wallet-api.md`, `docs/merchant-api.md`, `docs/payouts-api.md`, `docs/fx-api.md`, `docs/configuration.md`, `docs/sandbox.md`, `docs/ussd.md`
- `docs/runbooks/*.md`
- Source code in `src/main/java/com/elepay/...`, migrations in `src/main/resources/db/migration`, and scripts `scripts/smoke.py`, `scripts/acceptance.py`.

---

## 1. Observation

### 1.1 Direct Repository and Specification Observations
1. **Repository Layout and Stack**:
   - `pom.xml`: Java 25 baseline, Spring Boot 4, Spring Modulith, Flyway, PostgreSQL 18 testcontainers.
   - Codebase structure under `src/main/java/com/elepay`:
     `checkout`, `common`, `fees`, `fx`, `identity`, `ledger`, `merchant`, `operations`, `outbox`, `payout`, `rails`, `ussd`, `wallet`.
   - Database migrations: 12 Flyway migrations (`V1__sandbox_ledger.sql` through `V12__ussd.sql`).
   - Web frontend: `web/` containing React/TypeScript application with Vite and Tailwind.
   - Open API specification: `docs/openapi.json` (5,104 lines, 81 endpoints across `/v1/checkouts`, `/v1/fx`, `/v1/merchants`, `/v1/ops`, `/v1/public`, `/v1/rails`, `/v1/ussd`, `/v1/wallet`).

2. **Readiness Scorecard Baseline (`docs/readiness-scorecard.md`)**:
   - Total algorithms: 100 (Sections A through J).
   - **Built**: **0** (No algorithm is currently marked Built).
   - **Partial**: **18** (Claimed by master specification; runtime verification pending):
     - Section A (Ledger & Money): #001 (double-entry posting), #002 (ordered row locking), #003 (idempotent request hashing), #004 (integer proportional split), #005 (linked reversal), #006 (per-currency FX clearing).
     - Section B (Payment Processing): #011 (exactly-once rail submission), #012 (unknown-outcome recovery), #013 (callback state machine).
     - Section D (Compliance): #037 (audited lookups), #038 (single-use confirmations).
     - Section E (Reconciliation & Treasury): #049 (statement matching), #050 (ledger reconstruction), #051 (settlement batching).
     - Section H (Security): #073 (HMAC verification), #074 (PIN hashing).
     - Section I (Reliability & Platform): #085 (outbox and inbox dedupe).
     - Section J (Customer & Growth): #095 (phone normalisation).
   - **Missing**: **82** (All other algorithms in Sections A through J).
   - **Blocked**: **0** (However, external live partner connections are unconfigured/blocked by lack of partner credentials).
   - **Execution Mode**: 100% **Simulated** (`sandbox`, `sim-remit`). Zero live or partner-test integrations currently exist.

3. **Readiness Gates (`docs/readiness-scorecard.md`)**:
   - 11 gates listed (Coverage/10k sequences, Static/Modulith checks, KMS encryption/OIDC, Maker-checker/Audit chain, 99.95% SLO/p99<500ms, 200 tps/1h zero drift, Fault injection/PITR, Daily reconciliation/30s drift alert, Staged rollout, Compliance cases/BoG FIC, Channels).
   - All 11 gates are currently in status `Missing verification`.

---

## 2. Complete Inventory of the 100 Algorithms and Capabilities

The 100 algorithms are strictly defined and partitioned into 10 categories (Sections A through J) per `ORIGINAL_REQUEST.md` and `docs/product-specification.md`:

### Section A: Ledger and Money (#001 – #010)
- **001. Double-entry posting**: Atomic, balanced journal postings with currency validation, account validation, and immutable references. Debits equal credits per currency.
- **002. Ordered row locking**: Deterministic acquisition of locks (e.g., sorted account UUIDs) preventing deadlocks and race conditions during concurrent financial operations.
- **003. Idempotent request hashing**: Request canonicalization, SHA-256 fingerprint persistence, returning cached response for exact retries and HTTP 409 Conflict for altered payload retries.
- **004. Integer proportional allocation**: Distributes monetary amounts using deterministic rounding algorithms (e.g. Largest Remainder / Hare-Niemeyer), preserving exact integer sum with remainder allocation to final bucket.
- **005. Linked reversals**: Reverses eligible journal postings through explicit linked balanced entries, tracking original transaction reference and enforcing duplicate-reversal protection.
- **006. Per-currency FX clearing**: Multi-currency movement balancing each currency independently via dedicated clearing accounts (`system:fx_clearing:<CURRENCY>`) and recorded spot rates.
- **007. Fee schedule engine**: Configurable, versioned fee calculations supporting fixed pesewas, basis points (bps), tiered structures, minimums, caps, and effective date ranges with deterministic integer rounding.
- **008. Interest and yield accrual**: Day-count conventions (ACT/365, 30/360), eligibility gates, rounding, and posting schedules. Kept disabled for live use until regulatory authorization.
- **009. Revenue recognition**: Distinction between collected customer fees, deferred merchant balances, earned platform revenue, and fee reversals using explicit ledger rules.
- **010. Balance snapshots**: Point-in-time verifiable balance snapshots with mathematical proof of consistency against subsequent journal entries.

### Section B: Payment Processing (#011 – #022)
- **011. Duplicate-safe rail submission**: Durable pre-submission intent, stable unique rail references, partner idempotency keys, and explicit submission states preventing double debit/credit.
- **012. Unknown-outcome recovery**: Automated periodic and manual query mechanism for unresolved network instructions, preventing unsafe retries when outcomes are ambiguous.
- **013. Callback state machine**: Cryptographic signature validation, event deduplication by stable event ID, row locking, and transition guards accommodating out-of-order delivery.
- **014. Smart payment routing**: Dynamic route selection across available networks based on cost, rail capability, channel availability, and real-time success metrics.
- **015. Safe failover routing**: Secondary rail failover permitted strictly when the primary rail is proven definitively unsubmitted or failed without funds movement.
- **016. Network health detection**: Circuit health monitoring maintaining sliding error rates, response latencies, minimum sample sizes, and automatic recovery probes.
- **017. Card BIN lookup**: Card brand, issuing bank, card type, and country resolution from a versioned offline database without treating metadata as authorization.
- **018. Risk-based 3-D Secure**: 3DS 2.x protocol integration preserving challenge, frictionless exemption flags, directory server transaction IDs, and liability shift tokens.
- **019. Card tokenization boundary**: Isolated token lifecycle (generation, secure storage, lookup, revocation) preventing raw PAN/CVV exposure outside the security perimeter.
- **020. Recurring billing mandates**: Formal mandate capture, frequency, amount limits, customer consent timestamps, mandate cancellation, and duplicate billing protection.
- **021. Dunning schedules**: Bounded retry schedules with exponential/smart backoff, customer notifications, and hard-stop caps on failed recurring collections.
- **022. EMVCo QR encoding**: Generation and parsing of static and dynamic EMVCo QR merchant payloads including CRC16-CCITT checksum validation.

### Section C: Fraud and Risk (#023 – #036)
- **023. Velocity rules**: Configurable rolling counters (count, total amount) across customer, merchant, card, IP, or device over sliding windows (1h, 24h, 7d).
- **024. Rules engine and risk score**: Versioned rule evaluation pipeline producing numeric risk scores, trigger reasons, and auditable decision trees.
- **025. Device risk signals**: Privacy-compliant device fingerprinting (headers, canvas, WebGL, timezone, platform) evaluating risk confidence vs certainty.
- **026. SIM-swap checks**: Integration with telecom/aggregator SIM status APIs detecting IMSI change age and triggering step-up auth or hold.
- **027. Geo-velocity checks**: Great-circle / haversine calculation of geographic distance between successive transactions vs elapsed time (impossible travel detection), accounting for VPNs.
- **028. Account takeover detection**: Correlated risk evaluation of credential changes, new devices, unfamiliar IP ranges, and sudden withdrawal attempts.
- **029. Adaptive step-up authentication**: Dynamic challenge issuance (SMS/Email OTP, WebAuthn, USSD PIN) bound cryptographically to the exact action payload.
- **030. Merchant risk scoring**: Continuous evaluation of merchant transaction velocity, refund ratios, dispute rates, and onboarding flags with review queues.
- **031. Promotion and referral abuse detection**: Graph and cluster analysis detecting sybil accounts, circular referral chains, and incentive farming.
- **032. Mule-account graph detection**: Transaction relationship graph analysis identifying hub-and-spoke rapid fund pass-through and layering behavior.
- **033. Synthetic identity detection**: Anomaly detection cross-referencing national ID, TIN, phone ownership, and address for artificial identity combinations.
- **034. Behavioral biometrics**: Passive interaction telemetry (keystroke dynamics, pointer movement, swipe trajectories) evaluated with privacy protections.
- **035. Gradient-boosted fraud model**: ML classification pipeline (e.g. LightGBM/XGBoost) with versioned feature store; labeled experimental on synthetic data.
- **036. Chargeback prediction**: Calibrated risk model estimating probability of customer dispute prior to settlement release.

### Section D: Compliance Controls (#037 – #048)
- **037. Rate-limited audited lookups**: Sender-scoped limits (10/min, 100/day, 10 misses/hr), hash-only destination logging, preventing customer enumeration.
- **038. Bound single-use confirmations**: Ephemeral tokens (5-minute TTL) binding sender, normalized destination, recipient account, amount, and rail; single-use only.
- **039. KYC tier limits**: Tier 1 (starter), Tier 2 (medium), Tier 3 (enhanced) balance and daily/monthly transaction caps enforced atomically at API boundaries.
- **040. Ghana Card matching**: Integration with National Identification Authority (NIA) verification endpoint; explicit simulation tagging when disconnected.
- **041. Face matching and liveness**: Biometric photo matching against national ID selfie with anti-spoofing liveness detection and human review queues.
- **042. Fuzzy sanctions screening**: Sanctions list screening combining exact match, Jaro-Winkler distance, Double Metaphone / Soundex, list versioning, and hit triage.
- **043. PEP screening**: Politically Exposed Persons database matching, continuous re-screening, and mandatory senior compliance sign-off.
- **044. Structuring detection**: Detection of transaction smurfing (multiple transactions just below mandatory reporting thresholds such as $10,000 or GHS 50,000).
- **045. AML transaction monitoring**: Scenario-based detection rules (rapid movement, high-risk corridors, unusual dormant activation) creating auditable AML cases.
- **046. Alert prioritization**: Priority queuing algorithm scoring compliance cases by exposure, confidence, regulatory urgency, and aging.
- **047. Regulatory report generation**: Automated generation of Bank of Ghana (BoG) and Financial Intelligence Centre (FIC) STR/CTR export filings for manual review.
- **048. Retention and redaction**: Policy engine enforcing statutory data retention (e.g. 5-7 years for financial records), legal holds, and GDPR/Data Protection Act redaction.

### Section E: Reconciliation and Treasury (#049 – #058)
- **049. Exact statement matching**: Automated 4-way matching between bank/rail statement lines and internal transactions by reference, currency, amount, and status.
- **050. Ledger reconstruction checks**: Real-time validation verifying that the sum of journal entries equals cached account balances (`projected_balance == journal_balance`).
- **051. Settlement batching**: Grouping eligible merchant pending proceeds into immutable batches, enforcing settlement delay hours and preventing duplicate payout.
- **052. Fuzzy statement matching**: Heuristic matching for bank statements with missing reference numbers using timestamp windows, fee tolerances, and confidence scoring.
- **053. Partner fee verification**: Reconciliation of deducted network fees against contractual fee matrices (fixed + bps), raising fee discrepancy exceptions.
- **054. Suspense aging and clearing**: Tracking unallocated or ambiguous funds in suspense accounts, alerting on aged balances (>24h, >7d), requiring maker-checker clearance.
- **055. Settlement netting**: Calculation of bilateral/multilateral net obligations across merchants and rails within legal entity and currency boundaries.
- **056. Float forecasting**: Predictive liquidity modeling forecasting mobile money and bank float requirements across weekend/holiday windows.
- **057. Float rebalancing**: Policy-governed recommendations and automated transfers moving liquidity between bank and telecom partner accounts with safety thresholds.
- **058. Merchant cash-flow forecasting**: Merchant-facing analytics projecting expected payouts, working capital trends, and seasonality.

### Section F: FX and Cross-Border (#059 – #064)
- **059. Rate sourcing and blending**: Multi-provider FX rate ingestion, outlier rejection, latency checks, and mid-market rate synthesis.
- **060. Dynamic FX margin pricing**: Configurable tiered spread calculation over base rates, generating locked customer quotes with guaranteed lifetimes.
- **061. Corridor routing**: Intelligent routing of remittance instructions based on corridor availability, liquidity, fees, and speed.
- **062. Exposure limits and hedging triggers**: Real-time tracking of net open foreign currency positions against risk limits, triggering rebalancing or hedging alerts.
- **063. Delivery-time estimation**: Historical performance-based arrival time estimation communicated to senders.
- **064. Multi-currency partner reconciliation**: Dual-currency matching verifying source debits, destination credits, exchange rates, and partner fees.

### Section G: Credit (#065 – #072)
- **065. Cash-flow underwriting**: Creditworthiness assessment based on transaction volume, consistency, seasonality, and refund rates.
- **066. Earned-wage access eligibility**: Calculation of accrued employee earnings, existing deductions, and advance limits.
- **067. Overdraft eligibility scoring**: Dynamic qualification scoring for short-term wallet overdraft protection based on historical deposits.
- **068. Merchant cash-advance pricing**: Fixed-fee factor rate pricing, repayment percentage calculation, and disclosure formatting.
- **069. Repayment scheduling and sweeps**: Automatic deduction of agreed percentages from daily merchant checkout settlements until advance is repaid.
- **070. Collections prioritization**: Triage of overdue balances by delinquency bucket, recovery probability, and outstanding exposure.
- **071. Default prediction**: Calibrated ML classification estimating probability of loan default.
- **072. Dynamic credit limits**: Real-time adjustment of merchant advance or consumer overdraft limits based on performance signals.

### Section H: Security (#073 – #084)
- **073. HMAC verification**: Constant-time signature verification (`MessageDigest.isEqual`) for webhook payloads and partner callbacks (`t=...,v1=hex(...)`).
- **074. PIN hashing and lockout**: Cryptographic hashing (bcrypt) for USSD and wallet PINs, with 3 consecutive failure lockout for 30 minutes.
- **075. Field-level envelope encryption**: AES-GCM-256 encryption of sensitive PII and account details using customer/tenant data keys wrapped by master KMS keys.
- **076. Key rotation**: Automated re-encryption workflows allowing active key rollover while preserving historical decryption capability.
- **077. Request signing and replay protection**: Client request signature verification using timestamp windows (e.g. 5 minutes) and nonces.
- **078. JWKS caching and rollover**: OIDC public key retrieval, bounded in-memory caching, and asynchronous rollover handling on key ID change.
- **079. Maker-checker approvals**: Dual-control enforcement where privileged actions (dispute resolution, limits, manual adjustments) require two distinct authorized actors.
- **080. Tamper-evident audit records**: Cryptographic hash chaining (SHA-256 of previous record hash + current event payload) with external verifiable checkpoints.
- **081. Administrative anomaly detection**: Real-time monitoring of privileged operator activity for anomalous bulk exports, permission escalations, or off-hours actions.
- **082. Session and device binding**: Refresh token rotation, device identifier binding, and instant token revocation upon security state change.
- **083. Mobile certificate pinning**: Public key hash pinning in Android and iOS clients with backup pins and operational rotation runbooks.
- **084. CI security scanning**: Automated static analysis, secret scanning (e.g. truffleHog), dependency vulnerability checks (OWASP Dependency-Check), and container scanning.

### Section I: Reliability and Platform (#085 – #094)
- **085. Transactional outbox and inbox deduplication**: Transactional persistence of domain events in `outbox_event` table; idempotent consumer processing via unique message IDs.
- **086. Partner circuit breakers**: Resilience4j/custom circuit breakers tracking error thresholds, opening on partner downtime, probing via half-open states.
- **087. Backoff and jitter**: Exponential backoff with Full Jitter for network retries, preventing thundering herds.
- **088. Token-bucket rate limiting**: Distributed or Redis-backed token bucket rate limiters protecting public, merchant, and internal endpoints.
- **089. Load shedding and backpressure**: Request prioritization shedding low-priority read requests under high CPU/database pressure while protecting financial transactions.
- **090. SLO burn-rate alerting**: Multi-window multi-burn-rate alerting tracking 99.95% availability and latency budget consumption.
- **091. Success-rate anomaly detection**: Statistical anomaly detection on transaction conversion rates alerting on rail degradation before total failure.
- **092. Scaling signals**: Custom autoscaling metrics (database connection pool saturation, outbox lag, queue depth) driving horizontal pod autoscaling.
- **093. Fault injection**: Chaos engineering test harness simulating network partitions, slow queries, lost callbacks, and container restarts.
- **094. Database failover recovery**: Transparent reconnection, transaction retry on transient disconnection, and durability verification after PostgreSQL failover.

### Section J: Customer and Growth (#095 – #100)
- **095. Exact phone normalization**: Normalization of local (`024...`) and international (`+233...`) formats into canonical E.164 (`+23324...`), maintaining verified ownership.
- **096. Spending categorization**: Rule-based and semantic tagging of customer transactions into categories (Groceries, Utilities, Transport, etc.) without altering ledger records.
- **097. Savings goals and round-ups**: Voluntary transaction round-ups and automated recurring sweeps into dedicated savings sub-accounts.
- **098. Churn prediction**: Survival analysis / classification predicting customer dormancy, driving engagement nudges.
- **099. Notification timing**: Smart delivery queue observing user quiet hours, rate limits, and priority channels (SMS, Push, USSD, Email).
- **100. Personalized limits**: Dynamic risk-based limits tailored to individual usage history and creditworthiness under strict policy ceilings.

---

## 3. Features Discovered Table

| # | Category | Feature | Description | Inputs | Outputs | Error Behavior | Discovered Via |
|---|----------|---------|-------------|--------|---------|----------------|----------------|
| 1 | Ledger & Money | Double-entry posting | Balances debits and credits per currency atomically | Posting request, lines (account, amount, direction), currency | Journal transaction record, updated balances | Rejects unbalanced lines, missing accounts, negative amounts | ORIGINAL_REQUEST.md line 126; `com.elepay.ledger` |
| 2 | Ledger & Money | Ordered row locking | Eliminates race conditions and deadlocks | Account IDs involved in transaction | Lock acquisition in lexicographical order | Serializes concurrent updates; bounded deadlock retry | ORIGINAL_REQUEST.md line 129; `V4__ledger_core.sql` |
| 3 | Ledger & Money | Idempotent request hashing | Canonicalizes request, verifies fingerprint | Idempotency-Key header, request body | Cached response on match; executes once | 409 Conflict if payload changes under same key | ORIGINAL_REQUEST.md line 132; `docs/wallet-api.md` |
| 4 | Ledger & Money | Integer proportional allocation | Divides money across splits without losing minor units | Total pesewas, list of shares/bps | List of integer pesewas matching exact sum | Deterministic remainder assigned to final recipient | ORIGINAL_REQUEST.md line 135; `docs/merchant-api.md` |
| 5 | Ledger & Money | Linked reversals | Reverses previous postings cleanly | Original transaction UUID, reversal reason | New balanced journal transaction linked to original | 409 if already reversed or invalid state | ORIGINAL_REQUEST.md line 138; `docs/runbooks/disputes-and-refunds.md` |
| 6 | Ledger & Money | Per-currency FX clearing | Balanced cross-currency movements via clearing | Source currency/amount, destination currency/amount | Balanced journal entries in both currencies | Rejects unlinked or unbalanced cross-currency legs | ORIGINAL_REQUEST.md line 141; `docs/fx-api.md` |
| 7 | Ledger & Money | Fee schedule engine | Calculates fees using versioned schedules | Amount, method, merchant agreement | Calculated fee in integer pesewas, net amount | Defaults to standard tariff if no custom fee | ORIGINAL_REQUEST.md line 144; `docs/merchant-api.md` |
| 8 | Ledger & Money | Interest & yield accrual | Computes accrued interest on qualifying balances | Account balance, rate, day-count convention | Accrual posting to interest payable | Disabled in live until regulatory clearance | ORIGINAL_REQUEST.md line 147; `docs/product-specification.md` |
| 9 | Ledger & Money | Revenue recognition | Distinguishes deferred vs earned revenue | Transaction lifecycle events, fees | Postings to platform revenue ledger accounts | Rejects premature fee recognition | ORIGINAL_REQUEST.md line 150; `docs/merchant-api.md` |
| 10 | Ledger & Money | Balance snapshots | Verifiable historical balance checkpoints | Account ID, cutoff timestamp | Snapshot record, mathematical proof | Flags drift if snapshot + subsequent entries != current | ORIGINAL_REQUEST.md line 153; `docs/runbooks/reconciliation.md` |
| 11 | Payment Processing | Duplicate-safe rail submission | Prevents double submission to telecom/bank rails | Submission intent, unique rail reference | Confirmed dispatch or queued status | Blocks resubmission; resolves via query | ORIGINAL_REQUEST.md line 158; `docs/runbooks/rail-recovery.md` |
| 12 | Payment Processing | Unknown-outcome recovery | Queries external rails for ambiguous transactions | Rail reference, pending transaction ID | Updated definitive status (succeeded/failed) | Keeps funds reserved until confirmed | ORIGINAL_REQUEST.md line 161; `docs/runbooks/rail-recovery.md` |
| 13 | Payment Processing | Callback state machine | Processes async partner webhooks safely | Signature, event ID, payload, status | State transition, ledger posting | Ignores duplicates; queues out-of-order/conflicts | ORIGINAL_REQUEST.md line 164; `docs/runbooks/exceptions-queue.md` |
| 14 | Payment Processing | Smart payment routing | Selects optimal rail (MTN, Telecel, Bank) | Destination, amount, cost matrix, health | Selected route descriptor | Fallback if optimal rail degraded | ORIGINAL_REQUEST.md line 167; `docs/product-specification.md` |
| 15 | Payment Processing | Safe failover routing | Switches rail only before money movement | Failed submission, secondary eligible route | Rerouted submission intent | Rejects failover if initial outcome unknown | ORIGINAL_REQUEST.md line 170; `ORIGINAL_REQUEST.md` |
| 16 | Payment Processing | Network health detection | Tracks provider availability & latency | Rolling window of responses/timeouts | Health status (UP, DEGRADED, DOWN) | Triggers circuit breakers on threshold breach | ORIGINAL_REQUEST.md line 173; `docs/runbooks/monitoring.md` |
| 17 | Payment Processing | Card BIN lookup | Resolves card scheme, issuer, country | Card PAN (first 6-8 digits) | Scheme, bank name, card type, tier | Rejects malformed PAN; non-blocking on miss | ORIGINAL_REQUEST.md line 176; `docs/readiness-scorecard.md` |
| 18 | Payment Processing | Risk-based 3-D Secure | Orchestrates 3DS 2.x challenges | Card data, transaction amount, risk score | Frictionless approval or challenge redirect URL | Fails closed on authentication failure | ORIGINAL_REQUEST.md line 179; `docs/readiness-scorecard.md` |
| 19 | Payment Processing | Card tokenization boundary | Isolates sensitive card details | Full card PAN, CVV, expiry | Opaque token UUID, masked PAN (BIN + last 4) | Raw PAN never stored in general database | ORIGINAL_REQUEST.md line 182; `docs/product-specification.md` |
| 20 | Payment Processing | Recurring billing mandates | Manages customer recurring payment consent | Customer ID, mandate scope, frequency, max | Mandate ID, status (active, revoked) | 403 on charges exceeding mandate terms | ORIGINAL_REQUEST.md line 185; `docs/merchant-api.md` |
| 21 | Payment Processing | Dunning schedules | Manages retry schedules for failed recurring items | Failed charge ID, retry count | Scheduled next retry timestamp or termination | Caps maximum retry attempts; sends notices | ORIGINAL_REQUEST.md line 188; `docs/readiness-scorecard.md` |
| 22 | Payment Processing | EMVCo QR encoding | Generates compliant merchant QR strings | Merchant code, amount, currency, reference | EMVCo QR payload string with CRC16 | Fails on invalid country/currency codes | ORIGINAL_REQUEST.md line 191; `docs/ussd.md`, `docs/merchant-api.md` |
| 23 | Fraud & Risk | Velocity rules | Enforces rate and volume thresholds | Actor, entity, amount, time window | Allow, Challenge, or Reject | Rejects transaction with velocity violation error | ORIGINAL_REQUEST.md line 196; `docs/readiness-scorecard.md` |
| 24 | Fraud & Risk | Rules engine & risk score | Scores transactions via declarative rules | Transaction context, user history, device | Integer risk score (0-100), rule hits | Flags high-risk items for manual review | ORIGINAL_REQUEST.md line 199; `docs/readiness-scorecard.md` |
| 25 | Fraud & Risk | Device risk signals | Evaluates client environment signals | User-agent, IP, headers, device fingerprint | Device risk score, known device indicator | Step-up auth if untrusted or spoofed device | ORIGINAL_REQUEST.md line 202; `docs/readiness-scorecard.md` |
| 26 | Fraud & Risk | SIM-swap checks | Queries telco SIM status for recent swaps | MSISDN | SIM swap age in hours/days | Enforces hold or manual review if < 72h | ORIGINAL_REQUEST.md line 205; `docs/readiness-scorecard.md` |
| 27 | Fraud & Risk | Geo-velocity checks | Flags physically impossible location hops | Consecutive IP/GPS coordinates, timestamp delta | Velocity in km/h, anomaly flag | Challenges action if speed exceeds air travel | ORIGINAL_REQUEST.md line 208; `docs/readiness-scorecard.md` |
| 28 | Fraud & Risk | Account takeover detection | Detects unauthorized session hijacking | Credential changes, IP hops, sudden cashouts | ATO risk probability | Freezes wallet and alerts operations | ORIGINAL_REQUEST.md line 211; `docs/runbooks/exceptions-queue.md` |
| 29 | Fraud & Risk | Adaptive step-up auth | Issues dynamic MFA challenges on elevated risk | Context, target action, required factor | Verification challenge challengeId | Rejects operation if challenge unverified | ORIGINAL_REQUEST.md line 214; `docs/readiness-scorecard.md` |
| 30 | Fraud & Risk | Merchant risk scoring | Monitors merchant chargebacks & refunds | Merchant ID, rolling dispute/refund rates | Merchant tier / hold recommendation | Restricts payouts or holds reserve balance | ORIGINAL_REQUEST.md line 217; `docs/merchant-api.md` |
| 31 | Fraud & Risk | Promo abuse detection | Prevents voucher / incentive exploitation | Customer graph, device IDs, referral code | Sybil risk score, promo eligibility boolean | Denies bonus / discount to colluding accounts | ORIGINAL_REQUEST.md line 220; `docs/readiness-scorecard.md` |
| 32 | Fraud & Risk | Mule graph detection | Detects pass-through money mule rings | Transaction graph edges, fund dwell times | Circularity / mule score | Freezes accounts in suspicious cluster | ORIGINAL_REQUEST.md line 223; `docs/readiness-scorecard.md` |
| 33 | Fraud & Risk | Synthetic identity detection | Flags fake combinations of identity data | Ghana Card, name, DOB, phone history | Synthetic identity probability | Disallows onboarding; creates compliance case | ORIGINAL_REQUEST.md line 226; `docs/readiness-scorecard.md` |
| 34 | Fraud & Risk | Behavioral biometrics | Analyzes passive interaction cadence | Keystroke dynamics, pointer movement | Anomaly score | Non-blocking telemetry; advisory signal | ORIGINAL_REQUEST.md line 229; `docs/readiness-scorecard.md` |
| 35 | Fraud & Risk | Gradient-boosted fraud model | ML classification for fraud detection | Feature vector of transaction and user stats | Probability of fraud (0.0 to 1.0) | Fallback to deterministic rules if offline | ORIGINAL_REQUEST.md line 232; `ORIGINAL_REQUEST.md` |
| 36 | Fraud & Risk | Chargeback prediction | Predicts likelihood of subsequent dispute | Order details, merchant profile, buyer history | Chargeback probability score | Triggers delayed settlement if high risk | ORIGINAL_REQUEST.md line 235; `docs/merchant-api.md` |
| 37 | Compliance | Rate-limited audited lookups | Prevents phone number harvesting | Sender token, recipient phone | Verified name, confirmationId, expiresAt | 429 Too Many Requests; logs audit record | ORIGINAL_REQUEST.md line 240; `docs/wallet-api.md` |
| 38 | Compliance | Bound single-use confirmations | Binds confirmation to specific transfer details | ConfirmationId, amount, sender | Validated transfer intent | 409 expired/stale/used; 404 cross-sender | ORIGINAL_REQUEST.md line 243; `docs/wallet-api.md` |
| 39 | Compliance | KYC tier limits | Restricts transaction volume per customer tier | Customer KYC tier, proposed amount, window total | Approval boolean, remaining headroom | Rejects transaction exceeding tier limit | ORIGINAL_REQUEST.md line 246; `docs/readiness-scorecard.md` |
| 40 | Compliance | Ghana Card matching | Verifies identity against NIA database | Ghana Card PIN, full name, DOB | Verification match score, status | Distinguishes unavailable from unverified | ORIGINAL_REQUEST.md line 249; `docs/product-specification.md` |
| 41 | Compliance | Face matching and liveness | Matches selfie to identity card photo | Live video/photo feed, reference photo | Liveness score, facial similarity percentage | Routes failed match to compliance queue | ORIGINAL_REQUEST.md line 252; `docs/readiness-scorecard.md` |
| 42 | Compliance | Fuzzy sanctions screening | Checks names against UN/OFAC/Ghana lists | Customer / merchant name, list version | Match candidates, Jaro-Winkler score | Blocks matching transfers; raises case | ORIGINAL_REQUEST.md line 255; `docs/readiness-scorecard.md` |
| 43 | Compliance | PEP screening | Identifies Politically Exposed Persons | Full name, nationality, national ID | PEP status, relationship description | Requires senior compliance sign-off | ORIGINAL_REQUEST.md line 258; `docs/readiness-scorecard.md` |
| 44 | Compliance | Structuring detection | Detects smurfing under reporting thresholds | Rolling window of sub-threshold transactions | Structuring alert flag, grouped transactions | Automatically queues STR case | ORIGINAL_REQUEST.md line 261; `docs/readiness-scorecard.md` |
| 45 | Compliance | AML transaction monitoring | Scenarios for money laundering detection | Account transaction history, counterparties | AML case record, severity level | Queues for compliance officer review | ORIGINAL_REQUEST.md line 264; `docs/readiness-scorecard.md` |
| 46 | Compliance | Alert prioritization | Ranks compliance review cases | Case list, severity, exposure, aging | Ordered priority queue of cases | Escalates aged high-priority alerts | ORIGINAL_REQUEST.md line 267; `docs/runbooks/exceptions-queue.md` |
| 47 | Compliance | Regulatory report generation | Generates BoG / FIC compliant returns | Reporting period, transaction datasets | Standardized XML/JSON/CSV export files | Requires human review before submission | ORIGINAL_REQUEST.md line 270; `docs/readiness-scorecard.md` |
| 48 | Compliance | Retention and redaction | Manages data lifecycle & right-to-be-forgotten | Retention policies, customer deletion request | Redacted PII records, preserved financial logs | Preserves immutable double-entry history | ORIGINAL_REQUEST.md line 273; `docs/runbooks/deploy-and-migrate.md` |
| 49 | Recon & Treasury | Exact statement matching | 4-way matching of internal vs external transactions | External bank/rail statement, internal entries | Matched items, unmatched statement lines | Raises exception for unmatched lines | ORIGINAL_REQUEST.md line 278; `docs/runbooks/reconciliation.md` |
| 50 | Recon & Treasury | Ledger reconstruction checks | Rebuilds account balance from journal entries | Account ID or all accounts | Integrity report (drift count, unbalanced sum) | Alerts Sev-1 if drift > 0; halts payouts | ORIGINAL_REQUEST.md line 281; `docs/runbooks/reconciliation.md` |
| 51 | Recon & Treasury | Settlement batching | Aggregates merchant funds due for release | Merchant ID, settlement delay hours, pending lines | Immutable settlement batch record | Prevents duplicate settlement of same lines | ORIGINAL_REQUEST.md line 284; `docs/merchant-api.md` |
| 52 | Recon & Treasury | Fuzzy statement matching | Reconciles statements with truncated references | Unmatched statement rows, candidate transactions | Scored match candidates for operator review | Requires human confirmation to resolve | ORIGINAL_REQUEST.md line 287; `docs/readiness-scorecard.md` |
| 53 | Recon & Treasury | Partner fee verification | Compares charged rail fees against agreement | Network statement fee lines, fee contract rules | Variance report, overcharge alerts | Flags discrepancy for treasury dispute | ORIGINAL_REQUEST.md line 290; `docs/runbooks/reconciliation.md` |
| 54 | Recon & Treasury | Suspense aging & clearing | Tracks unallocated balances in suspense | Suspense account entries, age threshold | Aged suspense inventory, aging alerts | Requires maker-checker to clear suspense | ORIGINAL_REQUEST.md line 293; `docs/runbooks/exceptions-queue.md` |
| 55 | Recon & Treasury | Settlement netting | Calculates multilateral settlement balances | Inter-merchant/rail obligations | Net payable/receivable figures per entity | Validates legal entity & currency boundaries | ORIGINAL_REQUEST.md line 296; `docs/product-specification.md` |
| 56 | Recon & Treasury | Float forecasting | Estimates cash needs across telco/bank floats | Historical outflows, calendar events | Projected required float per rail | Alerts when float drops below safe buffer | ORIGINAL_REQUEST.md line 299; `docs/readiness-scorecard.md` |
| 57 | Recon & Treasury | Float rebalancing | Generates and executes inter-float transfers | Float balances, target ratios, policy | Transfer instructions across treasury accounts | Enforces liquidity floors before moving funds | ORIGINAL_REQUEST.md line 302; `docs/readiness-scorecard.md` |
| 58 | Recon & Treasury | Merchant cash-flow forecasting | Projects merchant liquidity and upcoming payouts | Merchant transaction history, pending sales | Projected daily available funds curve | Displays uncertainty boundaries | ORIGINAL_REQUEST.md line 305; `docs/product-specification.md` |
| 59 | FX & Cross-Border | Rate sourcing and blending | Ingests and sanitizes market FX rates | Multi-partner live rate feeds | Validated base mid-market rate | Discards stale or outlier quotes | ORIGINAL_REQUEST.md line 310; `docs/fx-api.md` |
| 60 | FX & Cross-Border | Dynamic FX margin pricing | Computes customer quote with margin | Corridor, source amount, base rate | Locked quote (rate, fee, destination amount) | Quote expires after configured TTL (e.g. 5m) | ORIGINAL_REQUEST.md line 313; `docs/fx-api.md` |
| 61 | FX & Cross-Border | Corridor routing | Selects optimal cross-border payout partner | Sending/receiving country, currency pair | Selected partner route (e.g. WU, Remitly) | Rejects unsupported corridors | ORIGINAL_REQUEST.md line 316; `docs/fx-api.md` |
| 62 | FX & Cross-Border | Exposure limits & hedging | Monitors net FX currency exposure | Open positions per currency, exposure caps | Hedging trigger alerts, exposure dashboard | Halts trading if corridor exposure breached | ORIGINAL_REQUEST.md line 319; `docs/readiness-scorecard.md` |
| 63 | FX & Cross-Border | Delivery-time estimation | Predicts transfer arrival time | Partner route, corridor, delivery method | Estimated arrival time window (minutes/hours) | Displays conservative SLA estimates | ORIGINAL_REQUEST.md line 322; `docs/fx-api.md` |
| 64 | FX & Cross-Border | Multi-currency partner recon | Reconciles FX partner clearing accounts | Partner statements in foreign currency | Dual-currency match status, variance queue | Raises exception for rate/amount mismatches | ORIGINAL_REQUEST.md line 325; `docs/runbooks/reconciliation.md` |
| 65 | Credit | Cash-flow underwriting | Assesses merchant credit capacity | Transaction history, revenues, refunds | Credit score, max offer limit | Denies advance if revenues volatile | ORIGINAL_REQUEST.md line 330; `docs/readiness-scorecard.md` |
| 66 | Credit | Earned-wage access eligibility | Calculates allowed early wage withdrawal | Verified earned salary, payroll schedule | Maximum eligible advance amount | Blocks if existing payroll deduction active | ORIGINAL_REQUEST.md line 333; `docs/readiness-scorecard.md` |
| 67 | Credit | Overdraft eligibility scoring | Evaluates wallet overdraft facility | Customer deposit velocity, tenure | Overdraft approval boolean, limit | Disabled in live until regulatory license | ORIGINAL_REQUEST.md line 336; `docs/readiness-scorecard.md` |
| 68 | Credit | Merchant cash-advance pricing | Generates fixed-fee advance proposals | Requested amount, factor rate (e.g. 1.15) | Total repayment amount, daily sweep bps | Full disclosure of cost; no hidden compounding | ORIGINAL_REQUEST.md line 339; `docs/readiness-scorecard.md` |
| 69 | Credit | Repayment scheduling & sweeps | Deducts agreed percentage from daily sales | Daily settled merchant proceeds, split rate | Sweep debit transaction against loan balance | Suspends sweep if merchant balance zero | ORIGINAL_REQUEST.md line 342; `docs/readiness-scorecard.md` |
| 70 | Credit | Collections prioritization | Prioritizes overdue debt recovery | Delinquent accounts, days past due | Prioritized queue for collections outreach | Auditable notes; respects fair debt rules | ORIGINAL_REQUEST.md line 345; `docs/readiness-scorecard.md` |
| 71 | Credit | Default prediction | Predicts probability of credit default | Loan repayment cadence, merchant trend | Default risk probability | Flags loans requiring early restructuring | ORIGINAL_REQUEST.md line 348; `docs/readiness-scorecard.md` |
| 72 | Credit | Dynamic credit limits | Adjusts credit line based on real-time health | Ongoing sales performance, external macro | Updated credit ceiling, notification | Decreases limit immediately on distress | ORIGINAL_REQUEST.md line 351; `docs/readiness-scorecard.md` |
| 73 | Security | HMAC verification | Constant-time webhook & callback validation | Request body, header signature, shared secret | Boolean valid/invalid | Rejects altered payload or bad signature | ORIGINAL_REQUEST.md line 358; `docs/merchant-api.md`, `docs/ussd.md` |
| 74 | Security | PIN hashing and lockout | Secure PIN storage and brute-force defense | Plain PIN, salt, stored hash, failure count | Hash comparison result, lockout state | Locks account for 30m after 3 wrong tries | ORIGINAL_REQUEST.md line 361; `docs/ussd.md` |
| 75 | Security | Field-level envelope encryption | Encrypts sensitive PII via data keys + KMS | Plaintext field, KMS key alias | Ciphertext envelope (encrypted DEK + payload) | Fails closed if KMS unavailable | ORIGINAL_REQUEST.md line 364; `docs/runbooks/access-and-keys.md` |
| 76 | Security | Key rotation | Rotates KMS keys and updates ciphertext | Active key version, historical records | Re-wrapped data keys, updated version tag | Preserves readability of old records | ORIGINAL_REQUEST.md line 367; `docs/readiness-scorecard.md` |
| 77 | Security | Request signing & replay protection | Validates client signatures with nonce & time | Signature header, timestamp, body, nonce | Request authorization boolean | Rejects timestamps > 5m or replayed nonce | ORIGINAL_REQUEST.md line 370; `docs/merchant-api.md` |
| 78 | Security | JWKS caching and rollover | Validates incoming JWT tokens with JWKS | OIDC JWT, issuer JWKS URL | Validated claims (sub, roles) | Refreshes key on unknown kid; caches safely | ORIGINAL_REQUEST.md line 373; `docs/configuration.md` |
| 79 | Security | Maker-checker approvals | Requires two distinct operators for actions | Action payload, proposer ID, approver ID | Approved execution result | Rejects if approver == proposer | ORIGINAL_REQUEST.md line 376; `docs/runbooks/exceptions-queue.md` |
| 80 | Security | Tamper-evident audit records | Hash-chained audit logs with checkpoints | Actor, action, target entity, timestamp | Hash-linked audit log entry (SHA-256) | Verification fails if any row tampered | ORIGINAL_REQUEST.md line 379; `docs/runbooks/access-and-keys.md` |
| 81 | Security | Admin anomaly detection | Alerts on abnormal operator actions | Operator logs, time, volume, export actions | Security alert flag to ops channel | Auto-suspends compromised admin tokens | ORIGINAL_REQUEST.md line 382; `docs/runbooks/monitoring.md` |
| 82 | Security | Session & device binding | Binds access/refresh tokens to device/IP | Refresh token, device fingerprint | Rotated token pair | Revokes entire family on reuse | ORIGINAL_REQUEST.md line 385; `docs/product-specification.md` |
| 83 | Security | Mobile certificate pinning | Pins TLS public keys in native apps | Server TLS certificate chain, pin set | Connection established boolean | Terminates connection if cert mismatch | ORIGINAL_REQUEST.md line 388; `docs/product-specification.md` |
| 84 | Security | CI security scanning | Automated code and artifact vulnerability gating | Commits, PRs, container images | Pass/Fail status report, blocker list | Fails build on high/critical vulnerabilities | ORIGINAL_REQUEST.md line 391; `docs/readiness-scorecard.md` |
| 85 | Reliability & Platform | Outbox & inbox deduplication | Atomically saves events; deduplicates processing | Domain event, target topic, message ID | Outbox entry, guaranteed at-least-once delivery | Consumer ignores duplicate message IDs | ORIGINAL_REQUEST.md line 396; `docs/configuration.md`, `V5__identity_outbox.sql` |
| 86 | Reliability & Platform | Partner circuit breakers | Prevents cascading failures to dead rails | Call result (success/timeout/error) | Circuit state (CLOSED, OPEN, HALF_OPEN) | Fast-fails requests when circuit is OPEN | ORIGINAL_REQUEST.md line 399; `docs/runbooks/monitoring.md` |
| 87 | Reliability & Platform | Backoff and jitter | Retries network requests with randomized delay | Attempt index, base delay, max delay | Sleep duration in milliseconds | Aborts after max attempts reached | ORIGINAL_REQUEST.md line 402; `docs/runbooks/rail-recovery.md` |
| 88 | Reliability & Platform | Token-bucket rate limiting | Rate-limits endpoints per client/IP/token | Request identity, capacity, refill rate | Allow/Reject (429) with Retry-After header | Rejects bursts exceeding bucket depth | ORIGINAL_REQUEST.md line 405; `docs/wallet-api.md` |
| 89 | Reliability & Platform | Load shedding & backpressure | Drops non-critical traffic under high load | System CPU/memory/DB pool metrics | Drop / Process decision | Protects core ledger transactions first | ORIGINAL_REQUEST.md line 408; `docs/readiness-scorecard.md` |
| 90 | Reliability & Platform | SLO burn-rate alerting | Multi-window burn-rate alerts for latency/errors | Rolling error rate, latency percentiles | PagerDuty/Alertmanager trigger | Alerts on 1h/6h budget depletion | ORIGINAL_REQUEST.md line 411; `docs/runbooks/monitoring.md` |
| 91 | Reliability & Platform | Success-rate anomaly detection | Detects drops in checkout/transfer completion | Rolling success count vs baseline | Anomaly alert flag | Triggers automated route switch | ORIGINAL_REQUEST.md line 414; `docs/runbooks/monitoring.md` |
| 92 | Reliability & Platform | Scaling signals | Emits capacity indicators for autoscaling | Queue depth, thread pool, in-flight DB tx | HPA metric values | Prevents saturation before lag grows | ORIGINAL_REQUEST.md line 417; `docs/configuration.md` |
| 93 | Reliability & Platform | Fault injection | Test harness for failure simulation | Chaos scenario (delay, partition, crash) | System recovery metrics | Proves zero drift and data integrity | ORIGINAL_REQUEST.md line 420; `docs/readiness-scorecard.md` |
| 94 | Reliability & Platform | Database failover recovery | Graceful handling of DB failover & reconnect | DB connection drop / read-only exception | Auto-reconnect to promoted primary | Prevents split-brain transactions | ORIGINAL_REQUEST.md line 423; `docs/runbooks/deploy-and-migrate.md` |
| 95 | Customer & Growth | Exact phone normalization | Converts phone numbers to standard format | Raw phone string (e.g. "024 123 4567") | Canonical E.164 (e.g. "+233241234567") | Rejects invalid length or unassigned prefix | ORIGINAL_REQUEST.md line 428; `docs/wallet-api.md`, `acceptance.py` |
| 96 | Customer & Growth | Spending categorization | Categorizes purchases automatically | Merchant category, description, amount | Category tag (e.g. Groceries, Transport) | Allows manual correction; preserves ledger | ORIGINAL_REQUEST.md line 431; `docs/readiness-scorecard.md` |
| 97 | Customer & Growth | Savings goals & round-ups | Sweeps spare change into savings goal | Transaction amount, round-up unit (e.g. GHS 1) | Auto-transfer to savings account | Skips if available funds insufficient | ORIGINAL_REQUEST.md line 434; `docs/readiness-scorecard.md` |
| 98 | Customer & Growth | Churn prediction | Evaluates user inactivity patterns | Inactivity duration, velocity decline | Churn risk probability score | Triggers retention message or survey | ORIGINAL_REQUEST.md line 437; `docs/readiness-scorecard.md` |
| 99 | Customer & Growth | Notification timing | Delivers notifications respecting quiet hours | Message payload, user timezone, urgency | Scheduled delivery dispatch timestamp | Delays non-urgent messages until morning | ORIGINAL_REQUEST.md line 440; `docs/readiness-scorecard.md` |
| 100 | Customer & Growth | Personalized limits | Adjusts user limits based on profile & history | Customer risk profile, tenure, turnover | Tailored per-transaction / daily limit | Requires maker-checker override for manual bumps | ORIGINAL_REQUEST.md line 443; `docs/wallet-api.md` |

---

## 4. Edge Cases Discovered

| # | Feature | Input | Observed Behavior |
|---|---------|-------|-------------------|
| 1 | Idempotent request hashing (#003) | Re-submitting identical payload with same `Idempotency-Key` | Returns original cached response and status code without re-executing. |
| 2 | Idempotent request hashing (#003) | Submitting modified payload with previously used `Idempotency-Key` | Returns HTTP 409 Conflict. |
| 3 | Single-use confirmation (#038) | Reusing an already-consumed `confirmationId` for wallet transfer | Returns HTTP 409 `confirmation_used`. |
| 4 | Single-use confirmation (#038) | Using a `confirmationId` older than 5 minutes (TTL expired) | Returns HTTP 409 `confirmation_expired`. |
| 5 | Single-use confirmation (#038) | Executing transfer when recipient phone reassigned or wallet closed | Returns HTTP 409 `confirmation_stale`. |
| 6 | Single-use confirmation (#038) | User B attempts to execute a transfer using User A's `confirmationId` | Returns HTTP 404 (actor mismatch / isolation). |
| 7 | Recipient lookup rate limits (#037) | Sending > 10 lookups in 1 minute or > 10 unmatched numbers in 1 hour | Returns HTTP 429 Too Many Requests; logs audit record; raises `recipient_lookup_abuse`. |
| 8 | Proportional allocation rounding (#004) | Allocating 10,000 pesewas across 3 sellers with 33.333% splits | Allocates 3,333, 3,333, and 3,334 pesewas; exact sum matches 10,000 pesewas without fractional loss. |
| 9 | Multi-merchant checkout refunds (#005) | Refunding allocation line 1 twice, or refunding > allocation amount | First refund succeeds and returns proportional fee; second refund returns HTTP 409. |
| 10 | Final allocation refund (#004, #005) | Final refund of remaining allocation balance | Returns all remaining allocation principal and exact remainder of original fee. |
| 11 | Concurrent spending (#002) | Two concurrent requests attempting to spend full balance simultaneously | Ordered row locking ensures one transaction completes and the second fails with HTTP 409 `insufficient_funds`. |
| 12 | Payout retry (#011, #012) | Attempting to retry a payout that is in `succeeded`, `reserved`, or `submitted` | Returns HTTP 409; only `failed` or `returned` payouts may be retried. |
| 13 | Unknown outcome on rail timeout (#012) | External rail call times out during payout or top-up | Transaction status set to `unknown`/`pending`; funds remain reserved; no second instruction sent; resolved via `/v1/ops/rails/recover`. |
| 14 | Cross-currency FX transfer (#006, #060) | Executing transfer with expired FX quote (> 5 minutes) | Returns HTTP 409 `quote_expired`; customer must request new quote. |
| 15 | Cross-currency FX transfer (#006, #060) | Partner changes FX rate before customer accepts quote | Returns HTTP 409 `quote_changed`. |
| 16 | USSD PIN lockout (#074) | Submitting 3 consecutive incorrect PINs via USSD generic gateway | USSD session locked for 30 minutes; returns lockout message; logs audit security event. |
| 17 | Multi-tenant isolation | Merchant A attempts to access Merchant B's balance or transactions | Returns HTTP 404 (prevents object enumeration and cross-tenant leakage). |
| 18 | Ledger drift detection (#050) | Manual update or mismatch between cached balance and journal entries | `GET /v1/ops/ledger/integrity` returns `accountsWithDrift > 0`; triggers immediate Sev-1 alert and payout halt. |

---

## 5. Specification for Phase 1 Core Financial Journey

### 5.1 Internal Wallet Transfer Flow (Steps 1–12)
1. **Authentication**: Customer authenticates via bearer JWT (`Authorization: Bearer <token>`). Tokens validate issuer (`ELEPAY_OIDC_ISSUER`), audience, subject (`sub`), and roles.
2. **Recipient Entry**: Sender inputs phone number in either local Ghana format (e.g., `0241234567`) or international E.164 format (e.g., `+233241234567`).
3. **Authorized Recipient Lookup**: Call `POST /v1/wallet/recipients/lookup` with body `{"rail": "wallet", "phone": "024..."}`. Sender must be authenticated. Rate limits enforced per sender (10/min, 100/day, 10 misses/hr).
4. **Masked Recipient Confirmation**: Server returns `status: verified`, `verifiedName` (registered display name), `confirmationId` (UUID), `expiresAt` (5-minute TTL). Zero balances, customer IDs, or profile data returned. Lookups stored only as salted hashes.
5. **Preview Transfer**: Sender calls `POST /v1/wallet/transfers/preview` with `confirmationId` and `amount`. Server verifies confirmation validity and returns verified name, destination, amount, fee (0 on Starter/Business), and total debit.
6. **Confirmation by Customer**: Sender reviews confirmation screen and clicks confirm.
7. **Pre-execution Boundary Checks**: Server validates:
   - Confirmation is still valid (not expired, not used, belongs to current sender).
   - Sender available funds >= total debit.
   - Recipient wallet is still active (`status == 'active'`) and still owns the number.
   - KYC transaction and daily limits are not exceeded.
8. **Atomic Internal Transfer Execution**:
   - `POST /v1/wallet/transfers/confirmed` with `Idempotency-Key` header and body `{"confirmationId": "...", "amount": 15000}`.
   - Deterministic row locking: locks sender and recipient `ledger_account` rows in lexicographical UUID order.
   - Single atomic PostgreSQL transaction:
     - Marks `confirmationId` as used (`used_at = now()`).
     - Inserts `journal_transaction`.
     - Inserts 2 balanced `journal_entry` rows (Debit sender available, Credit recipient available) summing to 0.
     - Updates cached `balance` on both accounts.
     - Inserts transfer receipt record.
     - Inserts event into `outbox_event`.
9. **Receipts and Balance Updates**: Response returns `transactionId`, `reference`, `amount`, `currency`, `timestamp`. Both parties see updated balances immediately via `GET /v1/wallet` and receipt via `GET /v1/wallet/receipts/{reference}`.
10. **Idempotency Guarantee**: Retrying with the same `Idempotency-Key` and identical body returns the exact original JSON response without re-executing. Retrying with a different body returns HTTP 409.
11. **Operational Traceability**: Operators can trace transaction via `GET /v1/wallet/statement`, inspect journal postings, view audit log, and verify zero ledger drift via `GET /v1/ops/ledger/integrity`.
12. **Actionable Errors**: Failures return standard RFC 7807 problem details or clean JSON: `insufficient_funds`, `confirmation_expired`, `confirmation_stale`, `confirmation_used`, `rate_limit_exceeded`.

### 5.2 Extension to Checkout, Refunds, Payouts, Disputes, Settlement, and FX
- **Multi-Merchant Checkout**:
  - `POST /v1/merchants/{id}/checkouts` allows allocations to multiple sellers and delivery partners.
  - Customer approves once via `POST /v1/checkouts/{reference}/pay/wallet`.
  - Single journal transaction debits customer wallet total, credits each merchant's `pending` balance net of agreed fees, and credits fees to `system:revenue:fees:GHS`.
- **Line-Level Refunds**:
  - `POST /v1/merchants/{id}/checkouts/{reference}/allocations/{line}/refunds`.
  - Reverses specified allocation. Proportional fee returned. Remainder fee returned on final refund. Debits merchant `pending` (or `available` if settled), debits fee revenue, credits customer wallet.
- **Payouts & Withdrawals**:
  - `POST /v1/merchants/{id}/withdrawals` (to merchant's own registered settlement destination) and `POST /v1/merchants/{id}/payout-batches` (up to 500 recipients).
  - Funds move from `available` to `reserved`.
  - Rail dispatch sent with unique reference.
  - If rail returns `succeeded`, funds leave `reserved`. If `failed`, funds return to `available`. If `returned`, funds credit back to `available` and raise `payout_returned` exception.
  - Retry permitted only for `failed` or `returned` items (`POST /v1/merchants/{id}/payouts/{reference}/retry`).
- **Disputes**:
  - `POST /v1/ops/disputes` moves disputed amount from merchant pending/available into `reserved`.
  - Resolved via `POST /v1/ops/disputes/{id}/resolve` with `merchant_won` (releases reserve back to merchant) or `customer_won` (refunds customer).
  - Unfunded disputes raise `dispute_unfunded` in the exceptions queue.
- **Settlement**:
  - Scheduled job (every 1m) or operator trigger `POST /v1/ops/settlements/run`.
  - Moves matured proceeds from `pending` to `available` per merchant's agreed `settlement_delay_hours`.
  - Generates immutable `settlement_batch` records per merchant.
- **FX and Cross-Border**:
  - `POST /v1/fx/quotes` returns locked rate, fee, and 5-minute expiry.
  - `POST /v1/fx/transfers` executes balanced dual-currency journal entries via `system:fx_clearing:GHS` and `system:fx_clearing:USD`.
  - Expired or modified quotes return HTTP 409.

---

## 6. Partner Adapter Requirements (Phase 2) and Analytics/Models (Phase 3)

### 6.1 Phase 2: Direct Partner Adapters
- **Direct Rail Connections Required**:
  - Mobile Money: MTN MoMo, Telecel Cash, AirtelTigo Money.
  - Banking: GHIPSS (Ghana Interbank Payment and Settlement Systems) / NIP (Near Instant Pay) / Bank direct integrations.
  - Card Acquiring: Direct acquiring processor (Visa / Mastercard gateway).
  - Remittances: Western Union, MoneyGram, Remitly, Wise, Payoneer.
  - USSD Gateways: Telco aggregator short-code interface.
- **Strict Exclusion**: Paystack and Hubtel are explicitly prohibited from the processing path.
- **Required Adapter State Model** (every adapter must explicitly map external statuses to 7 canonical states):
  1. `Not submitted` — internal record created, no external call made.
  2. `Submission in progress` — lock held, dispatch in-flight.
  3. `Pending` — acknowledged by rail, awaiting final terminal status.
  4. `Succeeded` — confirmed completed by rail with external reference.
  5. `Definitively failed` — terminal rejection by rail without funds movement.
  6. `Unknown outcome` — network timeout, connection reset, 5xx error; funds remain reserved.
  7. `Reversed` — completed transaction subsequently returned by partner.
- **Operational Rules**:
  - Durable submission intent: persist stable unique reference before network dispatch.
  - Asynchronous recovery: background recovery job queries unknown transactions every 15s with exponential backoff.
  - Webhook security: signature verification (HMAC-SHA256) and deduplication by event ID.
  - Execution environments: strict separation between Simulated (`sandbox`), Partner-test, and Live. Never report simulated results as partner certification.

### 6.2 Phase 3: Advanced Analytics and Models
- **Target Models**:
  1. Gradient-boosted fraud model (#035) — transaction risk classification.
  2. Chargeback prediction (#036) — dispute likelihood estimation.
  3. Default prediction (#071) — credit risk / MCA default forecasting.
  4. Churn prediction (#098) — user attrition modeling.
- **Operational & Governance Requirements**:
  - Reproducible training pipelines with versioned features and datasets.
  - Calibrated probability outputs with documented precision, recall, and ROC-AUC.
  - **Synthetic Data Constraint**: Models trained on synthetic or simulated data must be labeled **`experimental`** and are strictly barred from making autonomous live financial decisions.
  - Fallback mechanisms: deterministic business rules, threshold overrides, and maker-checker / human review queues for any low-confidence or experimental model output.

---

## 7. Financial Invariants, Security Rules, and Architectural Constraints

### 7.1 Financial Invariants (Non-Negotiable)
1. **Integer Minor Units**: All monetary values are represented as integers (pesewas for GHS, cents for USD/EUR, pence for GBP). Binary floating-point arithmetic is strictly prohibited.
2. **Double-Entry Balance**: Every journal transaction balances to zero independently per currency ($\sum \text{debits} + \sum \text{credits} = 0$).
3. **Append-Only Journal**: `journal_transaction` and `journal_entry` tables are immutable. Updates and deletes are rejected at the database level. Errors are corrected exclusively through linked reversals or balanced adjustments.
4. **Derived Balances**: Cached balances in `ledger_account.balance` are mathematically derived projections. They must reconcile exactly to journal entries at all times.
5. **Universal Idempotency**: Every mutating financial API requires an `Idempotency-Key` header. Identical request hash returns the original response; conflicting request hash returns HTTP 409.
6. **Deterministic Row Locking**: Row locks on accounts must always be acquired in a deterministic order (e.g. sorted by account UUID) to avoid deadlocks.
7. **Timeout Principle**: A timeout or connection failure means the outcome is unknown. Never blindly re-debit or re-credit without authoritative reconciliation or partner query.
8. **Durable Intent & Idempotent Submission**: Submission records must be committed to the database before external transmission.
9. **Callback Validation**: Validate partner signatures, deduplicate callbacks, and enforce legal state machine transitions.
10. **Duplicate Prevention**: Prevent double capture, double refund, duplicate settlement, and duplicate payout. Cumulative refunds can never exceed captured principal.
11. **Boundary Controls**: Verify account status (active), permissions, available balance, and KYC tier limits at the execution boundary before committing.
12. **Segregated Ledger Buckets**: Available funds, pending settlements, reserved funds, fee revenue, clearing accounts, and suspense balances reside in separate, distinct accounts.
13. **Auditable Discrepancies**: Financial discrepancies are never silently patched; they generate exceptions in `operations_queue` and require maker-checker resolution.

### 7.2 Security Rules
- **Multi-Tenant Isolation**: Merchant endpoints strictly enforce tenant scoping; requests for unauthorized merchant IDs return HTTP 404.
- **Data Protection**: Secrets, USSD PINs, full 16-digit card numbers (PANs), and CVVs are never logged or stored in plaintext.
- **Envelope Encryption**: Sensitive PII and stored card tokens protected using AES-GCM-256 with managed KMS wrapping keys.
- **Constant-Time Verification**: All cryptographic signatures and hashes compared using constant-time equality checks (`MessageDigest.isEqual`).
- **PIN Security**: USSD and wallet PINs hashed with bcrypt; 3 consecutive failures trigger a 30-minute lockout.
- **Maker-Checker**: Sensitive financial and administrative actions (dispute settlement, manual adjustments, limit overrides) require approval by an independent second operator.
- **Audit Logging**: Tamper-evident, hash-chained audit records for all administrative and operational actions.

### 7.3 Architectural Constraints
- **Modular Monolith**: Enforced package boundaries using Spring Modulith; no microservices or Kafka unless justified by measured requirements.
- **Core Technology Stack**: Java 25, Spring Boot 4, PostgreSQL 18, React/TypeScript web frontend, Kotlin Android, Swift iOS.
- **Database Migrations**: Sequential, append-only Flyway migrations (`V1__...` to `V12__...`). Past migrations must never be rewritten.
- **Outbox Pattern**: Transactional outbox pattern using PostgreSQL table `outbox_event` and `SKIP LOCKED` consumers for reliable asynchronous dispatch.

---

## 8. Logic Chain

1. **Premise 1 (Authoritative Requirements)**: `ORIGINAL_REQUEST.md` mandates a complete survey of the 100 algorithms (A through J), definition of Phase 1-3 journeys, scorecard audit, and identification of financial invariants.
2. **Premise 2 (Readiness Audit)**: Inspection of `docs/readiness-scorecard.md` reveals that 18 algorithms are currently designated as `Partial` ("Claimed by master specification; runtime verification pending"), 82 are `Missing`, 0 are `Built`, and 0 are `Blocked`.
3. **Premise 3 (Simulated Baseline)**: Codebase inspection (`README.md`, `acceptance.py`, `smoke.py`, `V1__...` to `V12__...`) demonstrates that while extensive data structures and simulated endpoints exist (`sandbox`, `sim-remit`), no live banking, telecom, or card networks are currently connected.
4. **Premise 4 (Phase 1 Completeness)**: The internal wallet transfer flow (Steps 1–12) is fully specified across `docs/wallet-api.md`, `docs/product-specification.md`, and `acceptance.py`. Its architectural primitives (idempotency, ordered locking, double-entry ledger, recipient lookup and single-use confirmation) provide the exact bedrock required to extend into checkout, refunds, payouts, disputes, settlement, and FX.
5. **Conclusion**: The architectural foundation of ELEPAY Core is rigorously designed and structurally mapped, but currently operates in simulated mode. Achieving launch readiness requires promoting the 18 Partial and implementing the 82 Missing algorithms, establishing direct partner adapters (Phase 2) without Paystack/Hubtel, and validating the 11 launch readiness gates.

---

## 9. Caveats

1. **No Live Credentials**: The current environment has no active credentials for Ghanaian banks, telecommunication networks (MTN, Telecel, AirtelTigo), national ID authority (NIA), or international remittance partners. All existing tests and flows run in `sandbox` mode.
2. **Spring Boot 4 / Java 25 Runtime**: The environment has Java 25 installed, but full compilation and test suite verification (`mvn -B verify`) depends on Docker / Testcontainers for PostgreSQL 18.
3. **Credit and Lending Features**: Algorithms #065 through #072 must remain disabled for live execution until regulatory licensing and external funding facilities are secured.
4. **ML/AI Models**: The fraud and risk models (#035, #036, #071, #098) currently lack production historical training sets; any synthetic data models must remain marked experimental.

---

## 10. Conclusion

The Step 0 Survey has successfully discovered, inventoried, and documented all 100 algorithms and capabilities across Sections A through J, verified the baseline readiness scorecard (0 Built, 18 Partial, 82 Missing, 0 Blocked, 100% simulated), mapped the 12-step Phase 1 Core Financial Journey, documented the 7-state Phase 2 adapter requirements (strictly excluding Paystack and Hubtel), detailed the Phase 3 analytics governance rules, and codified all non-negotiable financial, security, and architectural invariants.

This survey provides the authoritative specification baseline for subsequent engineering increments.

---

## 11. Verification Method

To independently verify the observations and findings in this report:
1. **Inspect Readiness Scorecard**:
   ```bash
   grep -E "Partial|Built|Missing|Blocked" docs/readiness-scorecard.md | wc -l
   # Verifies 100 entries: 18 Partial, 82 Missing, 0 Built, 0 Blocked
   ```
2. **Inspect 100 Algorithms in Master Specification**:
   ```bash
   grep -E "^[0-9]{3}\." docs/ORIGINAL_REQUEST.md
   # Verifies complete sequence from 001. Double-entry posting to 100. Personalized limits
   ```
3. **Inspect OpenAPI Endpoints**:
   ```bash
   python3 -c "import json; d=json.load(open('docs/openapi.json')); print(len(d['paths']))"
   # Confirms 81 API paths
   ```
4. **Inspect Database Migrations**:
   ```bash
   ls -1 src/main/resources/db/migration/V*.sql | wc -l
   # Confirms 12 migrations (V1 to V12)
   ```
5. **Inspect Test Scenarios**:
   ```bash
   cat scripts/smoke.py scripts/acceptance.py
   # Confirms simulated flow tests: lookup, preview, confirm, checkout, refund, settlement, withdrawal, drift check
   ```
