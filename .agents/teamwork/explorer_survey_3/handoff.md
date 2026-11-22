# Step 0 Codebase Survey: ELEPAY Core Source Code Implementation

## 1. Observation

### 1.1 Toolchain & Build Environment
- **`pom.xml` (lines 6-14, 26-48)**:
  - Spring Boot parent: `4.0.10`
  - Java version: `<java.version>25</java.version>`
  - Spring Modulith version: `2.0.10`
  - SpringDoc OpenAPI: `3.0.3`
  - Key dependencies: `spring-boot-starter-webmvc`, `spring-boot-starter-jdbc`, `spring-boot-starter-validation`, `spring-boot-starter-security`, `spring-boot-starter-security-oauth2-resource-server`, `postgresql`, `flyway-database-postgresql`, `spring-modulith-starter-core`, `micrometer-registry-prometheus`, `tools.jackson` (Jackson 3).
  - Note: No Spring Data JPA / Hibernate; database access uses raw `JdbcClient` with explicit SQL and row locking.
- **Compiler execution failure**:
  - Running `mvn test-compile` in `/home/antigravity/Elemarket9/ELEPAY-Core` produced:
    `[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:compile on project elepay-core: Fatal error compiling: error: release version 25 not supported`
  - Running `javac -version` output: `javac 17.0.21` (Debian OpenJDK 17).
  - No JDK 25 is currently installed on the host VM (`find /usr/lib/jvm -maxdepth 2` shows only `java-17-openjdk-amd64`).

### 1.2 Package & Module Structure
The codebase in `src/main/java/com/elepay` comprises 56 Java classes organized into 13 packages:
- `com.elepay` (Root): `SandboxApplication.java` (main class + `@Profile("sandbox")` `SandboxLedger`), `PaymentIntents.java` (`@Profile("sandbox")` payment intent controller).
- `com.elepay.ledger`: `Ledger.java` (Authoritative double-entry ledger, accounts, statements, integrity).
- `com.elepay.wallet`: `Wallets.java`, `RecipientConfirmations.java`, `TransferLimits.java`, `CustomerDirectory.java`, `CustomerAdmin.java`, `WalletChannel.java`, `WalletFunding.java`.
- `com.elepay.checkout`: `Checkouts.java`, `CheckoutService.java`, `PublicCheckouts.java`, `CheckoutChannel.java`, `CheckoutNotifications.java`, `CheckoutOperations.java`.
- `com.elepay.payout`: `Payouts.java`, `MerchantPayouts.java`, `PlatformRoutes.java`.
- `com.elepay.rails`: `PaymentRail.java`, `NameVerification.java`, `NameVerifications.java`, `RailTransactions.java`, `RailRegistry.java`, `RailCallbacks.java`, `RailEvents.java`, `SimulatedRail.java`, `SimulatedNameVerification.java`.
- `com.elepay.fees`: `FeeSchedule.java`.
- `com.elepay.fx`: `FxService.java`, `FxApi.java`, `FxPartner.java`, `SimulatedFxPartner.java`.
- `com.elepay.identity`: `SecurityConfig.java`, `Actor.java`, `ApiKeys.java`, `ApiKeyAuthentication.java`, `DevTokens.java`.
- `com.elepay.merchant`: `MerchantAccess.java`, `Merchants.java`.
- `com.elepay.operations`: `Operations.java`, `OperationalMetrics.java`, `SpaRoutes.java`.
- `com.elepay.outbox`: `Outbox.java`, `OutboxConsumer.java`, `OutboxDispatcher.java`, `Webhooks.java`.
- `com.elepay.ussd`: `UssdMenu.java`, `UssdGateways.java`.
- `com.elepay.common`: `ApiException.java`, `ApiExceptionHandler.java`, `Audit.java`, `Idempotency.java`, `OpsQueue.java`, `References.java`, `OpenApiConfig.java`.

### 1.3 Partner Exclusion Verification
- Running `grep -rni "paystack" src/ docs/ pom.xml` and `grep -rni "hubtel" src/ docs/ pom.xml`:
  - Exactly one match found in `docs/product-specification.md:40`:
    `"Paystack and Hubtel are outside the required processing path. Keep network adapters independent so a new connection can be added without moving merchant balances or changing checkout rules."`
  - Neither Paystack nor Hubtel is imported, referenced, or implemented in any Java class, migration script, or configuration.

### 1.4 Detailed Implementation Findings by Domain

