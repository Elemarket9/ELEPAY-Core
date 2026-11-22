## 2026-11-22T03:53:01Z
You are Worker 4 (Coding & Implementation) for Milestone 5B: Compliance, Sanctions, PEP, AML & Security.
Working directory: /home/antigravity/Elemarket9/ELEPAY-Core
State folder: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/worker_m5b_compliance
Original request: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/ORIGINAL_REQUEST.md
Project plan: /home/antigravity/Elemarket9/ELEPAY-Core/PROJECT.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

EXCLUSIVE WRITE SCOPE:
- Java packages: `src/main/java/com/elepay/compliance/` and `src/main/java/com/elepay/security/`
- Test packages: `src/test/java/com/elepay/compliance/` and `src/test/java/com/elepay/security/`
- Migration: `src/main/resources/db/migration/V20__compliance_security.sql`

TASKS:
Implement Algorithms #040 - #048 and Security Controls #075, #076, #078, #081-#084:
- 040: Ghana Card matching (NIA verification channel with explicit simulation tagging)
- 041: Face matching and liveness (biometric selfie matching against ID with human review queue)
- 042: Fuzzy sanctions screening (exact match + Jaro-Winkler + Soundex against UN/OFAC lists)
- 043: PEP screening (Politically Exposed Persons database screening)
- 044: Structuring detection (smurfing pattern detection across rolling windows)
- 045: AML transaction monitoring (scenario-based AML case generation)
- 046: Alert prioritization (multi-factor priority queue for compliance cases)
- 047: Regulatory report generation (Bank of Ghana BoG and FIC export filings)
- 048: Retention and redaction (statutory retention policy engine and GDPR redaction)
- 075: Field-level envelope encryption (AES-GCM-256 with KMS DEK wrapping)
- 076: Key rotation (re-encryption workflows preserving historical access)
- 078: JWKS caching and rollover (OIDC public key retrieval and asynchronous rollover)
- 081: Administrative anomaly detection (privileged operator behavior monitoring)
- 082: Session and device binding (refresh token rotation with family revocation on reuse)
- 083: Mobile certificate pinning (TLS public key pinning with backup pins)
- 084: CI security scanning (automated security check integration)
Add Flyway V20, write unit/integration tests, verify compile/pass, update docs/readiness-scorecard.md for 040-048 and 075-084, commit and push to origin/main immediately. Report handoff when done.

## 2026-11-22T04:00:58Z
**Context**: Heartbeat check iteration 8
**Content**: Heartbeat check on Worker 4 (M5B Compliance, AML & Security). Please report your progress on algorithms 040-048, 075-084, migration V20, and tests.
**Action**: Please share current status.
