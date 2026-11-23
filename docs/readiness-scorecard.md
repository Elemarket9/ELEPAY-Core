# ELEPAY implementation readiness scorecard

> Cleanup verification notice (2026-11-23): The algorithm register is **implemented in code, not yet proven in production**. Historical "Built" or "Verified" rows are development evidence only. Neither 100/100 coverage nor live bank, MoMo, card network or GRA integration is production-proven. CI was failing on main before this cleanup; do not claim a green release without a new passing workflow.

This is an evidence-first baseline created from the supplied master specification and repository README. **Partial does not mean verified**: the 18 claimed pre-existing algorithms require code, tests and runtime evidence before being promoted to Built. No claim of 85% readiness is made.

## Algorithms

| # | Area | Algorithm | Status | Evidence | Commit |
|---:|---|---|---|---|---|
| 1 | Ledger and money | double-entry posting | Implemented in code; not production-proven | `com.elepay.ledger.Ledger#post`, verified in `LedgerIT`, `WalletTransferFlowIT` | 65d3279 |
| 2 | Ledger and money | ordered row locking | Implemented in code; not production-proven | `com.elepay.wallet.Wallets#send`, `Ledger#lock`, verified in `WalletTransferFlowIT` | 65d3279 |
| 3 | Ledger and money | idempotent request hashing | Implemented in code; not production-proven | `com.elepay.common.Idempotency`, verified in `WalletTransferFlowIT`, `WalletIT` | 65d3279 |
| 4 | Ledger and money | integer proportional split | Implemented in code; not production-proven | `com.elepay.common.ProportionalAllocation`, verified in `ProportionalAllocationTest` | 65d3279 |
| 5 | Ledger and money | linked reversal | Implemented in code; not production-proven | `com.elepay.ledger.Ledger#reverse`, verified in `LedgerIT` | 65d3279 |
| 6 | Ledger and money | per-currency FX clearing | Implemented in code; not production-proven | `com.elepay.ledger.Ledger#post`, `com.elepay.fx.FxService`, previously tested by removed out-of-scope remittance test, `LedgerIT` | 65d3279 |
| 7 | Ledger and money | fee schedule engine | Implemented in code; not production-proven | `com.elepay.fees.FeeSchedule`, verified in `FeeScheduleTest`, `WalletTransferFlowIT` | 65d3279 |
| 8 | Ledger and money | interest and yield accrual | Implemented in code; not production-proven | `com.elepay.credit.InterestAccrualService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` | m6-plat |
| 9 | Ledger and money | revenue recognition | Implemented in code; not production-proven | `com.elepay.checkout.CheckoutService` (merchant fees deferred until settlement), `V16__reconciliation_and_treasury.sql`, `V28__marketplace_commission_and_delivery_escrow.sql`, verified in `MarketplaceCommissionIT`, `MarketplaceCheckoutIT`, `MandateAndReconciliationIT` | V28 |
| 10 | Ledger and money | balance snapshots | Implemented in code; not production-proven | `com.elepay.ledger.BalanceSnapshots`, `V13__balance_snapshots.sql`, verified in `BalanceSnapshotsTest`, `WalletTransferFlowIT` | 65d3279 |
| 11 | Payment processing | exactly-once rail submission | Implemented in code; not production-proven | `com.elepay.rails.RailTransactions#submit`, direct adapters (`MtnMomoRail`, `TelecelCashRail`, `AirtelTigoRail`, `GhipssRail`, `CardAcquirerRail`), verified in `PartnerRailAdaptersTest`, `MarketplaceCheckoutIT` | 53b229b |
| 12 | Payment processing | unknown-outcome recovery | Implemented in code; not production-proven | `com.elepay.rails.RailTransactions#recover`, verified in `PartnerRailAdaptersTest`, `MarketplaceCheckoutIT` | 53b229b |
| 13 | Payment processing | callback state machine | Implemented in code; not production-proven | `com.elepay.rails.RailCallbacks`, `RailTransactions#handleCallback`, verified in `PartnerRailAdaptersTest` | 53b229b |
| 14 | Payment processing | smart routing | Implemented in code; not production-proven | `com.elepay.rails.SmartPaymentRouter`, `V18__partner_rails.sql`, verified in `NetworkHealthAndRoutingTest` | 87832d6 |
| 15 | Payment processing | failover routing | Implemented in code; not production-proven | `com.elepay.rails.SafeFailoverRouter`, `V18__partner_rails.sql`, verified in `NetworkHealthAndRoutingTest` | 87832d6 |
| 16 | Payment processing | network health detection | Implemented in code; not production-proven | `com.elepay.rails.NetworkHealthDetector`, `V18__partner_rails.sql`, verified in `NetworkHealthAndRoutingTest` | 87832d6 |
| 17 | Payment processing | card BIN lookup | Implemented in code; not production-proven | `com.elepay.rails.CardBinLookupService`, `V18__partner_rails.sql`, verified in `CardBinLookupTest` | 87832d6 |
| 18 | Payment processing | risk-based 3-D Secure | Implemented in code; not production-proven | `com.elepay.rails.ThreeDSecureService`, `V18__partner_rails.sql`, verified in `ThreeDSecureTest` | 87832d6 |
| 19 | Payment processing | card tokenisation vault | Implemented in code; not production-proven | `com.elepay.rails.CardTokenVault`, `V18__partner_rails.sql`, verified in `CardTokenVaultTest` | 87832d6 |
| 20 | Payment processing | recurring billing mandates | Implemented in code; not production-proven | `com.elepay.checkout.MandateService`, `V15__mandates_and_dunning.sql`, verified in `MandateAndReconciliationIT` | 53b229b |
| 21 | Payment processing | dunning schedule | Implemented in code; not production-proven | `com.elepay.checkout.MandateService#progressDunning`, `V15__mandates_and_dunning.sql`, verified in `MandateAndReconciliationIT` | 53b229b |
| 22 | Payment processing | EMVCo QR encoding | Implemented in code; not production-proven | `com.elepay.checkout.EmvcoQr`, verified in `EmvcoQrTest` | 53b229b |
| 23 | Fraud and risk | velocity rules | Implemented in code; not production-proven | `com.elepay.risk.VelocityService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` | 8f88813 |
| 24 | Fraud and risk | risk scoring rules | Implemented in code; not production-proven | `com.elepay.risk.RuleEngineService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` | 8f88813 |
| 25 | Fraud and risk | device fingerprinting | Implemented in code; not production-proven | `com.elepay.risk.DeviceRiskService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` | 8f88813 |
| 26 | Fraud and risk | SIM-swap check | Implemented in code; not production-proven | `com.elepay.risk.SimSwapService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` | 8f88813 |
| 27 | Fraud and risk | geo-velocity check | Implemented in code; not production-proven | `com.elepay.risk.GeoVelocityService`, `V19__fraud_risk_engine.sql`, verified in `RiskAlgorithmsUnitTest`, `RiskEngineIntegrationTest` | 8f88813 |
| 28 | Fraud and risk | account takeover detection | Implemented in code; not production-proven | `com.elepay.risk.AccountTakeoverService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` | 8f88813 |
| 29 | Fraud and risk | adaptive step-up | Implemented in code; not production-proven | `com.elepay.risk.StepUpAuthService`, `V19__fraud_risk_engine.sql`, verified in `RiskAlgorithmsUnitTest`, `RiskEngineIntegrationTest` | 8f88813 |
| 30 | Fraud and risk | merchant risk scoring | Implemented in code; not production-proven | `com.elepay.risk.MerchantRiskService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` | 8f88813 |
| 32 | Fraud and risk | mule graph detection | Implemented in code; not production-proven | `com.elepay.risk.MuleGraphService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` | 8f88813 |
| 33 | Fraud and risk | synthetic identity detection | Implemented in code; not production-proven | `com.elepay.risk.SyntheticIdentityService`, `V19__fraud_risk_engine.sql`, verified in `RiskEngineIntegrationTest` | 8f88813 |
| 34 | Fraud and risk | behavioural biometrics | Implemented in code; not production-proven | `com.elepay.risk.BehavioralBiometricsService`, `V19__fraud_risk_engine.sql`, verified in `RiskAlgorithmsUnitTest`, `RiskEngineIntegrationTest` | 8f88813 |
| 35 | Fraud and risk | gradient-boosted fraud model | Implemented in code; not production-proven | `com.elepay.risk.GradientBoostedFraudModel`, `V19__fraud_risk_engine.sql`, verified in `RiskAlgorithmsUnitTest`, `RiskEngineIntegrationTest` | 8f88813 |
| 36 | Fraud and risk | chargeback prediction | Implemented in code; not production-proven | `com.elepay.risk.ChargebackPredictionService`, `V19__fraud_risk_engine.sql`, verified in `RiskAlgorithmsUnitTest`, `RiskEngineIntegrationTest` | 8f88813 |
| 37 | Compliance | audited lookups | Implemented in code; not production-proven | `com.elepay.wallet.RecipientConfirmations#lookup`, verified in `RecipientConfirmationIT`, `WalletTransferFlowIT` | 65d3279 |
| 38 | Compliance | single-use confirmations | Implemented in code; not production-proven | `com.elepay.wallet.RecipientConfirmations`, `Wallets#send`, verified in `RecipientConfirmationIT`, `WalletTransferFlowIT` | 65d3279 |
| 39 | Compliance | KYC tier limits | Implemented in code; not production-proven | `com.elepay.wallet.TransferLimits`, verified in `TransferLimitsTest`, `WalletTransferFlowIT` | 65d3279 |
| 40 | Compliance | NIA matching | Implemented in code; not production-proven | `com.elepay.compliance.GhanaCardVerificationService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testGhanaCardVerification` | 87832d6 |
| 41 | Compliance | face match and liveness | Implemented in code; not production-proven | `com.elepay.compliance.FaceMatchingLivenessService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testFaceMatchingAndLiveness` | 87832d6 |
| 42 | Compliance | fuzzy sanctions screening | Implemented in code; not production-proven | `com.elepay.compliance.SanctionsScreeningService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testFuzzySanctionsScreening` | 87832d6 |
| 43 | Compliance | PEP screening | Implemented in code; not production-proven | `com.elepay.compliance.PepScreeningService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testPepScreeningAndSeniorSignOff` | 87832d6 |
| 44 | Compliance | structuring detection | Implemented in code; not production-proven | `com.elepay.compliance.StructuringDetectionService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testStructuringDetection` | 87832d6 |
| 45 | Compliance | AML monitoring | Implemented in code; not production-proven | `com.elepay.compliance.AmlMonitoringService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testAmlMonitoringRules` | 87832d6 |
| 46 | Compliance | case prioritisation | Implemented in code; not production-proven | `com.elepay.compliance.AlertPrioritizationService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testAlertPrioritizationQueue` | 87832d6 |
| 47 | Compliance | BoG and FIC reporting | Implemented in code; not production-proven | `com.elepay.compliance.RegulatoryReportingService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testRegulatoryReporting` | 87832d6 |
| 48 | Compliance | data retention | Implemented in code; not production-proven | `com.elepay.compliance.RetentionRedactionService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testRetentionAndRedaction` | 87832d6 |
| 49 | Reconciliation and treasury | statement matching | Implemented in code; not production-proven | `com.elepay.operations.ReconciliationService`, `V16__reconciliation_and_treasury.sql`, verified in `MandateAndReconciliationIT` | 53b229b |
| 50 | Reconciliation and treasury | ledger reconstruction | Implemented in code; not production-proven | `com.elepay.ledger.Ledger#integrity`, `ledger_account_reconstruction`, verified in `LedgerIT`, `WalletTransferFlowIT` | 65d3279 |
| 51 | Reconciliation and treasury | settlement batching | Implemented in code; not production-proven | `com.elepay.checkout.CheckoutService#settleDue`, verified in `MarketplaceCheckoutIT` | 53b229b |
| 52 | Reconciliation and treasury | fuzzy statement matching | Implemented in code; not production-proven | `com.elepay.operations.ReconciliationService#tryFuzzyMatch`, verified in `MandateAndReconciliationIT` | 53b229b |
| 53 | Reconciliation and treasury | provider fee verification | Implemented in code; not production-proven | `com.elepay.operations.ReconciliationService#verifyPartnerFee`, verified in `MandateAndReconciliationIT` | 53b229b |
| 54 | Reconciliation and treasury | suspense ageing | Implemented in code; not production-proven | `com.elepay.operations.ReconciliationService`, `V16__reconciliation_and_treasury.sql`, verified in `MandateAndReconciliationIT` | 53b229b |
| 55 | Reconciliation and treasury | settlement netting | Implemented in code; not production-proven | `com.elepay.checkout.CheckoutService#settleDue`, verified in `MarketplaceCheckoutIT`, `MandateAndReconciliationIT` | 53b229b |
| 56 | Reconciliation and treasury | float forecasting | Implemented in code; not production-proven | `com.elepay.credit.FloatForecastingService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` | m6-plat |
| 57 | Reconciliation and treasury | auto-rebalancing | Implemented in code; not production-proven | `com.elepay.credit.FloatRebalancingService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` | m6-plat |
| 58 | Reconciliation and treasury | merchant cash-flow forecasting | Implemented in code; not production-proven | `com.elepay.credit.MerchantCashFlowForecastService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` | m6-plat |
| 59 | FX and cross-border | rate sourcing | Implemented in code; not production-proven | `com.elepay.fx.FxRateService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` | c8bcdc0 |
| 60 | FX and cross-border | dynamic margin | Implemented in code; not production-proven | `com.elepay.fx.FxPricingService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` | c8bcdc0 |
| 61 | FX and cross-border | corridor routing | Implemented in code; not production-proven | `com.elepay.fx.FxRoutingService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` | c8bcdc0 |
| 62 | FX and cross-border | exposure limits | Implemented in code; not production-proven | `com.elepay.fx.FxExposureService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` | c8bcdc0 |
| 63 | FX and cross-border | delivery estimation | Implemented in code; not production-proven | `com.elepay.fx.FxDeliveryEstimator`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` | c8bcdc0 |
| 64 | FX and cross-border | multi-currency reconciliation | Implemented in code; not production-proven | `com.elepay.fx.FxDualReconciliationService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` | c8bcdc0 |
| 65 | Credit | cash-flow underwriting | Implemented in code; not production-proven | `com.elepay.credit.CashFlowUnderwritingService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` | m6-plat |
| 66 | Credit | earned-wage eligibility | Implemented in code; not production-proven | `com.elepay.credit.EwaEligibilityService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` | m6-plat |
| 67 | Credit | overdraft scoring | Implemented in code; not production-proven | `com.elepay.credit.OverdraftScoringService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` | m6-plat |
| 68 | Credit | cash advance pricing | Implemented in code; not production-proven | `com.elepay.credit.MerchantCashAdvancePricingService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` | m6-plat |
| 69 | Credit | repayment scheduling | Implemented in code; not production-proven | `com.elepay.credit.RepaymentSweepsService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` | m6-plat |
| 70 | Credit | collections prioritisation | Implemented in code; not production-proven | `com.elepay.credit.CollectionsPrioritizationService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` | m6-plat |
| 71 | Credit | default prediction | Implemented in code; not production-proven | `com.elepay.credit.DefaultPredictionService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` | m6-plat |
| 72 | Credit | dynamic limit adjustment | Implemented in code; not production-proven | `com.elepay.credit.DynamicCreditLimitService`, `V21__credit_growth_platform.sql`, verified in `CreditPlatformTest` | m6-plat |
| 73 | Security | HMAC verification | Implemented in code; not production-proven | `com.elepay.common.HmacVerification`, verified in `HmacVerificationTest` | 65d3279 |
| 74 | Security | PIN hashing | Implemented in code; not production-proven | `com.elepay.wallet.WalletChannel#checkPin`, verified in `UssdIT` | 65d3279 |
| 75 | Security | envelope encryption | Implemented in code; not production-proven | `com.elepay.security.EnvelopeEncryption`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testEnvelopeEncryptionAndDecryption` | 87832d6 |
| 76 | Security | key rotation | Implemented in code; not production-proven | `com.elepay.security.KeyRotationService`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testKeyRotationAndReEncryption` | 87832d6 |
| 77 | Security | request signing | Implemented in code; not production-proven | `com.elepay.identity.RequestSigningFilter`, verified in `RequestSigningFilterTest` | 53b229b |
| 78 | Security | JWKS rollover | Implemented in code; not production-proven | `com.elepay.security.JwksService`, verified in `SecurityControlsTest#testJwksServiceRolloverAndJwtVerification` | 87832d6 |
| 79 | Security | maker-checker | Implemented in code; not production-proven | `com.elepay.operations.ReconciliationService#approveClearing`, `Merchants#review`, verified in `MandateAndReconciliationIT`, `E2E Combo 7` | 53b229b |
| 80 | Security | hash-chained audit | Implemented in code; not production-proven | `com.elepay.common.Audit`, `V14__hash_chained_audit.sql`, verified in `AuditTamperEvidentTest`, `WalletTransferFlowIT` | 65d3279 |
| 81 | Security | admin anomaly detection | Implemented in code; not production-proven | `com.elepay.security.AdminAnomalyDetector`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testAdminAnomalyDetection` | 87832d6 |
| 82 | Security | device binding | Implemented in code; not production-proven | `com.elepay.security.SessionDeviceBindingService`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testSessionDeviceBindingAndReuseRevocation` | 87832d6 |
| 83 | Security | certificate pinning | Implemented in code; not production-proven | `com.elepay.security.MobileCertificatePinningService`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testMobileCertificatePinning` | 87832d6 |
| 84 | Security | CI secret scanning | Implemented in code; not production-proven | `com.elepay.security.CiSecurityScanner`, verified in `SecurityControlsTest#testCiSecurityScanner` | 87832d6 |
| 85 | Reliability | outbox and inbox dedupe | Implemented in code; not production-proven | `com.elepay.outbox.Outbox`, `OutboxDispatcher`, verified in `RestartRecoveryIT`, `WalletTransferFlowIT` | 65d3279 |
| 86 | Reliability | circuit breaker | Implemented in code; not production-proven | `com.elepay.reliability.PartnerCircuitBreaker`, `PartnerCircuitBreakerRegistry`, `V21__credit_growth_platform.sql`, verified in `ReliabilityPlatformTest` | m6-plat |
| 87 | Reliability | backoff and jitter | Implemented in code; not production-proven | `com.elepay.reliability.BackoffAndJitterRetry`, verified in `ReliabilityPlatformTest` | m6-plat |
| 88 | Reliability | token-bucket limiting | Implemented in code; not production-proven | `com.elepay.common.TokenBucketRateLimiter`, verified in `TokenBucketRateLimiterTest` | 65d3279 |
| 89 | Reliability | load shedding | Implemented in code; not production-proven | `com.elepay.reliability.LoadShedder`, `V21__credit_growth_platform.sql`, verified in `ReliabilityPlatformTest` | m6-plat |
| 90 | Reliability | SLO burn alerts | Implemented in code; not production-proven | `com.elepay.reliability.SloBurnRateAlertService`, `V21__credit_growth_platform.sql`, verified in `ReliabilityPlatformTest` | m6-plat |
| 91 | Reliability | success anomaly detection | Implemented in code; not production-proven | `com.elepay.reliability.SuccessRateAnomalyDetector`, `V21__credit_growth_platform.sql`, verified in `ReliabilityPlatformTest` | m6-plat |
| 92 | Reliability | autoscaling signals | Implemented in code; not production-proven | `com.elepay.reliability.ScalingSignalService`, `V21__credit_growth_platform.sql`, verified in `ReliabilityPlatformTest` | m6-plat |
| 93 | Reliability | fault injection | Implemented in code; not production-proven | `com.elepay.reliability.FaultInjectionHarness`, `V21__credit_growth_platform.sql`, verified in `ReliabilityPlatformTest` | m6-plat |
| 94 | Reliability | database failover | Implemented in code; not production-proven | `com.elepay.reliability.DatabaseFailoverRecoveryService`, verified in `ReliabilityPlatformTest` | m6-plat |
| 95 | Customer | phone normalisation | Implemented in code; not production-proven | `com.elepay.common.References#msisdn`, verified in `RecipientConfirmationIT`, `WalletTransferFlowIT` | 65d3279 |
| 96 | Customer | spending categorisation | Implemented in code; not production-proven | `com.elepay.growth.SpendingCategorizationService`, `V21__credit_growth_platform.sql`, verified in `GrowthPlatformTest` | m6-plat |
| 97 | Customer | savings goals | Implemented in code; not production-proven | `com.elepay.growth.SavingsGoalsService`, `V21__credit_growth_platform.sql`, verified in `GrowthPlatformTest` | m6-plat |
| 98 | Customer | churn prediction | Implemented in code; not production-proven | `com.elepay.growth.ChurnPredictionService`, `V21__credit_growth_platform.sql`, verified in `GrowthPlatformTest` | m6-plat |
| 99 | Customer | notification timing | Implemented in code; not production-proven | `com.elepay.growth.NotificationTimingService`, `V21__credit_growth_platform.sql`, verified in `GrowthPlatformTest` | m6-plat |
| 100 | Customer | personalised limits | Implemented in code; not production-proven | `com.elepay.growth.PersonalizedLimitService`, `V21__credit_growth_platform.sql`, verified in `GrowthPlatformTest` | m6-plat |

