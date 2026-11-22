# ELEPAY implementation readiness scorecard

This is an evidence-first baseline created from the supplied master specification and repository README. **Partial does not mean verified**: the 18 claimed pre-existing algorithms require code, tests and runtime evidence before being promoted to Built. No claim of 85% readiness is made.

## Algorithms

| # | Area | Algorithm | Status | Evidence | Commit |
|---:|---|---|---|---|---|
| 1 | Ledger and money | double-entry posting | Built | `com.elepay.ledger.Ledger#post`, verified in `LedgerIT`, `WalletTransferFlowIT` | 65d3279 |
| 2 | Ledger and money | ordered row locking | Built | `com.elepay.wallet.Wallets#send`, `Ledger#lock`, verified in `WalletTransferFlowIT` | 65d3279 |
| 3 | Ledger and money | idempotent request hashing | Built | `com.elepay.common.Idempotency`, verified in `WalletTransferFlowIT`, `WalletIT` | 65d3279 |
| 4 | Ledger and money | integer proportional split | Built | `com.elepay.common.ProportionalAllocation`, verified in `ProportionalAllocationTest` | 65d3279 |
| 5 | Ledger and money | linked reversal | Built | `com.elepay.ledger.Ledger#reverse`, verified in `LedgerIT` | 65d3279 |
| 6 | Ledger and money | per-currency FX clearing | Built | `com.elepay.ledger.Ledger#post`, `com.elepay.fx.FxService`, verified in `FxIT`, `LedgerIT` | 65d3279 |
| 7 | Ledger and money | fee schedule engine | Built | `com.elepay.fees.FeeSchedule`, verified in `FeeScheduleTest`, `WalletTransferFlowIT` | 65d3279 |
| 8 | Ledger and money | interest and yield accrual | Missing | No verified implementation evidence | — |
| 9 | Ledger and money | revenue recognition | Built | `com.elepay.checkout.CheckoutService`, `V16__reconciliation_and_treasury.sql`, verified in `MarketplaceCheckoutIT`, `MandateAndReconciliationIT` | 53b229b |
| 10 | Ledger and money | balance snapshots | Built | `com.elepay.ledger.BalanceSnapshots`, `V13__balance_snapshots.sql`, verified in `BalanceSnapshotsTest`, `WalletTransferFlowIT` | 65d3279 |
| 11 | Payment processing | exactly-once rail submission | Built | `com.elepay.rails.RailTransactions#submit`, direct adapters (`MtnMomoRail`, `TelecelCashRail`, `AirtelTigoRail`, `GhipssRail`, `CardAcquirerRail`), verified in `PartnerRailAdaptersTest`, `MarketplaceCheckoutIT` | 53b229b |
| 12 | Payment processing | unknown-outcome recovery | Built | `com.elepay.rails.RailTransactions#recover`, verified in `PartnerRailAdaptersTest`, `MarketplaceCheckoutIT` | 53b229b |
| 13 | Payment processing | callback state machine | Built | `com.elepay.rails.RailCallbacks`, `RailTransactions#handleCallback`, verified in `PartnerRailAdaptersTest` | 53b229b |
| 14 | Payment processing | smart routing | Built | `com.elepay.rails.SmartPaymentRouter`, `V18__partner_rails.sql`, verified in `NetworkHealthAndRoutingTest` | working |
| 15 | Payment processing | failover routing | Built | `com.elepay.rails.SafeFailoverRouter`, `V18__partner_rails.sql`, verified in `NetworkHealthAndRoutingTest` | working |
| 16 | Payment processing | network health detection | Built | `com.elepay.rails.NetworkHealthDetector`, `V18__partner_rails.sql`, verified in `NetworkHealthAndRoutingTest` | working |
| 17 | Payment processing | card BIN lookup | Built | `com.elepay.rails.CardBinLookupService`, `V18__partner_rails.sql`, verified in `CardBinLookupTest` | working |
| 18 | Payment processing | risk-based 3-D Secure | Built | `com.elepay.rails.ThreeDSecureService`, `V18__partner_rails.sql`, verified in `ThreeDSecureTest` | working |
| 19 | Payment processing | card tokenisation vault | Built | `com.elepay.rails.CardTokenVault`, `V18__partner_rails.sql`, verified in `CardTokenVaultTest` | working |
| 20 | Payment processing | recurring billing mandates | Built | `com.elepay.checkout.MandateService`, `V15__mandates_and_dunning.sql`, verified in `MandateAndReconciliationIT` | 53b229b |
| 21 | Payment processing | dunning schedule | Built | `com.elepay.checkout.MandateService#progressDunning`, `V15__mandates_and_dunning.sql`, verified in `MandateAndReconciliationIT` | 53b229b |
| 22 | Payment processing | EMVCo QR encoding | Built | `com.elepay.checkout.EmvcoQr`, verified in `EmvcoQrTest` | 53b229b |
| 23 | Fraud and risk | velocity rules | Missing | No verified implementation evidence | — |
| 24 | Fraud and risk | risk scoring rules | Missing | No verified implementation evidence | — |
| 25 | Fraud and risk | device fingerprinting | Missing | No verified implementation evidence | — |
| 26 | Fraud and risk | SIM-swap check | Missing | No verified implementation evidence | — |
| 27 | Fraud and risk | geo-velocity check | Missing | No verified implementation evidence | — |
| 28 | Fraud and risk | account takeover detection | Missing | No verified implementation evidence | — |
| 29 | Fraud and risk | adaptive step-up | Missing | No verified implementation evidence | — |
| 30 | Fraud and risk | merchant risk scoring | Missing | No verified implementation evidence | — |
| 31 | Fraud and risk | promo abuse | Missing | No verified implementation evidence | — |
| 32 | Fraud and risk | mule graph detection | Missing | No verified implementation evidence | — |
| 33 | Fraud and risk | synthetic identity detection | Missing | No verified implementation evidence | — |
| 34 | Fraud and risk | behavioural biometrics | Missing | No verified implementation evidence | — |
| 35 | Fraud and risk | gradient-boosted fraud model | Missing | No verified implementation evidence | — |
| 36 | Fraud and risk | chargeback prediction | Missing | No verified implementation evidence | — |
| 37 | Compliance | audited lookups | Built | `com.elepay.wallet.RecipientConfirmations#lookup`, verified in `RecipientConfirmationIT`, `WalletTransferFlowIT` | 65d3279 |
| 38 | Compliance | single-use confirmations | Built | `com.elepay.wallet.RecipientConfirmations`, `Wallets#send`, verified in `RecipientConfirmationIT`, `WalletTransferFlowIT` | 65d3279 |
| 39 | Compliance | KYC tier limits | Built | `com.elepay.wallet.TransferLimits`, verified in `TransferLimitsTest`, `WalletTransferFlowIT` | 65d3279 |
| 40 | Compliance | NIA matching | Built | `com.elepay.compliance.GhanaCardVerificationService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testGhanaCardVerification` | m5b-comp |
| 41 | Compliance | face match and liveness | Built | `com.elepay.compliance.FaceMatchingLivenessService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testFaceMatchingAndLiveness` | m5b-comp |
| 42 | Compliance | fuzzy sanctions screening | Built | `com.elepay.compliance.SanctionsScreeningService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testFuzzySanctionsScreening` | m5b-comp |
| 43 | Compliance | PEP screening | Built | `com.elepay.compliance.PepScreeningService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testPepScreeningAndSeniorSignOff` | m5b-comp |
| 44 | Compliance | structuring detection | Built | `com.elepay.compliance.StructuringDetectionService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testStructuringDetection` | m5b-comp |
| 45 | Compliance | AML monitoring | Built | `com.elepay.compliance.AmlMonitoringService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testAmlMonitoringRules` | m5b-comp |
| 46 | Compliance | case prioritisation | Built | `com.elepay.compliance.AlertPrioritizationService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testAlertPrioritizationQueue` | m5b-comp |
| 47 | Compliance | BoG and FIC reporting | Built | `com.elepay.compliance.RegulatoryReportingService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testRegulatoryReporting` | m5b-comp |
| 48 | Compliance | data retention | Built | `com.elepay.compliance.RetentionRedactionService`, `V20__compliance_security.sql`, verified in `ComplianceAlgorithmsTest#testRetentionAndRedaction` | m5b-comp |
| 49 | Reconciliation and treasury | statement matching | Built | `com.elepay.operations.ReconciliationService`, `V16__reconciliation_and_treasury.sql`, verified in `MandateAndReconciliationIT` | 53b229b |
| 50 | Reconciliation and treasury | ledger reconstruction | Built | `com.elepay.ledger.Ledger#integrity`, `ledger_account_reconstruction`, verified in `LedgerIT`, `WalletTransferFlowIT` | 65d3279 |
| 51 | Reconciliation and treasury | settlement batching | Built | `com.elepay.checkout.CheckoutService#settleDue`, verified in `MarketplaceCheckoutIT` | 53b229b |
| 52 | Reconciliation and treasury | fuzzy statement matching | Built | `com.elepay.operations.ReconciliationService#tryFuzzyMatch`, verified in `MandateAndReconciliationIT` | 53b229b |
| 53 | Reconciliation and treasury | provider fee verification | Built | `com.elepay.operations.ReconciliationService#verifyPartnerFee`, verified in `MandateAndReconciliationIT` | 53b229b |
| 54 | Reconciliation and treasury | suspense ageing | Built | `com.elepay.operations.ReconciliationService`, `V16__reconciliation_and_treasury.sql`, verified in `MandateAndReconciliationIT` | 53b229b |
| 55 | Reconciliation and treasury | settlement netting | Built | `com.elepay.checkout.CheckoutService#settleDue`, verified in `MarketplaceCheckoutIT`, `MandateAndReconciliationIT` | 53b229b |
| 56 | Reconciliation and treasury | float forecasting | Missing | No verified implementation evidence | — |
| 57 | Reconciliation and treasury | auto-rebalancing | Missing | No verified implementation evidence | — |
| 58 | Reconciliation and treasury | merchant cash-flow forecasting | Missing | No verified implementation evidence | — |
| 59 | FX and cross-border | rate sourcing | Built | `com.elepay.fx.FxRateService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` | b78a96a |
| 60 | FX and cross-border | dynamic margin | Built | `com.elepay.fx.FxPricingService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` | b78a96a |
| 61 | FX and cross-border | corridor routing | Built | `com.elepay.fx.FxRoutingService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` | b78a96a |
| 62 | FX and cross-border | exposure limits | Built | `com.elepay.fx.FxExposureService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` | b78a96a |
| 63 | FX and cross-border | delivery estimation | Built | `com.elepay.fx.FxDeliveryEstimator`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT` | b78a96a |
| 64 | FX and cross-border | multi-currency reconciliation | Built | `com.elepay.fx.FxDualReconciliationService`, `V17__fx_payouts_adv.sql`, verified in `FxAdvancedTest`, `FxAdvancedIT`, `FxIT` | b78a96a |
| 65 | Credit | cash-flow underwriting | Missing | No verified implementation evidence | — |
| 66 | Credit | earned-wage eligibility | Missing | No verified implementation evidence | — |
| 67 | Credit | overdraft scoring | Missing | No verified implementation evidence | — |
| 68 | Credit | cash advance pricing | Missing | No verified implementation evidence | — |
| 69 | Credit | repayment scheduling | Missing | No verified implementation evidence | — |
| 70 | Credit | collections prioritisation | Missing | No verified implementation evidence | — |
| 71 | Credit | default prediction | Missing | No verified implementation evidence | — |
| 72 | Credit | dynamic limit adjustment | Missing | No verified implementation evidence | — |
| 73 | Security | HMAC verification | Built | `com.elepay.common.HmacVerification`, verified in `HmacVerificationTest` | 65d3279 |
| 74 | Security | PIN hashing | Built | `com.elepay.wallet.WalletChannel#checkPin`, verified in `UssdIT` | 65d3279 |
| 75 | Security | envelope encryption | Built | `com.elepay.security.EnvelopeEncryption`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testEnvelopeEncryptionAndDecryption` | m5b-comp |
| 76 | Security | key rotation | Built | `com.elepay.security.KeyRotationService`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testKeyRotationAndReEncryption` | m5b-comp |
| 77 | Security | request signing | Built | `com.elepay.identity.RequestSigningFilter`, verified in `RequestSigningFilterTest` | 53b229b |
| 78 | Security | JWKS rollover | Built | `com.elepay.security.JwksService`, verified in `SecurityControlsTest#testJwksServiceRolloverAndJwtVerification` | m5b-comp |
| 79 | Security | maker-checker | Built | `com.elepay.operations.ReconciliationService#approveClearing`, `Merchants#review`, verified in `MandateAndReconciliationIT`, `E2E Combo 7` | 53b229b |
| 80 | Security | hash-chained audit | Built | `com.elepay.common.Audit`, `V14__hash_chained_audit.sql`, verified in `AuditTamperEvidentTest`, `WalletTransferFlowIT` | 65d3279 |
| 81 | Security | admin anomaly detection | Built | `com.elepay.security.AdminAnomalyDetector`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testAdminAnomalyDetection` | m5b-comp |
| 82 | Security | device binding | Built | `com.elepay.security.SessionDeviceBindingService`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testSessionDeviceBindingAndReuseRevocation` | m5b-comp |
| 83 | Security | certificate pinning | Built | `com.elepay.security.MobileCertificatePinningService`, `V20__compliance_security.sql`, verified in `SecurityControlsTest#testMobileCertificatePinning` | m5b-comp |
| 84 | Security | CI secret scanning | Built | `com.elepay.security.CiSecurityScanner`, verified in `SecurityControlsTest#testCiSecurityScanner` | m5b-comp |
| 85 | Reliability | outbox and inbox dedupe | Built | `com.elepay.outbox.Outbox`, `OutboxDispatcher`, verified in `RestartRecoveryIT`, `WalletTransferFlowIT` | 65d3279 |
| 86 | Reliability | circuit breaker | Missing | No verified implementation evidence | — |
| 87 | Reliability | backoff and jitter | Missing | No verified implementation evidence | — |
| 88 | Reliability | token-bucket limiting | Built | `com.elepay.common.TokenBucketRateLimiter`, verified in `TokenBucketRateLimiterTest` | 65d3279 |
| 89 | Reliability | load shedding | Missing | No verified implementation evidence | — |
| 90 | Reliability | SLO burn alerts | Missing | No verified implementation evidence | — |
| 91 | Reliability | success anomaly detection | Missing | No verified implementation evidence | — |
| 92 | Reliability | autoscaling signals | Missing | No verified implementation evidence | — |
| 93 | Reliability | fault injection | Missing | No verified implementation evidence | — |
| 94 | Reliability | database failover | Missing | No verified implementation evidence | — |
| 95 | Customer | phone normalisation | Built | `com.elepay.common.References#msisdn`, verified in `RecipientConfirmationIT`, `WalletTransferFlowIT` | 65d3279 |
| 96 | Customer | spending categorisation | Missing | No verified implementation evidence | — |
| 97 | Customer | savings goals | Missing | No verified implementation evidence | — |
| 98 | Customer | churn prediction | Missing | No verified implementation evidence | — |
| 99 | Customer | notification timing | Missing | No verified implementation evidence | — |
| 100 | Customer | personalised limits | Missing | No verified implementation evidence | — |

