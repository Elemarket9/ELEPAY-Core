## 2026-11-22T20:07:38Z
You are worker_fix_reconciliation_fraud, a financial engineering worker for ELEPAY Core.

MANDATORY FIRST STEP:
Read the master directive file at /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/ORIGINAL_REQUEST.md completely.
Also read /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/orchestrator_gen3/ALGORITHM_VERIFICATION_REGISTER.md and /home/antigravity/Elemarket9/ELEPAY-Core/PROJECT.md.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

EXCLUSIVE WRITE FILE OWNERSHIP:
- src/main/java/com/elepay/operations/ReconciliationService.java
- src/main/java/com/elepay/risk/GeoVelocityService.java
- src/main/java/com/elepay/risk/PromoAbuseService.java
- src/main/java/com/elepay/risk/MuleGraphService.java
- src/main/java/com/elepay/risk/ChargebackPredictor.java
- src/main/java/com/elepay/compliance/SyntheticId.java
- src/test/java/com/elepay/operations/ReconciliationServiceTest.java (or create if needed)
- src/test/java/com/elepay/risk/ (targeted test files for modified risk classes)
Do NOT modify any files outside this exclusive list.

YOUR OBJECTIVES:
1. Repair Algorithm 052 (Fuzzy Statement Matching):
   - In ReconciliationService.java line 239: fix the boolean logic defect from `if (refMatch && (amountMatch || timeMatch))` to `if (refMatch && amountMatch && timeMatch)`.
   - Do NOT mark lines `MATCHED` immediately without review; insert uncertain candidate matches into `OpsQueue` with status `PROPOSED_MATCH` requiring maker-checker ops approval.
2. Repair Algorithm 053 (Partner Fee Verification):
   - In ReconciliationService.java lines 276-299: replace hardcoded in-code if/else matrix with database fee matrices or versioned configuration. Handle integer minor-unit calculations with banker's rounding without truncation. Detect both overcharges and undercharges.
3. Repair Algorithm 054 (Suspense Aging and Clearing):
   - In ReconciliationService.java line 395: route clearing entries through currency-specific suspense accounts (`"platform:suspense:" + item.currency().toLowerCase()`) instead of hardcoded `Ledger.GHS`.
   - Add `SELECT ... FOR UPDATE` in `approveClearing` to eliminate double-approval race conditions.
4. Repair Algorithm 027 (Geo-velocity Checks):
   - In GeoVelocityService.java: fix subsecond clamping bug preventing division by zero or negative time deltas.
5. Repair Algorithm 031 & 032 (Promotion Abuse & Mule Graph):
   - In PromoAbuseService.java: eliminate recursive N+1 database queries.
   - In MuleGraphService.java: add bounded limits/paging to prevent unbounded full-table memory scans.
6. Repair Algorithm 036 (Chargeback Prediction):
   - In ChargebackPredictor.java: handle multi-currency fee conversions without corrupting foreign currency amounts with flat GHS fees.
7. Verification:
   - Run compilation and tests for affected targets (`mvn test -Dtest="*Reconciliation*,*GeoVelocity*,*PromoAbuse*,*MuleGraph*"`).
   - Commit and push verified increment to main.
8. Write your completion report to:
   /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/worker_fix_reconciliation_fraud/handoff.md
   Include exact build/test commands and output summaries, commit hash, and send message when done.

State folder: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/worker_fix_reconciliation_fraud
Working directory: /home/antigravity/Elemarket9/ELEPAY-Core
