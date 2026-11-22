# Milestone 2 Scope: Commercial Workflows & Settlement

## Assigned Algorithms
- **009**: Revenue recognition (separate collected fees, deferred merchant balances, earned platform revenue)
- **020**: Recurring billing mandates (consent, scope, schedules, cancellation, duplicate protection)
- **021**: Dunning schedules (smart exponential backoff, retry ceilings, customer notifications)
- **022**: EMVCo QR encoding & decoding (static/dynamic, Tag 63 CRC16-CCITT checksum)
- **049**: Exact statement matching (4-way matching of external rail statement lines to internal transactions)
- **051**: Settlement batching (reproducible batches, eligibility criteria, delay hours, duplicate-settlement protection)
- **052**: Fuzzy statement matching (heuristic matching with timestamp windows and fee tolerances)
- **053**: Partner fee verification (reconciles deducted network fees against contractual fee schedules)
- **054**: Suspense aging and clearing (tracks unallocated balances in suspense, maker-checker clearance)
- **055**: Settlement netting (bilateral/multilateral net obligations within legal entity and currency boundaries)
- **077**: Request signing & replay protection (client request signature verification with timestamp windows and nonces)
- **079**: Maker-checker approvals (dual-control enforcement where privileged operations require 2 distinct authorized actors)

## Key Workflows & Features
1. Multi-allocation commercial checkout (merchant/delivery split, merchant fees, customer fees)
2. Dynamic EMVCo QR code encoding & decoding with CRC16-CCITT checksum
3. Payment links & merchant codes
4. Revenue recognition accounting engine (collected, deferred, earned)
5. Recurring billing mandates & automated dunning schedules
6. Line-level refunds with proportional fee recovery
7. Dispute management with reserved dispute hold bucket & maker-checker resolution
8. Settlement batching, netting, partner fee audit, and suspense clearing
9. Request signing & replay defense
10. Maker-checker dual-control approvals across privileged operations (disputes, suspense, settlement overrides)

## Verification Targets
- Backend unit and integration tests passing (`mvn -B verify`)
- Tier 1, 2, 3 E2E test suites passing (`python3 scripts/run_e2e.py --tier 1,2,3`)
- `docs/readiness-scorecard.md` updated for all Milestone 2 algorithms
- Zero ledger drift (`GET /v1/ops/ledger/integrity`)
