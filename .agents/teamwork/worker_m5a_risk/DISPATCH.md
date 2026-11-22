## 2026-11-22T03:53:01Z
You are Worker 3 (Coding & Implementation) for Milestone 5A: Fraud & Risk Engine.
Working directory: /home/antigravity/Elemarket9/ELEPAY-Core
State folder: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/worker_m5a_risk
Original request: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/ORIGINAL_REQUEST.md
Project plan: /home/antigravity/Elemarket9/ELEPAY-Core/PROJECT.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

EXCLUSIVE WRITE SCOPE:
- Java packages: `src/main/java/com/elepay/risk/`
- Test packages: `src/test/java/com/elepay/risk/`
- Migration: `src/main/resources/db/migration/V19__fraud_risk_engine.sql`

TASKS:
Implement Algorithms #023 - #036:
- 023: Velocity rules (sliding window transaction counters across 1h, 24h, 7d)
- 024: Rules engine and risk score (versioned declarative rule evaluation with explainable decision trees)
- 025: Device risk signals (privacy-conscious client fingerprinting and risk scoring)
- 026: SIM-swap checks (IMSI swap age detection triggering holds/step-up)
- 027: Geo-velocity checks (great-circle distance / impossible travel detection)
- 028: Account takeover detection (session/IP/withdrawal anomaly correlation)
- 029: Adaptive step-up authentication (dynamic action-bound challenge issuance)
- 030: Merchant risk scoring (continuous monitoring of dispute and refund velocity)
- 031: Promotion and referral abuse detection (sybil and circular referral graph detection)
- 032: Mule-account graph detection (transaction flow graph identifying pass-through mules)
- 033: Synthetic identity detection (cross-referencing inconsistencies in ID/phone data)
- 034: Behavioral biometrics (keystroke dynamics and pointer cadence telemetry)
- 035: Gradient-boosted fraud model (LightGBM/XGBoost style feature vector scoring; marked experimental)
- 036: Chargeback prediction (calibrated dispute probability scoring)
Add Flyway V19, write unit/integration tests, verify compile/pass, update docs/readiness-scorecard.md for 023-036, commit and push to origin/main immediately. Report handoff when done.

## 2026-11-22T04:00:53Z
**Context**: Heartbeat check iteration 8
**Content**: Heartbeat check on Worker 3 (M5A Fraud & Risk Engine). Please report your progress on algorithms 023-036, migration V19, and tests.
**Action**: Please share current status.
