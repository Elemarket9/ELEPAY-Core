package com.elepay.merchanttools.pos;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.ApiException;
import com.elepay.merchanttools.restaurant.KdsService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PosPhase2Phase3Test extends PostgresTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private PosTerminalService terminalService;

    @Autowired
    private PosCashierService cashierService;

    @Autowired
    private PosOrderService orderService;

    @Autowired
    private PosVoidDiscountService voidDiscountService;

    @Autowired
    private KdsService kdsService;

    @Autowired
    private JdbcClient db;

    private UUID merchantId;
    private UUID terminalId;
    private PosCashierService.Cashier cashier;
    private PosCashierService.Cashier manager;

    @BeforeEach
    void setup() {
        merchantId = UUID.randomUUID();
        db.sql("""
            INSERT INTO merchant (
                id, legal_name, trading_name, kind, status, contact_phone,
                merchant_code, fee_bps, fee_fixed, settlement_delay_hours, created_by
            ) VALUES (
                :id, 'Phase23 Bistro Ltd', 'Phase23 Bistro', 'merchant', 'active', '0241112233',
                :code, 0, 0, 24, 'system'
            ) ON CONFLICT DO NOTHING
            """)
            .param("id", merchantId)
            .param("code", String.format("%06d", (int) (Math.random() * 900000 + 100000)))
            .update();

        var terminal = terminalService.registerTerminal(
                merchantId, null, "SN-P23-" + UUID.randomUUID().toString().substring(0, 8), "PAX A920");
        terminalId = terminal.id();

        cashier = cashierService.registerCashier(
                merchantId, "Ama Cashier", "1234", "C-" + UUID.randomUUID().toString().substring(0, 6));
        manager = cashierService.registerCashier(
                merchantId, "Kofi Manager", "9876", "M-" + UUID.randomUUID().toString().substring(0, 6),
                "MANAGER", true);
    }

    @Test
    @DisplayName("Phase 2: Add-on items create separate KDS rounds and auto-ticket on PAID skips already-fired items")
    void addOnItemsAndMultiRoundKdsFiring() {
        var order = orderService.createOrder(
                merchantId, null, terminalId, cashier.id(), null,
                List.of(new PosOrderService.OrderItemInput(
                        null, "Kelewele", BigDecimal.ONE, 2500L, 0L, 0L, "[]", 1, "APPETIZER", null)),
                0L, 0L, 0L, "DINE_IN");

        // Round 1 fire
        var r1 = orderService.fireUnfiredItemsToKds(order.id(), null, "Ama Cashier", "Table starter")
                .orElseThrow();
        assertThat(r1.clientRef()).isEqualTo("pos:" + order.id() + ":r1");

        // Firing again with no new items returns empty
        assertThat(orderService.fireUnfiredItemsToKds(order.id(), null, null, null)).isEmpty();

        // Add-on item on PENDING order
        var afterAdd1 = orderService.addItemsToOrder(order.id(), List.of(
                new PosOrderService.OrderItemInput(
                        null, "Jollof & Chicken", BigDecimal.ONE, 4500L, 0L, 0L, "[]", 2, "MAIN", "Spicy")));
        assertThat(afterAdd1.subtotalPesewas()).isEqualTo(7000L);
        assertThat(afterAdd1.totalPesewas()).isEqualTo(7000L);

        // Round 2 fire sends ONLY the new item
        var r2 = orderService.fireUnfiredItemsToKds(order.id(), null, "Ama Cashier", "Round 2")
                .orElseThrow();
        assertThat(r2.clientRef()).isEqualTo("pos:" + order.id() + ":r2");
        assertThat(r2.id()).isNotEqualTo(r1.id());
        var r2Items = kdsService.listTicketItems(r2.id());
        assertThat(r2Items).hasSize(1);
        assertThat(r2Items.get(0).itemName()).isEqualTo("Jollof & Chicken");

        // Partial payment -> still accepts add-on items
        var partial = orderService.addTenderPayment(order.id(), TenderType.CASH, 3000L, "PARTIAL-1");
        assertThat(partial.status()).isEqualTo("PARTIAL");

        var afterAdd2 = orderService.addItemsToOrder(order.id(), List.of(
                new PosOrderService.OrderItemInput(
                        null, "Sobolo", BigDecimal.ONE, 1500L, 0L, 0L, "[]", 1, "DRINK", null)));
        assertThat(afterAdd2.totalPesewas()).isEqualTo(8500L);

        // Pay remaining -> auto-ticket on PAID fires ONLY the unfired item (Round 3)
        var paid = orderService.addTenderPayment(order.id(), TenderType.CASH, 5500L, "FINAL-1");
        assertThat(paid.status()).isEqualTo("PAID");

        List<String> ticketRefs = db.sql("""
                SELECT client_ref FROM restaurant_kds_ticket
                WHERE order_id = :id ORDER BY created_at ASC, id ASC
                """).param("id", order.id()).query(String.class).list();
        assertThat(ticketRefs).containsExactly(
                "pos:" + order.id() + ":r1",
                "pos:" + order.id() + ":r2",
                "pos:" + order.id() + ":r3"
        );

        // Cannot add items once PAID
        assertThatThrownBy(() -> orderService.addItemsToOrder(order.id(), List.of(
                new PosOrderService.OrderItemInput(
                        null, "Water", BigDecimal.ONE, 500L, 0L, 0L, "[]", 1, "DRINK", null))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PAID");
    }

    @Test
    @DisplayName("Phase 3: Manager PIN verification enforces role and 5-attempt lockout")
    void managerPinVerificationAndLockout() {
        // Regular cashier is not a manager
        assertThatThrownBy(() -> cashierService.verifyManagerPin(merchantId, cashier.badgeNumber(), "1234"))
                .isInstanceOf(ApiException.class);

        // Valid manager succeeds (badge + PIN and PIN-only)
        assertThat(cashierService.verifyManagerPin(merchantId, manager.badgeNumber(), "9876").id())
                .isEqualTo(manager.id());
        assertThat(cashierService.verifyManagerPin(merchantId, null, "9876").id())
                .isEqualTo(manager.id());

        // 5 failed attempts lock the manager PIN
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> cashierService.verifyManagerPin(merchantId, manager.badgeNumber(), "0000"))
                    .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> cashierService.verifyManagerPin(merchantId, manager.badgeNumber(), "9876"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("locked");
    }

    @Test
    @DisplayName("Phase 3: Item-level void before and after KDS firing updates totals, cancels KDS item, and writes pos_void_log")
    void itemVoidBeforeAndAfterFiring() {
        var order = orderService.createOrder(
                merchantId, null, terminalId, cashier.id(), null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Fired Jollof", BigDecimal.ONE, 4000L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN");
        var ticket = orderService.fireUnfiredItemsToKds(order.id(), null, "Ama", null).orElseThrow();
        orderService.addItemsToOrder(order.id(), List.of(
                new PosOrderService.OrderItemInput(null, "Unfired Pie", BigDecimal.ONE, 1500L, 0L, 0L, "[]", 1, "APPETIZER", null)));

        var items = orderService.listOrderItems(order.id());
        var firedItem = items.stream().filter(i -> "Fired Jollof".equals(i.itemName())).findFirst().orElseThrow();
        var unfiredItem = items.stream().filter(i -> "Unfired Pie".equals(i.itemName())).findFirst().orElseThrow();

        // Missing reason rejected
        assertThatThrownBy(() -> voidDiscountService.voidOrderItem(
                order.id(), unfiredItem.id(), " ", manager.badgeNumber(), "9876", "cashier-1"))
                .isInstanceOf(ApiException.class);

        // Void unfired item
        var afterUnfiredVoid = voidDiscountService.voidOrderItem(
                order.id(), unfiredItem.id(), "Customer changed mind", manager.badgeNumber(), "9876", "cashier-1");
        assertThat(afterUnfiredVoid.totalPesewas()).isEqualTo(4000L);
        assertThat(orderService.listOrderItems(order.id())).hasSize(1);

        // Idempotent repeat void returns same order without duplicate log
        voidDiscountService.voidOrderItem(
                order.id(), unfiredItem.id(), "Customer changed mind", manager.badgeNumber(), "9876", "cashier-1");

        // Void fired item -> cancels KDS item
        var afterFiredVoid = voidDiscountService.voidOrderItem(
                order.id(), firedItem.id(), "Out of stock", manager.badgeNumber(), "9876", "cashier-1");
        assertThat(afterFiredVoid.totalPesewas()).isEqualTo(0L);

        var kdsItems = kdsService.listTicketItems(ticket.id());
        assertThat(kdsItems).allMatch(ki -> "CANCELLED".equals(ki.itemStatus()));

        var voids = voidDiscountService.listVoids(order.id());
        assertThat(voids).hasSize(2);
        assertThat(voids).anyMatch(v -> "ITEM".equals(v.voidScope()) && !v.wasFired() && v.amountPesewas() == 1500L);
        assertThat(voids).anyMatch(v -> "ITEM".equals(v.voidScope()) && v.wasFired() && v.amountPesewas() == 4000L);
    }

    @Test
    @DisplayName("Phase 3: PARTIAL order void requires refundPayments=true and reverses shift cash drawer")
    void partialOrderVoidRequiresRefundAndReversesShiftCash() {
        var shift = cashierService.openShift(terminalId, cashier.id(), merchantId, 10_000L, "Shift 1");
        var order = orderService.createOrder(
                merchantId, null, terminalId, cashier.id(), null,
                List.of(new PosOrderService.OrderItemInput(null, "Banku & Tilapia", BigDecimal.ONE, 6000L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN");
        orderService.fireUnfiredItemsToKds(order.id(), null, "Ama", null);
        orderService.addTenderPayment(order.id(), TenderType.CASH, 2000L, "PART-CASH");

        assertThat(cashierService.getShift(shift.id()).orElseThrow().expectedCashPesewas()).isEqualTo(12_000L);

        // Blocked when refundPayments=false
        assertThatThrownBy(() -> voidDiscountService.voidOrderWithControl(
                order.id(), "Walkout", manager.badgeNumber(), "9876", false, null, "cashier-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("refunding");

        // Succeeds when refundPayments=true
        var voided = voidDiscountService.voidOrderWithControl(
                order.id(), "Customer cancelled", manager.badgeNumber(), "9876", true, null, "cashier-1");
        assertThat(voided.status()).isEqualTo("VOIDED");
        assertThat(voided.paidPesewas()).isEqualTo(0L);
        assertThat(cashierService.getShift(shift.id()).orElseThrow().expectedCashPesewas()).isEqualTo(10_000L);

        var voids = voidDiscountService.listVoids(order.id());
        assertThat(voids).hasSize(1);
        assertThat(voids.get(0).voidScope()).isEqualTo("ORDER");
        assertThat(voids.get(0).approvedBy()).isEqualTo(manager.id());
    }

    @Test
    @DisplayName("Phase 3: Discount requires reason and enforces manager PIN above per-merchant threshold")
    void discountThresholdAndAuditLog() {
        voidDiscountService.updateSettings(merchantId, 500L, null);
        var order = orderService.createOrder(
                merchantId, null, terminalId, cashier.id(), null,
                List.of(new PosOrderService.OrderItemInput(null, "Waakye", BigDecimal.ONE, 5000L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN");

        // Below/at threshold: no PIN needed
        var d1 = voidDiscountService.applyDiscount(order.id(), null, 500L, "Loyalty", null, null, "cashier-1");
        assertThat(d1.discountPesewas()).isEqualTo(500L);
        assertThat(d1.totalPesewas()).isEqualTo(4500L);

        // Above threshold without PIN: rejected
        assertThatThrownBy(() -> voidDiscountService.applyDiscount(order.id(), null, 1000L, "VIP", null, null, "cashier-1"))
                .isInstanceOf(ApiException.class);

        // Above threshold with manager PIN: succeeds
        var d2 = voidDiscountService.applyDiscount(
                order.id(), null, 1000L, "VIP", manager.badgeNumber(), "9876", "cashier-1");
        assertThat(d2.discountPesewas()).isEqualTo(1500L);
        assertThat(d2.totalPesewas()).isEqualTo(3500L);

        var logs = voidDiscountService.listDiscounts(order.id());
        assertThat(logs).hasSize(2);
        assertThat(logs).anyMatch(l -> l.discountPesewas() == 500L && l.approvedBy() == null);
        assertThat(logs).anyMatch(l -> l.discountPesewas() == 1000L && manager.id().equals(l.approvedBy()));
    }

    @Test
    @DisplayName("Track D Hook: BANK_TRANSFER requires reference, settles immediately, and is excluded from shift cash drawer")
    void bankTransferRequiresReferenceAndExcludesShiftDrawer() {
        var shift = cashierService.openShift(terminalId, cashier.id(), merchantId, 10_000L, "Bank Shift");
        var order = orderService.createOrder(
                merchantId, null, terminalId, cashier.id(), null,
                List.of(new PosOrderService.OrderItemInput(null, "Platter", BigDecimal.ONE, 8000L, 0L, 0L, "[]", null, null, null)),
                0L, 0L, 0L, "STANDARD");

        // Missing reference rejected
        assertThatThrownBy(() -> orderService.initiateTender(
                order.id(),
                new PosOrderService.TenderRequest(TenderType.BANK_TRANSFER, 8000L, null, null, null, null, "  "),
                "cashier-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reference");

        // Overpayment rejected
        assertThatThrownBy(() -> orderService.initiateTender(
                order.id(),
                new PosOrderService.TenderRequest(TenderType.BANK_TRANSFER, 9000L, null, null, null, null, "NIP-12345"),
                "cashier-1"))
                .isInstanceOf(ApiException.class);

        // Valid bank transfer settles immediately without touching shift cash drawer
        var result = orderService.initiateTender(
                order.id(),
                new PosOrderService.TenderRequest(TenderType.BANK_TRANSFER, 8000L, null, null, null, null, "NIP-987654"),
                "cashier-1");
        assertThat(result.orderStatus()).isEqualTo("PAID");
        assertThat(result.paymentStatus()).isEqualTo("paid");
        assertThat(cashierService.getShift(shift.id()).orElseThrow().expectedCashPesewas()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("Regression: consecutive sub-threshold discounts whose cumulative total exceeds threshold require manager PIN (order, item, cross-item, and item+order)")
    void cumulativeDiscountsRequireManagerPin() {
        voidDiscountService.updateSettings(merchantId, 500L, null);

        // 1. Order-level cumulative discount
        var order1 = orderService.createOrder(
                merchantId, null, terminalId, cashier.id(), null,
                List.of(new PosOrderService.OrderItemInput(null, "Jollof Platter", BigDecimal.ONE, 5000L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN");
        voidDiscountService.applyDiscount(order1.id(), null, 300L, "Promo 1", null, null, "cashier-1");
        assertThatThrownBy(() -> voidDiscountService.applyDiscount(order1.id(), null, 300L, "Promo 2", null, null, "cashier-1"))
                .isInstanceOf(ApiException.class);
        var afterOrderDisc = voidDiscountService.applyDiscount(
                order1.id(), null, 300L, "Promo 2", manager.badgeNumber(), "9876", "cashier-1");
        assertThat(afterOrderDisc.discountPesewas()).isEqualTo(600L);

        // 2. Same-item cumulative discount
        var order2 = orderService.createOrder(
                merchantId, null, terminalId, cashier.id(), null,
                List.of(new PosOrderService.OrderItemInput(null, "Banku Platter", BigDecimal.ONE, 5000L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN");
        UUID item2Id = orderService.listOrderItems(order2.id()).getFirst().id();
        voidDiscountService.applyDiscount(order2.id(), item2Id, 300L, "Item promo 1", null, null, "cashier-1");
        assertThatThrownBy(() -> voidDiscountService.applyDiscount(order2.id(), item2Id, 300L, "Item promo 2", null, null, "cashier-1"))
                .isInstanceOf(ApiException.class);
        var afterItemDisc = voidDiscountService.applyDiscount(
                order2.id(), item2Id, 300L, "Item promo 2", manager.badgeNumber(), "9876", "cashier-1");
        assertThat(afterItemDisc.subtotalPesewas()).isEqualTo(4400L);

        // 3. Cross-item smurfing (Item A + Item B) and Item + Order smurfing
        var order3 = orderService.createOrder(
                merchantId, null, terminalId, cashier.id(), null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Item A", BigDecimal.ONE, 3000L, 0L, 0L, "[]", 1, "MAIN", null),
                        new PosOrderService.OrderItemInput(null, "Item B", BigDecimal.ONE, 3000L, 0L, 0L, "[]", 1, "MAIN", null)
                ),
                0L, 0L, 0L, "DINE_IN");
        var order3Items = orderService.listOrderItems(order3.id());
        UUID itemAId = order3Items.stream().filter(i -> "Item A".equals(i.itemName())).findFirst().orElseThrow().id();
        UUID itemBId = order3Items.stream().filter(i -> "Item B".equals(i.itemName())).findFirst().orElseThrow().id();

        // First sub-threshold discount on Item A (300 <= 500) succeeds without PIN
        voidDiscountService.applyDiscount(order3.id(), itemAId, 300L, "Item A promo", null, null, "cashier-1");

        // Second sub-threshold discount on Item B (300) brings order-wide total to 600 > 500 -> rejected without PIN
        assertThatThrownBy(() -> voidDiscountService.applyDiscount(order3.id(), itemBId, 300L, "Item B promo", null, null, "cashier-1"))
                .isInstanceOf(ApiException.class);
        // Succeeds with Manager PIN
        var afterCrossItem = voidDiscountService.applyDiscount(
                order3.id(), itemBId, 300L, "Item B promo", manager.badgeNumber(), "9876", "cashier-1");
        assertThat(afterCrossItem.subtotalPesewas()).isEqualTo(5400L);

        // Subsequent sub-threshold order-level discount (200) brings order-wide total to 800 > 500 -> rejected without PIN
        assertThatThrownBy(() -> voidDiscountService.applyDiscount(order3.id(), null, 200L, "Order promo", null, null, "cashier-1"))
                .isInstanceOf(ApiException.class);
        // Succeeds with Manager PIN
        var afterItemPlusOrder = voidDiscountService.applyDiscount(
                order3.id(), null, 200L, "Order promo", manager.badgeNumber(), "9876", "cashier-1");
        assertThat(afterItemPlusOrder.discountPesewas()).isEqualTo(200L);
        assertThat(afterItemPlusOrder.totalPesewas()).isEqualTo(5200L);

        // 4. Order-level first, then sub-threshold item-level smurfing
        var order4 = orderService.createOrder(
                merchantId, null, terminalId, cashier.id(), null,
                List.of(new PosOrderService.OrderItemInput(null, "Item C", BigDecimal.ONE, 4000L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN");
        UUID itemCId = orderService.listOrderItems(order4.id()).getFirst().id();
        voidDiscountService.applyDiscount(order4.id(), null, 300L, "Order first", null, null, "cashier-1");
        assertThatThrownBy(() -> voidDiscountService.applyDiscount(order4.id(), itemCId, 300L, "Item second", null, null, "cashier-1"))
                .isInstanceOf(ApiException.class);
        var afterOrderPlusItem = voidDiscountService.applyDiscount(
                order4.id(), itemCId, 300L, "Item second", manager.badgeNumber(), "9876", "cashier-1");
        assertThat(afterOrderPlusItem.subtotalPesewas()).isEqualTo(3700L);
        assertThat(afterOrderPlusItem.discountPesewas()).isEqualTo(300L);
        assertThat(afterOrderPlusItem.totalPesewas()).isEqualTo(3400L);
    }

    @Test
    @DisplayName("Regression: omitting managerBadgeNumber increments pin_failures and locks out after 5 wrong attempts")
    void verifyManagerPinWithoutBadgeLocksOutAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> cashierService.verifyManagerPin(merchantId, null, "0000"))
                    .isInstanceOf(ApiException.class);
        }

        // 6th attempt with the CORRECT PIN must now fail (both without badge and with badge)
        assertThatThrownBy(() -> cashierService.verifyManagerPin(merchantId, null, "9876"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> cashierService.verifyManagerPin(merchantId, manager.badgeNumber(), "9876"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("locked");
    }

    @Test
    @DisplayName("Regression: POST /v1/pos/orders/{orderId}/items rejects discountPesewas > 0 with HTTP 400")
    void addItemsRejectsDiscountPesewasBypass() {
        var api = new TestApi(port);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String opsToken = api.token("ops-add-items-" + suffix, "ops");
        String ownerToken = api.token("owner-add-items-" + suffix);

        var createdMerchant = api.call("POST", "/v1/merchants", ownerToken, null, Map.of(
                "legalName", "AddItems Test " + suffix,
                "tradingName", "AddItems Test " + suffix,
                "kind", "merchant",
                "contactPhone", TestApi.uniquePhone()
        ));
        assertThat(createdMerchant.status()).isEqualTo(200);
        UUID testMerchantId = UUID.fromString(createdMerchant.body().get("id").asString());
        assertThat(api.call("POST", "/v1/ops/merchants/" + testMerchantId + "/review", opsToken, null,
                Map.of("decision", "approve")).status()).isEqualTo(200);

        var order = orderService.createOrder(
                testMerchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Jollof", BigDecimal.ONE, 4000L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN");

        var res = api.call(
                "POST",
                "/v1/pos/orders/" + order.id() + "/items",
                ownerToken,
                "idem-add-disc-" + UUID.randomUUID(),
                Map.of("items", List.of(Map.of(
                        "itemName", "Discounted Drink",
                        "quantity", 1,
                        "unitPricePesewas", 1500,
                        "discountPesewas", 500,
                        "taxPesewas", 0,
                        "modifiersJson", "[]",
                        "course", "DRINK"
                ))));

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.body().path("code").asString()).isEqualTo("use_discount_endpoint");
        assertThat(orderService.listOrderItems(order.id())).hasSize(1);
    }
}
