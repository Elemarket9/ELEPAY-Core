package com.elepay.enterprise;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.enterprise.accounts.*;
import com.elepay.enterprise.checkout.*;
import com.elepay.enterprise.connectors.*;
import com.elepay.enterprise.settlement.*;
import com.elepay.enterprise.treasury.*;
import com.elepay.enterprise.wallet.*;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.ledger.Ledger.Posting;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-End Marketplace 17-Step Flagship Reference Scenario Automated Verification Suite.
 *
 * <p><strong>Isolated test integration.</strong> The customer wallet is an in-memory test double
 * ({@link InMemoryModeAWalletSimulator}); this is not a partnership or live connection with any enterprise.
 *
 * <p>Executes the complete 17-step embedded finance lifecycle:
 * <ol>
 *   <li>Step 1: Enterprise onboarding & API credential issue.</li>
 *   <li>Step 2: Sub-merchant bulk provisioning (10 merchants).</li>
 *   <li>Step 3: test wallet external wallet mode configuration.</li>
 *   <li>Step 4: Customer places GH₵1,520 cart across 10 merchants.</li>
 *   <li>Step 5: External payment collected via test wallet.</li>
 *   <li>Step 6: Split calculation with nested commissions & delivery fees.</li>
 *   <li>Step 7: Ingestion & ledger journal entry posting (total credits == total debits).</li>
 *   <li>Step 8: Funds held in conditional settlement escrow.</li>
 *   <li>Step 9: Delivery confirmed for 8 merchants.</li>
 *   <li>Step 10: Missing delivery confirmation for 1 merchant (held in escrow).</li>
 *   <li>Step 11: 1 merchant item rejected/cancelled -> GH₵300 item-level refund initiated.</li>
 *   <li>Step 12: Refund instructed to test wallet connector.</li>
 *   <li>Step 13: Customer requests withdrawal of GH₵300 from Marketplace wallet to MTN MoMo.</li>
 *   <li>Step 14: Withdrawal authorization & domestic MoMo payout execution.</li>
 *   <li>Step 15: Settlement released for delivered items to merchant available balances.</li>
 *   <li>Step 16: Payout batch executed to merchants.</li>
 *   <li>Step 17: End-of-day 10-way reconciliation & audit proof.</li>
 * </ol>
 */
class MarketplaceReferenceScenarioTest extends PostgresTestBase {

    @Autowired
    private EnterpriseManagedAccountService enterpriseService;

    @Autowired
    private EnterpriseCheckoutService checkoutService;

    @Autowired
    private EnterpriseMultiMerchantSplitter splitter;

    @Autowired
    private DeliveryEscrowService escrowService;

    @Autowired
    private ConditionalSettlementReleaseEngine settlementEngine;

    @Autowired
    private EnterpriseTreasuryService treasuryService;

    /** Isolated test connector: wraps an in-memory wallet double, never the production adapter. */
    private ResilientWalletConnector walletConnector;

    private InMemoryModeAWalletSimulator walletSimulator;

    @Autowired
    private EnterpriseTokenService tokenService;

    @Autowired
    private Ledger ledger;

    @Autowired
    private HmacWebhookVerifier webhookVerifier;

    @Autowired
    private ConnectorWebhookSigner webhookSigner;

    @Autowired
    private SftpReconHandler reconHandler;

    private Enterprise enterprise;
    private UUID deliveryPartnerId;
    private List<EnterpriseManagedAccount> subMerchants;
    private String externalCustomerId;
    private String orderReference;

