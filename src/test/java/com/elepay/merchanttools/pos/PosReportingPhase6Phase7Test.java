package com.elepay.merchanttools.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.ApiException;
import com.elepay.identity.ApiKeyAuthentication;
import com.elepay.merchanttools.restaurant.DiningMode;
import com.elepay.merchanttools.restaurant.KdsService;
import com.elepay.merchanttools.restaurant.RestaurantTableService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;

class PosReportingPhase6Phase7Test extends PostgresTestBase {

    @LocalServerPort int port;

    @Autowired JdbcClient db;
    @Autowired PosOrderService orderService;
    @Autowired PosTerminalService terminalService;
    @Autowired PosCashierService cashierService;
    @Autowired RestaurantTableService tableService;
    @Autowired KdsService kdsService;
    @Autowired PosReportingController reportingController;

    private TestApi api;
    private String opsToken;
    private String ownerToken;
    private String otherOwnerToken;
    private String branchACashierToken;
    private String branchBCashierToken;
    private String waiterToken;

    private UUID merchantId;
    private UUID otherMerchantId;
    private UUID branchAId;
    private UUID branchBId;
    private UUID otherMerchantBranchId;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        opsToken = api.token("ops-rep-" + suffix, "ops");
        ownerToken = api.token("owner-rep-" + suffix);
        otherOwnerToken = api.token("other-owner-rep-" + suffix);
        String cashierASub = "cashier-rep-a-" + suffix;
        String cashierBSub = "cashier-rep-b-" + suffix;
        String waiterSub = "waiter-rep-" + suffix;
        branchACashierToken = api.token(cashierASub);
        branchBCashierToken = api.token(cashierBSub);
        waiterToken = api.token(waiterSub);

        merchantId = createApprovedMerchant(ownerToken, "Reporting Grill " + suffix);
        otherMerchantId = createApprovedMerchant(otherOwnerToken, "Other Bistro " + suffix);

        branchAId = createBranch(merchantId, ownerToken, "Osu Branch");
        branchBId = createBranch(merchantId, ownerToken, "East Legon Branch");
        otherMerchantBranchId = createBranch(otherMerchantId, otherOwnerToken, "Foreign Branch");

        assertThat(api.call("PUT", "/v1/merchants/" + merchantId + "/members/" + cashierASub, ownerToken, null,
                Map.of("role", "cashier", "branchId", branchAId.toString())).status()).isEqualTo(200);
        assertThat(api.call("PUT", "/v1/merchants/" + merchantId + "/members/" + cashierBSub, ownerToken, null,
                Map.of("role", "cashier", "branchId", branchBId.toString())).status()).isEqualTo(200);
        assertThat(api.call("PUT", "/v1/merchants/" + merchantId + "/members/" + waiterSub, ownerToken, null,
                Map.of("role", "waiter", "branchId", branchAId.toString())).status()).isEqualTo(200);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Phase 6: GET /v1/pos/orders returns enriched live orders, supports filters, and cursor-pages cleanly with tie-breaking")
    void liveOrdersEnrichmentFiltersAndCursorPagination() {
        var cashier = cashierService.registerCashier(
                merchantId, "Ama Server", "1234", "BG-" + UUID.randomUUID().toString().substring(0, 6));
        var floorPlan = tableService.createFloorPlan(merchantId, "Main Floor", "{}");
        var table12 = tableService.createTable(merchantId, floorPlan.id(), "T12", 4);

        // 1. DINE_IN PENDING order on table 12 with 2 KDS tickets (one NEW, one READY => PREPARING)
        var dineInOrder = orderService.createOrder(
                merchantId, branchAId, null, cashier.id(), null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Jollof Rice", BigDecimal.TWO, 3500L, 0L, 0L, "[]", 1, "MAIN", null),
                        new PosOrderService.OrderItemInput(null, "Sobolo", BigDecimal.ONE, 1500L, 0L, 0L, "[]", 1, "DRINK", null)
                ),
                0L, 0L, 0L, "DINE_IN");
        db.sql("UPDATE restaurant_table SET current_order_id = :orderId, status = 'OCCUPIED' WHERE id = :tableId")
                .param("orderId", dineInOrder.id())
                .param("tableId", table12.id())
                .update();
        var dineInItems = orderService.listOrderItems(dineInOrder.id());
        var jollofItem = dineInItems.stream().filter(i -> "Jollof Rice".equals(i.itemName())).findFirst().orElseThrow();
        var soboloItem = dineInItems.stream().filter(i -> "Sobolo".equals(i.itemName())).findFirst().orElseThrow();
        kdsService.createTicket(merchantId, dineInOrder.id(), table12.id(), DiningMode.DINE_IN, "Ama Server",
                "MAIN", List.of(new KdsService.KdsItemInput(jollofItem.id(), "Jollof Rice", BigDecimal.TWO, "[]", "MAIN")),
                null, "rep-t1-" + dineInOrder.id());
        var t2 = kdsService.createTicket(merchantId, dineInOrder.id(), table12.id(), DiningMode.DINE_IN, "Ama Server",
                "DRINK", List.of(new KdsService.KdsItemInput(soboloItem.id(), "Sobolo", BigDecimal.ONE, "[]", "DRINK")),
                null, "rep-t2-" + dineInOrder.id());
        kdsService.advanceTicket(t2.id(), "NEW", "start-" + t2.id());
        kdsService.advanceTicket(t2.id(), "PREPARING", "ready-" + t2.id());

