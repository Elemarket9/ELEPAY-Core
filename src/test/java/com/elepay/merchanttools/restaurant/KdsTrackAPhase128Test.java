package com.elepay.merchanttools.restaurant;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import com.elepay.identity.PosDevicePaths;
import com.elepay.merchanttools.inventory.ProductCatalogService;
import com.elepay.merchanttools.pos.PosOrderService;
import com.elepay.merchanttools.pos.TenderType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Track A backend tests covering:
 * - Phase 1: tableNumber join, per-merchant urgency thresholds (10/15 defaults, custom overrides).
 * - Phase 2: multi-round tickets, nextRoundForOrder, ADD-ON - ROUND n banner, item-level VOID printing.
 * - Phase 8: RECEIPT print jobs, ReceiptText 32/42-col wrapping + reprint banner, kind-filtered claims,
 *            branch-aware stations/routes, auto_ready flag, and PosDevicePaths whitelisting.
 */
class KdsTrackAPhase128Test extends PostgresTestBase {
    @Autowired KdsService kds;
    @Autowired KitchenStationService stations;
    @Autowired KdsPrintService printing;
    @Autowired RestaurantTableService tables;
    @Autowired PosOrderService orders;
    @Autowired ProductCatalogService catalog;
    @Autowired JdbcClient db;

    UUID merchantId;
    UUID branchA;
    UUID branchB;

