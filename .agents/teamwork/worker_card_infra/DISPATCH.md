## 2026-11-22T15:06:56Z
You are an expert Card Payments & Cryptographic Vault Worker.

MANDATORY FIRST STEP:
Read the master request file at:
/home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/ORIGINAL_REQUEST.md
Specifically review:
- Section 10: ELIMINATE ALL PRODUCTION SIMULATIONS
- Section 13: COMPLETE CARD PAYMENT INFRASTRUCTURE (Direct card acquiring, 3DS 2.x, network tokens, PCI DSS 4.0.1 compliance, never store CVV, no test BINs in production)
- Section 21: CRYPTOGRAPHIC ENGINEERING (AES-256-GCM envelope encryption, key rotation)
- Strictly exclude native mobile applications ("don't dev applications yet")
- Review survey report: `/home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/explorer_survey_simulations/handoff.md`

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

EXCLUSIVE OWNERSHIP:
Package: `com.elepay.cards` (`src/main/java/com/elepay/cards/` and `src/test/java/com/elepay/cards/`)

YOUR ASSIGNMENT:
Working directory: /home/antigravity/Elemarket9/ELEPAY-Core
State folder: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/worker_card_infra

1. PCI DSS 4.0.1 Compliance in `CardIssuingService`:
   - Never store CVV2 or PIN blocks in the database. Eliminate plaintext `cvv2` column storage in `card_registry`.
   - Remove standard test BINs (`411111`, `539999`) from production card issuance.
   - Replace pseudo-encryption `"ENC:"` with real AES-256-GCM authenticated envelope encryption for PAN storage using securely derived keys and AAD.
2. Wire Card Authorization Engine to Authoritative Ledger:
   - In `CardAuthorizationEngine`, place atomic balance holds/reservations on customer or merchant accounts in `com.elepay.ledger.Ledger`, ensuring card spends are backed by actual financial balances.
3. Card Tokenization Lifecycle:
   - Implement network tokenization boundary, token cryptograms, expiry management, and status lifecycle (ACTIVE, SUSPENDED, REVOKED).
4. Add comprehensive unit tests in `src/test/java/com/elepay/cards/`.
5. Run tests: `mvn test -Dtest="*Card*Test"` and `mvn test-compile`.
6. Commit your changes and push to `origin/main`.
7. Write your 5-component handoff report to:
   `/home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/worker_card_infra/handoff.md`
8. When finished, message the orchestrator (`7de8fe21f55a2763ea2f89762355a60a`).
