# Stage 1 financial foundation inventory

Baseline main revision `f14bb72959cf7db2038074cb11915f53c075d4e6`. The recursive Git tree contains 776 tracked files. The repository remains one Spring Boot application.

## Financial workflow review

| Area | Status and evidence |
|---|---|
| Ledger and customer wallets | Partially verified. PostgreSQL ledger and wallet integration suites exercise posting, reconstruction, ownership, confirmation expiry and concurrent spend. Crash recovery and PITR rehearsal remain open. |
| Multi-merchant checkout | Partially verified. The marketplace tests execute payment, allocated refunds, disputes and settlement through HTTP. Settlement now opens the beneficiary available account for older records whose pending bucket exists alone. |
| Enterprise externally collected payments | Implemented but defective. EnterpriseCheckoutService indexes results in JVM maps, including an order-reference index without enterprise scope. Durable external-reference uniqueness, verified collection evidence and restart recovery are prerequisites for real ingestion. |
| External wallets | White-label and settlement-only map-backed implementations are test-only. ExternalWalletEngine requires a registered connector. No live provider evidence was supplied. |
| Merchant POS | Financial tender acceptance retained. Inventory, purchasing and retail marketing have been removed from the production source set. Historical Flyway tables remain. |
| Ghana compliance | Current levy assessment produces no posting. Local batch preparation carries no GRA acknowledgment. Historical refunds use a linked ledger reversal. The complete KYC, safeguarding, AML and limits policy mapping remains open. |
| Reporting | RegulatoryReportingService stores generated reports in process memory. Durable preparation, approval evidence and authenticated external submission remain open. |
| Build and architecture | Java 25, Spring Boot 4.2.0, Spring Modulith 2.2.0. The verification suite includes module verification and clean and V3 upgrade migration tests. |