#### A. Ledger & Balances (Algorithms 001 - 010)
- **001 Double-entry posting** (`com.elepay.ledger.Ledger.java:73-162`):
  - `post(Posting)` enforces: minimum 2 legs, non-zero leg amounts, currency matches account, per-currency sum == 0 (`perCurrency.values().stream().anyMatch(sum -> sum != 0)`), and overdraft prevention (`!account.allowNegative() && Math.addExact(account.balance(), change.getValue()) < 0`).
  - DB migration `V4__ledger_core.sql` defines trigger `enforce_journal_transaction_balance()` ensuring zero-sum balance per currency at transaction boundary, and trigger `reject_unauthorized_balance_update()` preventing direct mutations to `ledger_account.balance`.
- **002 Ordered row locking** (`Ledger.java:164-176`, `Wallets.java:92-96`):
  - Accounts locked via `SELECT ... FROM ledger_account WHERE code IN (:codes) ORDER BY id FOR UPDATE`.
  - Customers in transfers locked via `SELECT id FROM customer WHERE id IN (:sender, :recipient) ORDER BY id FOR UPDATE`.
  - Missing: Application-level bounded retry handling for Postgres serialization (`40001`) / deadlock (`40P01`) exceptions.
- **003 Idempotent request hashing** (`com.elepay.common.Idempotency.java:38-98`, `Ledger.java:100-132`):
  - Request canonicalization via `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS` and recursive JSON property tree sorting.
  - SHA-256 fingerprinting.
  - Insertion into `idempotent_request (scope, key, request_hash)` with `ON CONFLICT DO NOTHING`.
  - Replay returns cached response; modified payload under same key throws `409 Conflict`.
- **004 Integer proportional allocation**:
  - Missing generalized integer allocation algorithm (e.g. Largest Remainder / Hare-Niemeyer). Allocation calculations in `CheckoutService` rely on standard integer division.
- **005 Linked reversals** (`Ledger.java:81-96`):
  - `reverse(UUID original, String scope, String key, String reason)` creates inverse legs, sets `reverses = original`, blocks reversal of a reversal, and enforces single reversal through database unique index.
- **006 Per-currency FX clearing** (`com.elepay.fx.FxService.java:203-210`, `393-398`):
  - Postings balance independently in each currency using explicit clearing accounts `system:fx_clearing:SRC` and `system:fx_clearing:DST`.
- **007 Fee schedule engine** (`com.elepay.fees.FeeSchedule.java:11-56`):
  - Implements tiered, progressive, fixed, and capped fee calculation with integer arithmetic and half-up rounding.
  - Gap: Not integrated into runtime database fee configuration or live transfer/checkout endpoints.
- **008 Interest and yield accrual**:
  - Not implemented (marked missing, required to remain disabled for live use).
- **009 Revenue recognition**:
  - Direct postings to `system:revenue:GHS`; deferred revenue accounting is not implemented.
- **010 Balance snapshots**:
  - Database view `ledger_account_reconstruction` compares projected vs journal sum.
  - Gap: No periodic immutable balance snapshot table/records or point-in-time snapshot proofing algorithm.

#### B. Core Internal Wallet Transfer Journey (Phase 1)
- **Customer Authentication**:
  - Web/API: Bearer JWT validated via `SecurityConfig` and `Actor.current()`.
  - USSD: MSISDN supplied by signed gateway webhook + BCrypt hashed PIN (`WalletChannel.checkPin` with 3-failure 30-minute lockout).
- **Recipient Lookup & Protection (037)**:
  - `RecipientConfirmations.java:108-171`: Endpoint `POST /v1/wallet/recipients/lookup`.
  - Normalizes Ghana numbers (`References.msisdn()` handles `024...`, `+233...`).
  - Audited rate limiting: 10/min, 100/day, max 10 misses/hour (`enforceLimits`). Blocked lookups raise `recipient_lookup_abuse` in `ops_item`.
  - Returns ONLY masked recipient information (`verifiedName`, `destination`, `confirmationId`, `expiresAt`). Internal UUID and balances are never exposed.
- **Bound Single-Use Confirmation (038)**:
  - Generates `recipient_confirmation` row with 5-minute TTL (`expires_at`), bound to sender, rail, destination, network, recipient customer ID, recipient account, and verified name.
  - Re-validated under row lock at execution (`validate()`). Expired, used, or modified recipient records are rejected with HTTP 409 (`confirmation_expired`, `confirmation_used`, `confirmation_stale`).
