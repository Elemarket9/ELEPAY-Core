# Step 0 Survey: Toolchain, Database Migrations, CI, Baseline Checks & Frontend

## 1. Observation

### 1.1 Toolchain & Dependency Versions
- **`pom.xml`** (lines 3–25):
  - Parent POM: `org.springframework.boot:spring-boot-starter-parent:4.0.10`
  - Java version: `<java.version>25</java.version>`
  - Spring Modulith version: `<spring-modulith.version>2.0.10</spring-modulith.version>` importing `org.springframework.modulith:spring-modulith-bom:2.0.10`
  - Runtime Database Driver: `org.postgresql:postgresql` (runtime scope)
  - Flyway: `org.springframework.boot:spring-boot-starter-flyway`, `org.flywaydb:flyway-database-postgresql`
  - Documentation: `org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3`
  - Metrics: `io.micrometer:micrometer-registry-prometheus`
  - Test Dependencies: `spring-boot-starter-test`, `spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`, `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql`, `spring-modulith-starter-test`.
  - Build Plugins: `spring-boot-maven-plugin`, `maven-failsafe-plugin` (goals: `integration-test`, `verify`).

- **Host Environment Toolchain Observation**:
  - `java -version`:
    ```
    openjdk version "17.0.21" 2026-10-20
    OpenJDK Runtime Environment (build 17.0.21+9-1-deb12u1-Debian)
    OpenJDK 64-Bit Server VM (build 17.0.21+9-1-deb12u1-Debian, mixed mode, sharing)
    javac 17.0.21
    ```
  - `mvn -version`:
    ```
    Apache Maven 3.8.7
    Maven home: /usr/share/maven
    Java version: 17.0.21, vendor: Debian, runtime: /usr/lib/jvm/java-17-openjdk-amd64
    Default locale: en_US, platform encoding: UTF-8
    OS name: "linux", version: "6.12.105+", arch: "amd64", family: "unix"
    ```
  - `node -v` & `npm -v`: Node.js `v24.22.2`, npm `11.21.2`.
  - `docker`: `docker: command not found` (exit code 127).
  - PostgreSQL locally: `psql` installed (`/usr/bin/psql`, version 15.19), but service `15/main (port 5432): down`.
  - Java CA certificates: `/usr/lib/jvm/java-17-openjdk-amd64/lib/security/cacerts -> /etc/ssl/certs/java/cacerts` (broken symlink: `/etc/ssl/certs/java/` directory does not exist).
  - Intended PostgreSQL version in project: PostgreSQL 18 (`postgres:18` in `Dockerfile`, `docker-compose.yml`, `PostgresTestBase.java` line 23, and CI `.github/workflows/sandbox.yml` line 27).

### 1.2 Flyway Database Migrations
Migrations located in `src/main/resources/db/migration/`:
1. **`V1__sandbox_ledger.sql`**:
   - Tables: `ledger_operation` (`id uuid PRIMARY KEY`, `request jsonb NOT NULL`), `ledger_move` (`operation_id uuid REFERENCES ledger_operation(id)`, `line integer`, `source text`, `destination text`, `pesewas bigint CHECK (pesewas > 0)`, `CHECK (source <> destination)`, `PRIMARY KEY (operation_id, line)`).
   - Views: `ledger_balance` summing destination and source deltas per account.