        // 2. TAKEAWAY PARTIAL order with no KDS tickets (kitchenStatus = NONE)
        var takeawayOrder = orderService.createOrder(
                merchantId, branchAId, null, cashier.id(), null,
                List.of(new PosOrderService.OrderItemInput(null, "Banku & Tilapia", BigDecimal.ONE, 6000L, 0L, 0L, "[]", null, "MAIN", null)),
                0L, 0L, 0L, "TAKEAWAY");
        orderService.addTenderPayment(takeawayOrder.id(), TenderType.CASH, 2000L, "CASH-PART-1");

        // 3. DELIVERY PAID order with SERVED ticket
        var deliveryOrder = orderService.createOrder(
                merchantId, branchAId, null, cashier.id(), null,
                List.of(new PosOrderService.OrderItemInput(null, "Waakye Special", BigDecimal.ONE, 4000L, 0L, 0L, "[]", null, "MAIN", null)),
                0L, 0L, 0L, "DELIVERY");
        orderService.addTenderPayment(deliveryOrder.id(), TenderType.CASH, 4000L, "CASH-PAID-1");
        List<UUID> deliveryTickets = db.sql("SELECT id FROM restaurant_kds_ticket WHERE order_id = :id")
                .param("id", deliveryOrder.id())
                .query(UUID.class)
                .list();
        if (deliveryTickets.isEmpty()) {
            var deliveryItem = orderService.listOrderItems(deliveryOrder.id()).getFirst();
            var t3 = kdsService.createTicket(merchantId, deliveryOrder.id(), null, DiningMode.DELIVERY, "Kofi Rider",
                    "MAIN", List.of(new KdsService.KdsItemInput(deliveryItem.id(), "Waakye Special", BigDecimal.ONE, "[]", "MAIN")),
                    null, "rep-t3-" + deliveryOrder.id());
            deliveryTickets = List.of(t3.id());
        }
        for (UUID tid : deliveryTickets) {
            kdsService.advanceTicket(tid, "NEW", "start-" + tid);
            kdsService.advanceTicket(tid, "PREPARING", "ready-" + tid);
            kdsService.advanceTicket(tid, "READY", "serve-" + tid);
        }

        // 4. VOIDED order on Branch B
        var voidedOrder = orderService.createOrder(
                merchantId, branchBId, null, cashier.id(), null,
                List.of(new PosOrderService.OrderItemInput(null, "Kelewele", BigDecimal.ONE, 2500L, 0L, 0L, "[]", null, "STARTER", null)),
                0L, 0L, 0L, "TAKEAWAY");
        orderService.voidOrder(voidedOrder.id(), "owner");

        // Force identical created_at timestamps on two orders to prove UUID tie-breaking in keyset pagination
        OffsetDateTime sharedTs = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        db.sql("UPDATE pos_order SET created_at = :ts WHERE id IN (:id1, :id2)")
                .param("ts", sharedTs)
                .param("id1", dineInOrder.id())
                .param("id2", takeawayOrder.id())
                .update();

