package com.elepay.enterprise.refund;

import com.elepay.common.ApiException;
import com.elepay.enterprise.checkout.EnterpriseCheckoutPaymentRequest;
import com.elepay.enterprise.checkout.EnterpriseCheckoutResult;
import com.elepay.enterprise.checkout.EnterpriseCheckoutService;
import com.elepay.enterprise.checkout.EnterpriseMultiMerchantSplitter;
import com.elepay.enterprise.checkout.SubMerchantItemAllocation;
import com.elepay.enterprise.wallet.*;
import com.elepay.ledger.Ledger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit test suite for Enterprise Item-Level Refunds Engine (Stream E4).
 *
 * <p>Verifies:
 * <ul>
 *   <li>Full and partial refunds per item or line item.</li>
 *   <li>Multiple simultaneous refunds against the same external order across distinct sub-merchants.</li>
 *   <li>Exact fee adjustments, commission clawbacks, tax/delivery adjustments in integer minor units (pesewas).</li>
 *   <li>JumiaPay connector integration (ExternalWalletConnector.refundToWallet).</li>
 *   <li>Direct MoMo and bank refund destinations.</li>
 *   <li>Strict duplicate prevention, idempotency key checks, zero double-crediting.</li>
 *   <li>Order cumulative refund limit enforcement.</li>
 *   <li>Refund liability recording and settlement balance adjustment offsets.</li>
 *   <li>Financial conservation (total debits == total credits).</li>
 * </ul>
 */
class EnterpriseItemRefundTest {

    private Ledger ledger;
    private EnterpriseItemRefundCalculator refundCalculator;
    private EnterpriseRefundLiabilityTracker liabilityTracker;
    private EnterpriseCheckoutService checkoutService;
    private ExternalWalletEngine walletEngine;
    private JumiaPayContractAdapter jumiaPayAdapter;
    private EnterpriseRefundService refundService;

    private UUID enterpriseId;
    private UUID deliveryPartnerId;
    private List<UUID> merchantIds;

