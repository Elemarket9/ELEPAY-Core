package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.References;
import com.elepay.enterprise.checkout.*;
import com.elepay.enterprise.refund.*;
import com.elepay.ledger.Ledger;
import com.elepay.merchanttools.pos.PosOrderService;
import com.elepay.merchanttools.pos.PosSplitBillService;
import com.elepay.merchanttools.restaurant.RestaurantSplitBillService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Regression & high-concurrency tests for marketplace split arithmetic,
 * reference collision resistance, multi-tenant cache isolation, and refund bounds.
 */
class MarketplaceSplitHardeningTest {

    @Test
    void splitterDoesNotOverflowOnLargeGrossAmounts() {
        EnterpriseMultiMerchantSplitter splitter = new EnterpriseMultiMerchantSplitter();
        UUID merchantId = UUID.randomUUID();
        long largeGross = 2_000_000_000_000_000L; // 2e15 pesewas -> gross * 5000 overflows 64-bit long

        EnterpriseSplitResult res = splitter.split(EnterpriseSplitRequest.of(
                largeGross,
                List.of(new SubMerchantItemAllocation(
                        merchantId, "WholesaleMerchant", largeGross, "wholesale", "Bulk",
                        5000, 0L, null, 100, 0L, 500, 0L, false
                ))
        ));

        assertThat(res.totalMarketplaceCommission()).isEqualTo(1_000_000_000_000_000L); // 50%
        assertThat(res.totalPlatformRevenue()).isEqualTo(20_000_000_000_000L);         // 1%
        assertThat(res.totalReserveRetained()).isEqualTo(100_000_000_000_000L);        // 5%
        assertThat(res.totalMerchantNet()).isEqualTo(880_000_000_000_000L);            // 44%
        res.validateConservation();
    }

    @Test
    void restaurantSplitBySeatConservesOrderDiscount() {
        PosOrderService mockPos = Mockito.mock(PosOrderService.class);
        UUID merchantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        // subtotal=10_000, discount=2_000, tax=500, tip=500 -> total=9_000
        var order = new PosOrderService.PosOrder(
                orderId, "ORD-DISC-1", merchantId, null, null, null, null,
                10_000L, 2_000L, 500L, 500L, 9_000L, 0L, 0L, "PENDING", "DINE_IN", OffsetDateTime.now()
        );
        var items = List.of(
                new PosOrderService.PosOrderItem(UUID.randomUUID(), orderId, null, "Jollof", BigDecimal.ONE, 6_000L, 0L, 0L, 6_000L, "[]", 1, null, null),
                new PosOrderService.PosOrderItem(UUID.randomUUID(), orderId, null, "Waakye", BigDecimal.ONE, 4_000L, 0L, 0L, 4_000L, "[]", 2, null, null)
        );
        Mockito.when(mockPos.getOrder(orderId)).thenReturn(Optional.of(order));
        Mockito.when(mockPos.listOrderItems(orderId)).thenReturn(items);

        var split = new RestaurantSplitBillService(mockPos).splitBySeat(orderId);
        long sumSeatBills = split.seatBills().stream()
                .mapToLong(RestaurantSplitBillService.SeatBill::totalDuePesewas)
                .sum();
        assertThat(sumSeatBills).isEqualTo(order.totalPesewas());
    }

    @Test
    void posSplitBySeatsPreservesExactConservationUnderLargeDiscount() {
        PosOrderService mockPos = Mockito.mock(PosOrderService.class);
        UUID merchantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        var order = new PosOrderService.PosOrder(
                orderId, "ORD-POS-1", merchantId, null, null, null, null,
                10_000L, 9_997L, 0L, 0L, 3L, 0L, 0L, "PENDING", "DINE_IN", OffsetDateTime.now()
        );
        var items = List.of(
                new PosOrderService.PosOrderItem(UUID.randomUUID(), orderId, null, "A", BigDecimal.ONE, 5_000L, 0L, 0L, 5_000L, "[]", 1, null, null),
                new PosOrderService.PosOrderItem(UUID.randomUUID(), orderId, null, "B", BigDecimal.ONE, 4_999L, 0L, 0L, 4_999L, "[]", 2, null, null),
                new PosOrderService.PosOrderItem(UUID.randomUUID(), orderId, null, "C", BigDecimal.ONE, 1L, 0L, 0L, 1L, "[]", 3, null, null)
        );
        Mockito.when(mockPos.getOrder(orderId)).thenReturn(Optional.of(order));
        Mockito.when(mockPos.listOrderItems(orderId)).thenReturn(items);

        var split = new PosSplitBillService(mockPos).splitBillBySeats(orderId);
        long sum = split.seatGroups().stream()
                .mapToLong(PosSplitBillService.SeatSplitGroup::subtotalPesewas)
                .sum();
        assertThat(sum).isEqualTo(order.totalPesewas());
        assertThat(split.seatGroups()).allSatisfy(g -> assertThat(g.subtotalPesewas()).isGreaterThanOrEqualTo(0L));
    }