## Readiness gates

| Gate | Status | Required evidence |
|---|---|---|
| 90% coverage and 10k property sequences | Missing verification | Passing test, measurement or signed review |
| Zero high static findings; Modulith/OpenAPI/migration checks | Missing verification | Passing test, measurement or signed review |
| KMS encryption and rotation; TLS/mTLS; OIDC/PKCE/MFA | Historical development evidence; not production-proven | `EnvelopeEncryption`, `KeyRotationService`, `JwksService`, `MobileCertificatePinningService`, verified in `SecurityControlsTest` |
| Maker-checker, audit chain, CI scans, threat models, external pen test | Historical development evidence; not production-proven | `RegulatoryReportingService` maker-checker, `Audit`, `CiSecurityScanner`, verified in `SecurityControlsTest`, `ComplianceAlgorithmsTest` |
| 99.95% SLO and p99 under 500ms | Missing verification | Passing test, measurement or signed review |
| 200 payments/sec for 1h with zero drift | Missing verification | Passing test, measurement or signed review |
| Fault injection, failover under 60s, PITR drills | Missing verification | Passing test, measurement or signed review |
| Daily reconciliation and 30-second drift alert | Missing verification | Passing test, measurement or signed review |
| Staged rollout and automatic rollback | Missing verification | Passing test, measurement or signed review |
| KYC, sanctions, PEP, AML cases and regulatory returns | Historical development evidence; not production-proven | `GhanaCardVerificationService`, `FaceMatchingLivenessService`, `SanctionsScreeningService`, `PepScreeningService`, `StructuringDetectionService`, `AmlMonitoringService`, `AlertPrioritizationService`, `RegulatoryReportingService`, `RetentionRedactionService`, verified in `ComplianceAlgorithmsTest` |
| Web checkout, dashboards, native Android/iOS, USSD | Missing verification | Passing test, measurement or signed review |

## Release rules

- Only promote an algorithm to **Built** after its real application entry point, tests, metrics and runbook are demonstrated; record the commit.
- Preserve append-only balanced journal entries, integer minor units, idempotency and unknown-outcome recovery.
- Keep simulated, partner-test and live evidence distinct. Never count a simulated rail as a live integration.
- Do not merge failing financial, security or tenant-isolation tests.
- The 85% threshold requires at least 85 **Built** algorithms and all readiness gates met or individually accepted with documented exceptions.
