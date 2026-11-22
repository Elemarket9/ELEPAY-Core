# Milestone 3 Scope: FX, Cross-Border Remittances & Payouts

## Algorithms & Features
1. **059 Rate sourcing and blending (`com.elepay.fx.RateBlendingEngine`)**:
   - Ingest rates from multiple providers (e.g. Bloomberg, Reuters, Central Bank, Partner FX feeds)
   - Outlier rejection (e.g., modified Z-score or standard deviation cutoff > threshold)
   - Mid-market rate synthesis with confidence weights and volume-weighted averaging.
2. **060 Dynamic FX margin pricing (`com.elepay.fx.MarginPricingService`)**:
   - Tiered spread/margin calculation over synthesized base rates based on transaction volume/amount tiers, customer KYC tier, and corridor risk.
   - Locked customer quotes with explicit TTL (e.g. 60-300 seconds), quote tokens, quote confirmation, and expired quote rejection.
3. **061 Corridor routing (`com.elepay.fx.CorridorRoutingService`)**:
   - Multi-partner route evaluation for cross-border payouts (currency pair, corridors, e.g. GHS->NGN, USD->GHS, EUR->GHS, GBP->KES).
   - Route ranking and selection scoring based on partner corridor support, transactional cost (fee bps + fixed), available liquidity, rail health, and delivery speed.
4. **062 Exposure limits and hedging triggers (`com.elepay.fx.ExposureTrackingService`)**:
   - Real-time tracking of net open foreign currency positions (NOP) across all currency pairs.
   - Configurable exposure limits per currency (soft limit, hard limit).
   - Automated hedging trigger generation when threshold breached (e.g. alert/order creation).
5. **063 Delivery-time estimation (`com.elepay.fx.DeliveryEstimationService`)**:
   - Empirical arrival time estimation based on historical route SLA, partner cutoff times, banking hours, weekends, and holidays.
   - Communicates accurate delivery estimates (e.g. `estimated_arrival_at`, confidence interval).
6. **064 Multi-currency partner reconciliation (`com.elepay.fx.MultiCurrencyReconciliationService`)**:
   - Dual-currency matching engine verifying external partner statement against internal remittance records:
     * Source debits (sent amount & currency)
     * Destination credits (received amount & currency)
     * Realized exchange rate vs contracted quote rate
     * Partner fees and discrepancies / tolerance checking.
7. **Payouts & Cross-Currency Clearing (`com.elepay.payout`, `com.elepay.fx`)**:
   - Payout batching: Batch processing up to 500 items per payout batch, batch status transitions (PENDING, PROCESSING, COMPLETED, PARTIALLY_FAILED).
   - Available balance reservation: Instant hold on merchant/customer available balance before rail dispatch; release on final debit or reversal on failure.
   - Safe rail retries: Use of `retry_of` partial unique index on retry transactions to guarantee at-most-once submission.
   - Multi-currency clearing ledger entries: Balanced double-entry postings using explicit per-currency clearing accounts (`system:fx_clearing:GHS`, `system:fx_clearing:USD`, etc.).
8. **Flyway Migration (`V17__fx_cross_border_payouts.sql`)**:
   - Tables for FX provider quotes, exchange rates, corridor configurations, partner routing rules, FX exposure limits & positions, customer FX quotes, and payout batching / reservations / safe retry tracking.

## Fast-Track Execution Strategy
Under FAST-TRACK directive:
- Dispatch specialized worker (`teamwork_worker`) equipped with the MANDATORY INTEGRITY WARNING.
- Worker implements backend services, domain models, controllers/APIs, database migrations, and comprehensive integration tests.
- Worker verifies with test execution.
- Immediately commit, push to origin/main, update `docs/readiness-scorecard.md`, and report handoff.
