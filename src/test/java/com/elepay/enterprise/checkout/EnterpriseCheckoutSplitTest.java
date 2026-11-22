package com.elepay.enterprise.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Enterprise Embedded Finance & Multi-Merchant Split Engine Automated Verification Suite.
 *
 * <p>Verifies:
 * <ul>
 *   <li>10-sub-merchant checkout ingestion and multi-tier splitting (e.g. Jumia GH₵1,520 order)</li>
 *   <li>Delivery partner fulfillment fees and marketplace commission deductions</li>
 *   <li>Strict conservation of money: {@code totalPesewas == sum(allocations)}</li>
 *   <li>Integer minor-unit arithmetic throughout; deterministic largest-remainder distribution</li>
 *   <li>Authoritative double-entry ledger integration, account balances, and zero ledger drift</li>
 *   <li>Idempotent re-entry protection and rejection of fraudulent/mismatched requests</li>
 * </ul>
 */
class EnterpriseCheckoutSplitTest extends PostgresTestBase {

    @Autowired
    private Ledger ledger;

    @Autowired
    private EnterpriseMultiMerchantSplitter splitter;

    @Autowired
    private EnterpriseCheckoutService checkoutService;

    private UUID enterpriseMarketplaceId;
    private UUID deliveryPartnerId;
    private List<UUID> merchantIds;

