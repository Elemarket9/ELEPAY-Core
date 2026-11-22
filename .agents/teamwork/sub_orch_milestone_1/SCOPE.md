# Milestone 1 Scope: Core Financial Journey & Ledger Hardening

## Objectives
1. Toolchain & Build Environment Alignment:
   - Ensure Java / Maven builds cleanly and tests compile and pass.
   - Baseline environment setup (JDK 25 / Spring Boot 4 / Postgres).
2. Complete implementation and hardening of the 12-step internal wallet transfer flow:
   - Step 1: Authentication (JWT & USSD PIN with BCrypt lockout #074)
   - Step 2: Recipient phone number entry & MSISDN normalization (Ghana +233 and international #095)
   - Step 3: Rate-limited audited recipient lookup (#037)
   - Step 4: Masked recipient name presentation
   - Step 5: Fee schedule engine integration (#007) in preview and execution (percentage, fixed, tiered, caps)
   - Step 6: Bound single-use confirmation token (#038)
   - Step 7: KYC tier limits (#039), velocity rules (#023 baseline), available funds check
   - Step 8: Atomic double-entry transfer (#001) with ID-ordered row locking and deadlock retry (#002)
   - Step 9: Receipts and balances (#010 balance snapshots & #050 ledger reconstruction)
   - Step 10: Idempotent request hashing (#003)
   - Step 11: Operational tracing and tamper-evident audit records (#080)
   - Step 12: Clear, actionable error responses
3. Core Ledger & Financial Primitives:
   - Integer proportional allocation (#004)
   - Linked reversals (#005)
   - Per-currency FX clearing (#006)
   - HMAC verification (#073)
   - Transactional outbox & inbox deduplication (#085)
   - Token-bucket rate limiting (#088)
4. Scorecard & Documentation:
   - Update `docs/readiness-scorecard.md` with verified commits, test evidence, and execution mode.
   - Comprehensive test suite (positive, negative, concurrency, idempotency, edge cases).