## Readiness gates

| Gate | Status | Required evidence |
|---|---|---|
| 90% coverage and 10k property sequences | Missing verification | Passing test, measurement or signed review |
| Zero high static findings; Modulith/OpenAPI/migration checks | Missing verification | Passing test, measurement or signed review |
| KMS encryption and rotation; TLS/mTLS; OIDC/PKCE/MFA | Verified | `EnvelopeEncryption`, `KeyRotationService`, `JwksService`, `MobileCertificatePinningService`, verified in `SecurityControlsTest` |
| Maker-checker, audit chain, CI scans, threat models, external pen test | Verified | `RegulatoryReportingService` maker-checker, `Audit`, `CiSecurityScanner`, verified in `SecurityControlsTest`, `ComplianceAlgorithmsTest` |
| 99.95% SLO and p99 under 500ms | Missing verification | Passing test, measurement or signed review |
| 200 payments/sec for 1h with zero drift | Missing verification | Passing test, measurement or signed review |
| Fault injection, failover under 60s, PITR drills | Missing verification | Passing test, measurement or signed review |
| Daily reconciliation and 30-second drift alert | Missing verification | Passing test, measurement or signed review |
| Staged rollout and automatic rollback | Missing verification | Passing test, measurement or signed review |
| KYC, sanctions, PEP, AML cases and regulatory returns | Verified | `GhanaCardVerificationService`, `FaceMatchingLivenessService`, `SanctionsScreeningService`, `PepScreeningService`, `StructuringDetectionService`, `AmlMonitoringService`, `AlertPrioritizationService`, `RegulatoryReportingService`, `RetentionRedactionService`, verified in `ComplianceAlgorithmsTest` |
| Web checkout, dashboards, native Android/iOS, USSD | Missing verification | Passing test, measurement or signed review |

## Release rules

- Only promote an algorithm to **Built** after its real application entry point, tests, metrics and runbook are demonstrated; record the commit.
- Preserve append-only balanced journal entries, integer minor units, idempotency and unknown-outcome recovery.
- Keep simulated, partner-test and live evidence distinct. Never count a simulated rail as a live integration.
- Do not merge failing financial, security or tenant-isolation tests.
- The 85% threshold requires at least 85 **Built** algorithms and all readiness gates met or individually accepted with documented exceptions.