- **Transfer Limits & Velocity (023)**:
  - `TransferLimits.java:33-62`: Checks single-transfer limit, rolling 24h daily limit (using `Africa/Accra` midnight truncation), and transfers-per-minute rate limit against `wallet_operation`.
  - Gaps: Limits are not tiered by `customer.kyc_tier` (039 missing); advanced fraud rules (024-036) are missing.
- **Atomic Wallet-to-Wallet Execution**:
  - `Wallets.send()` locks sender and recipient rows in ID order (`ORDER BY id FOR UPDATE`).
  - Calls `ledger.post()` debiting `customer:<sender>:available:GHS` and crediting `customer:<recipient>:available:GHS`.
  - Inserts atomic `wallet_operation` entries (`transfer_out` and `transfer_in` linked by `related_id`).
  - Marks `recipient_confirmation` as `status = 'used'`, `used_at = now()`, `used_by_tx = txId`.
- **Receipts, Balance & Tracing**:
  - Returns `ConfirmedReceipt` with unique reference (`WLT-...`), amount, fee (currently hardcoded 0), total, verified name, and completed status.
  - Traced via ledger journal transaction, statement endpoint, and operations console.

#### C. Checkout, Merchant Payments, Payouts, Refunds, Disputes, FX
- **Checkout** (`CheckoutService.java`, `Checkouts.java`): Multi-allocation split (sale/delivery), payment links, QR payloads, wallet payment, and rail payment via MoMo/Card.
- **Settlement Batches** (`CheckoutService.java:588-630`): Settle due allocations after merchant `settlement_delay_hours`, batching journal movements from `pending` to `available`.
- **Refunds** (`CheckoutService.java:378-450`): Partial and full refunds, proportional merchant fee recovery, debits `pending` before `available`, instant return to customer wallet or rail return via `system:refunds_in_flight:GHS`.
- **Disputes** (`CheckoutService.java:484-575`): Moves funds from merchant to `merchant:<id>:reserved:GHS`; resolves to `merchant_won` or `customer_won`.
- **Payouts** (`Payouts.java`): Single and batch payouts (up to 500 items), reserves available balance, submits to rail, handles rail success/failure/returns, and supports safe retries via linked records (`retry_of`).
- **FX** (`FxService.java`): Corridors, quotes with TTL, conversion with explicit rounding, multi-currency clearing postings, outbound delivery, failure reversals, and inbound remittances.

#### D. Partner Rails & Simulators
- `PaymentRail` interface defines contracts for charges, payouts, refunds, status queries, and callback verification.
- `RailTransactions.java` implements:
  - Algorithm 011 (Duplicate-safe rail submission): intent recorded as `created`, transitioned to `unknown` before rail call.
  - Algorithm 012 (Unknown-outcome recovery): scheduled background poller with exponential backoff (`next_check_at`) queries unresolved transactions.
  - Algorithm 013 (Callback state machine): locked state machine enforces valid transitions, ignores duplicate callbacks, and routes contradictory results or amount mismatches to `ops_item`.
- Concrete implementations: `SimulatedRail` and `SimulatedNameVerification` (no live telecom or bank adapters currently active). Paystack and Hubtel are excluded.

#### E. Security Controls
- **HMAC Verification (073)**: USSD gateway (`UssdGateways.java:43`) and webhooks (`Webhooks.java:127`) use constant-time `MessageDigest.isEqual` with HMAC-SHA256.
- **PIN Hashing & Lockout (074)**: `WalletChannel.java` uses `BCryptPasswordEncoder` with anti-pattern PIN rejection (repeated or sequential digits) and 3-attempt 30-minute lockout.
- **Envelope Encryption (075) & Key Rotation (076)**: Missing.
- **Request Signing (077)**: Implemented for incoming USSD (`X-Ussd-Signature`) and outgoing Webhooks (`Elepay-Signature`). Not implemented for general REST APIs.
- **Maker-Checker (079)**: Missing.
- **Audit Records (080)**: `Audit.java` inserts into `audit_log`, but records are not hash-chained or tamper-evident.

---

## 2. Logic Chain