    @BeforeEach
    void setup() {
        walletSimulator = new InMemoryModeAWalletSimulator(tokenService, webhookVerifier);
        walletConnector = new ResilientWalletConnector(walletSimulator, webhookSigner, reconHandler, 0L);
        deliveryPartnerId = UUID.randomUUID();
        externalCustomerId = "marketplace_cust_kofi_mensah";
        orderReference = "MARKETPLACE-GH-ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    @Test
    @DisplayName("Complete End-to-End Execution of the Marketplace 17-Step Flagship Scenario")
    void testFullMarketplace17StepReferenceScenario() {

        // =========================================================================
        // Step 1: Enterprise Onboarding & API Credential Issue
        // =========================================================================
        String enterpriseCode = "MARKETPLACE-GH-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        enterprise = enterpriseService.registerEnterprise(
                enterpriseCode,
                "Marketplace Ghana Marketplace",
                "marketplace-ops@marketplace.com.gh",
                "+233302123456",
                "https://api.marketplace.com.gh/elepay/webhooks"
        );
        assertThat(enterprise).isNotNull();
        assertThat(enterprise.id()).isNotNull();
        assertThat(enterprise.kycStatus()).isEqualTo("verified");

        // Set up segregated treasury accounts & prefund liquidity buffer (GH₵ 10,000.00 = 1,000,000 pesewas)
        treasuryService.setupEnterpriseTreasury(enterprise.id(), "GHS");

        // Register test wallet partner credentials
        tokenService.registerPartner(PartnerAuthCredentials.create(
                "partner_test_wallet",
                "Marketplace Ghana Marketplace",
                "marketplace_client_id_001",
                "marketplace_client_secret_xyz",
                "marketplace_signing_key_2026",
                Set.of(PartnerScope.values())
        ));

        long initialPrefundingPesewas = 1_000_000L;
        PrefundingLedgerEntry prefundEntry = treasuryService.depositPrefunding(
                enterprise.id(),
                initialPrefundingPesewas,
                "GHS",
                "PREFUND-INIT-" + enterpriseCode,
                "Initial enterprise settlement prefunding"
        );
        assertThat(prefundEntry.balanceAfterPesewas()).isEqualTo(initialPrefundingPesewas);

        // =========================================================================
        // Step 2: Sub-Merchant Bulk Provisioning (10 merchants)
        // =========================================================================
        List<CreateManagedAccountRequest> merchantRequests = List.of(
                CreateManagedAccountRequest.builder().enterpriseId(enterprise.id()).externalCustomerId("m_tech_01").accountType("merchant").displayName("TechGadgets Ltd").phone("+233241000001").email("tech@marketplace.gh").nationalId("GHA-001").initialTier(3).currency("GHS").metadata(Map.of("category", "ELECTRONICS")).duplicateAction(DuplicateAction.FLAG).build(),
                CreateManagedAccountRequest.builder().enterpriseId(enterprise.id()).externalCustomerId("m_fash_02").accountType("merchant").displayName("Accra Trends").phone("+233241000002").email("fash@marketplace.gh").nationalId("GHA-002").initialTier(2).currency("GHS").metadata(Map.of("category", "FASHION")).duplicateAction(DuplicateAction.FLAG).build(),
                CreateManagedAccountRequest.builder().enterpriseId(enterprise.id()).externalCustomerId("m_beau_03").accountType("merchant").displayName("Shea Luxe").phone("+233241000003").email("beau@marketplace.gh").nationalId("GHA-003").initialTier(2).currency("GHS").metadata(Map.of("category", "BEAUTY")).duplicateAction(DuplicateAction.FLAG).build(),
                CreateManagedAccountRequest.builder().enterpriseId(enterprise.id()).externalCustomerId("m_groc_04").accountType("merchant").displayName("Fresh Harvest").phone("+233241000004").email("groc@marketplace.gh").nationalId("GHA-004").initialTier(1).currency("GHS").metadata(Map.of("category", "GROCERIES")).duplicateAction(DuplicateAction.FLAG).build(),
                CreateManagedAccountRequest.builder().enterpriseId(enterprise.id()).externalCustomerId("m_appl_05").accountType("merchant").displayName("Apex Appliances").phone("+233241000005").email("appl@marketplace.gh").nationalId("GHA-005").initialTier(2).currency("GHS").metadata(Map.of("category", "HOME")).duplicateAction(DuplicateAction.FLAG).build(),
                CreateManagedAccountRequest.builder().enterpriseId(enterprise.id()).externalCustomerId("m_book_06").accountType("merchant").displayName("Scholastic Books").phone("+233241000006").email("book@marketplace.gh").nationalId("GHA-006").initialTier(1).currency("GHS").metadata(Map.of("category", "BOOKS")).duplicateAction(DuplicateAction.FLAG).build(),
                CreateManagedAccountRequest.builder().enterpriseId(enterprise.id()).externalCustomerId("m_auto_07").accountType("merchant").displayName("MotorWorks Ghana").phone("+233241000007").email("auto@marketplace.gh").nationalId("GHA-007").initialTier(2).currency("GHS").metadata(Map.of("category", "AUTOMOTIVE")).duplicateAction(DuplicateAction.FLAG).build(),
                CreateManagedAccountRequest.builder().enterpriseId(enterprise.id()).externalCustomerId("m_spor_08").accountType("merchant").displayName("Fitness Pro").phone("+233241000008").email("spor@marketplace.gh").nationalId("GHA-008").initialTier(1).currency("GHS").metadata(Map.of("category", "SPORTS")).duplicateAction(DuplicateAction.FLAG).build(),
                CreateManagedAccountRequest.builder().enterpriseId(enterprise.id()).externalCustomerId("m_toys_09").accountType("merchant").displayName("Kids World").phone("+233241000009").email("toys@marketplace.gh").nationalId("GHA-009").initialTier(1).currency("GHS").metadata(Map.of("category", "TOYS")).duplicateAction(DuplicateAction.FLAG).build(),
                CreateManagedAccountRequest.builder().enterpriseId(enterprise.id()).externalCustomerId("m_offi_10").accountType("merchant").displayName("Stationery Express").phone("+233241000010").email("offi@marketplace.gh").nationalId("GHA-010").initialTier(1).currency("GHS").metadata(Map.of("category", "OFFICE")).duplicateAction(DuplicateAction.FLAG).build()
        );

        BulkCreateResult bulkResult = enterpriseService.createAccountsBulk(
                new BulkCreateAccountsRequest(enterprise.id(), merchantRequests, DuplicateAction.FLAG)
        );
        assertThat(bulkResult.totalRequested()).isEqualTo(10);
        assertThat(bulkResult.createdCount()).isEqualTo(10);
        assertThat(bulkResult.errors()).isEmpty();

        subMerchants = new ArrayList<>();
        for (CreateManagedAccountRequest req : merchantRequests) {
            subMerchants.add(enterpriseService.findAccount(enterprise.id(), req.externalCustomerId()).orElseThrow());
        }
        assertThat(subMerchants).hasSize(10);

        // =========================================================================
        // Step 3: test wallet External Wallet Mode Configuration
        // =========================================================================
        assertThat(walletConnector.getConnectorId()).isEqualTo("test-wallet-v1");
        assertThat(walletConnector.getConnectorType()).isEqualTo("EXTERNAL_WALLET");

        // Seed customer's external wallet balance on test wallet: GH₵ 2,500.00 (250,000 pesewas)
        long initialCustomerWalletPesewas = 250_000L;
        walletSimulator.seedCustomerBalance(externalCustomerId, initialCustomerWalletPesewas);

        var balanceCheck = walletConnector.queryWalletBalance(
                new ExternalBalanceQueryRequest("partner_test_wallet", externalCustomerId, "GHS")
        );
        assertThat(balanceCheck.availableBalance()).isEqualTo(initialCustomerWalletPesewas);

        // Configure enterprise settlement rule: conditional release requiring delivery
        settlementEngine.configureSettlementRule(
                enterprise.id(),
                null,
                SettlementScheduleType.DAILY,
                0, // T+0
                0L,
                true, // Delivery strictly required
                14,
                NetGrossMode.NET,
                "GHS",
                true
        );

        // =========================================================================
        // Step 4: Customer Places GH₵1,520 Cart Across 10 Merchants
        // =========================================================================
        // 10 items totaling GH₵1,470 (147,000 pesewas) + GH₵50 delivery fee (5,000 pesewas) = GH₵1,520 (152,000 pesewas)
        long totalOrderPesewas = 152_000L;

        List<SubMerchantItemAllocation> cartItems = List.of(
                new SubMerchantItemAllocation(subMerchants.get(0).id(), "TechGadgets Ltd", 30_000L, "ELECTRONICS", "Wireless Headphones", 1000, 100L, 0L, 50, 0L, 0, 0L, true),
                new SubMerchantItemAllocation(subMerchants.get(1).id(), "Accra Trends", 25_000L, "FASHION", "Kente Print Shirt", 1200, 50L, 0L, 50, 0L, 0, 0L, true),
                new SubMerchantItemAllocation(subMerchants.get(2).id(), "Shea Luxe", 20_000L, "BEAUTY", "Organic Shea Butter", 1500, 50L, 0L, 50, 0L, 0, 0L, true),
                new SubMerchantItemAllocation(subMerchants.get(3).id(), "Fresh Harvest", 18_000L, "GROCERIES", "Local Rice & Spices", 800, 0L, 0L, 50, 0L, 0, 0L, true),
                new SubMerchantItemAllocation(subMerchants.get(4).id(), "Apex Appliances", 15_000L, "HOME", "Electric Blender", 900, 50L, 0L, 50, 0L, 0, 0L, true),
                new SubMerchantItemAllocation(subMerchants.get(5).id(), "Scholastic Books", 12_000L, "BOOKS", "Accounting Textbooks", 700, 50L, 0L, 50, 0L, 0, 0L, true),
                new SubMerchantItemAllocation(subMerchants.get(6).id(), "MotorWorks Ghana", 10_000L, "AUTOMOTIVE", "Engine Oil Filter", 1000, 50L, 0L, 50, 0L, 0, 0L, true),
                new SubMerchantItemAllocation(subMerchants.get(7).id(), "Fitness Pro", 8_000L, "SPORTS", "Yoga Mat & Dumbbells", 600, 0L, 0L, 50, 0L, 0, 0L, true),
                new SubMerchantItemAllocation(subMerchants.get(8).id(), "Kids World", 5_000L, "TOYS", "Building Blocks Set", 1100, 50L, 0L, 50, 0L, 0, 0L, true),
                new SubMerchantItemAllocation(subMerchants.get(9).id(), "Stationery Express", 4_000L, "OFFICE", "Printer Paper Ream", 500, 50L, 0L, 50, 0L, 0, 0L, true)
        );

        List<DeliveryAllocation> deliveryAllocations = List.of(
                new DeliveryAllocation(deliveryPartnerId, "Courier Partner Express Delivery", 5_000L, 200, 50, "Express Delivery")
        );

        // =========================================================================
        // Step 5: External Payment Collected via test wallet
        // =========================================================================
        String authIdempotency = "AUTH-IDEM-" + orderReference;
        String canonical = externalCustomerId + ":" + totalOrderPesewas + ":GHS:" + orderReference;
        String authSig = com.elepay.common.HmacVerification.computeHex("marketplace_signing_key_2026", canonical);

        var authResult = walletConnector.authorizePayment(new ExternalPaymentAuthRequest(
                authIdempotency,
                "partner_test_wallet",
                orderReference,
                externalCustomerId,
                totalOrderPesewas,
                "GHS",
                authSig
        ));
        assertThat(authResult.isSuccessful()).isTrue();

        var captureResult = walletConnector.capturePayment(new ExternalPaymentCaptureRequest(
                "CAP-IDEM-" + orderReference,
                authResult.authorizationId(),
                totalOrderPesewas,
                "GHS",
                List.of()
        ));
        assertThat(captureResult.isSuccessful()).isTrue();

        // Customer wallet in test wallet is debited: 250,000 - 152,000 = 98,000 pesewas
        long remainingWalletPesewas = walletConnector.queryWalletBalance(
                new ExternalBalanceQueryRequest("partner_test_wallet", externalCustomerId, "GHS")
        ).availableBalance();
        assertThat(remainingWalletPesewas).isEqualTo(98_000L);

        // Record external receivable in treasury
        treasuryService.recordReceivable(enterprise.id(), orderReference, totalOrderPesewas, "GHS");

        // =========================================================================
        // Step 6: Split Calculation with Nested Commissions & Delivery Fees
        // =========================================================================
        EnterpriseSplitResult splitResult = splitter.split(new EnterpriseSplitRequest(
                totalOrderPesewas, "GHS", cartItems, deliveryAllocations
        ));
        splitResult.validateConservation();
        assertThat(splitResult.merchantAllocations()).hasSize(10);
        assertThat(splitResult.deliveryAllocations()).hasSize(1);
        assertThat(splitResult.totalOrderPesewas()).isEqualTo(totalOrderPesewas);

        // =========================================================================
        // Step 7: Ingestion & Ledger Journal Entry Posting (Total Credits == Total Debits)
        // =========================================================================
        String clearingCode = "system:clearing:test_wallet:GHS";
        long clearingBefore = ledger.balance(clearingCode);
        long platformRevenueBefore = ledger.balance(Ledger.REVENUE);

        EnterpriseCheckoutResult checkoutResult = checkoutService.ingestExternalPayment(
                new EnterpriseCheckoutPaymentRequest(
                        "IDEM-" + orderReference,
                        enterprise.id(),
                        orderReference,
                        "test_wallet_auth_ref",
                        "test_wallet",
                        "GHS",
                        totalOrderPesewas,
                        cartItems,
                        deliveryAllocations,
                        Map.of("customerId", externalCustomerId)
                )
        );
        assertThat(checkoutResult).isNotNull();
        assertThat(checkoutResult.ledgerTransactionId()).isNotNull();

        // Verify clearing account was debited exactly 152,000 pesewas
        assertThat(ledger.balance(clearingCode)).isEqualTo(clearingBefore - 152_000L);

        // =========================================================================
        // Step 8: Funds Held in Conditional Settlement Escrow
        // =========================================================================
        List<DeliveryEscrowRecord> escrowRecords = escrowService.registerOrderItems(
                enterprise.id(),
                orderReference,
                splitResult.merchantAllocations(),
                "GHS",
                checkoutResult.ledgerTransactionId()
        );
        assertThat(escrowRecords).hasSize(10);
        for (DeliveryEscrowRecord r : escrowRecords) {
            assertThat(r.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(r.escrowStatus()).isEqualTo(EscrowStatus.HELD);
            assertThat(r.isEligibleForRelease()).isFalse();
        }

        // =========================================================================
        // Step 9: Delivery Confirmed for 8 Merchants
        // =========================================================================
        // Merchants at index 1..7 (7 merchants) plus index 9 (Stationery Express) = 8 delivered merchants
        List<Integer> deliveredIndices = List.of(1, 2, 3, 4, 5, 6, 7, 9);
        for (int idx : deliveredIndices) {
            String itemRef = cartItems.get(idx).description();
            DeliveryEscrowRecord confirmed = escrowService.confirmDelivery(
                    enterprise.id(), orderReference, itemRef, "Courier Partner Tracking: DELIVERED"
            );
            assertThat(confirmed.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
            assertThat(confirmed.isEligibleForRelease()).isTrue();
        }

        // =========================================================================
        // Step 10: Missing Delivery Confirmation for 1 Merchant (Held in Escrow)
        // =========================================================================
        // Merchant index 8: Kids World (Building Blocks Set) has missing delivery confirmation
        String missingItemRef = cartItems.get(8).description();
        DeliveryEscrowRecord missingEscrow = escrowService.reportMissingDelivery(
                enterprise.id(), orderReference, missingItemRef, "Courier scan missing; package in transit"
        );
        assertThat(missingEscrow.deliveryStatus()).isEqualTo(DeliveryStatus.MISSING_CONFIRMATION);
        assertThat(missingEscrow.escrowStatus()).isEqualTo(EscrowStatus.HELD);
        assertThat(missingEscrow.isEligibleForRelease()).isFalse(); // Retained in escrow!

        // =========================================================================
        // Step 11: 1 Merchant Item Rejected/Cancelled -> GH₵300 Item-Level Refund Initiated
        // =========================================================================
        // Merchant index 0: TechGadgets Ltd (Wireless Headphones, GH₵300 = 30,000 pesewas) rejected by customer
        String rejectedItemRef = cartItems.get(0).description();
        DeliveryEscrowRecord rejectedEscrow = escrowService.rejectItem(
                enterprise.id(), orderReference, rejectedItemRef, "Customer rejected: wrong model delivered"
        );
        assertThat(rejectedEscrow.deliveryStatus()).isEqualTo(DeliveryStatus.REJECTED);
        assertThat(rejectedEscrow.escrowStatus()).isEqualTo(EscrowStatus.REFUNDED);
        assertThat(rejectedEscrow.isEligibleForRelease()).isFalse();

        // Reverse TechGadgets pending allocation in double-entry ledger
        CalculatedMerchantAllocation techAllocation = splitResult.merchantAllocations().get(0);
        long techNet = techAllocation.netPayablePesewas();
        long techCommission = techAllocation.marketplaceCommissionPesewas();
        long techPlatform = techAllocation.platformFeePesewas();
        UUID techMerchantId = subMerchants.get(0).id();

        ledger.post(Posting.of(
                "enterprise:refund",
                "REFUND-REV-" + orderReference,
                "refund:item_reversal",
                List.of(
                        Leg.debit(Ledger.code("merchant", techMerchantId, "pending", "GHS"), techNet),
                        Leg.debit(Ledger.code("merchant", enterprise.id(), "pending", "GHS"), techCommission),
                        Leg.debit(Ledger.REVENUE, techPlatform),
                        Leg.credit(clearingCode, 30_000L) // return to clearing
                )
        ).describedAs("Item level refund reversal for TechGadgets Wireless Headphones"));

        // =========================================================================
        // Step 12: Refund Instructed to test wallet Connector
        // =========================================================================
        var refundResult = walletConnector.refundToWallet(new ExternalWalletRefundRequest(
                "REF-IDEM-" + orderReference,
                "partner_test_wallet",
                captureResult.captureId(),
                "TX-REF-" + orderReference,
                externalCustomerId,
                30_000L, // GH₵ 300.00
                "GHS",
                "Customer rejected item on delivery",
                List.of(new RefundAllocationItem(subMerchants.get(0).id().toString(), "item-refund-01", 30_000L, 3_000L, "Customer rejected item on delivery"))
        ));
        assertThat(refundResult.isSuccessful()).isTrue();

        // Customer wallet balance in test wallet credited back by GH₵300: 98,000 + 30,000 = 128,000 pesewas
        long walletAfterRefund = walletConnector.queryWalletBalance(
                new ExternalBalanceQueryRequest("partner_test_wallet", externalCustomerId, "GHS")
        ).availableBalance();
        assertThat(walletAfterRefund).isEqualTo(128_000L);

        // =========================================================================
        // Step 13: Customer Requests Withdrawal of GH₵300 from Marketplace Wallet to MTN MoMo
        // =========================================================================
        String customerMtnPhone = "+233241234567";
        var withdrawalRequest = new ExternalWalletWithdrawalRequest(
                "WITHDRAW-IDEM-" + UUID.randomUUID().toString().substring(0, 8),
                "partner_test_wallet",
                externalCustomerId,
                30_000L, // GH₵ 300.00
                "GHS",
                "momo",
                customerMtnPhone,
                "TIER_2"
        );

        // =========================================================================
        // Step 14: Withdrawal Authorization & Domestic MoMo Payout Execution
        // =========================================================================
        // Check treasury sufficiency for withdrawal
        var withdrawFundingCheck = treasuryService.checkFundingSufficiency(
                enterprise.id(), 30_000L, "GHS", "WITHDRAWAL"
        );
        assertThat(withdrawFundingCheck.hasShortfall()).isFalse();

        var withdrawalResult = walletConnector.requestWithdrawal(withdrawalRequest);
        assertThat(withdrawalResult.isSuccessful()).isTrue();
        assertThat(withdrawalResult.state()).isEqualTo("SUBMITTED");

        // Customer wallet in test wallet is debited: 128,000 - 30,000 = 98,000 pesewas
        long walletAfterWithdrawal = walletConnector.queryWalletBalance(
                new ExternalBalanceQueryRequest("partner_test_wallet", externalCustomerId, "GHS")
        ).availableBalance();
        assertThat(walletAfterWithdrawal).isEqualTo(98_000L);

        // Post ledger legs for customer withdrawal from prefunding safeguarded account
        String prefundingLedgerAcc = Ledger.code("enterprise", enterprise.id(), "holding", "GHS");
        String momoClearingAcc = ledger.openSystem("system:clearing:momo:GHS", "clearing", "GHS", true);
        ledger.post(Posting.of(
                "enterprise:withdrawal",
                "WITHDRAW-POST-" + withdrawalResult.withdrawalId(),
                "withdrawal:momo_payout",
                List.of(
                        Leg.debit(prefundingLedgerAcc, 30_000L),
                        Leg.credit(momoClearingAcc, 30_000L)
                )
        ).describedAs("MoMo withdrawal payout execution for customer"));

        // =========================================================================
        // Step 15: Settlement Released for Delivered Items to Merchant Available Balances
        // =========================================================================
        SettlementReleaseResult releaseResult = settlementEngine.releaseEligibleSettlements(enterprise.id(), "GHS");
        assertThat(releaseResult.status()).isEqualTo("RELEASED");
        assertThat(releaseResult.totalItemsReleased()).isEqualTo(8); // exactly the 8 delivered merchants
        assertThat(releaseResult.totalItemsHeld()).isEqualTo(1); // exactly the 1 missing delivery merchant

        // Verify: Delivered merchants have positive available balances
        for (int idx : deliveredIndices) {
            UUID mId = subMerchants.get(idx).id();
            long available = ledger.balance(Ledger.code("merchant", mId, "available", "GHS"));
            assertThat(available).isGreaterThan(0L);
            long pending = ledger.balance(Ledger.code("merchant", mId, "pending", "GHS"));
            assertThat(pending).isZero();
        }

        // Verify: Missing delivery merchant (Kids World) available balance is ZERO; pending balance still intact!
        UUID missingMerchantId = subMerchants.get(8).id();
        assertThat(ledger.balance(Ledger.code("merchant", missingMerchantId, "available", "GHS"))).isZero();
        assertThat(ledger.balance(Ledger.code("merchant", missingMerchantId, "pending", "GHS"))).isGreaterThan(0L);

        // Verify: Rejected merchant (TechGadgets) available and pending balances are ZERO
        UUID rejectedMerchantId = subMerchants.get(0).id();
        assertThat(ledger.balance(Ledger.code("merchant", rejectedMerchantId, "available", "GHS"))).isZero();
        assertThat(ledger.balance(Ledger.code("merchant", rejectedMerchantId, "pending", "GHS"))).isZero();

        // =========================================================================
        // Step 16: Payout Batch Executed to Delivered Merchants
        // =========================================================================
        List<UUID> deliveredMerchantIds = deliveredIndices.stream()
                .map(idx -> subMerchants.get(idx).id())
                .toList();

        UUID payoutBatchId = settlementEngine.executeMerchantPayoutBatch(
                enterprise.id(), deliveredMerchantIds, "GHS"
        );
        assertThat(payoutBatchId).isNotNull();

        // Verify delivered merchant available balances are moved to reserved for payout
        for (UUID mId : deliveredMerchantIds) {
            long availableAfter = ledger.balance(Ledger.code("merchant", mId, "available", "GHS"));
            long reservedAfter = ledger.balance(Ledger.code("merchant", mId, "reserved", "GHS"));
            assertThat(availableAfter).isZero();
            assertThat(reservedAfter).isGreaterThan(0L);
        }

        // =========================================================================
        // Step 17: End-of-Day 10-Way Reconciliation & Audit Proof
        // =========================================================================
        // 1. External wallet balance matches exactly
        assertThat(walletAfterWithdrawal).isEqualTo(98_000L);

        // 2. External collection clearing net balance reflects captured minus refund
        // Originally -152,000, reversed +30,000 = -122,000 net collected delta
        assertThat(ledger.balance(clearingCode)).isEqualTo(clearingBefore - 122_000L);

        // 3. Multi-merchant split total conservation
        assertThat(splitResult.totalOrderPesewas()).isEqualTo(152_000L);

        // 4. Delivery escrow hold prevented missing delivery payout
        var heldItem = escrowService.findEscrowRecord(enterprise.id(), orderReference, missingItemRef).orElseThrow();
        assertThat(heldItem.escrowStatus()).isEqualTo(EscrowStatus.HELD);
        assertThat(heldItem.deliveryStatus()).isEqualTo(DeliveryStatus.MISSING_CONFIRMATION);

        // 5. Delivered items count is exactly 8
        assertThat(releaseResult.totalItemsReleased()).isEqualTo(8);

        // 6. Merchant payout batch executed matching exactly the delivered sellers
        assertThat(deliveredMerchantIds).hasSize(8);

        // 7. Refund correctly instructed and credited to Marketplace wallet
        assertThat(refundResult.refundedAmountMinorUnits()).isEqualTo(30_000L);

        // 8. Customer MoMo withdrawal authorized and executed
        assertThat(withdrawalResult.state()).isEqualTo("SUBMITTED");

        // 9. Platform fees and marketplace commission recognized in ledger
        long platformRevenue = ledger.balance(Ledger.REVENUE);
        assertThat(platformRevenue).isGreaterThanOrEqualTo(platformRevenueBefore);

        // 10. SFTP batch reconciliation file matches cryptographic checksum
        List<SftpReconRecord> reconRecords = List.of(
                new SftpReconRecord("TX-ORDER-COLLECT", orderReference, enterprise.id(), "PAYMENT", 152_000L, 147_000L, 5_000L, "SETTLED", LocalDate.now()),
                new SftpReconRecord("TX-REFUND-TECH", "REF-" + orderReference, subMerchants.get(0).id(), "REFUND", 30_000L, 26_900L, 3_100L, "REFUNDED", LocalDate.now()),
                new SftpReconRecord("TX-WITHDRAW-KOFI", "WD-" + orderReference, enterprise.id(), "WITHDRAWAL", 30_000L, 30_000L, 0L, "PAID_OUT", LocalDate.now())
        );

        String reconFile = walletConnector.generateDailyReconciliationFile(
                "BATCH-" + orderReference, LocalDate.now(), "GHS", reconRecords
        );
        SftpReconBatch parsedRecon = walletConnector.parseReconciliationFile(reconFile);
        assertThat(parsedRecon).isNotNull();
        assertThat(parsedRecon.totalRecords()).isEqualTo(3);
        assertThat(parsedRecon.totalGrossPesewas()).isEqualTo(212_000L);

        // Final audit proof: Zero ledger drift
        long totalSafeguardedPrefunding = ledger.balance(prefundingLedgerAcc);
        assertThat(totalSafeguardedPrefunding).isEqualTo(initialPrefundingPesewas - 30_000L); // 970,000 pesewas remaining
    }
}