Ghana repealed the E-Levy in April 2025. See [Act 1127](https://repository.parliament.gh/items/e9133f9a-1df6-43c8-852a-967b61561cff) and the [GRA repeal notice](https://gra.gov.gh/news/portfolio/electronic-transfer-levy-repealed-in-april-2025/). Existing historical batch statuses require independent corroboration before external reporting.

## Production code baseline

Nonblank noncomment Java under src/main, SQL migrations, and TypeScript, TSX and CSS under web/src. Counts exclude tests, generated files, configuration and documentation. This source-set measure includes partially implemented features.

| Module | Main | Candidate |
|---|---:|---:|
| agents | 823 | 307 |
| application | 11 | 11 |
| bills | 1146 | 531 |
| cards | 1671 | 805 |
| checkout | 2117 | 2117 |
| common | 1511 | 1511 |
| compliance | 2420 | 2024 |
| credit | 989 | 989 |
| database migrations | 3954 | 3954 |
| developer | 796 | 796 |
| enterprise | 6934 | 6345 |
| fees | 46 | 46 |
| fx | 1428 | 1428 |
| growth | 562 | 562 |
| identity | 1250 | 1250 |
| ledger | 476 | 492 |
| lending | 1411 | 1411 |
| merchant | 523 | 523 |
| merchanttools | 5464 | 3649 |
| operations | 629 | 629 |
| outbox | 389 | 389 |
| payout | 739 | 739 |
| rails | 4139 | 4139 |
| reconciliation | 715 | 715 |
| reliability | 869 | 869 |
| risk | 2962 | 2962 |
| security | 1126 | 1126 |
| settlement | 994 | 994 |
| ussd | 216 | 216 |
| wallet | 1080 | 1080 |
| web | 3464 | 3464 |
| **Total** | **50854** | **46073** |

## Original algorithm traceability

The specification is in .agents/teamwork/ORIGINAL_REQUEST.md. Each row reproduces the implementation and test pointers from the historical scorecard. These pointers need independent checking where methods moved. Production qualification remains open for every row, including workflows with passing local tests.

| ID | Specification item | Repository evidence to verify |
|---:|---|---|
| 001 | Double-entry posting | `com.elepay.ledger.Ledger#post`, verified in `LedgerIT`, `WalletTransferFlowIT` |
| 002 | Ordered row locking | `com.elepay.wallet.Wallets#send`, `Ledger#lock`, verified in `WalletTransferFlowIT` |
| 003 | Idempotent request hashing | `com.elepay.common.Idempotency`, verified in `WalletTransferFlowIT`, `WalletIT` |
| 004 | Integer proportional allocation | `com.elepay.common.ProportionalAllocation`, verified in `ProportionalAllocationTest` |
| 005 | Linked reversals | `com.elepay.ledger.Ledger#reverse`, verified in `LedgerIT` |
| 006 | Per-currency FX clearing | `com.elepay.ledger.Ledger#post`, `com.elepay.fx.FxService`, verified in `FxIT`, `LedgerIT` |
| 007 | Fee schedule engine | `com.elepay.fees.FeeSchedule`, verified in `FeeScheduleTest`, `WalletTransferFlowIT` |
| 008 | Interest and yield accrual | `com.elepay.credit.InterestAccrualService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` |
| 009 | Revenue recognition | `com.elepay.checkout.CheckoutService` (merchant fees deferred until settlement), `V16__reconciliation_and_treasury.sql`, `V28__marketplace_commission_and_delivery_escrow.sql`, verified in `MarketplaceCommissionIT`, `MarketplaceCheckoutIT`, `MandateAndReconciliationIT` |
| 010 | Balance snapshots | `com.elepay.ledger.BalanceSnapshots`, `V13__balance_snapshots.sql`, verified in `BalanceSnapshotsTest`, `WalletTransferFlowIT` |
| 011 | Duplicate-safe rail submission | `com.elepay.rails.RailTransactions#submit`, direct adapters (`MtnMomoRail`, `TelecelCashRail`, `AirtelTigoRail`, `GhipssRail`, `CardAcquirerRail`), verified in `PartnerRailAdaptersTest`, `MarketplaceCheckoutIT` |
| 012 | Unknown-outcome recovery | `com.elepay.rails.RailTransactions#recover`, verified in `PartnerRailAdaptersTest`, `MarketplaceCheckoutIT` |
| 013 | Callback state machine | `com.elepay.rails.RailCallbacks`, `RailTransactions#handleCallback`, verified in `PartnerRailAdaptersTest` |
| 014 | Smart payment routing | `com.elepay.rails.SmartPaymentRouter`, `V18__partner_rails.sql`, verified in `NetworkHealthAndRoutingTest` |
| 015 | Safe failover routing | `com.elepay.rails.SafeFailoverRouter`, `V18__partner_rails.sql`, verified in `NetworkHealthAndRoutingTest` |
| 016 | Network health detection | `com.elepay.rails.NetworkHealthDetector`, `V18__partner_rails.sql`, verified in `NetworkHealthAndRoutingTest` |
| 017 | Card BIN lookup | `com.elepay.rails.CardBinLookupService`, `V18__partner_rails.sql`, verified in `CardBinLookupTest` |
| 018 | Risk-based 3-D Secure | `com.elepay.rails.ThreeDSecureService`, `V18__partner_rails.sql`, verified in `ThreeDSecureTest` |
| 019 | Card tokenization boundary | `com.elepay.rails.CardTokenVault`, `V18__partner_rails.sql`, verified in `CardTokenVaultTest` |
| 020 | Recurring billing mandates | `com.elepay.checkout.MandateService`, `V15__mandates_and_dunning.sql`, verified in `MandateAndReconciliationIT` |
| 021 | Dunning schedules | `com.elepay.checkout.MandateService#progressDunning`, `V15__mandates_and_dunning.sql`, verified in `MandateAndReconciliationIT` |
| 022 | EMVCo QR encoding | `com.elepay.checkout.EmvcoQr`, verified in `EmvcoQrTest` |
| 023 | Velocity rules | `com.elepay.risk.VelocityService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` |
| 024 | Rules engine and risk score | `com.elepay.risk.RuleEngineService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` |
| 025 | Device risk signals | `com.elepay.risk.DeviceRiskService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` |
| 026 | SIM-swap checks | `com.elepay.risk.SimSwapService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` |
| 027 | Geo-velocity checks | `com.elepay.risk.GeoVelocityService`, `V19__fraud_risk_engine.sql`, verified in `RiskAlgorithmsUnitTest`, `RiskEngineIntegrationTest` |
| 028 | Account takeover detection | `com.elepay.risk.AccountTakeoverService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` |
| 029 | Adaptive step-up authentication | `com.elepay.risk.StepUpAuthService`, `V19__fraud_risk_engine.sql`, verified in `RiskAlgorithmsUnitTest`, `RiskEngineIntegrationTest` |
| 030 | Merchant risk scoring | `com.elepay.risk.MerchantRiskService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` |
| 031 | Promotion and referral abuse detection | `com.elepay.risk.PromotionAbuseService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` |
| 032 | Mule-account graph detection | `com.elepay.risk.MuleGraphService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` |
| 033 | Synthetic identity detection | `com.elepay.risk.SyntheticIdentityService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` |
| 034 | Behavioral biometrics | `com.elepay.risk.BehavioralBiometricsService`, `V19__fraud_risk_engine.sql`, verified in `RiskAlgorithmsUnitTest`, `RiskEngineIntegrationTest` |
| 035 | Gradient-boosted fraud model | `com.elepay.risk.GradientBoostedFraudModel`, `V19__fraud_risk_engine.sql`, verified in `RiskAlgorithmsUnitTest`, `RiskEngineIntegrationTest` |
| 036 | Chargeback prediction | `com.elepay.risk.ChargebackPredictionService`, `V19__fraud_risk_engine.sql`, verified in `RiskAlgorithmsUnitTest`, `RiskEngineIntegrationTest` |
| 037 | Rate-limited audited lookups | `com.elepay.wallet.RecipientConfirmations#lookup`, verified in `RecipientConfirmationIT`, `WalletTransferFlowIT` |
| 038 | Bound single-use confirmations | `com.elepay.wallet.RecipientConfirmations`, `Wallets#send`, verified in `RecipientConfirmationIT`, `WalletTransferFlowIT` |
| 039 | KYC tier limits | `com.elepay.wallet.TransferLimits`, verified in `TransferLimitsTest`, `WalletTransferFlowIT` |
| 040 | Ghana Card matching | `com.elepay.compliance.GhanaCardVerificationService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testGhanaCardVerification` |
| 041 | Face matching and liveness | `com.elepay.compliance.FaceMatchingLivenessService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testFaceMatchingAndLiveness` |
| 042 | Fuzzy sanctions screening | `com.elepay.compliance.SanctionsScreeningService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testFuzzySanctionsScreening` |
| 043 | PEP screening | `com.elepay.compliance.PepScreeningService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testPepScreeningAndSeniorSignOff` |
| 044 | Structuring detection | `com.elepay.compliance.StructuringDetectionService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testStructuringDetection` |
| 045 | AML transaction monitoring | `com.elepay.compliance.AmlMonitoringService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testAmlMonitoringRules` |
| 046 | Alert prioritization | `com.elepay.compliance.AlertPrioritizationService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testAlertPrioritizationQueue` |
| 047 | Regulatory report generation | `com.elepay.compliance.RegulatoryReportingService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testRegulatoryReporting` |
| 048 | Retention and redaction | `com.elepay.compliance.RetentionRedactionService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testRetentionAndRedaction` |
| 049 | Exact statement matching | `com.elepay.operations.ReconciliationService`, `V16__reconciliation_and_treasury.sql`, verified in `MandateAndReconciliationIT` |
| 050 | Ledger reconstruction checks | `com.elepay.ledger.Ledger#integrity`, `ledger_account_reconstruction`, verified in `LedgerIT`, `WalletTransferFlowIT` |
| 051 | Settlement batching | `com.elepay.checkout.CheckoutService#settleDue`, verified in `MarketplaceCheckoutIT` |
| 052 | Fuzzy statement matching | `com.elepay.operations.ReconciliationService#tryFuzzyMatch`, verified in `MandateAndReconciliationIT` |
| 053 | Partner fee verification | `com.elepay.operations.ReconciliationService#verifyPartnerFee`, verified in `MandateAndReconciliationIT` |
| 054 | Suspense aging and clearing | `com.elepay.operations.ReconciliationService`, `V16__reconciliation_and_treasury.sql`, verified in `MandateAndReconciliationIT` |
| 055 | Settlement netting | `com.elepay.checkout.CheckoutService#settleDue`, verified in `MarketplaceCheckoutIT`, `MandateAndReconciliationIT` |
| 056 | Float forecasting | `com.elepay.credit.FloatForecastingService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` |
| 057 | Float rebalancing | `com.elepay.credit.FloatRebalancingService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` |
| 058 | Merchant cash-flow forecasting | `com.elepay.credit.MerchantCashFlowForecastService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` |
| 059 | Rate sourcing and blending | `com.elepay.fx.FxRateService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` |
| 060 | Dynamic FX margin pricing | `com.elepay.fx.FxPricingService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` |
| 061 | Corridor routing | `com.elepay.fx.FxRoutingService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` |
| 062 | Exposure limits and hedging triggers | `com.elepay.fx.FxExposureService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` |
| 063 | Delivery-time estimation | `com.elepay.fx.FxDeliveryEstimator`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` |
| 064 | Multi-currency partner reconciliation | `com.elepay.fx.FxDualReconciliationService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT`, `FxIT` |
| 065 | Cash-flow underwriting | `com.elepay.credit.CashFlowUnderwritingService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` |
| 066 | Earned-wage access eligibility | `com.elepay.credit.EwaEligibilityService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` |
| 067 | Overdraft eligibility scoring | `com.elepay.credit.OverdraftScoringService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` |
| 068 | Merchant cash-advance pricing | `com.elepay.credit.MerchantCashAdvancePricingService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` |
| 069 | Repayment scheduling and sweeps | `com.elepay.credit.RepaymentSweepsService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` |
| 070 | Collections prioritization | `com.elepay.credit.CollectionsPrioritizationService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` |
| 071 | Default prediction | `com.elepay.credit.DefaultPredictionService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` |
| 072 | Dynamic credit limits | `com.elepay.credit.DynamicCreditLimitService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` |
| 073 | HMAC verification | `com.elepay.common.HmacVerification`, verified in `HmacVerificationTest` |
| 074 | PIN hashing and lockout | `com.elepay.wallet.WalletChannel#checkPin`, verified in `UssdIT` |
| 075 | Field-level envelope encryption | `com.elepay.security.EnvelopeEncryption`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testEnvelopeEncryptionAndDecryption` |
| 076 | Key rotation | `com.elepay.security.KeyRotationService`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testKeyRotationAndReEncryption` |
| 077 | Request signing and replay protection | `com.elepay.identity.RequestSigningFilter`, verified in `RequestSigningFilterTest` |
| 078 | JWKS caching and rollover | `com.elepay.security.JwksService`, verified in `SecurityControlsTest#testJwksServiceRolloverAndJwtVerification` |
| 079 | Maker-checker approvals | `com.elepay.operations.ReconciliationService#approveClearing`, `Merchants#review`, verified in `MandateAndReconciliationIT`, `E2E Combo 7` |
| 080 | Tamper-evident audit records | `com.elepay.common.Audit`, `V14__hash_chained_audit.sql`, verified in `AuditTamperEvidentTest`, `WalletTransferFlowIT` |
| 081 | Administrative anomaly detection | `com.elepay.security.AdminAnomalyDetector`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testAdminAnomalyDetection` |
| 082 | Session and device binding | `com.elepay.security.SessionDeviceBindingService`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testSessionDeviceBindingAndReuseRevocation` |
| 083 | Mobile certificate pinning | `com.elepay.security.MobileCertificatePinningService`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testMobileCertificatePinning` |
| 084 | CI security scanning | `com.elepay.security.CiSecurityScanner`, verified in `SecurityControlsTest#testCiSecurityScanner` |
| 085 | Transactional outbox and inbox deduplication | `com.elepay.outbox.Outbox`, `OutboxDispatcher`, verified in `RestartRecoveryIT`, `WalletTransferFlowIT` |
| 086 | Partner circuit breakers | `com.elepay.reliability.PartnerCircuitBreaker`, `PartnerCircuitBreakerRegistry`, `V21__credit_growth_platform.sql`, verified in `ReliabilityPlatformTest` |
| 087 | Backoff and jitter | `com.elepay.reliability.BackoffAndJitterRetry`, verified in `ReliabilityPlatformTest` |
| 088 | Token-bucket rate limiting | `com.elepay.common.TokenBucketRateLimiter`, verified in `TokenBucketRateLimiterTest` |
| 089 | Load shedding and backpressure | `com.elepay.reliability.LoadShedder`, `V21__credit_growth_platform.sql`, verified in `ReliabilityPlatformTest` |
| 090 | SLO burn-rate alerting | `com.elepay.reliability.SloBurnRateAlertService`, `V21__credit_growth_platform.sql`, verified in `ReliabilityPlatformTest` |
| 091 | Success-rate anomaly detection | `com.elepay.reliability.SuccessRateAnomalyDetector`, `V21__credit_growth_platform.sql`, verified in `ReliabilityPlatformTest` |
| 092 | Scaling signals | `com.elepay.reliability.ScalingSignalService`, `V21__credit_growth_platform.sql`, verified in `ReliabilityPlatformTest` |
| 093 | Fault injection | `com.elepay.reliability.FaultInjectionHarness`, `V21__credit_growth_platform.sql`, verified in `ReliabilityPlatformTest` |
| 094 | Database failover recovery | `com.elepay.reliability.DatabaseFailoverRecoveryService`, verified in `ReliabilityPlatformTest` |
| 095 | Exact phone normalization | `com.elepay.common.References#msisdn`, verified in `RecipientConfirmationIT`, `WalletTransferFlowIT` |
| 096 | Spending categorization | `com.elepay.growth.SpendingCategorizationService`, `V21__credit_growth_platform.sql`, verified in `GrowthPlatformTest` |
| 097 | Savings goals and round-ups | `com.elepay.growth.SavingsGoalsService`, `V21__credit_growth_platform.sql`, verified in `GrowthPlatformTest` |
| 098 | Churn prediction | `com.elepay.growth.ChurnPredictionService`, `V21__credit_growth_platform.sql`, verified in `GrowthPlatformTest` |
| 099 | Notification timing | `com.elepay.growth.NotificationTimingService`, `V21__credit_growth_platform.sql`, verified in `GrowthPlatformTest` |
| 100 | Personalized limits | `com.elepay.growth.PersonalizedLimitService`, `V21__credit_growth_platform.sql`, verified in `GrowthPlatformTest` |

## Remaining engineering sequence

1. Persist enterprise payment and refund state with tenant-scoped unique payment references, atomic ledger linkage and crash-recovery tests. Verify financial evidence before crediting externally collected money.
2. Exercise per-currency fee posting, snapshot recovery, durable outbox retries, partner callback ordering and backup restoration under injected failures. Resolve the outstanding evidence for each original algorithm.
3. Validate Ghana policy against the licensed product and safeguarding arrangement. Record policy version and effective date. Obtain partner authorization, official credentials, webhook verification material, reconciliation files and network access.
4. Verify collection, capture, unknown outcome, refund, domestic payout and settlement against authorized partner infrastructure. Advance the 800,000-line engineering target only as working features require additional production code.