    @BeforeEach
    void setup() {
        merchantId = UUID.randomUUID();
        branchA = UUID.randomUUID();
        branchB = UUID.randomUUID();
        db.sql("""
            INSERT INTO merchant (id, legal_name, trading_name, kind, status, contact_phone,
                                  merchant_code, fee_bps, fee_fixed, settlement_delay_hours, created_by)
            VALUES (:id, 'Osu Grill & Bar Ltd', 'Osu Grill', 'merchant', 'active', '0244112233',
                    :code, 0, 0, 24, 'system')
            """)
                .param("id", merchantId)
                .param("code", String.format("%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100000, 1000000)))
                .update();

        db.sql("""
            INSERT INTO merchant_branch (id, merchant_id, name, active)
            VALUES (:a, :m, 'Osu Branch', true),
                   (:b, :m, 'East Legon Branch', true)
            """)
                .param("a", branchA)
                .param("b", branchB)
                .param("m", merchantId)
                .update();
    }

    @Test
    @DisplayName("Phase 1: tableNumber is joined on tickets and per-merchant urgency thresholds default to 10/15 and can be customized")
    void tableNumberJoinAndPerMerchantUrgencyThresholds() {
        var plan = tables.createFloorPlan(merchantId, "Main Hall", "{}");
        var table12 = tables.createTable(merchantId, plan.id(), "T-12", 4);

        var order = orders.createOrder(
                merchantId, branchA, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Jollof Rice", BigDecimal.ONE, 4500L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN"
        );
        var orderItems = orders.listOrderItems(order.id());
        var ticket = kds.createTicket(
                merchantId, order.id(), table12.id(), DiningMode.DINE_IN, "Kofi", "ALL",
                List.of(new KdsService.KdsItemInput(orderItems.get(0).id(), "Jollof Rice", BigDecimal.ONE, null, "MAIN")),
                null, "pos:" + order.id() + ":r1", 1
        );

        assertThat(ticket.tableNumber()).isEqualTo("T-12");
        assertThat(ticket.round()).isEqualTo(1);
        assertThat(kds.getTicket(ticket.id()).orElseThrow().tableNumber()).isEqualTo("T-12");
        assertThat(kds.getActiveTickets(merchantId)).singleElement()
                .satisfies(t -> assertThat(t.tableNumber()).isEqualTo("T-12"));

        // Default urgency thresholds are 10 / 15
        var syncDefault = kds.sync(merchantId, 0, null);
        assertThat(syncDefault.urgencyThresholds()).isEqualTo(new KdsService.UrgencyThresholds(10, 15));
        assertThat(syncDefault.tickets()).singleElement()
                .satisfies(v -> assertThat(v.ticket().tableNumber()).isEqualTo("T-12"));

        // Verify urgencyLevel boundaries (10 min warning, 15 min critical)
        KdsService.KdsTicket aged12m = new KdsService.KdsTicket(
                ticket.id(), merchantId, order.id(), table12.id(), "DINE_IN", "Kofi", "NEW", "ALL", null,
                OffsetDateTime.now().minusMinutes(12), null, null, null, 1, null, null, 1L, "T-12", 1
        );
        assertThat(aged12m.urgencyLevel()).isEqualTo("WARNING");
        assertThat(aged12m.urgencyLevel(5, 10)).isEqualTo("CRITICAL");
        assertThat(aged12m.urgencyLevel(15, 20)).isEqualTo("NORMAL");

        // Update per-merchant settings
        var updated = kds.updateUrgencyThresholds(merchantId, 6, 12);
        assertThat(updated).isEqualTo(new KdsService.UrgencyThresholds(6, 12));
        assertThat(kds.sync(merchantId, 0, null).urgencyThresholds()).isEqualTo(new KdsService.UrgencyThresholds(6, 12));

        // Invalid thresholds rejected
        assertThatThrownBy(() -> kds.updateUrgencyThresholds(merchantId, 15, 15)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> kds.updateUrgencyThresholds(merchantId, 0, 10)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Phase 2: Multi-round tickets increment nextRoundForOrder and print ADD-ON - ROUND n banner")
    void multiRoundTicketsAndAddOnBanner() {
        stations.create(merchantId, new KitchenStationService.StationInput(
                "Grill", branchA, true, KitchenStationService.Output.PRINTER, "tcp://192.168.1.50:9100", 42));

        var order = orders.createOrder(
                merchantId, branchA, null, null, null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Tilapia", BigDecimal.ONE, 6000L, 0L, 0L, "[]", 1, "MAIN", null),
                        new PosOrderService.OrderItemInput(null, "Kelewele", BigDecimal.ONE, 2000L, 0L, 0L, "[]", 1, "APPETIZER", null)
                ),
                0L, 0L, 0L, "DINE_IN"
        );
        var items = orders.listOrderItems(order.id());

        assertThat(kds.nextRoundForOrder(order.id())).isEqualTo(1);
        var r1 = kds.createTicket(
                merchantId, order.id(), null, DiningMode.DINE_IN, "Ama", "ALL",
                List.of(new KdsService.KdsItemInput(items.get(0).id(), "Tilapia", BigDecimal.ONE, null, "MAIN")),
                null, "pos:" + order.id() + ":r1", 1
        );
        assertThat(r1.round()).isEqualTo(1);
        assertThat(kds.nextRoundForOrder(order.id())).isEqualTo(2);

        var job1 = printing.claimNext(merchantId, "kds-1", branchA, "KITCHEN").orElseThrow();
        assertThat(job1.content()).doesNotContain("ADD-ON");
        printing.report(job1.id(), "kds-1", true, null, branchA);

        var r2 = kds.createTicket(
                merchantId, order.id(), null, DiningMode.DINE_IN, "Ama", "ALL",
                List.of(new KdsService.KdsItemInput(items.get(1).id(), "Kelewele", BigDecimal.ONE, null, "APPETIZER")),
                null, "pos:" + order.id() + ":r2", 2
        );
        assertThat(r2.round()).isEqualTo(2);
        assertThat(kds.nextRoundForOrder(order.id())).isEqualTo(3);

        var job2 = printing.claimNext(merchantId, "kds-1", branchA, "KITCHEN").orElseThrow();
        assertThat(job2.content()).contains("ADD-ON - ROUND 2");
        assertThat(job2.content()).contains("Kelewele");
    }

    @Test
    @DisplayName("Phase 3 contract: cancelOrderItem cancels only that KDS item, prints station-only VOID slip, and cancels ticket when empty")
    void cancelOrderItemPrintsStationOnlyVoidAndCancelsEmptyTicket() {
        var kitchen = stations.create(merchantId, new KitchenStationService.StationInput(
                "Kitchen", branchA, true, KitchenStationService.Output.PRINTER, "tcp://192.168.1.51:9100", 42));
        var bar = stations.create(merchantId, new KitchenStationService.StationInput(
                "Bar", branchA, false, KitchenStationService.Output.PRINTER, "tcp://192.168.1.52:9100", 42));

        var drinkCat = catalog.createCategory(merchantId, "Drinks");
        var drinkProd = catalog.createProduct(merchantId, drinkCat.id(), "Sobolo", null, null);
        var soboloVar = catalog.createVariant(merchantId, drinkProd.id(), "SOB-" + UUID.randomUUID().toString().substring(0, 6),
                null, "Glass", "{}", 500L, 1500L, false);
        stations.route(merchantId, bar.id(), drinkCat.id(), null, false);

        var order = orders.createOrder(
                merchantId, branchA, null, null, null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Banku", BigDecimal.ONE, 4000L, 0L, 0L, "[]", 1, "MAIN", null),
                        new PosOrderService.OrderItemInput(soboloVar.id(), "Sobolo", BigDecimal.ONE, 1500L, 0L, 0L, "[]", 1, "BEVERAGE", null)
                ),
                0L, 0L, 0L, "DINE_IN"
        );
        var orderItems = orders.listOrderItems(order.id());
        var bankuItem = orderItems.stream().filter(i -> i.itemName().equals("Banku")).findFirst().orElseThrow();
        var soboloItem = orderItems.stream().filter(i -> i.itemName().equals("Sobolo")).findFirst().orElseThrow();

        var ticket = kds.createTicket(
                merchantId, order.id(), null, DiningMode.DINE_IN, "Ama", "ALL",
                List.of(
                        new KdsService.KdsItemInput(bankuItem.id(), "Banku", BigDecimal.ONE, null, "MAIN"),
                        new KdsService.KdsItemInput(soboloItem.id(), "Sobolo", BigDecimal.ONE, null, "BEVERAGE")
                ),
                null, "pos:" + order.id() + ":r1", 1
        );

        // Drain the 2 NEW print jobs (Kitchen + Bar)
        for (int i = 0; i < 2; i++) {
            var j = printing.claimNext(merchantId, "kds-1", branchA, "KITCHEN").orElseThrow();
            printing.report(j.id(), "kds-1", true, null, branchA);
        }

        // Cancel only Sobolo
        kds.cancelOrderItem(merchantId, soboloItem.id(), "void-sobolo-1", "Customer changed mind");
        // Replay with same actionId must be idempotent
        kds.cancelOrderItem(merchantId, soboloItem.id(), "void-sobolo-1", "Customer changed mind");

        // Parent ticket stays NEW because Banku is still active
        assertThat(kds.getTicket(ticket.id()).orElseThrow().status()).isEqualTo("NEW");

        // Only Bar station gets a VOID slip containing Sobolo and the reason
        var barVoid = printing.claimNext(merchantId, "kds-1", branchA, "KITCHEN").orElseThrow();
        assertThat(barVoid.kind()).isEqualTo("VOID");
        assertThat(barVoid.stationId()).isEqualTo(bar.id());
        assertThat(barVoid.content()).contains("Sobolo").contains("Customer changed mind");
        assertThat(barVoid.content()).doesNotContain("Banku");
        printing.report(barVoid.id(), "kds-1", true, null, branchA);
        assertThat(printing.claimNext(merchantId, "kds-1", branchA, "KITCHEN")).isEmpty();

        // Now cancel Banku -> parent ticket becomes CANCELLED and Kitchen gets its VOID slip
        kds.cancelOrderItem(merchantId, bankuItem.id(), "void-banku-1", "Out of fish");
        assertThat(kds.getTicket(ticket.id()).orElseThrow().status()).isEqualTo("CANCELLED");

        var kitchenVoid = printing.claimNext(merchantId, "kds-1", branchA, "KITCHEN").orElseThrow();
        assertThat(kitchenVoid.kind()).isEqualTo("VOID");
        assertThat(kitchenVoid.stationId()).isEqualTo(kitchen.id());
        assertThat(kitchenVoid.content()).contains("Banku").contains("Out of fish");
    }

    @Test
    @DisplayName("Phase 8: Receipt printing via KdsPrintService, 32-col wrap, reprint banner, kind isolation & branch isolation")
    void receiptPrintingAndBranchIsolation() {
        var orderA = orders.createOrder(
                merchantId, branchA, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Assorted Fried Rice with Grilled Chicken",
                        new BigDecimal("2"), 5000L, 0L, 0L, "[]", 1, "MAIN", null)),
                500L, 1000L, 0L, "DINE_IN"
        );
        orders.addTenderPayment(orderA.id(), TenderType.CASH, orderA.totalPesewas(), "CASH-1");

        // Enqueue 32-column receipt
        var receiptJob = printing.enqueueReceipt(merchantId, orderA.id(), false, 32);
        assertThat(receiptJob.kind()).isEqualTo("RECEIPT");
        assertThat(receiptJob.orderId()).isEqualTo(orderA.id());
        assertThat(receiptJob.stationId()).isNull();
        assertThat(receiptJob.ticketId()).isNull();
        assertThat(receiptJob.paperWidth()).isEqualTo(32);
        assertThat(receiptJob.content()).contains("OSU GRILL").contains(orderA.orderNumber()).contains("CASH");
        assertThat(receiptJob.content()).doesNotContain("REPRINT");
        assertThat(receiptJob.content().lines()).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(32));