        // Cursor-paginate across all 4 orders with limit=2
        List<UUID> seenIds = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String path = "/v1/pos/orders?merchantId=" + merchantId + "&limit=2"
                    + (cursor != null ? "&cursor=" + cursor : "");
            var resp = api.call("GET", path, ownerToken, null, null);
            assertThat(resp.status()).isEqualTo(200);
            JsonNode orders = resp.body().get("orders");
            for (JsonNode o : orders) {
                seenIds.add(UUID.fromString(o.get("id").asString()));
            }
            boolean hasMore = resp.body().get("hasMore").asBoolean();
            cursor = hasMore ? resp.body().get("nextCursor").asString() : null;
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seenIds).hasSize(4);
        assertThat(new HashSet<>(seenIds)).containsExactlyInAnyOrder(
                dineInOrder.id(), takeawayOrder.id(), deliveryOrder.id(), voidedOrder.id());

        // Verify enrichment fields on the full list
        var fullResp = api.call("GET", "/v1/pos/orders?merchantId=" + merchantId, ownerToken, null, null);
        assertThat(fullResp.status()).isEqualTo(200);
        JsonNode dineInNode = findById(fullResp.body().get("orders"), dineInOrder.id());
        assertThat(dineInNode.get("orderType").asString()).isEqualTo("DINE_IN");
        assertThat(dineInNode.get("tableNumber").asString()).isEqualTo("T12");
        assertThat(dineInNode.get("serverName").asString()).isEqualTo("Ama Server");
        assertThat(dineInNode.get("itemCount").asInt()).isEqualTo(2);
        assertThat(dineInNode.get("totalPesewas").asLong()).isEqualTo(8500L);
        assertThat(dineInNode.get("paidPesewas").asLong()).isEqualTo(0L);
        assertThat(dineInNode.get("balancePesewas").asLong()).isEqualTo(8500L);
        assertThat(dineInNode.get("paymentStatus").asString()).isEqualTo("PENDING");
        assertThat(dineInNode.get("kitchenStatus").asString()).isEqualTo("PREPARING");
        assertThat(dineInNode.get("kdsTicketCount").asInt()).isEqualTo(2);
        assertThat(dineInNode.get("ageSeconds").asLong()).isGreaterThanOrEqualTo(290L);

        JsonNode takeawayNode = findById(fullResp.body().get("orders"), takeawayOrder.id());
        assertThat(takeawayNode.get("paymentStatus").asString()).isEqualTo("PARTIAL");
        assertThat(takeawayNode.get("paidPesewas").asLong()).isEqualTo(2000L);
        assertThat(takeawayNode.get("balancePesewas").asLong()).isEqualTo(4000L);
        assertThat(takeawayNode.get("kitchenStatus").asString()).isEqualTo("NONE");

        JsonNode deliveryNode = findById(fullResp.body().get("orders"), deliveryOrder.id());
        assertThat(deliveryNode.get("paymentStatus").asString()).isEqualTo("PAID");
        assertThat(deliveryNode.get("kitchenStatus").asString()).isEqualTo("SERVED");

        // Verify OPEN status filter (PENDING + PARTIAL only)
        var openResp = api.call("GET", "/v1/pos/orders?merchantId=" + merchantId + "&status=OPEN", ownerToken, null, null);
        assertThat(openResp.status()).isEqualTo(200);
        assertThat(openResp.body().get("orders")).hasSize(2);

        // Verify type filter
        var dineInOnly = api.call("GET", "/v1/pos/orders?merchantId=" + merchantId + "&type=DINE_IN", ownerToken, null, null);
        assertThat(dineInOnly.status()).isEqualTo(200);
        assertThat(dineInOnly.body().get("orders")).hasSize(1);
        assertThat(dineInOnly.body().get("orders").get(0).get("id").asString()).isEqualTo(dineInOrder.id().toString());

        // Verify invalid cursor & invalid status return 400
        assertThat(api.call("GET", "/v1/pos/orders?merchantId=" + merchantId + "&cursor=not-valid-base64!!!", ownerToken, null, null).status())
                .isEqualTo(400);
        assertThat(api.call("GET", "/v1/pos/orders?merchantId=" + merchantId + "&status=BOGUS", ownerToken, null, null).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("Phase 7: GET /v1/pos/reports/daily computes Accra business-day sales, tender mix, order types, top 10 items, KDS timing, voids, and shift cash")
    void dailyReportAggregatesAllMetricsAndRespectsAccraBusinessDay() {
        LocalDate reportDate = LocalDate.of(2026, 6, 15);
        OffsetDateTime middayAccra = OffsetDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endOfDayAccra = OffsetDateTime.of(2026, 6, 15, 23, 59, 59, 0, ZoneOffset.UTC);
        OffsetDateTime nextDayMidnightAccra = OffsetDateTime.of(2026, 6, 16, 0, 0, 0, 0, ZoneOffset.UTC);

        var terminalA = terminalService.registerTerminal(
                merchantId, branchAId, "SN-RPTA-" + UUID.randomUUID().toString().substring(0, 8), "PAX A920");
        var terminalB = terminalService.registerTerminal(
                merchantId, branchBId, "SN-RPTB-" + UUID.randomUUID().toString().substring(0, 8), "PAX A920");
        var cashier = cashierService.registerCashier(
                merchantId, "Kwame Cashier", "1234", "BG-" + UUID.randomUUID().toString().substring(0, 6));

        // Open and close a shift on Branch A (opening 10,000 + 5,000 cash = 15,000 expected; counted 14,700 => -300 variance)
        var shiftA = cashierService.openShift(terminalA.id(), cashier.id(), merchantId, 10_000L, "Morning");
        cashierService.recordCashTender(shiftA.id(), 5_000L);
        cashierService.closeShift(shiftA.id(), 14_700L, "Shortage");
        db.sql("UPDATE pos_shift SET shift_start = :ts, shift_end = :endTs WHERE id = :id")
                .param("ts", middayAccra.minusHours(4))
                .param("endTs", middayAccra.plusHours(4))
                .param("id", shiftA.id())
                .update();

        // Open shift on Branch B (opening 20,000, still OPEN)
        var shiftB = cashierService.openShift(terminalB.id(), cashier.id(), merchantId, 20_000L, "Evening");
        db.sql("UPDATE pos_shift SET shift_start = :ts WHERE id = :id")
                .param("ts", middayAccra)
                .param("id", shiftB.id())
                .update();

        // Order 1 (Branch A, DINE_IN, PAID via CASH): subtotal 10,000, discount 1,000, tax 500, tip 300 => total 9,800
        var order1 = orderService.createOrder(
                merchantId, branchAId, terminalA.id(), cashier.id(), null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Grilled Tilapia", BigDecimal.ONE, 8_000L, 0L, 0L, "[]", 1, "MAIN", null),
                        new PosOrderService.OrderItemInput(null, "Water Bottle", BigDecimal.TWO, 1_000L, 0L, 0L, "[]", 1, "DRINK", null)
                ),
                1_000L, 500L, 300L, "DINE_IN");
        orderService.addTenderPayment(order1.id(), TenderType.CASH, 9_800L, "CASH-R1");
        setOrderAndPaymentTimestamp(order1.id(), middayAccra);

        // Order 2 (Branch A, TAKEAWAY, PAID via MOMO at 23:59:59Z — still belongs to 2026-06-15!)
        var order2 = orderService.createOrder(
                merchantId, branchAId, terminalA.id(), cashier.id(), null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Water Bottle", BigDecimal.valueOf(10), 500L, 0L, 0L, "[]", null, "DRINK", null)
                ),
                0L, 0L, 0L, "TAKEAWAY");
        insertDigitalPayment(order2.id(), "MOMO", 5_000L, "MOMO-R2", endOfDayAccra);
        db.sql("UPDATE pos_order SET paid_pesewas = 5000, status = 'PAID', created_at = :ts WHERE id = :id")
                .param("ts", endOfDayAccra)
                .param("id", order2.id())
                .update();

        // Order 3 (Branch B, DELIVERY, PAID via CARD) — tests all-branches vs single-branch aggregation
        var order3 = orderService.createOrder(
                merchantId, branchBId, terminalB.id(), cashier.id(), null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Lobster Platter", BigDecimal.ONE, 25_000L, 0L, 0L, "[]", null, "MAIN", null)
                ),
                0L, 0L, 0L, "DELIVERY");
        insertDigitalPayment(order3.id(), "CARD", 25_000L, "CARD-R3", middayAccra);
        db.sql("UPDATE pos_order SET paid_pesewas = 25000, status = 'PAID', created_at = :ts WHERE id = :id")
                .param("ts", middayAccra)
                .param("id", order3.id())
                .update();

        // Order 4 (Branch A, VOIDED)
        var voidedOrder = orderService.createOrder(
                merchantId, branchAId, terminalA.id(), cashier.id(), null,
                List.of(new PosOrderService.OrderItemInput(null, "Cancelled Dish", BigDecimal.ONE, 3_000L, 0L, 0L, "[]", null, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN");
        orderService.voidOrder(voidedOrder.id(), "owner");
        db.sql("UPDATE pos_order SET created_at = :ts WHERE id = :id")
                .param("ts", middayAccra)
                .param("id", voidedOrder.id())
                .update();

        // Order 5 (Next Accra business day: 2026-06-16T00:00:00Z — MUST NOT appear in 2026-06-15 report!)
        var nextDayOrder = orderService.createOrder(
                merchantId, branchAId, terminalA.id(), cashier.id(), null,
                List.of(new PosOrderService.OrderItemInput(null, "Midnight Snack", BigDecimal.ONE, 9_999L, 0L, 0L, "[]", null, "TAKEAWAY", null)),
                0L, 0L, 0L, "TAKEAWAY");
        orderService.addTenderPayment(nextDayOrder.id(), TenderType.CASH, 9_999L, "CASH-NEXT");
        setOrderAndPaymentTimestamp(nextDayOrder.id(), nextDayMidnightAccra);

        // Seed 2 KDS tickets on Branch A with exact timestamps:
        // Ticket 1: fired -> ready = 300s, ready -> served = 120s
        // Ticket 2: fired -> ready = 600s, ready -> served = 180s
        // Averages: fired->ready = 450s, ready->served = 150s
        var order1Item = orderService.listOrderItems(order1.id()).stream()
                .filter(i -> "Grilled Tilapia".equals(i.itemName())).findFirst().orElseThrow();
        var order2Item = orderService.listOrderItems(order2.id()).stream()
                .filter(i -> "Water Bottle".equals(i.itemName())).findFirst().orElseThrow();
        var kt1 = kdsService.createTicket(merchantId, order1.id(), null, DiningMode.DINE_IN, "Ama", "MAIN",
                List.of(new KdsService.KdsItemInput(order1Item.id(), "Grilled Tilapia", BigDecimal.ONE, "[]", "MAIN")),
                null, "rpt-kds-1-" + order1.id());
        var kt2 = kdsService.createTicket(merchantId, order2.id(), null, DiningMode.TAKEOUT, "Ama", "DRINK",
                List.of(new KdsService.KdsItemInput(order2Item.id(), "Water Bottle", BigDecimal.TEN, "[]", "DRINK")),
                null, "rpt-kds-2-" + order2.id());
        db.sql("UPDATE restaurant_kds_ticket SET created_at = :c, ready_at = :r, served_at = :s, status = 'SERVED' WHERE id = :id")
                .param("c", middayAccra)
                .param("r", middayAccra.plusSeconds(300))
                .param("s", middayAccra.plusSeconds(420))
                .param("id", kt1.id())
                .update();
        db.sql("UPDATE restaurant_kds_ticket SET created_at = :c, ready_at = :r, served_at = :s, status = 'SERVED' WHERE id = :id")
                .param("c", middayAccra)
                .param("r", middayAccra.plusSeconds(600))
                .param("s", middayAccra.plusSeconds(780))
                .param("id", kt2.id())
                .update();

        // 1. Owner requests ALL branches for 2026-06-15
        var allResp = api.call("GET",
                "/v1/pos/reports/daily?merchantId=" + merchantId + "&date=" + reportDate,
                ownerToken, null, null);
        assertThat(allResp.status()).isEqualTo(200);
        JsonNode body = allResp.body();
        assertThat(body.get("businessDate").asString()).isEqualTo("2026-06-15");
        assertThat(body.get("timezone").asString()).isEqualTo("Africa/Accra");
        assertThat(body.get("branchId").isNull()).isTrue();

        JsonNode sales = body.get("sales");
        // Gross = 10,000 + 5,000 + 25,000 = 40,000 (excludes VOIDED 3,000 and next-day 9,999)
        assertThat(sales.get("grossSalesPesewas").asLong()).isEqualTo(40_000L);
        assertThat(sales.get("discountsPesewas").asLong()).isEqualTo(1_000L);
        assertThat(sales.get("netSalesPesewas").asLong()).isEqualTo(39_000L);
        assertThat(sales.get("taxPesewas").asLong()).isEqualTo(500L);
        assertThat(sales.get("tipsPesewas").asLong()).isEqualTo(300L);
        assertThat(sales.get("voidsPesewas").asLong()).isEqualTo(3_000L);
        assertThat(sales.get("totalCollectedPesewas").asLong()).isEqualTo(39_800L);
        assertThat(sales.get("orderCount").asInt()).isEqualTo(3);
        assertThat(sales.get("paidOrderCount").asInt()).isEqualTo(3);
        assertThat(sales.get("voidedOrderCount").asInt()).isEqualTo(1);

        // Tender mix: CARD 25,000, CASH 9,800, MOMO 5,000
        JsonNode tenders = body.get("tenderMix");
        assertThat(tenders).hasSize(3);
        assertThat(tenders.get(0).get("tenderType").asString()).isEqualTo("CARD");
        assertThat(tenders.get(0).get("amountPesewas").asLong()).isEqualTo(25_000L);
        assertThat(tenders.get(1).get("tenderType").asString()).isEqualTo("CASH");
        assertThat(tenders.get(1).get("amountPesewas").asLong()).isEqualTo(9_800L);
        assertThat(tenders.get(2).get("tenderType").asString()).isEqualTo("MOMO");
        assertThat(tenders.get(2).get("amountPesewas").asLong()).isEqualTo(5_000L);

        // Top items: Water Bottle wins by quantity (12), Lobster Platter wins by revenue (25,000)
        assertThat(body.get("topItemsByQuantity").get(0).get("itemName").asString()).isEqualTo("Water Bottle");
        assertThat(body.get("topItemsByRevenue").get(0).get("itemName").asString()).isEqualTo("Lobster Platter");

        // Kitchen timing averages
        JsonNode timing = body.get("kitchenTiming");
        assertThat(timing.get("ticketCount").asInt()).isEqualTo(2);
        assertThat(timing.get("avgFiredToReadySeconds").asLong()).isEqualTo(450L);
        assertThat(timing.get("avgReadyToServedSeconds").asLong()).isEqualTo(150L);

        // Voids list
        assertThat(body.get("voids")).hasSize(1);
        assertThat(body.get("voids").get(0).get("amountPesewas").asLong()).isEqualTo(3_000L);

        // Shift cash across both branches: expected = 15,000 + 20,000 = 35,000; counted = 14,700; variance = -300
        JsonNode shiftCash = body.get("shiftCash");
        assertThat(shiftCash.get("totalExpectedCashPesewas").asLong()).isEqualTo(35_000L);
        assertThat(shiftCash.get("totalCountedCashPesewas").asLong()).isEqualTo(14_700L);
        assertThat(shiftCash.get("totalVariancePesewas").asLong()).isEqualTo(-300L);
        assertThat(shiftCash.get("openShiftCount").asInt()).isEqualTo(1);
        assertThat(shiftCash.get("closedShiftCount").asInt()).isEqualTo(1);

        // 2. Owner requests ONLY Branch A for 2026-06-15 -> excludes Branch B's 25,000 CARD order and 20,000 shift
        var branchAResp = api.call("GET",
                "/v1/pos/reports/daily?merchantId=" + merchantId + "&branchId=" + branchAId + "&date=" + reportDate,
                ownerToken, null, null);
        assertThat(branchAResp.status()).isEqualTo(200);
        assertThat(branchAResp.body().get("branchId").asString()).isEqualTo(branchAId.toString());
        assertThat(branchAResp.body().get("sales").get("grossSalesPesewas").asLong()).isEqualTo(15_000L);
        assertThat(branchAResp.body().get("shiftCash").get("totalExpectedCashPesewas").asLong()).isEqualTo(15_000L);
        assertThat(branchAResp.body().get("shiftCash").get("openShiftCount").asInt()).isEqualTo(0);

        // 3. Next day (2026-06-16) contains ONLY the 00:00:00Z order
        var nextDayResp = api.call("GET",
                "/v1/pos/reports/daily?merchantId=" + merchantId + "&date=2026-06-16",
                ownerToken, null, null);
        assertThat(nextDayResp.status()).isEqualTo(200);
        assertThat(nextDayResp.body().get("sales").get("grossSalesPesewas").asLong()).isEqualTo(9_999L);
        assertThat(nextDayResp.body().get("sales").get("orderCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Adversarial: Branch-scoped cashiers, bound POS terminals, waiters, and cross-merchant callers are strictly isolated")
    void adversarialBranchScopeAndRbacIsolation() {
        LocalDate today = LocalDate.now(PosDailyReportService.ACCRA);

        // Seed one order in Branch A (1,200p) and one order in Branch B (7,700p)
        var orderA = orderService.createOrder(
                merchantId, branchAId, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Branch A Item", BigDecimal.ONE, 1_200L, 0L, 0L, "[]", null, "MAIN", null)),
                0L, 0L, 0L, "TAKEAWAY");
        orderService.addTenderPayment(orderA.id(), TenderType.CASH, 1_200L, "CASH-A");

        var orderB = orderService.createOrder(
                merchantId, branchBId, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Branch B Item", BigDecimal.ONE, 7_700L, 0L, 0L, "[]", null, "TAKEAWAY", null)),
                0L, 0L, 0L, "TAKEAWAY");
        orderService.addTenderPayment(orderB.id(), TenderType.CASH, 7_700L, "CASH-B");

        // 1. Branch A cashier requesting Branch B orders -> 404
        assertThat(api.call("GET",
                "/v1/pos/orders?merchantId=" + merchantId + "&branchId=" + branchBId,
                branchACashierToken, null, null).status()).isEqualTo(404);

        // 2. Branch A cashier omitting branchId -> receives ONLY Branch A order (zero Branch B leakage)
        var cashierAOrders = api.call("GET",
                "/v1/pos/orders?merchantId=" + merchantId,
                branchACashierToken, null, null);
        assertThat(cashierAOrders.status()).isEqualTo(200);
        assertThat(cashierAOrders.body().get("orders")).hasSize(1);
        assertThat(cashierAOrders.body().get("orders").get(0).get("id").asString()).isEqualTo(orderA.id().toString());

        // 3. Branch A cashier requesting Branch B daily report -> 404
        assertThat(api.call("GET",
                "/v1/pos/reports/daily?merchantId=" + merchantId + "&branchId=" + branchBId + "&date=" + today,
                branchACashierToken, null, null).status()).isEqualTo(404);

        // 4. Branch A cashier omitting branchId on daily report -> strictly scoped to Branch A (1,200p, not 8,900p)
        var cashierAReport = api.call("GET",
                "/v1/pos/reports/daily?merchantId=" + merchantId + "&date=" + today,
                branchACashierToken, null, null);
        assertThat(cashierAReport.status()).isEqualTo(200);
        assertThat(cashierAReport.body().get("branchId").asString()).isEqualTo(branchAId.toString());
        assertThat(cashierAReport.body().get("sales").get("grossSalesPesewas").asLong()).isEqualTo(1_200L);

        // 5. Branch B cashier omitting branchId on daily report -> strictly scoped to Branch B (7,700p)
        var cashierBReport = api.call("GET",
                "/v1/pos/reports/daily?merchantId=" + merchantId + "&date=" + today,
                branchBCashierToken, null, null);
        assertThat(cashierBReport.status()).isEqualTo(200);
        assertThat(cashierBReport.body().get("branchId").asString()).isEqualTo(branchBId.toString());
        assertThat(cashierBReport.body().get("sales").get("grossSalesPesewas").asLong()).isEqualTo(7_700L);

        // 6. Waiter lacks VIEW permission -> 403 on both endpoints
        assertThat(api.call("GET", "/v1/pos/orders?merchantId=" + merchantId, waiterToken, null, null).status())
                .isEqualTo(403);
        assertThat(api.call("GET", "/v1/pos/reports/daily?merchantId=" + merchantId + "&date=" + today, waiterToken, null, null).status())
                .isEqualTo(403);

        // 7. Cross-merchant caller cannot read merchantId's orders or daily report
        int crossMerchantOrders = api.call("GET", "/v1/pos/orders?merchantId=" + merchantId, otherOwnerToken, null, null).status();
        int crossMerchantReport = api.call("GET", "/v1/pos/reports/daily?merchantId=" + merchantId + "&date=" + today, otherOwnerToken, null, null).status();
        assertThat(crossMerchantOrders).isIn(403, 404);
        assertThat(crossMerchantReport).isIn(403, 404);

        // 8. Owner passing a branchId belonging to another merchant -> 404
        assertThat(api.call("GET",
                "/v1/pos/orders?merchantId=" + merchantId + "&branchId=" + otherMerchantBranchId,
                ownerToken, null, null).status()).isEqualTo(404);
        assertThat(api.call("GET",
                "/v1/pos/reports/daily?merchantId=" + merchantId + "&branchId=" + otherMerchantBranchId + "&date=" + today,
                ownerToken, null, null).status()).isEqualTo(404);

        // 9. Paired POS terminal bound to Branch A cannot query Branch B (even when merchantId is omitted)
        var regA = terminalService.registerTerminal(
                merchantId, branchAId, "SN-BNDA-" + UUID.randomUUID().toString().substring(0, 8), "PAX A920");
        var pairedA = terminalService.pairTerminal(regA.serialNumber(), regA.pairingCode(), "HW-FP-BNDA");
        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication(
                pairedA.credentialId(), merchantId, Set.of("collect", "view"),
                pairedA.signingSecret(), "pos_terminal", List.of()));

        var termPage = reportingController.listOrders(null, null, null, null, null, null, null);
        assertThat(termPage.orders()).hasSize(1);
        assertThat(termPage.orders().get(0).id()).isEqualTo(orderA.id());

        assertThatThrownBy(() -> reportingController.listOrders(null, branchBId, null, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> reportingController.dailyReport(null, branchBId, today))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private UUID createApprovedMerchant(String token, String name) {
        var created = api.call("POST", "/v1/merchants", token, null, Map.of(
                "legalName", name,
                "tradingName", name,
                "kind", "merchant",
                "contactPhone", TestApi.uniquePhone()
        ));
        assertThat(created.status()).isEqualTo(200);
        UUID id = UUID.fromString(created.body().get("id").asString());
        var approved = api.call("POST", "/v1/ops/merchants/" + id + "/review", opsToken, null,
                Map.of("decision", "approve"));
        assertThat(approved.status()).isEqualTo(200);
        return id;
    }

    private UUID createBranch(UUID mId, String token, String name) {
        var resp = api.call("POST", "/v1/merchants/" + mId + "/branches", token, null, Map.of("name", name));
        assertThat(resp.status()).isEqualTo(200);
        return UUID.fromString(resp.body().get("id").asString());
    }

    private void setOrderAndPaymentTimestamp(UUID orderId, OffsetDateTime ts) {
        db.sql("UPDATE pos_order SET created_at = :ts WHERE id = :id")
                .param("ts", ts)
                .param("id", orderId)
                .update();
        db.sql("UPDATE pos_tender_payment SET created_at = :ts WHERE order_id = :id")
                .param("ts", ts)
                .param("id", orderId)
                .update();
    }

    private void insertDigitalPayment(UUID orderId, String tenderType, long amountPesewas, String ref, OffsetDateTime ts) {
        db.sql("""
                INSERT INTO pos_tender_payment (id, order_id, tender_type, amount_pesewas, reference, status, created_at)
                VALUES (:id, :orderId, :tenderType, :amount, :ref, 'SUCCESS', :ts)
                """)
                .param("id", UUID.randomUUID())
                .param("orderId", orderId)
                .param("tenderType", tenderType)
                .param("amount", amountPesewas)
                .param("ref", ref)
                .param("ts", ts)
                .update();
    }

    private static JsonNode findById(JsonNode array, UUID id) {
        for (JsonNode n : array) {
            if (id.toString().equals(n.get("id").asString())) {
                return n;
            }
        }
        throw new AssertionError("Order not found in response: " + id);
    }
}