1. **Premise**: The repository contains an advanced, high-quality modular monolith implementation centered around an authoritative append-only double-entry ledger (`Ledger.java`), with transactional outbox, idempotency, wallet operations, checkout, settlement, payouts, and FX.
2. **Observation 1.1**: The project requires Java 25 (`<java.version>25</java.version>`) and Spring Boot 4.0.10. However, the host environment provides OpenJDK 17 (`javac 17.0.21`), causing Maven compilation to fail immediately (`error: release version 25 not supported`).
3. **Inference 1.1**: Code cannot be compiled or verified via `mvn verify` in this environment until either JDK 25 is installed or the build configuration is reconciled with available toolchains.
4. **Observation 1.3**: Searching the entire repository for "paystack" and "hubtel" yields only one comment in `docs/product-specification.md:40` stating they are excluded. Neither provider appears anywhere in source code, configurations, or migrations.
5. **Inference 1.3**: The architectural constraint excluding Paystack and Hubtel is strictly satisfied.
6. **Observation 1.4.B**: The internal wallet transfer journey (Phase 1) is already deeply implemented across `Wallets.java`, `RecipientConfirmations.java`, `TransferLimits.java`, and `Ledger.java`.
   - Authentication (JWT and USSD PIN with BCrypt lockout) exists.
   - Recipient lookup with MSISDN normalization, rate-limiting, and masked name confirmation exists.
   - Idempotency hashing, preview endpoint, and balance validation exist.
   - Atomic transfer with row locking (`ORDER BY id FOR UPDATE`) and double-entry journal postings exists.
   - Balance statements, operation logging, and integrity validation exist.
7. **Inference 1.4.B**: Phase 1 is not missing from scratch; it is approximately 85% built and needs targeted refinement:
   - Connecting `FeeSchedule` into `RecipientConfirmations` (replacing hardcoded fee 0).
   - Enforcing `customer.kyc_tier` limits inside `TransferLimits.java` (Algorithm 039).
   - Adding bounded deadlock/serialization retry loops around `Ledger.post()` (Algorithm 002).
   - Adding tamper-evident hash chaining to `audit_log` (Algorithm 080).

---

## 3. Caveats

1. **Compilation & Execution Blocked by Toolchain**:
   Because the environment lacks a Java 25 JDK, automated tests (`RecipientConfirmationIT`, `LedgerIT`, `WalletIT`) could not be executed during this step. Static code analysis and line-by-line inspection were used.
2. **Credit & Advanced ML Models**:
   Credit algorithms (065-072) and advanced ML models (035, 036, 098) are entirely missing; per product instructions, credit must remain disabled until regulatory approval.
3. **Hardware / Performance Limits**:
   The requirement for 200 payments/sec sustained load testing could not be benchmarked in this read-only survey step.

---

## 4. Conclusion

1. **Current State Assessment**:
   - The core architecture is fundamentally sound: Spring Modulith boundaries, clean separation between ledger and business aggregates, transactional outbox, append-only immutable journal enforced by PostgreSQL triggers, and explicit row locking.
   - **Exclusions**: Paystack and Hubtel are completely excluded.
   - **Phase 1 Status**: The internal wallet transfer journey is already largely implemented and needs only minor gap closures (KYC tier limits, fee schedule binding, deadlock retry, tamper-evident audit).
   - **100 Algorithms Status**: Roughly 18 algorithms are Built/Substantially Built, ~8 are Partial, and ~74 are Missing (primarily advanced risk, compliance screening, automated treasury/float, and credit).
2. **Immediate Blocker**:
   - The environment Java version (JDK 17) does not match `pom.xml` (`java.version = 25`). Resolving the JDK version is the top prerequisite for build and test execution.

---

## 5. Verification Method

To independently verify these findings:

1. **Check Java toolchain version mismatch**:
   ```bash
   javac -version
   mvn test-compile
   ```
   *Expected result*: `javac 17.0.21` and Maven error `release version 25 not supported`.

2. **Verify exclusion of Paystack and Hubtel**:
   ```bash
   grep -rni "paystack" src/ pom.xml
   grep -rni "hubtel" src/ pom.xml
   ```
   *Expected result*: 0 matches in `src/` and `pom.xml`.

3. **Verify Spring Modulith module layout**:
   Inspect directories in `src/main/java/com/elepay`:
   ```bash
   find src/main/java/com/elepay -maxdepth 2 -type d
   ```

4. **Inspect Phase 1 Wallet Transfer implementation**:
   - `view_file` on `src/main/java/com/elepay/wallet/RecipientConfirmations.java` (lookup, confirmation binding, execution).
   - `view_file` on `src/main/java/com/elepay/wallet/Wallets.java` (locking and ledger postings).
   - `view_file` on `src/main/java/com/elepay/wallet/TransferLimits.java` (velocity limits).
   - `view_file` on `src/main/java/com/elepay/ledger/Ledger.java` (double-entry invariants).