    @Test
    void refundCalculatorRejectsNegativeCustomReversalsAndHandlesLargeGross() {
        EnterpriseItemRefundCalculator calc = new EnterpriseItemRefundCalculator();
        UUID merchantId = UUID.randomUUID();

        var negativeCommission = EnterpriseRefundItemSpecification.withCustomReversal(
                "item-1", merchantId, 10_000L, 15_000L, -5_000L, "invalid"
        );
        assertThatThrownBy(() -> calc.calculateSingleItem(negativeCommission))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");

        long largeGross = 2_000_000_000_000_000L;
        var largeSpec = EnterpriseRefundItemSpecification.of("item-big", merchantId, largeGross, 5000, "bulk refund");
        CalculatedItemRefund res = calc.calculateSingleItem(largeSpec);
        assertThat(res.commissionReversalPesewas()).isEqualTo(1_000_000_000_000_000L);
        assertThat(res.merchantReversalPesewas()).isEqualTo(1_000_000_000_000_000L);
    }

    @Test
    void enterpriseCheckoutTenantScopedLookupDoesNotCrossContaminate() {
        Ledger mockLedger = Mockito.mock(Ledger.class);
        Mockito.when(mockLedger.openSystem(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(mockLedger.openStripedSystem(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any()))
                .thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(mockLedger.open(Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> Ledger.code(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        Mockito.when(mockLedger.post(Mockito.any()))
                .thenAnswer(inv -> new Ledger.Posted(UUID.randomUUID(), false));

        EnterpriseCheckoutService service = new EnterpriseCheckoutService(mockLedger, new EnterpriseMultiMerchantSplitter());
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID seller = UUID.randomUUID();

        service.ingestExternalPayment(EnterpriseCheckoutPaymentRequest.of(
                "key-a", tenantA, "ORD-001", "PAY-A", "momo", 10_000L,
                List.of(SubMerchantItemAllocation.simple(seller, "Seller", 10_000L)), List.of()));
        service.ingestExternalPayment(EnterpriseCheckoutPaymentRequest.of(
                "key-b", tenantB, "ORD-001", "PAY-B", "momo", 25_000L,
                List.of(SubMerchantItemAllocation.simple(seller, "Seller", 25_000L)), List.of()));

        assertThat(service.findCheckoutByOrderReference(tenantA, "ORD-001"))
                .isPresent()
                .get()
                .satisfies(r -> {
                    assertThat(r.enterpriseId()).isEqualTo(tenantA);
                    assertThat(r.totalPesewas()).isEqualTo(10_000L);
                });
        assertThat(service.findCheckoutByOrderReference(tenantB, "ORD-001"))
                .isPresent()
                .get()
                .satisfies(r -> {
                    assertThat(r.enterpriseId()).isEqualTo(tenantB);
                    assertThat(r.totalPesewas()).isEqualTo(25_000L);
                });
    }

    @Test
    void oneMillionConcurrentReferencesHaveZeroCollisions() throws Exception {
        final int total = 1_000_000;
        final int workers = 16;
        final int perWorker = total / workers;
        Set<String> seen = ConcurrentHashMap.newKeySet(total);
        AtomicInteger collisions = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < workers; w++) {
                exec.submit(() -> {
                    try {
                        go.await();
                        for (int i = 0; i < perWorker; i++) {
                            if (!seen.add(References.next("EP"))) {
                                collisions.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(collisions.get()).isZero();
        assertThat(seen).hasSize(total);
    }
}