2. **`V2__payment_intents.sql`**:
   - Tables: `payment_intent` (`id uuid PRIMARY KEY`, `checkout jsonb`, `status text CHECK ('pending', 'paid', 'cancelled')`), `payment_outbox` (`sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `payment_id uuid REFERENCES payment_intent`, `event_type text CHECK ('paid', 'cancelled')`, `created_at timestamptz`, `UNIQUE (payment_id, event_type)`).
3. **`V3__immutable_ledger.sql`**:
   - Added column: `currency text NOT NULL DEFAULT 'GHS' CHECK (currency = 'GHS')` on `ledger_move`.
   - Function: `reject_ledger_mutation()` raising exception `55000` ('Posted ledger records are immutable').
   - Triggers: `ledger_move_immutable` and `ledger_operation_immutable` before UPDATE/DELETE/TRUNCATE.
4. **`V4__ledger_core.sql`**:
   - Tables:
     - `ledger_account`: `id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `code text UNIQUE`, `owner_type text CHECK ('customer', 'merchant', 'system')`, `owner_id uuid`, `bucket text CHECK ('available', 'pending', 'reserved', 'clearing', 'revenue', 'suspense', 'refunds_in_flight', 'disputes')`, `currency char(3) CHECK (~ '^[A-Z]{3}$')`, `allow_negative boolean DEFAULT false`, `balance bigint DEFAULT 0`, `CHECK (allow_negative OR balance >= 0)`, `CHECK ((owner_type = 'system') = (owner_id IS NULL))`, `UNIQUE (owner_type, owner_id, bucket, currency)`.
     - `journal_transaction`: `id uuid PRIMARY KEY`, `idempotency_scope text`, `idempotency_key text`, `request_hash text`, `kind text`, `description text`, `reverses uuid REFERENCES journal_transaction(id)`, `related_to uuid REFERENCES journal_transaction(id)`, `metadata jsonb`, `posted_at timestamptz`, `UNIQUE (idempotency_scope, idempotency_key)`, `CHECK (reverses IS NULL OR reverses <> id)`. Partial unique index on `reverses` where not null, index on `related_to`.
     - `journal_entry`: `transaction_id uuid REFERENCES journal_transaction(id)`, `line integer CHECK (line >= 0)`, `account_id bigint REFERENCES ledger_account(id)`, `currency char(3)`, `amount bigint CHECK (amount <> 0)`, `PRIMARY KEY (transaction_id, line)`. Index on `(account_id, transaction_id)`.
     - `idempotent_request`: `(scope, key) PRIMARY KEY`, `request_hash text`, `response jsonb`, `created_at timestamptz`.
   - Triggers & Functions:
     - `journal_entry_apply`: AFTER INSERT on `journal_entry`, updates `ledger_account.balance = balance + amount`, verifies currency matches.
     - `journal_transaction_check_balanced`: AFTER INSERT on `journal_entry` DEFERRABLE INITIALLY DEFERRED, asserts >= 2 entries and per-currency `sum(amount) = 0`.
     - `journal_transaction_check_has_entries`: AFTER INSERT on `journal_transaction` DEFERRABLE INITIALLY DEFERRED, asserts transaction has entries.
     - `ledger_account_guard`: Prevents account deletion, identity mutation, and balance updates unless trigger depth >= 2 (only through `journal_entry_apply`).
     - `journal_transaction_immutable`, `journal_entry_immutable`: Rejects UPDATE/DELETE/TRUNCATE.
   - Views: `ledger_account_reconstruction` comparing `projected_balance` with `sum(journal_entry.amount)`.
   - Seeded system accounts: `system:revenue:GHS`, `system:suspense:GHS`, `system:refunds_in_flight:GHS`, `system:clearing:sandbox:GHS`.
5. **`V5__identity_outbox.sql`**:
   - Tables: `api_credential` (`id uuid`, `prefix text`, `secret_hash text UNIQUE`, `merchant_id uuid`, `label text`, `created_by text`, `revoked_at timestamptz`), `outbox_event` (`id uuid`, `sequence identity`, `aggregate_type`, `aggregate_id`, `event_type`, `payload jsonb`, `merchant_ids uuid[]`, `published_at`, `attempts`, `next_attempt_at`, `last_error`), `outbox_consumer_inbox` (`(consumer, event_id) PRIMARY KEY`), `webhook_endpoint`, `webhook_delivery`, `notification`, `audit_log` (`id identity`, `actor`, `action`, `subject`, `details jsonb`, `created_at`). Trigger `audit_log_immutable`.
6. **`V6__domain.sql`**:
   - Expanded buckets on `ledger_account` to include `rail_fees`; seeded `system:rail_fees:GHS`.
   - Tables:
     - `customer`: `id uuid`, `subject UNIQUE`, `phone UNIQUE CHECK (~ '^233[0-9]{9}$')`, `display_name`, `email`, `kyc_tier CHECK (1..3)`, `status CHECK ('active', 'frozen', 'closed')`.
     - `merchant`: `id uuid`, `legal_name`, `trading_name`, `kind CHECK ('merchant', 'delivery', 'marketplace')`, `status CHECK ('pending_review', 'active', 'suspended', 'rejected')`, `merchant_code UNIQUE CHECK (~ '^[0-9]{6}$')`, `fee_bps CHECK (0..10000)`, `fee_fixed >= 0`, `settlement_delay_hours CHECK (0..720)`.
     - `merchant_branch`, `merchant_member` (role: owner, finance, cashier).
     - `settlement_destination` (type: momo, bank).
     - `marketplace_seller` (marketplace_id, seller_id PK).
     - `payment_link` (slug UNIQUE, amount, active).
     - `checkout` (reference UNIQUE, collector_id, status: open, processing, paid, expired, cancelled, currency char(3), subtotal, customer_fee, total_paid, journal_tx, expires_at).
     - `checkout_allocation` (checkout_id, line, merchant_id, kind: sale/delivery, amount, merchant_fee, refunded, refunded_fee, disputed, settled_at, settlement_tx).
     - `rail_transaction` (id, rail, direction: charge/payout/refund, reference UNIQUE, purpose, purpose_id, amount, status: created, pending, succeeded, failed, unknown, mismatch).
     - `rail_callback` (rail, dedupe_key UNIQUE, reference, event_type, payload jsonb, outcome).
     - `payment_attempt` (checkout_id, method, rail_tx, status: pending/succeeded/failed; UNIQUE on checkout_id WHERE status IN ('pending', 'succeeded')).
     - `refund` (reference UNIQUE, checkout_id, line, amount, fee_returned, destination: wallet/rail, status, journal_tx, completion_tx, rail_tx).
     - `wallet_operation` (reference UNIQUE, customer_id, kind: topup, transfer_out, transfer_in, withdrawal, payment, refund, amount, status, journal_tx, rail_tx).
     - `payout` (reference UNIQUE, owner_type, owner_id, destination jsonb, amount, rail, status: reserved, submitted, succeeded, failed, reservation_tx, completion_tx, rail_tx).
     - `dispute` (checkout_id, line, amount, reason, status: open, merchant_won, customer_won, hold_tx, resolution_tx).
     - `ops_item` (kind, severity: low, medium, high, subject, details jsonb, status: open, resolved; UNIQUE (kind, subject) WHERE status = 'open').
     - `reconciliation_run`, `reconciliation_line`.
     - `ussd_session` (`session_id PRIMARY KEY`, `msisdn`, `state jsonb`).
     - `simulated_rail_txn` (`reference PRIMARY KEY`, `direction`, `amount`, `outcome`, `status`).
7. **`V7__recipient_confirmation.sql`**:
   - `recipient_confirmation`: `id uuid`, `sender_customer_id REFERENCES customer`, `rail CHECK ('wallet', 'momo', 'bank')`, `destination`, `network`, `recipient_customer_id`, `verified_name`, `status CHECK ('active', 'used', 'revoked')`, `expires_at`, `used_at`, `used_by_tx REFERENCES journal_transaction`.
   - `recipient_lookup`: `sender_customer_id`, `rail`, `destination_hash`, `outcome CHECK ('verified', 'not_found', 'unavailable', 'disabled', 'self', 'rate_limited', 'failed')`.
   - `customer_phone_history`: tracks reassigned phone numbers.
8. **`V8__merchant_checkout.sql`**:
   - Adds `'admin'` role to `merchant_member`.
   - Columns on `checkout_allocation`: `pending_amount`, `settled_amount`, `settlement_batch REFERENCES settlement_batch(id)`.
   - `settlement_batch`: `id uuid`, `reference UNIQUE`, `merchant_id`, `amount`, `lines`, `journal_tx REFERENCES journal_transaction`.
   - Index on `checkout(expires_at) WHERE status = 'open'`.
9. **`V9__payouts.sql`**:
   - Status updates on `rail_transaction` and `payout` (adding `'returned'`, `'rejected'`).
   - `payout_batch`: `id uuid`, `reference UNIQUE`, `owner_type`, `owner_id`, `item_count`, `total`, `created_by`.
   - Payout additions: `batch_id`, `client_reference`, `verified_name`, `verification`, `failure_reason`, `return_tx`, `retry_of` (unique partial index).
10. **`V10__fx.sql`**:
    - Expanded buckets on `ledger_account`: `'fx_clearing'`, `'fx_in_flight'`.
    - `currency`: `code char(3) PRIMARY KEY`, `exponent integer`, `name text`. Seeds GHS, USD, GBP, EUR.
    - `fx_corridor`: `code PRIMARY KEY`, `direction (inbound/outbound)`, source and destination countries/currencies, `funding_method`, `delivery_method`, `partner`, `fee_fixed`, `fee_bps`, `min_amount`, `max_amount`, `quote_ttl_seconds`, `est_delivery_minutes`, `active`.
    - `fx_quote`: `id uuid`, `corridor REFERENCES fx_corridor`, `rate numeric(24,12)`, `source_amount`, `fee`, `total_debit`, `destination_amount`, `expires_at`, `status (open, accepted, expired, withdrawn)`.
    - `fx_transfer`: `id uuid`, `reference UNIQUE`, `direction`, `corridor`, `quote_id UNIQUE REFERENCES fx_quote`, `recipient jsonb`, `status (pending, paid, failed, returned, unknown)`, `journal_tx`, `delivery_tx`, `return_tx`, `reversal_tx`.
    - `simulated_fx_partner`: simulated sandbox table.
    - Seeded accounts: `system:revenue:USD`, `system:fx_clearing:GHS`, `system:fx_clearing:USD`, `system:fx_in_flight:USD`, `system:fx_in_flight:GHS`.
11. **`V11__platform_api.sql`**:
    - `api_credential`: added `scopes text[] DEFAULT '{view,collect,refund}'` checking subset of `['view', 'collect', 'refund', 'withdraw', 'payouts']`.
    - `merchant_delegation`: `merchant_id`, `delegate_id`, `permission`, `granted_by`.
    - `checkout`: added `order_reference` with unique partial index `(collector_id, order_reference)`.
    - `settlement_destination`: added verification columns.
    - GIN index on `outbox_event(merchant_ids)`.
12. **`V12__ussd.sql`**:
    - `customer`: added `ussd_pin_hash`, `ussd_pin_failures`, `ussd_locked_until`.
    - `ussd_session`: added `gateway`, `created_at`, index on `updated_at`.

### 1.3 CI Workflows
- **File**: `.github/workflows/sandbox.yml` (no other files exist in `.github/workflows/`).
- Trigger: `push`, `pull_request` on any branch.
- **Job 1: `web`**:
  - Environment: `ubuntu-latest`, Node.js 22 (`actions/setup-node@v4`), npm cache keyed on `web/package-lock.json`.
  - Commands:
    1. `npm ci --no-audit --no-fund`
    2. `npm run typecheck`
    3. `npm test`
    4. `npm run build`
- **Job 2: `smoke`**:
  - Environment: `ubuntu-latest`.
  - Service container: `postgres:18` (`POSTGRES_DB: elepay_sandbox`, `POSTGRES_USER: elepay`, `POSTGRES_PASSWORD: sandbox-ci-only`, port 5432:5432, health check `pg_isready -U elepay -d elepay_sandbox`).
  - Java: `actions/setup-java@v4` with `distribution: temurin`, `java-version: '25'`, Maven cache.
  - Commands:
    1. `mvn -B verify`
    2. Starts background service:
       `java -jar target/elepay-core-0.1.0-SNAPSHOT.jar --spring.profiles.active=sandbox,dev > sandbox.log 2>&1 &`
    3. Health poll (up to 60s) on `http://127.0.0.1:8080/sandbox/balances/customer:readiness`.
    4. Executes `python3 scripts/smoke.py`.
    5. Executes `python3 scripts/acceptance.py`.

### 1.4 Baseline Check Results

#### Web Frontend Checks:
All passed cleanly:
- `npm ci --no-audit --no-fund`: Exit code 0, 137 packages installed in 3s.
- `npm run typecheck`: Exit code 0 (`tsc -b` succeeded with 0 errors).
- `npm test`: Exit code 0 (`vitest run`):
  - 1 test file: `src/money.test.ts`
  - 3 passed tests (0 failed) in 21ms.
- `npm run build`: Exit code 0 (`tsc -b && vite build`):
  - 87 modules transformed.
  - Output artifacts generated in `web/dist/`:
    - `dist/index.html` (0.48 kB)
    - `dist/assets/index-BS9pIkuD.css` (2.48 kB)
    - `dist/assets/Wallet-Dzm4ZsiR.js` (4.74 kB)
    - `dist/assets/Ops-DchjRJI7.js` (6.45 kB)
    - `dist/assets/Merchant-BoN_JYd1.js` (32.69 kB)
    - `dist/assets/index-BO_mRu6L.js` (233.82 kB)

#### Java Backend Checks:
- Command: `mvn -B test-compile`
  - Exit code: 1
  - Primary error:
    `Fatal error compiling: error: release version 25 not supported`
    (Maven Compiler Plugin 3.14.1 targeting `javac [debug parameters release 25]` against host OpenJDK 17.0.21).
  - Preceding SSL truststore error (resolved via `-Dmaven.wagon.http.ssl.insecure=true`):
    `java.security.InvalidAlgorithmParameterException: the trustAnchors parameter must be non-empty` caused by broken `/etc/ssl/certs/java/cacerts` symlink.
  - When test-compiled targeting Java 17 (`-Dmaven.compiler.release=17`):
    - 54 of 56 source files compiled without error.
    - 2 compilation errors:
      `/home/antigravity/Elemarket9/ELEPAY-Core/src/main/java/com/elepay/wallet/RecipientConfirmations.java:[279,37] cannot find symbol: method getFirst()`
      `/home/antigravity/Elemarket9/ELEPAY-Core/src/main/java/com/elepay/wallet/RecipientConfirmations.java:[294,73] cannot find symbol: method getFirst()`
      This confirms Java 21+ Sequenced Collections (`List.getFirst()`) is required.
- Test Suite (`mvn -B test` / `mvn -B verify`):
  - Cannot run on host due to Java 25 compiler incompatibility and missing Docker daemon for Testcontainers (`docker: command not found`).
- Scripts (`scripts/smoke.py`, `scripts/acceptance.py`):
  - `smoke.py`: requires running server at `http://127.0.0.1:8080/sandbox`.
  - `acceptance.py`: requires running server at `http://127.0.0.1:8080`.
  - Neither can be executed without a compiled and running backend service.

### 1.5 Web Frontend Architecture & Dashboards
Located in `/home/antigravity/Elemarket9/ELEPAY-Core/web/`:
- **Framework & Libraries**:
  - React 19.1.0 (`react`, `react-dom`)
  - Vite 7.0.0 (`@vitejs/plugin-react` 5.0.0)
  - TypeScript 5.8.0
  - Vitest 3.2.0
  - `oidc-client-ts` 3.1.0
  - `qrcode` 1.5.4
- **Entry point and Client Routing** (`web/src/main.tsx`):
  - `/pay/l/:slug` -> `StartFromLink` component for slug-based payment links.
  - `/pay/m/:code` -> `StartFromLink` component for 6-digit merchant code payments.
  - `/pay/:reference` -> `Checkout` component for direct checkout payment flows.
  - `/merchant` -> `MerchantDashboard` (lazy-loaded from `Merchant.tsx`). Protected by `SignedIn` component.
  - `/ops` -> `OpsConsole` (lazy-loaded from `Ops.tsx`). Protected by `SignedIn` requiring `roles={["ops"]}`.
  - `/app` -> `Wallet` (lazy-loaded from `Wallet.tsx`). Protected by `SignedIn`.
  - `/` -> Landing page with merchant code direct-entry form.
  - `/app/callback` -> OIDC callback token completion handler.
- **Dashboards Detail**:
  1. **Wallet (`web/src/Wallet.tsx`)**: Customer wallet interface showing available/pending balances, send money to phone with recipient verification lookup, top up via simulated mobile money, transaction history with receipts.
  2. **Merchant Dashboard (`web/src/Merchant.tsx`)**: Merchant operations interface displaying balances (available, pending, reserved), checkout link/QR code generation, incoming sales, settlement batching, dispute notifications, and branch/cashier management.
  3. **Operations Console (`web/src/Ops.tsx`)**: Operations console for review queues, merchant onboarding approvals, dispute resolution, ops items management, rail transaction recovery, and ledger integrity monitoring (`/v1/ops/ledger/integrity`).

---

## 2. Logic Chain

1. **Premise 1 (Toolchain configuration)**: Observation 1.1 shows `pom.xml` specifies `<java.version>25</java.version>`, `spring-boot-starter-parent:4.0.10`, and Spring Modulith 2.0.10. Dockerfile and CI workflow explicitly use Temurin JDK 25 and PostgreSQL 18.
2. **Premise 2 (Environment divergence)**: Observation 1.1 and 1.4 show the host VM has OpenJDK 17.0.21, no Docker daemon, and PostgreSQL 15 (stopped).
3. **Inference 1**: The host VM is not yet configured with the required Java 25 toolchain or container runtime specified by the project.
4. **Premise 3 (Language requirement)**: Observation 1.4 shows `RecipientConfirmations.java` calls `List.getFirst()`, a method introduced in Java 21, and `application.properties` enables `spring.threads.virtual.enabled=true` (virtual threads, Java 21+).
5. **Inference 2**: The backend cannot be downgraded to Java 17 without rewriting code and disabling virtual threads; rather, the runtime environment must provide JDK 21+ (and specifically JDK 25 to satisfy `pom.xml`).
6. **Premise 4 (Web frontend status)**: Observation 1.4 shows `npm ci`, `npm run typecheck`, `npm test`, and `npm run build` all pass with zero errors in Node 24.
7. **Inference 3**: The web frontend is healthy, self-contained, and baseline-ready.
8. **Premise 5 (Database Schema coverage)**: Observation 1.2 shows 12 Flyway migrations providing complete relational and trigger foundations for double-entry ledger, immutability, outbox/inbox, checkouts, payouts, FX, USSD, and recipient confirmation.
9. **Inference 4**: The database schema is fully aligned with the requirements of the 100 algorithms for core financial journeys, but requires PostgreSQL 18 with PL/pgSQL triggers and constraint trigger support.

---

## 3. Caveats

1. **Local Test Execution Blocked**: Because JDK 25 and Docker are absent from the host environment, backend unit and integration tests (`mvn -B test`, `mvn -B verify`) could not be executed locally during this survey turn. The baseline test counts reported for Java are based on test source file discovery (14 test classes in `src/test/java`).
2. **Sandbox Service Unstarted**: Neither `scripts/smoke.py` nor `scripts/acceptance.py` could be run locally because they require a running ELEPAY backend service.
3. **CA Certificates Workaround**: The host environment requires either fixing `/etc/ssl/certs/java/cacerts` (e.g. via `sudo apt-get install --reinstall ca-certificates-java`) or running Maven with `-Dmaven.wagon.http.ssl.insecure=true`.

---

## 4. Conclusion

1. **Toolchain**:
   - Repository standard: Java 25, Spring Boot 4.0.10, Spring Modulith 2.0.10, PostgreSQL 18.
   - Host environment status: OpenJDK 17.0.21 (mismatch: release 25 unsupported), Docker missing, Node 24 / npm 11 (compatible).
2. **Flyway Migrations**:
   - 12 migrations (V1–V12) are present, clean, and comprehensive, covering double-entry accounting, transaction immutability, transactional outbox/inbox, customer/merchant models, checkouts, allocations, payouts, FX corridors/quotes, platform credentials/delegations, and USSD PIN security.
3. **CI Workflows**:
   - `.github/workflows/sandbox.yml` runs two jobs (`web` on Node 22, `smoke` on Temurin 25 with PostgreSQL 18 container running `mvn -B verify`, `scripts/smoke.py`, and `scripts/acceptance.py`).
4. **Baseline Verification**:
   - **Frontend**: 100% passing (typecheck: 0 errors; Vitest: 3/3 tests pass; build: 6 production asset bundles generated).
   - **Backend**: Compilation blocked on host solely by JDK 17 vs JDK 25 release mismatch; dependencies download successfully from Maven Central; 54/56 source files are Java 17-clean and 2 call Java 21+ `List.getFirst()`.
5. **Web Frontend / Dashboards**:
   - Full React 19 + TypeScript single-page app containing 3 major dashboards: Customer Wallet (`/app`), Merchant Dashboard (`/merchant`), and Operations Console (`/ops`), plus payment link and QR checkout flows (`/pay/*`).

---

## 5. Verification Method

To independently verify the observations:
1. **Toolchain versions**:
   - `java -version`
   - `mvn -version`
   - `node -v` && `npm -v`
   - `head -n 25 /home/antigravity/Elemarket9/ELEPAY-Core/pom.xml`
2. **Web baseline test and build**:
   ```bash
   cd /home/antigravity/Elemarket9/ELEPAY-Core/web
   npm run typecheck
   npm test
   npm run build
   ```
3. **Database migrations inspection**:
   ```bash
   ls -la /home/antigravity/Elemarket9/ELEPAY-Core/src/main/resources/db/migration/
   ```
4. **Backend compilation attempt**:
   ```bash
   cd /home/antigravity/Elemarket9/ELEPAY-Core
   mvn -B -Dmaven.wagon.http.ssl.insecure=true test-compile
   ```
   (Expect failure: `Fatal error compiling: error: release version 25 not supported` under JDK 17).
5. **CI workflow inspection**:
   ```bash
   cat /home/antigravity/Elemarket9/ELEPAY-Core/.github/workflows/sandbox.yml
   ```
