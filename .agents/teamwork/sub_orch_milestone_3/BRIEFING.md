# Milestone 3 Technical Briefing: FX, Cross-Border Remittances & Payouts

## Overview
This briefing synthesizes the architecture and implementation blueprint for Milestone 3, covering Algorithms 059 to 064, Payout Batching, Available Balance Reservation, Safe Rail Retries, and Multi-Currency Clearing.

## 1. Algorithms & Scope
- **Algorithm 059: Rate Sourcing and Blending (`com.elepay.fx.FxRateService`)**:
  - Ingestion of multi-provider FX bid/ask quotes with source weights.
  - Outlier detection: sample median with deviation cutoff > 2.0% (`OUTLIER_THRESHOLD = 0.0200`).
  - Mid-market rate synthesis using volume/reliability-weighted averages and spread calculation.
- **Algorithm 060: Dynamic FX Margin Pricing (`com.elepay.fx.FxPricingService`)**:
  - Tiered margin matrix matching corridor, customer tier (`standard`, `vip`, `enterprise`, `merchant`), and transaction volume.
  - Customer rate calculation: `EffectiveRate = BaseRate * (10000 - marginBps) / 10000`.
  - Integer minor-unit conversion with explicit floor rounding (`RoundingMode.DOWN`).
  - Quote locking with TTL (e.g. 60–300s) and token verification.
- **Algorithm 061: Corridor Routing Engine (`com.elepay.fx.FxRoutingService`)**:
  - Multi-partner route evaluation per corridor.
  - Multi-criteria weighted scoring: Cost (30%), Delivery Speed (25%), Liquidity (25%), Reliability (20%).
  - Dynamic selection of best partner route for quote generation and execution.
- **Algorithm 062: Exposure Limits and Hedging Triggers (`com.elepay.fx.FxExposureService`)**:
  - Real-time calculation of Net Open Foreign Position (NOFP) per currency across clearing and in-flight accounts:
    `NOFP(C) = Balance(fx_clearing:C) + Balance(fx_in_flight:C) + sum(Balance(partner_clearing:C))`
  - Configurable exposure limits (warning threshold, hard limit).
  - Automated hedging trigger generation (`fx_hedging_trigger`), ops alert escalation, and domain events.
- **Algorithm 063: Delivery-Time Estimation (`com.elepay.fx.FxDeliveryEstimator`)**:
  - Empirical arrival time estimation using historical route SLA percentiles (p50, p95).
  - Adjustments for partner clearing cutoffs (e.g. after 15:00 GMT rolls over to next business day) and weekend delays (Saturday/Sunday rolls over to Monday 09:00 GMT).
- **Algorithm 064: Multi-Currency Partner Reconciliation (`com.elepay.fx.FxDualReconciliationService`)**:
  - Dual-currency statement matching verifying source debits, destination credits, realized exchange rates, and fees.
  - Verification of partner stated closing balances against double-entry ledger accounts (`system:clearing:fx-{partner}:{dstCur}`).
- **Payouts & Cross-Currency Clearing (`com.elepay.payout`, `com.elepay.ledger`)**:
  - Payout batching: up to 500 items per batch, atomic validation, duplicate client reference prevention.
  - Available balance reservation: `available` -> `reserved` before rail submission; release on failure, settlement on success.
  - Safe rail retries: `payout.retry_of` unique constraint preventing duplicate retries; only `failed` or `returned` are retryable.
  - Multi-currency ledger entries: per-currency zero-sum balancing via `system:fx_clearing:SRC` and `system:fx_clearing:DST`.
- **Flyway Migration `V17__fx_payouts_adv.sql`**:
  - Supports all advanced FX tables, margin tiers, exposure limits, hedging triggers, and delivery SLAs.

## 2. Test Execution Guidelines
- Run Maven with offline flag and SSL truststore:
  `MAVEN_OPTS="-Djavax.net.ssl.trustStore=/home/antigravity/cacerts.jks -Djavax.net.ssl.trustStorePassword=changeit" mvn --offline test -Dtest=FxIT,PayoutBatchIT,FxAdvancedTest,PayoutHardeningTest,FxAdvancedIT`
- Run Python E2E verification:
  `python3 scripts/run_e2e.py --tier 1,2,3,4`