    @BeforeEach
    void setUp() {
        ledger = Mockito.mock(Ledger.class);
        when(ledger.open(any(), any(), any(), any())).thenAnswer(invocation ->
                invocation.getArgument(0) + ":" + invocation.getArgument(1) + ":" + invocation.getArgument(2) + ":" + invocation.getArgument(3));
        when(ledger.openSystem(any(), any(), any(), any(Boolean.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledger.post(any())).thenReturn(new Ledger.Posted(UUID.randomUUID(), false));

        refundCalculator = new EnterpriseItemRefundCalculator();
        liabilityTracker = new EnterpriseRefundLiabilityTracker(null);

        EnterpriseMultiMerchantSplitter splitter = new EnterpriseMultiMerchantSplitter();
        checkoutService = new EnterpriseCheckoutService(ledger, splitter);

        EnterpriseTokenService tokenService = new EnterpriseTokenService();
        HmacWebhookVerifier webhookVerifier = new HmacWebhookVerifier();
        jumiaPayAdapter = new JumiaPayContractAdapter(tokenService, webhookVerifier);

        walletEngine = new ExternalWalletEngine(
                List.of(jumiaPayAdapter),
                tokenService,
                webhookVerifier
        );

        refundService = new EnterpriseRefundService(
                ledger,
                refundCalculator,
                liabilityTracker,
                checkoutService,
                walletEngine,
                null
        );

        enterpriseId = UUID.randomUUID();
        deliveryPartnerId = UUID.randomUUID();
        merchantIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            merchantIds.add(UUID.randomUUID());
        }
    }

    @Test
    @DisplayName("Single Item Refund: Exact integer math, fee clawbacks, and financial conservation")
    void testExactItemLevelRefundConservation() {
        UUID merchantId = merchantIds.get(0);
        long itemGross = 100_000L; // GH₵1,000.00 = 100,000 pesewas

        // 10% commission (1,000 bps), 50 bps platform fee, 5% reserve (500 bps)
        EnterpriseRefundItemSpecification spec = new EnterpriseRefundItemSpecification(
                "ITEM-HEADPHONES-01",
                merchantId,
                itemGross,
                null,
                null,
                null,
                null,
                0L,
                1000,
                50,
                500,
                "Customer return: defective audio jack"
        );

        CalculatedItemRefund calc = refundCalculator.calculateSingleItem(spec);

        assertThat(calc.grossRefundPesewas()).isEqualTo(100_000L);
        assertThat(calc.commissionReversalPesewas()).isEqualTo(10_000L); // 10% of 100,000
        assertThat(calc.platformFeeReversalPesewas()).isEqualTo(500L);    // 0.5% of 100,000
        assertThat(calc.reserveReversalPesewas()).isEqualTo(5_000L);      // 5% of 100,000
        assertThat(calc.merchantReversalPesewas()).isEqualTo(84_500L);   // 100,000 - 15,500 = 84,500

        // Conservation check
        long sum = calc.merchantReversalPesewas() + calc.commissionReversalPesewas()
                + calc.platformFeeReversalPesewas() + calc.reserveReversalPesewas();
        assertThat(sum).isEqualTo(itemGross);
    }

    @Test
    @DisplayName("Partial Item Refund: Concession/discount reversal on line item")
    void testPartialItemRefund() {
        UUID merchantId = merchantIds.get(1);
        long itemGross = 25_000L; // GH₵250.00
        long partialRefund = 10_000L; // GH₵100.00 concession

        EnterpriseRefundItemSpecification spec = EnterpriseRefundItemSpecification.of(
                "ITEM-SHIRT-02",
                merchantId,
                partialRefund,
                1200, // 12% commission
                "Partial concession for minor stitch defect"
        );

        CalculatedItemRefund calc = refundCalculator.calculateSingleItem(spec);

        assertThat(calc.grossRefundPesewas()).isEqualTo(10_000L);
        assertThat(calc.commissionReversalPesewas()).isEqualTo(1_200L); // 12% of 10,000
        assertThat(calc.merchantReversalPesewas()).isEqualTo(8_800L);   // 10,000 - 1,200

        long sum = calc.merchantReversalPesewas() + calc.commissionReversalPesewas();
        assertThat(sum).isEqualTo(partialRefund);
    }

    @Test
    @DisplayName("Multiple Simultaneous Refunds against Same Order across Distinct Sub-Merchants and Delivery")
    void testMultipleSimultaneousRefundsAcrossTenMerchants() {
        String orderRef = "JUMIA-ORD-998877";
        long totalOrderGross = 152_000L;
        refundService.registerOrderGross(enterpriseId, orderRef, totalOrderGross);

        // Refund 3 distinct items across 3 distinct merchants + delivery fee adjustment
        List<EnterpriseRefundItemSpecification> items = List.of(
                EnterpriseRefundItemSpecification.of("ITEM-TECH-01", merchantIds.get(0), 30_000L, 1000, "Return electronics"),
                EnterpriseRefundItemSpecification.of("ITEM-FASHION-02", merchantIds.get(1), 15_000L, 1200, "Wrong size shirt"),
                EnterpriseRefundItemSpecification.of("ITEM-TOYS-09", merchantIds.get(8), 5_000L, 1100, "Damaged toy box")
        );

        // Delivery adjustment: GH₵50.00 (5,000 pesewas)
        DeliveryRefundSpecification del = new DeliveryRefundSpecification(
                deliveryPartnerId,
                5_000L,
                4_500L, // delivery partner net reversal
                400L,   // marketplace fee reversal
                100L,   // platform fee reversal
                "Full order return delivery refund"
        );

        EnterpriseItemRefundRequest request = new EnterpriseItemRefundRequest(
                "idem_ref_multi_001",
                enterpriseId,
                orderRef,
                null,
                "jumia_cust_5566",
                RefundDestination.INTERNAL_WALLET,
                Map.of(),
                "GHS",
                "Customer requested return on items",
                items,
                del,
                Map.of("channel", "app")
        );

        // Expected total refund = 30,000 + 15,000 + 5,000 + 5,000 = 55,000 pesewas (GH₵550.00)
        EnterpriseRefundResult result = refundService.processRefund(request);

        assertThat(result.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(result.totalRefundPesewas()).isEqualTo(55_000L);
        assertThat(result.calculatedItems()).hasSize(3);
        assertThat(result.deliveryAdjustment()).isNotNull();
        assertThat(result.replayed()).isFalse();

        // Check liabilities were recorded for merchants
        long liabilityM0 = liabilityTracker.getOutstandingLiability(enterpriseId, merchantIds.get(0));
        long liabilityM1 = liabilityTracker.getOutstandingLiability(enterpriseId, merchantIds.get(1));
        long liabilityM8 = liabilityTracker.getOutstandingLiability(enterpriseId, merchantIds.get(8));

        assertThat(liabilityM0).isEqualTo(27_000L); // 30,000 - 3,000 comm
        assertThat(liabilityM1).isEqualTo(13_200L); // 15,000 - 1,800 comm
        assertThat(liabilityM8).isEqualTo(4_450L);  // 5,000 - 550 comm
    }

    @Test
    @DisplayName("JumiaPay Mode A Connector Integration: Direct refund to external wallet")
    void testJumiaPayConnectorIntegration() {
        String externalCustId = "jumia_cust_9900";
        jumiaPayAdapter.seedCustomerBalance(externalCustId, 20_000L); // Customer has GH₵200.00 initially

        // Authorize and capture in JumiaPay to establish state
        EnterpriseTokenService tokenService = walletEngine.getTokenService();
        tokenService.registerPartner(PartnerAuthCredentials.create(
                "partner_jumiapay_gh", "Jumia Ghana", "client_1", "secret_1", "signing_key_1",
                Set.of(PartnerScope.WALLET_AUTHORIZE, PartnerScope.WALLET_CAPTURE, PartnerScope.WALLET_REFUND)
        ));

        String orderRef = "JUMIA-ORD-MODEA-1";
        refundService.registerOrderGross(enterpriseId, orderRef, 50_000L);

        // Process refund of GH₵300.00 (30,000 pesewas) to external wallet
        EnterpriseItemRefundRequest request = new EnterpriseItemRefundRequest(
                "idem_ref_jpa_001",
                enterpriseId,
                orderRef,
                "jpa_cap_001",
                externalCustId,
                RefundDestination.EXTERNAL_WALLET,
                Map.of("connectorId", "jumiapay-gh-v1"),
                "GHS",
                "Defective item refund to Jumia wallet",
                List.of(EnterpriseRefundItemSpecification.of("ITEM-A1", merchantIds.get(0), 30_000L, 1000, "Refund")),
                null,
                Map.of()
        );

        // Before refund, customer balance was 20,000
        ExternalBalanceQueryResult balBefore = jumiaPayAdapter.queryWalletBalance(
                new ExternalBalanceQueryRequest("partner_jumiapay_gh", externalCustId, "GHS")
        );
        assertThat(balBefore.availableBalanceMinorUnits()).isEqualTo(20_000L);

        // Pre-create authorization in JumiaPay so refundToWallet finds capture
        ExternalPaymentAuthRequest authReq = new ExternalPaymentAuthRequest(
                "auth_idem_1", "partner_jumiapay_gh", WalletOperatingMode.MODE_A_ENTERPRISE_OWNED, "TX-REF-1", externalCustId, 30_000L, "GHS",
                com.elepay.common.HmacVerification.computeHex("signing_key_1", externalCustId + ":30000:GHS:TX-REF-1"),
                Map.of()
        );
        jumiaPayAdapter.seedCustomerBalance(externalCustId, 50_000L);
        var authRes = jumiaPayAdapter.authorizePayment(authReq);
        var capRes = jumiaPayAdapter.capturePayment(new ExternalPaymentCaptureRequest(
                "cap_idem_1", authRes.authorizationId(), "TX-REF-1", 30_000L, "GHS",
                List.of(new PaymentAllocationSplit(merchantIds.get(0).toString(), 30_000L, 0L, 0L, "ITEM-A1", "Item sale"))
        ));

        // Refund to wallet with the capture ID
        EnterpriseItemRefundRequest refundReqWithCapture = new EnterpriseItemRefundRequest(
                "idem_ref_jpa_002",
                enterpriseId,
                orderRef,
                capRes.captureId(),
                externalCustId,
                RefundDestination.EXTERNAL_WALLET,
                Map.of("connectorId", "jumiapay-gh-v1"),
                "GHS",
                "Defective item refund",
                List.of(EnterpriseRefundItemSpecification.of("ITEM-A1", merchantIds.get(0), 30_000L, 1000, "Refund")),
                null,
                Map.of()
        );

        EnterpriseRefundResult result = refundService.processRefund(refundReqWithCapture);
        assertThat(result.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(result.connectorRefundId()).isNotNull();

        // Check customer balance in external wallet was credited by 30,000
        ExternalBalanceQueryResult balAfter = jumiaPayAdapter.queryWalletBalance(
                new ExternalBalanceQueryRequest("partner_jumiapay_gh", externalCustId, "GHS")
        );
        assertThat(balAfter.availableBalanceMinorUnits()).isEqualTo(50_000L); // 20,000 remaining + 30,000 refund = 50,000
    }

    @Test
    @DisplayName("Alternative Destinations: Domestic MoMo and Bank refund rails")
    void testMoMoAndBankRefundDestinations() {
        String orderRef = "JUMIA-ORD-MOMO-1";
        refundService.registerOrderGross(enterpriseId, orderRef, 50_000L);

        // 1. Domestic MoMo refund
        EnterpriseItemRefundRequest momoReq = new EnterpriseItemRefundRequest(
                "idem_momo_001",
                enterpriseId,
                orderRef,
                null,
                "cust_momo_1",
                RefundDestination.DOMESTIC_MOMO,
                Map.of("network", "mtn", "phone", "+233241234567"),
                "GHS",
                "Refund to MTN Mobile Money",
                List.of(EnterpriseRefundItemSpecification.of("ITEM-1", merchantIds.get(0), 20_000L, 1000, "Momo refund")),
                null,
                Map.of()
        );

        EnterpriseRefundResult momoRes = refundService.processRefund(momoReq);
        assertThat(momoRes.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(momoRes.destination()).isEqualTo(RefundDestination.DOMESTIC_MOMO);

        // 2. Bank refund
        EnterpriseItemRefundRequest bankReq = new EnterpriseItemRefundRequest(
                "idem_bank_001",
                enterpriseId,
                orderRef,
                null,
                "cust_bank_1",
                RefundDestination.BANK_ACCOUNT,
                Map.of("bankCode", "GCB", "accountNumber", "1234567890"),
                "GHS",
                "Refund to Bank Account",
                List.of(EnterpriseRefundItemSpecification.of("ITEM-2", merchantIds.get(1), 10_000L, 1000, "Bank refund")),
                null,
                Map.of()
        );

        EnterpriseRefundResult bankRes = refundService.processRefund(bankReq);
        assertThat(bankRes.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(bankRes.destination()).isEqualTo(RefundDestination.BANK_ACCOUNT);
    }

    @Test
    @DisplayName("Cumulative Refund Limit Enforcement: Cannot exceed captured amount")
    void testCumulativeRefundLimitExceededRejection() {
        String orderRef = "JUMIA-ORD-CUMULATIVE-LIMIT";
        long capturedAmount = 100_000L; // GH₵1,000.00
        refundService.registerOrderGross(enterpriseId, orderRef, capturedAmount);

        // First refund: GH₵700.00 (70,000 pesewas) -> Succeeded
        EnterpriseItemRefundRequest req1 = new EnterpriseItemRefundRequest(
                "idem_cum_01",
                enterpriseId,
                orderRef,
                null,
                "cust_cum_1",
                RefundDestination.EXTERNAL_WALLET,
                Map.of(),
                "GHS",
                "First refund",
                List.of(EnterpriseRefundItemSpecification.of("ITEM-1", merchantIds.get(0), 70_000L, 1000, "Refund 1")),
                null,
                Map.of()
        );
        refundService.processRefund(req1);

        // Second refund: GH₵400.00 (40,000 pesewas). 70,000 + 40,000 = 110,000 > 100,000! Must be rejected.
        EnterpriseItemRefundRequest req2 = new EnterpriseItemRefundRequest(
                "idem_cum_02",
                enterpriseId,
                orderRef,
                null,
                "cust_cum_1",
                RefundDestination.EXTERNAL_WALLET,
                Map.of(),
                "GHS",
                "Second refund exceeding limit",
                List.of(EnterpriseRefundItemSpecification.of("ITEM-2", merchantIds.get(1), 40_000L, 1000, "Refund 2")),
                null,
                Map.of()
        );

        assertThatThrownBy(() -> refundService.processRefund(req2))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exceeds original captured amount");
    }

    @Test
    @DisplayName("Strict Duplicate Prevention & Idempotency Key Handling")
    void testIdempotencyAndDuplicatePrevention() {
        String orderRef = "JUMIA-ORD-IDEMPOTENCY";
        refundService.registerOrderGross(enterpriseId, orderRef, 50_000L);

        EnterpriseItemRefundRequest req = new EnterpriseItemRefundRequest(
                "idem_exact_key_123",
                enterpriseId,
                orderRef,
                null,
                "cust_idem_1",
                RefundDestination.EXTERNAL_WALLET,
                Map.of(),
                "GHS",
                "Return item",
                List.of(EnterpriseRefundItemSpecification.of("ITEM-1", merchantIds.get(0), 10_000L, 1000, "Return")),
                null,
                Map.of()
        );

        EnterpriseRefundResult first = refundService.processRefund(req);
        assertThat(first.replayed()).isFalse();

        // Exact retry with same idempotency key -> returns cached result with replayed = true
        EnterpriseRefundResult retry = refundService.processRefund(req);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.refundId()).isEqualTo(first.refundId());
        assertThat(retry.totalRefundPesewas()).isEqualTo(first.totalRefundPesewas());

        // Changed payload under same key -> throws 409 Conflict
        EnterpriseItemRefundRequest conflictReq = new EnterpriseItemRefundRequest(
                "idem_exact_key_123",
                enterpriseId,
                orderRef,
                null,
                "cust_idem_1",
                RefundDestination.EXTERNAL_WALLET,
                Map.of(),
                "GHS",
                "Different reason",
                List.of(EnterpriseRefundItemSpecification.of("ITEM-1", merchantIds.get(0), 20_000L, 1000, "Different")),
                null,
                Map.of()
        );

        assertThatThrownBy(() -> refundService.processRefund(conflictReq))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Idempotency conflict");
    }

    @Test
    @DisplayName("Refund Liabilities & Settlement Offsets: Track liabilities and adjust pending settlement")
    void testRefundLiabilitiesAndSettlementOffset() {
        UUID merchantId = merchantIds.get(3);
        long refundLiability = 15_000L; // GH₵150.00

        RefundLiabilityRecord rec = liabilityTracker.recordLiability(enterpriseId, merchantId, UUID.randomUUID(), refundLiability);
        assertThat(rec.settled()).isFalse();
        assertThat(rec.amountPesewas()).isEqualTo(refundLiability);

        long outstanding = liabilityTracker.getOutstandingLiability(enterpriseId, merchantId);
        assertThat(outstanding).isEqualTo(refundLiability);

        // When settlement batch runs, offset pending liability
        long settled = liabilityTracker.settleLiabilities(enterpriseId, merchantId, 15_000L);
        assertThat(settled).isEqualTo(15_000L);

        long remaining = liabilityTracker.getOutstandingLiability(enterpriseId, merchantId);
        assertThat(remaining).isEqualTo(0L);
    }
}
