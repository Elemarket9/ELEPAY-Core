# Milestone 4 Handoff Report: Direct Partner Rail Adapters & Simulators (Algorithms #011 - #019)

## 1. Observation
- Implemented full suite of partner rails and algorithms in `src/main/java/com/elepay/rails/`:
  - `MtnMomoRail.java`: Direct MTN Mobile Money adapter (charges, payouts, refunds, status queries, HMAC callback verification, 1% fee capped at 1000 pesewas).
  - `TelecelCashRail.java`: Direct Telecel Cash adapter (charges, payouts, refunds, status queries, HMAC-SHA256 callbacks, 0.75% fee capped at 750 pesewas).
  - `AirtelTigoRail.java`: Direct AirtelTigo Money adapter (charges, payouts, refunds, queries, HMAC callbacks, 0.50% fee capped at 500 pesewas).
  - `GhipssRail.java`: Direct GhIPSS interbank clearing adapter (instant interbank transfers, status queries, HMAC callbacks, 50 pesewas flat fee).
  - `CardAcquirerRail.java`: Direct Card Acquirer adapter integrating with `CardTokenVault` and `ThreeDSecureService` (1.5% acquirer fee).
  - `SmartPaymentRouter.java` (Algorithm #014): Dynamic route selection across MTN, Telecel, AirtelTigo, Banks, and Card evaluating channel capability, circuit health, cost (bps & fixed fee), priority, and measured success rate.
  - `SafeFailoverRouter.java` (Algorithm #015): Enforces strict financial failover rules. Reroutes only when primary rail is definitively unsubmitted or failed without funds movement (`NOT_FOUND`, circuit breaker OPEN prior to submission, or pre-submission failure). Strictly blocks failover on `UNKNOWN`, `PENDING`, or `SUCCEEDED` status with `UnsafeFailoverException` and raises high-severity ops alerts.
  - `NetworkHealthDetector.java` (Algorithm #016): Bounded sliding window tracking error rates, timeouts, and p95/avg latencies. Operates 3-state circuit breaker (`CLOSED`, `OPEN`, `HALF_OPEN`) per rail. Trips on error rate >= 40% (min 5 samples) or 3 consecutive timeouts; probes after 30s cooldown.
  - `CardBinLookupService.java` (Algorithm #017): Offline, versioned card BIN database resolving 6-8 digit BINs for scheme (Visa, Mastercard, Gh-Link), card type, issuer bank, country code, and currency. Loaded into in-memory cache with fallback to DB.
  - `ThreeDSecureService.java` (Algorithm #018): Risk-based 3DS 2.x protocol engine supporting frictionless flow (immediate authentication, liability shift = true, ECI '05', CAVV, DS transaction ID) and challenge flow (OTP challenge issuance, challenge URL, verification with attempt lockout).
  - `CardTokenVault.java` (Algorithm #019): Card tokenization boundary. Enforces Luhn checksum validation, future expiration, PAN masking (`411111******1111`), SHA-256 fingerprint deduplication, AES-GCM-256 authenticated encryption, and token lifecycle (creation, lookup, rail decryption, revocation). Never retains CVV/CVC.
  - `RailEndpoints.java`: REST controller exposing endpoints for BIN lookup, tokenization, token revocation, 3DS challenge completion, route preview, and rail health queries.
- Created Flyway migration `src/main/resources/db/migration/V18__partner_rails.sql` creating tables: `card_bin`, `card_token`, `three_d_secure_session`, `rail_route_config`, `rail_health_window`, `rail_failover_log`, and seeding Ghanaian BINs and routing configs.
- Created comprehensive test suite under `src/test/java/com/elepay/rails/`:
  - `CardBinLookupTest.java` (5 tests)
  - `CardTokenVaultTest.java` (4 tests)
  - `ThreeDSecureTest.java` (2 tests)
  - `NetworkHealthAndRoutingTest.java` (9 tests)
  - `PartnerRailAdaptersTest.java` (5 tests)
- Verification result: `mvn -o test -Dtest=CardBinLookupTest,CardTokenVaultTest,ThreeDSecureTest,NetworkHealthAndRoutingTest,PartnerRailAdaptersTest` -> 25 tests run, 0 failures, 0 errors, BUILD SUCCESS.

## 2. Logic Chain
- Algorithms #011, #012, and #013 require durable submission intent, safe recovery queries with backoff, and strict callback state machines. The direct partner adapters (`MtnMomoRail`, `TelecelCashRail`, `AirtelTigoRail`, `GhipssRail`, `CardAcquirerRail`) connect seamlessly to `RailTransactions`, enforcing idempotency keys, deterministic status mappings, and cryptographic HMAC callback authentication.
- Algorithm #014 requires dynamic route selection. `SmartPaymentRouter` queries `rail_route_config` and evaluates live health from `NetworkHealthDetector`, selecting the optimal route by success rate and fee cost while steering away from degraded or open-circuit rails.
- Algorithm #015 enforces zero duplicate funds movement: `SafeFailoverRouter` blocks failover when primary status is `UNKNOWN`, `PENDING`, or `SUCCEEDED`. Failover is permitted only when primary was confirmed `NOT_FOUND` or circuit was `OPEN` before submission.
- Algorithm #016 operates a sliding window of recent requests per rail, tripping to `OPEN` when error rates exceed 40% or 3 timeouts occur in sequence, protecting partner networks and preventing stuck requests.
- Algorithm #017 provides offline versioned card BIN metadata for Ghanaian and international cards without treating BIN lookup as authorization.
- Algorithm #018 evaluates transaction risk: sub-threshold or low-risk payments pass via frictionless flow (with liability shift), while elevated amounts or risk scores trigger challenge authentication.
- Algorithm #019 isolates raw card data in an AES-GCM-256 encrypted vault. Raw PAN is never logged or exposed to general modules; CVV is strictly discarded after validation.
- All code strictly excludes Paystack and Hubtel as mandated.

## 3. Caveats
- Direct partner network APIs are configured to run in realistic simulated/sandbox mode in the cloud development environment since live credentials for Bank of Ghana / GhIPSS / telco production gateways are not available in this environment.
- When live gateway credentials become available, partner endpoint URLs and API keys can be supplied via environment properties (`elepay.rails.mtn.*`, `elepay.rails.telecel.*`, etc.).

## 4. Conclusion
Milestone 4 (Direct Partner Rail Adapters & Simulators — Algorithms #011 through #019) is completely built, validated, and tested. All 25 unit/integration tests pass with 100% success. Flyway V18 migration is in place, and `docs/readiness-scorecard.md` has been updated with verified status for Algorithms 011 through 019.

## 5. Verification Method
Run the rail test suites:
```bash
mvn -o test -Dtest=CardBinLookupTest,CardTokenVaultTest,ThreeDSecureTest,NetworkHealthAndRoutingTest,PartnerRailAdaptersTest
```
Expected output:
```
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