        // Kitchen claim must NOT pick up RECEIPT job
        assertThat(printing.claimNext(merchantId, "kds-tablet", branchA, "KITCHEN")).isEmpty();

        // Branch B cashier must NOT claim or report Branch A's receipt job
        assertThat(printing.claimNext(merchantId, "pos-branch-b", branchB, "RECEIPT")).isEmpty();
        assertThatThrownBy(() -> printing.report(receiptJob.id(), "pos-branch-b", true, null, branchB))
                .isInstanceOf(ApiException.class);

        // Branch A cashier claims and marks printed
        var claimed = printing.claimNext(merchantId, "pos-branch-a", branchA, "RECEIPT").orElseThrow();
        assertThat(claimed.id()).isEqualTo(receiptJob.id());
        var done = printing.report(claimed.id(), "pos-branch-a", true, null, branchA);
        assertThat(done.status()).isEqualTo("PRINTED");

        // Reprint includes *** REPRINT *** banner
        var reprintJob = printing.enqueueReceipt(merchantId, orderA.id(), true, 42);
        assertThat(reprintJob.content()).contains("*** REPRINT ***");
        assertThat(reprintJob.content().lines()).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(42));

        // PosDevicePaths allows receipt print & print-job claim/result, blocks KDS settings
        assertThat(PosDevicePaths.allowed("POST", "/v1/restaurant/kds/orders/" + orderA.id() + "/receipt/print")).isTrue();
        assertThat(PosDevicePaths.allowed("POST", "/v1/restaurant/kds/print-jobs/claim")).isTrue();
        assertThat(PosDevicePaths.allowed("POST", "/v1/restaurant/kds/print-jobs/" + receiptJob.id() + "/result")).isTrue();
        assertThat(PosDevicePaths.allowed("PUT", "/v1/restaurant/kds/settings/" + merchantId)).isFalse();
    }

    @Test
    @DisplayName("Phase 8: Branch-aware default stations, per-branch routing, and auto-ready fridge items")
    void branchAwareStationsAndAutoReadyRoutes() {
        // Both branches can have their own default station with the SAME name
        var kitchenA = stations.create(merchantId, new KitchenStationService.StationInput(
                "Kitchen", branchA, true, KitchenStationService.Output.SCREEN, null, null));
        var barA = stations.create(merchantId, new KitchenStationService.StationInput(
                "Bar", branchA, false, KitchenStationService.Output.SCREEN, null, null));
        var kitchenB = stations.create(merchantId, new KitchenStationService.StationInput(
                "Kitchen", branchB, true, KitchenStationService.Output.SCREEN, null, null));
        var barB = stations.create(merchantId, new KitchenStationService.StationInput(
                "Bar", branchB, false, KitchenStationService.Output.SCREEN, null, null));

        // Branch listing isolates stations per branch
        assertThat(stations.list(merchantId, branchA)).extracting(KitchenStationService.Station::id)
                .containsExactlyInAnyOrder(kitchenA.id(), barA.id());
        assertThat(stations.list(merchantId, branchB)).extracting(KitchenStationService.Station::id)
                .containsExactlyInAnyOrder(kitchenB.id(), barB.id());

        // Route Water category to Bar A (autoReady=true) in Branch A and Bar B (autoReady=false) in Branch B
        var drinksCat = catalog.createCategory(merchantId, "Cold Drinks");
        var waterProd = catalog.createProduct(merchantId, drinksCat.id(), "Voltic Water", null, null);
        var waterVar = catalog.createVariant(merchantId, waterProd.id(), "WAT-" + UUID.randomUUID().toString().substring(0, 6),
                null, "500ml", "{}", 200L, 500L, false);

        stations.route(merchantId, barA.id(), drinksCat.id(), null, true);
        stations.route(merchantId, barB.id(), drinksCat.id(), null, false);

        var resolvedA = stations.resolveRoute(merchantId, waterVar.id(), branchA);
        var resolvedB = stations.resolveRoute(merchantId, waterVar.id(), branchB);
        assertThat(resolvedA).isEqualTo(new KitchenStationService.ResolvedRoute(barA.id(), true));
        assertThat(resolvedB).isEqualTo(new KitchenStationService.ResolvedRoute(barB.id(), false));

        // All-auto-ready ticket in Branch A starts immediately READY with startedAt & readyAt stamped
        var waterOrderA = orders.createOrder(
                merchantId, branchA, null, null, null,
                List.of(new PosOrderService.OrderItemInput(waterVar.id(), "Voltic Water", BigDecimal.ONE, 500L, 0L, 0L, "[]", 1, "BEVERAGE", null)),
                0L, 0L, 0L, "DINE_IN"
        );
        var waterItemA = orders.listOrderItems(waterOrderA.id()).get(0);
        var autoReadyTicket = kds.createTicket(
                merchantId, waterOrderA.id(), null, DiningMode.DINE_IN, "Ama", "ALL",
                List.of(new KdsService.KdsItemInput(waterItemA.id(), "Voltic Water", BigDecimal.ONE, null, "BEVERAGE")),
                null, null, 1
        );
        assertThat(autoReadyTicket.status()).isEqualTo("READY");
        assertThat(autoReadyTicket.startedAt()).isNotNull();
        assertThat(autoReadyTicket.readyAt()).isNotNull();
        assertThat(kds.listTicketItems(autoReadyTicket.id())).singleElement().satisfies(i -> {
            assertThat(i.stationId()).isEqualTo(barA.id());
            assertThat(i.itemStatus()).isEqualTo("READY");
            assertThat(i.autoReady()).isTrue();
        });

        // Mixed ticket in Branch A (1 auto-ready water + 1 normal burger) stays NEW; water is READY, burger is NEW
        var mixedOrderA = orders.createOrder(
                merchantId, branchA, null, null, null,
                List.of(
                        new PosOrderService.OrderItemInput(waterVar.id(), "Voltic Water", BigDecimal.ONE, 500L, 0L, 0L, "[]", 1, "BEVERAGE", null),
                        new PosOrderService.OrderItemInput(null, "Beef Burger", BigDecimal.ONE, 5500L, 0L, 0L, "[]", 1, "MAIN", null)
                ),
                0L, 0L, 0L, "DINE_IN"
        );
        var mixedItems = orders.listOrderItems(mixedOrderA.id());
        var mixedTicket = kds.createTicket(
                merchantId, mixedOrderA.id(), null, DiningMode.DINE_IN, "Ama", "ALL",
                mixedItems.stream().map(i -> new KdsService.KdsItemInput(i.id(), i.itemName(), i.quantity(), null, i.course())).toList(),
                null, null, 1
        );
        assertThat(mixedTicket.status()).isEqualTo("NEW");
        var kdsItems = kds.listTicketItems(mixedTicket.id());
        assertThat(kdsItems).anySatisfy(i -> {
            assertThat(i.itemName()).isEqualTo("Voltic Water");
            assertThat(i.stationId()).isEqualTo(barA.id());
            assertThat(i.itemStatus()).isEqualTo("READY");
            assertThat(i.autoReady()).isTrue();
        });
        assertThat(kdsItems).anySatisfy(i -> {
            assertThat(i.itemName()).isEqualTo("Beef Burger");
            assertThat(i.stationId()).isEqualTo(kitchenA.id());
            assertThat(i.itemStatus()).isEqualTo("NEW");
            assertThat(i.autoReady()).isFalse();
        });
    }

    @Test
    @DisplayName("Regression: enqueueReceipt excludes soft-voided items and refunded/failed tender payments")
    void enqueueReceiptExcludesVoidedItemsAndNonSuccessPayments() {
        var order = orders.createOrder(
                merchantId, branchA, null, null, null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Active Jollof", BigDecimal.ONE, 5000L, 0L, 0L, "[]", 1, "MAIN", null),
                        new PosOrderService.OrderItemInput(null, "Voided Burger", BigDecimal.ONE, 3000L, 0L, 0L, "[]", 1, "MAIN", null)
                ),
                0L, 0L, 0L, "DINE_IN"
        );
        // Soft-void the second item and adjust order totals
        db.sql("UPDATE pos_order_item SET voided = true WHERE order_id = :o AND item_name = 'Voided Burger'")
                .param("o", order.id()).update();
        db.sql("UPDATE pos_order SET subtotal_pesewas = 5000, total_pesewas = 5000 WHERE id = :o")
                .param("o", order.id()).update();

        // Record one SUCCESS payment plus REFUNDED and FAILED payments
        orders.addTenderPayment(order.id(), com.elepay.merchanttools.pos.TenderType.CASH, 5000L, "CASH-OK");
        db.sql("""
            INSERT INTO pos_tender_payment (id, order_id, tender_type, amount_pesewas, status, reference)
            VALUES (:id1, :orderId, 'CASH', 3000, 'REFUNDED', 'CASH-REFUND'),
                   (:id2, :orderId, 'MOMO', 5000, 'FAILED', 'MOMO-FAIL')
            """)
                .param("id1", UUID.randomUUID())
                .param("id2", UUID.randomUUID())
                .param("orderId", order.id())
                .update();

        var job = printing.enqueueReceipt(merchantId, order.id(), false, 42);
        assertThat(job.content()).contains("Active Jollof");
        assertThat(job.content()).doesNotContain("Voided Burger");
        assertThat(job.content()).contains("CASH");
        assertThat(job.content()).doesNotContain("MOMO");
        assertThat(job.content()).doesNotContain("CASH-REFUND");
        assertThat(job.content()).doesNotContain("MOMO-FAIL");
    }
}