    @BeforeEach
    void setup() {
        enterpriseMarketplaceId = UUID.randomUUID();
        deliveryPartnerId = UUID.randomUUID();
        merchantIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            merchantIds.add(UUID.randomUUID());
        }
    }

    @Test
    @DisplayName("Flagship Acceptance Scenario: Jumia order with GH₵1,520 across 10 sub-merchants and delivery partner")
    void testTenMerchantSplitWithExactConservationAndLedgerPosting() {
        // Total order = GH₵1,520.00 = 152,000 pesewas
        // 10 sub-merchants selling products totaling GH₵1,470.00 = 147,000 pesewas
        // Delivery partner fee = GH₵50.00 = 5,000 pesewas
        long totalOrderPesewas = 152_000L;

        List<SubMerchantItemAllocation> items = List.of(
                // 1. Electronics Seller: GH₵300.00 (30,000 pesewas), 10% commission + 100 fixed, 50 bps platform, 5% reserve
                new SubMerchantItemAllocation(merchantIds.get(0), "TechGadgets Ltd", 30_000L,
                        "ELECTRONICS", "Wireless Headphones", 1000, 100L, 0L, 50, 0L, 500, 0L, true),
                // 2. Fashion Boutique: GH₵250.00 (25,000 pesewas), 12% commission + 50 fixed, 50 bps platform, 0 reserve
                new SubMerchantItemAllocation(merchantIds.get(1), "Accra Trends", 25_000L,
                        "FASHION", "Kente Print Shirt", 1200, 50L, 0L, 50, 0L, 0, 0L, true),
                // 3. Beauty & Cosmetics: GH₵200.00 (20,000 pesewas), 15% commission + 50 fixed, 50 bps platform, 5% reserve
                new SubMerchantItemAllocation(merchantIds.get(2), "Shea Luxe", 20_000L,
                        "BEAUTY", "Organic Shea Butter", 1500, 50L, 0L, 50, 0L, 500, 0L, true),
                // 4. Groceries Hub: GH₵180.00 (18,000 pesewas), 8% commission + 0 fixed, 50 bps platform, 0 reserve
                new SubMerchantItemAllocation(merchantIds.get(3), "Fresh Harvest", 18_000L,
                        "GROCERIES", "Local Rice & Spices", 800, 0L, 0L, 50, 0L, 0, 0L, true),
                // 5. Home Appliances: GH₵150.00 (15,000 pesewas), 9% commission + 50 fixed, 50 bps platform, 3% reserve
                new SubMerchantItemAllocation(merchantIds.get(4), "Apex Appliances", 15_000L,
                        "HOME", "Electric Blender", 900, 50L, 0L, 50, 0L, 300, 0L, true),
                // 6. Books & Stationery: GH₵120.00 (12,000 pesewas), 7% commission + 50 fixed, 50 bps platform, 0 reserve
                new SubMerchantItemAllocation(merchantIds.get(5), "Scholastic Books", 12_000L,
                        "BOOKS", "Accounting Textbooks", 700, 50L, 0L, 50, 0L, 0, 0L, true),
                // 7. Automotive Supplies: GH₵100.00 (10,000 pesewas), 10% commission + 50 fixed, 50 bps platform, 5% reserve
                new SubMerchantItemAllocation(merchantIds.get(6), "MotorWorks Ghana", 10_000L,
                        "AUTOMOTIVE", "Engine Oil Filter", 1000, 50L, 0L, 50, 0L, 500, 0L, true),
                // 8. Sports & Outdoors: GH₵80.00 (8,000 pesewas), 6% commission + 0 fixed, 50 bps platform, 0 reserve
                new SubMerchantItemAllocation(merchantIds.get(7), "Fitness Pro", 8_000L,
                        "SPORTS", "Yoga Mat & Dumbbells", 600, 0L, 0L, 50, 0L, 0, 0L, true),
                // 9. Toys & Games: GH₵50.00 (5,000 pesewas), 11% commission + 50 fixed, 50 bps platform, 2% reserve
                new SubMerchantItemAllocation(merchantIds.get(8), "Kids World", 5_000L,
                        "TOYS", "Building Blocks Set", 1100, 50L, 0L, 50, 0L, 200, 0L, true),
                // 10. Office Essentials: GH₵40.00 (4,000 pesewas), 5% commission + 50 fixed, 50 bps platform, 0 reserve
                new SubMerchantItemAllocation(merchantIds.get(9), "Stationery Express", 4_000L,
                        "OFFICE", "Printer Paper Ream", 500, 50L, 0L, 50, 0L, 0, 0L, true)
        );

        // Verify items sum = 147,000 pesewas
        long itemsSum = items.stream().mapToLong(SubMerchantItemAllocation::grossAmountPesewas).sum();
        assertThat(itemsSum).isEqualTo(147_000L);

        // Delivery Partner: ELESEND Logistics GH₵50.00 (5,000 pesewas), 200 bps marketplace fee, 50 bps platform fee
        List<DeliveryAllocation> deliveryAllocations = List.of(
                new DeliveryAllocation(deliveryPartnerId, "ELESEND Express Delivery", 5_000L,
                        200, 50, "Courier delivery to customer address")
        );

        String idempotencyKey = "EP-IDEMP-" + UUID.randomUUID();
        String orderRef = "JUMIA-ORD-" + System.currentTimeMillis();
        String paymentRef = "JUMIAPAY-TXN-GH-" + System.currentTimeMillis();
        String channel = "JUMIAPAY_WALLET";
        String clearingAccount = "system:clearing:" + channel.toLowerCase() + ":GHS";
        long initialClearingBalance = ledger.balance(clearingAccount);

        EnterpriseCheckoutPaymentRequest request = new EnterpriseCheckoutPaymentRequest(
                idempotencyKey,
                enterpriseMarketplaceId,
                orderRef,
                paymentRef,
                channel,
                Ledger.GHS,
                totalOrderPesewas,
                items,
                deliveryAllocations,
                Map.of("platform", "JUMIA_GHANA", "channel", "WEB")
        );

        // Execute Ingestion & Multi-Merchant Split
        EnterpriseCheckoutResult result = checkoutService.ingestExternalPayment(request);

        // Invariant 1: Result metadata and status
        assertThat(result.status()).isEqualTo("PROCESSED");
        assertThat(result.journalTxId()).isNotNull();
        assertThat(result.totalPesewas()).isEqualTo(152_000L);
        assertThat(result.isReplay()).isFalse();

        EnterpriseSplitResult split = result.splitResult();
        assertThat(split.totalMerchantGross()).isEqualTo(147_000L);
        assertThat(split.totalDeliveryGross()).isEqualTo(5_000L);
        assertThat(split.totalPesewas()).isEqualTo(152_000L);

        // Invariant 2: Strict conservation of money across all allocations
        long totalCredits = split.totalMerchantNet() + split.totalDeliveryNet()
                + split.totalMarketplaceCommission() + split.totalPlatformRevenue()
                + split.totalReserveRetained();
        assertThat(totalCredits).isEqualTo(totalOrderPesewas);

        // Invariant 3: Verify individual item line calculations
        // Line 0: TechGadgets Ltd (30,000 gross)
        // Comm: 30000 * 10% = 3000 + 100 = 3100
        // Plat: 30000 * 0.5% = 150
        // Res: 30000 * 5% = 1500
        // Net: 30000 - 3100 - 150 - 1500 = 25,250
        CalculatedMerchantAllocation line0 = split.merchantAllocations().get(0);
        assertThat(line0.grossAmountPesewas()).isEqualTo(30_000L);
        assertThat(line0.marketplaceCommissionPesewas()).isEqualTo(3_100L);
        assertThat(line0.platformFeePesewas()).isEqualTo(150L);
        assertThat(line0.reservePesewas()).isEqualTo(1_500L);
        assertThat(line0.netPayablePesewas()).isEqualTo(25_250L);

        // Line 1: Accra Trends (25,000 gross)
        // Comm: 25000 * 12% = 3000 + 50 = 3050
        // Plat: 25000 * 0.5% = 125
        // Res: 0
        // Net: 25000 - 3050 - 125 = 21,825
        CalculatedMerchantAllocation line1 = split.merchantAllocations().get(1);
        assertThat(line1.grossAmountPesewas()).isEqualTo(25_000L);
        assertThat(line1.marketplaceCommissionPesewas()).isEqualTo(3_050L);
        assertThat(line1.platformFeePesewas()).isEqualTo(125L);
        assertThat(line1.reservePesewas()).isEqualTo(0L);
        assertThat(line1.netPayablePesewas()).isEqualTo(21_825L);

        // Delivery Partner: 5,000 gross
        // Comm: 5000 * 2% = 100
        // Plat: 5000 * 0.5% = 25
        // Net: 5000 - 100 - 25 = 4,875
        CalculatedDeliveryAllocation delivery0 = split.deliveryAllocations().get(0);
        assertThat(delivery0.deliveryGrossPesewas()).isEqualTo(5_000L);
        assertThat(delivery0.marketplaceCommissionPesewas()).isEqualTo(100L);
        assertThat(delivery0.platformFeePesewas()).isEqualTo(25L);
        assertThat(delivery0.deliveryNetPesewas()).isEqualTo(4_875L);

        // Invariant 4: Authoritative Double-Entry Ledger Balances
        assertThat(ledger.balance(clearingAccount) - initialClearingBalance).isEqualTo(-152_000L);

        // Verify Merchant 0 pending & reserved balances
        String m0Pending = Ledger.code("merchant", merchantIds.get(0), "pending", Ledger.GHS);
        String m0Reserved = Ledger.code("merchant", merchantIds.get(0), "reserved", Ledger.GHS);
        assertThat(ledger.balance(m0Pending)).isEqualTo(25_250L);
        assertThat(ledger.balance(m0Reserved)).isEqualTo(1_500L);

        // Verify Merchant 1 pending balance
        String m1Pending = Ledger.code("merchant", merchantIds.get(1), "pending", Ledger.GHS);
        assertThat(ledger.balance(m1Pending)).isEqualTo(21_825L);

        // Verify Delivery partner pending balance
        String delivPending = Ledger.code("merchant", deliveryPartnerId, "pending", Ledger.GHS);
        assertThat(ledger.balance(delivPending)).isEqualTo(4_875L);

        // Verify Marketplace commission pending balance
        String marketplacePending = Ledger.code("merchant", enterpriseMarketplaceId, "pending", Ledger.GHS);
        assertThat(ledger.balance(marketplacePending)).isEqualTo(split.totalMarketplaceCommission());

        // Verify Platform revenue balance
        assertThat(ledger.balance(Ledger.REVENUE)).isGreaterThanOrEqualTo(split.totalPlatformRevenue());

        // Invariant 5: Ledger Integrity Check (Zero Drift, Balance per currency = 0)
        Ledger.Integrity integrity = ledger.integrity();
        assertThat(integrity.ok()).isTrue();
        assertThat(integrity.accountsWithDrift()).isEqualTo(0L);
        assertThat(integrity.unbalancedTransactions()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Deterministic largest-remainder distribution for fractional minor units")
    void testLargestRemainderDistributionForFractionalPesewas() {
        // Distribute 100 pesewas across 3 equal weights of 33.333%
        List<Long> weights = List.of(10L, 10L, 10L);
        List<Long> allocated = splitter.distributeProportionally(100L, weights);

        // Floor division is 33 each = 99. Remainder is 1. First index receives the extra 1 pesewa.
        assertThat(allocated).containsExactly(34L, 33L, 33L);
        assertThat(allocated.stream().mapToLong(Long::longValue).sum()).isEqualTo(100L);

        // Distribute 1,000 pesewas across 7 unequal weights
        List<Long> weights7 = List.of(15L, 25L, 10L, 30L, 5L, 8L, 7L); // sum = 100
        List<Long> allocated7 = splitter.distributeProportionally(1_000L, weights7);
        assertThat(allocated7.stream().mapToLong(Long::longValue).sum()).isEqualTo(1_000L);
        assertThat(allocated7).containsExactly(150L, 250L, 100L, 300L, 50L, 80L, 70L);

        // Proportional discount distribution across 10 items
        List<Long> itemAmounts = List.of(
                12_345L, 23_456L, 34_567L, 45_678L, 56_789L,
                11_111L, 22_222L, 33_333L, 44_444L, 55_555L
        );
        long totalDiscount = 5_000L; // GH₵50.00 promo discount
        List<Long> discountPerItem = splitter.distributeDiscountByLargestRemainder(totalDiscount, itemAmounts);

        assertThat(discountPerItem).hasSize(10);
        assertThat(discountPerItem.stream().mapToLong(Long::longValue).sum()).isEqualTo(totalDiscount);
    }

    @Test
    @DisplayName("Strict zero-loss financial conservation with odd pesewas amounts and irregular rates")
    void testZeroLossFinancialConservationAcrossOddAmounts() {
        // Odd total: GH₵1,520.37 = 152,037 pesewas
        long oddTotal = 152_037L;

        // Allocate odd gross amounts across 10 sub-merchants:
        // 15,203 + 15,204 + 15,203 + 15,204 + 15,203 + 15,204 + 15,203 + 15,204 + 15,204 + 10,208 = 147,037
        // Plus 5,000 delivery = 152,037 total.
        List<SubMerchantItemAllocation> items = List.of(
                new SubMerchantItemAllocation(merchantIds.get(0), "M1", 15_203L, "CAT1", "Desc", 333, 11L, 0L, 37, 0L, 150, 0L, true),
                new SubMerchantItemAllocation(merchantIds.get(1), "M2", 15_204L, "CAT2", "Desc", 777, 13L, 0L, 41, 0L, 250, 0L, true),
                new SubMerchantItemAllocation(merchantIds.get(2), "M3", 15_203L, "CAT3", "Desc", 999, 17L, 0L, 43, 0L, 0, 0L, true),
                new SubMerchantItemAllocation(merchantIds.get(3), "M4", 15_204L, "CAT4", "Desc", 1234, 19L, 0L, 47, 0L, 350, 0L, true),
                new SubMerchantItemAllocation(merchantIds.get(4), "M5", 15_203L, "CAT5", "Desc", 555, 23L, 0L, 51, 0L, 0, 0L, true),
                new SubMerchantItemAllocation(merchantIds.get(5), "M6", 15_204L, "CAT6", "Desc", 888, 29L, 0L, 53, 0L, 400, 0L, true),
                new SubMerchantItemAllocation(merchantIds.get(6), "M7", 15_203L, "CAT7", "Desc", 666, 31L, 0L, 59, 0L, 0, 0L, true),
                new SubMerchantItemAllocation(merchantIds.get(7), "M8", 15_204L, "CAT8", "Desc", 1111, 37L, 0L, 61, 0L, 200, 0L, true),
                new SubMerchantItemAllocation(merchantIds.get(8), "M9", 15_204L, "CAT9", "Desc", 444, 41L, 0L, 67, 0L, 0, 0L, true),
                new SubMerchantItemAllocation(merchantIds.get(9), "M10", 10_205L, "CAT10", "Desc", 1250, 43L, 0L, 71, 0L, 300, 0L, true)
        );

        List<DeliveryAllocation> delivery = List.of(
                new DeliveryAllocation(deliveryPartnerId, "Odd Express", 5_000L, 125, 25, "Delivery")
        );

        EnterpriseSplitRequest request = new EnterpriseSplitRequest(oddTotal, Ledger.GHS, items, delivery);
        EnterpriseSplitResult result = splitter.split(request);

        // Verify zero-loss invariant
        long totalCredits = result.totalMerchantNet() + result.totalDeliveryNet()
                + result.totalMarketplaceCommission() + result.totalPlatformRevenue()
                + result.totalReserveRetained();

        assertThat(totalCredits).isEqualTo(oddTotal);

        for (CalculatedMerchantAllocation alloc : result.merchantAllocations()) {
            long lineTotal = alloc.netPayablePesewas() + alloc.marketplaceCommissionPesewas()
                    + alloc.platformFeePesewas() + alloc.reservePesewas();
            assertThat(lineTotal).isEqualTo(alloc.grossAmountPesewas());
        }
    }

    @Test
    @DisplayName("Idempotency: Replaying identical checkout request returns original result without double-crediting")
    void testIdempotentIngestionPreventsDoubleCrediting() {
        long amount = 10_000L;
        List<SubMerchantItemAllocation> items = List.of(
                SubMerchantItemAllocation.of(merchantIds.get(0), "Seller A", 10_000L, 500, 50L)
        );

        String idempKey = "EP-IDEMP-" + UUID.randomUUID();
        EnterpriseCheckoutPaymentRequest req = new EnterpriseCheckoutPaymentRequest(
                idempKey, enterpriseMarketplaceId, "ORD-IDEMP-01", "PAY-IDEMP-01",
                "EXTERNAL_MOMO", Ledger.GHS, amount, items, List.of(), Map.of()
        );

        // First ingestion
        EnterpriseCheckoutResult res1 = checkoutService.ingestExternalPayment(req);
        assertThat(res1.isReplay()).isFalse();

        String merchantPending = Ledger.code("merchant", merchantIds.get(0), "pending", Ledger.GHS);
        long initialPending = ledger.balance(merchantPending);
        assertThat(initialPending).isGreaterThan(0L);

        // Second ingestion (replayed with identical payload)
        EnterpriseCheckoutResult res2 = checkoutService.ingestExternalPayment(req);
        assertThat(res2.isReplay()).isTrue();
        assertThat(res2.journalTxId()).isEqualTo(res1.journalTxId());

        // Balance must not change (no double credit)
        assertThat(ledger.balance(merchantPending)).isEqualTo(initialPending);

        // Third ingestion with changed payload under same key -> 409 Conflict
        EnterpriseCheckoutPaymentRequest conflictingReq = new EnterpriseCheckoutPaymentRequest(
                idempKey, enterpriseMarketplaceId, "ORD-IDEMP-01", "PAY-IDEMP-01",
                "EXTERNAL_MOMO", Ledger.GHS, 12_000L,
                List.of(SubMerchantItemAllocation.of(merchantIds.get(0), "Seller A", 12_000L, 500, 50L)),
                List.of(), Map.of()
        );

        assertThatThrownBy(() -> checkoutService.ingestExternalPayment(conflictingReq))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("idempotency_conflict"));
    }

    @Test
    @DisplayName("Validation: Rejects mismatched amounts, empty items, and excessive deductions")
    void testRejectionOfInvalidRequests() {
        // 1. Empty items
        EnterpriseSplitRequest emptyReq = new EnterpriseSplitRequest(1000L, Ledger.GHS, List.of(), List.of());
        assertThatThrownBy(() -> splitter.split(emptyReq))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("empty_items"));

        // 2. Amount mismatch (totalPesewas 1000 != items 500)
        List<SubMerchantItemAllocation> items = List.of(
                SubMerchantItemAllocation.simple(merchantIds.get(0), "Seller", 500L)
        );
        EnterpriseSplitRequest mismatchReq = new EnterpriseSplitRequest(1000L, Ledger.GHS, items, List.of());
        assertThatThrownBy(() -> splitter.split(mismatchReq))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("amount_mismatch"));

        // 3. Deductions exceed gross amount
        List<SubMerchantItemAllocation> excessiveDeduction = List.of(
                new SubMerchantItemAllocation(merchantIds.get(0), "Seller", 1_000L,
                        "GENERAL", "Item", 5000, 600L, 0L, 0, 0L, 0, 0L, false)
        );
        // 50% of 1000 is 500 + 600 fixed = 1100 > 1000 gross
        EnterpriseSplitRequest excessiveReq = new EnterpriseSplitRequest(1000L, Ledger.GHS, excessiveDeduction, List.of());
        assertThatThrownBy(() -> splitter.split(excessiveReq))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("deductions_exceed_gross"));
    }

    @Test
    @DisplayName("Multi-delivery partner support allocates fulfillment fees to respective couriers")
    void testMultiDeliveryPartnerSplit() {
        UUID courierA = UUID.randomUUID();
        UUID courierB = UUID.randomUUID();

        List<SubMerchantItemAllocation> items = List.of(
                SubMerchantItemAllocation.of(merchantIds.get(0), "Merchant A", 10_000L, 500, 0L)
        );

        List<DeliveryAllocation> deliveries = List.of(
                new DeliveryAllocation(courierA, "Inter-City Hauler", 3_000L, 100, 50, "Zone 1 Transport"),
                new DeliveryAllocation(courierB, "Last-Mile Rider", 2_000L, 100, 50, "Zone 2 Final Drop")
        );

        long total = 15_000L;
        String idempKey = "EP-DELIV-" + UUID.randomUUID();

        EnterpriseCheckoutPaymentRequest req = new EnterpriseCheckoutPaymentRequest(
                idempKey, enterpriseMarketplaceId, "ORD-MULTI-DELIV-01", "PAY-MULTI-DELIV-01",
                "EXTERNAL_CARD", Ledger.GHS, total, items, deliveries, Map.of()
        );

        EnterpriseCheckoutResult result = checkoutService.ingestExternalPayment(req);
        assertThat(result.status()).isEqualTo("PROCESSED");

        // Courier A net: 3000 - (3000*1%) 30 - (3000*0.5%) 15 = 2,955
        String courierAPending = Ledger.code("merchant", courierA, "pending", Ledger.GHS);
        assertThat(ledger.balance(courierAPending)).isEqualTo(2_955L);

        // Courier B net: 2000 - (2000*1%) 20 - (2000*0.5%) 10 = 1,970
        String courierBPending = Ledger.code("merchant", courierB, "pending", Ledger.GHS);
        assertThat(ledger.balance(courierBPending)).isEqualTo(1_970L);

        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("Lookup methods find checkout by ID and order reference")
    void testCheckoutLookups() {
        long amount = 5_000L;
        List<SubMerchantItemAllocation> items = List.of(
                SubMerchantItemAllocation.simple(merchantIds.get(0), "Seller", 5_000L)
        );
        String orderRef = "LOOKUP-ORD-" + UUID.randomUUID();

        EnterpriseCheckoutPaymentRequest req = new EnterpriseCheckoutPaymentRequest(
                "IDEMP-" + UUID.randomUUID(), enterpriseMarketplaceId, orderRef, "PAY-REF-01",
                "JUMIAPAY_WALLET", Ledger.GHS, amount, items, List.of(), Map.of()
        );

        EnterpriseCheckoutResult ingested = checkoutService.ingestExternalPayment(req);

        var byOrder = checkoutService.findCheckoutByOrderReference(orderRef);
        assertThat(byOrder).isPresent();
        assertThat(byOrder.get().checkoutId()).isEqualTo(ingested.checkoutId());

        var byId = checkoutService.findCheckoutById(ingested.checkoutId());
        assertThat(byId).isPresent();
        assertThat(byId.get().orderReference()).isEqualTo(orderRef);
    }
}
