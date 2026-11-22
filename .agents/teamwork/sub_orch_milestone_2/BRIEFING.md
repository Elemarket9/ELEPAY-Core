# Milestone 2 Briefing: Commercial Workflows & Settlement

## Objective
Implement, harden, test, and verify all 12 algorithms and commercial capabilities assigned to Milestone 2:
- 009: Revenue recognition
- 020: Recurring billing mandates
- 021: Dunning schedules
- 022: Dynamic & static EMVCo QR encoding/decoding
- 049: Exact 4-way statement matching
- 051: Settlement batching
- 052: Fuzzy statement matching
- 053: Partner fee verification
- 054: Suspense aging and clearing
- 055: Settlement netting
- 077: Request signing & replay protection
- 079: Maker-checker approvals

## Work Breakdown
1. **Quick Fixes & E2E Alignment**:
   - `CheckoutService.java`: Fix `Set.of` crash on duplicate merchant/collector ID.
   - `RailTransactions.java`: Match `reference = :r OR purpose_id = :uuid` in callback handling.
   - `CheckoutOperations.java`: Add `@JsonAlias("allocationLine")` on `line` in dispute request record.
   - `ApiKeys.java`: Include `"apiKey"` alongside `"secret"` in JSON response.
   - `MerchantPayouts.java`: Check payout existence returning 404 before validating idempotency header.
2. **Checkout, QR & Mandates**:
   - Algorithm 022: Implement `EmvcoQr` utility with Tag 63 CRC16-CCITT checksum calculation, generation, and decoding. Return `"qrPayload"` in `Checkouts.qr()`.
   - Algorithm 020 & 021: Flyway migration `V15__mandates_and_dunning.sql`, `MandateService` with customer consent, recurring charge idempotency, and `DunningScheduler` with smart backoff retries and notifications.
3. **Reconciliation, Treasury & Revenue Recognition**:
   - Flyway migration `V16__reconciliation_and_treasury.sql`.
   - Algorithm 049 & 052: Generalized 4-way statement matching engine and fuzzy heuristic matcher for truncated references.
   - Algorithm 053: Partner fee verification matrix and overcharge anomaly detection.
   - Algorithm 054: Suspense aging buckets (0-30d, 31-60d, 61-90d, 90d+) and maker-checker clearance workflow.
   - Algorithm 055: Settlement netting (offsetting liabilities, dispute holds, and refunds against gross proceeds).
   - Algorithm 009: Revenue recognition separating collected, deferred, and earned platform fees.
4. **Security & Dual Control**:
   - Algorithm 077: Request signing filter checking `X-Elepay-Signature`, `X-Elepay-Timestamp` (within 300s), and `X-Elepay-Nonce`.
   - Algorithm 079: Maker-checker dual control enforcement preventing self-approval on privileged actions (disputes, suspense, merchant onboarding review).
5. **Testing, Scorecard & Verification Wave**:
   - Run unit and integration tests (`mvn -B test`).
   - Run E2E suites (`python3 scripts/run_e2e.py --tier 1,2,3`).
   - Update `docs/readiness-scorecard.md`.
