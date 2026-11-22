## 2026-11-22T03:53:00Z
You are Worker 2 (Coding & Implementation) for Milestone 4: Direct Partner Rail Adapters & Simulators.
Working directory: /home/antigravity/Elemarket9/ELEPAY-Core
State folder: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/worker_m4_rails
Original request: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/ORIGINAL_REQUEST.md
Project plan: /home/antigravity/Elemarket9/ELEPAY-Core/PROJECT.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

EXCLUSIVE WRITE SCOPE:
- Java packages: `src/main/java/com/elepay/rails/`
- Test packages: `src/test/java/com/elepay/rails/`
- Migration: `src/main/resources/db/migration/V18__partner_rails.sql`
- NOTE: Paystack and Hubtel are STRICTLY EXCLUDED.

TASKS:
Implement Algorithms #011 - #019:
- 011: Duplicate-safe rail submission (pre-submission intent, idempotency keys, stable references)
- 012: Unknown-outcome recovery (polling unresolved transactions with backoff)
- 013: Callback state machine (HMAC validation, deduplication, valid state transitions)
- 014: Smart payment routing (dynamic route selection across MTN, Telecel, AirtelTigo, Banks)
- 015: Safe failover routing (switching only when definitively unsubmitted or failed without funds movement)
- 016: Network health detection (sliding window error rates, latencies, circuit health)
- 017: Card BIN lookup (offline versioned BIN database for card scheme, bank, country)
- 018: Risk-based 3-D Secure (3DS 2.x authentication challenge / frictionless flow)
- 019: Card tokenization boundary (token lifecycle, secure storage, masked PAN)
Add Flyway V18, write unit/integration tests, verify compile/pass, update docs/readiness-scorecard.md for 011-019, commit and push to origin/main immediately. Report handoff when done.


## 2026-11-22T04:00:46Z
**Context**: Heartbeat check iteration 8
**Content**: Heartbeat check on Worker 2 (M4 Direct Partner Rails). Please report your progress on algorithms 011-019, migration V18, and tests (Paystack/Hubtel strictly excluded).
**Action**: Please share current status.
