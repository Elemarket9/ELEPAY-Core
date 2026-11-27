package com.elepay.merchanttools.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import com.elepay.merchanttools.restaurant.RestaurantSplitBillService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class PosSplitBankTransferPhase5Test extends PostgresTestBase {

    @Autowired
    private JdbcClient db;

    @Autowired
    private PosOrderService orderService;

    @Autowired
    private PosSplitBillService splitBillService;

    @Autowired
    private RestaurantSplitBillService restaurantSplitBillService;

    @Autowired
    private PosSplitBillController splitBillController;

    @Autowired
    private PosTerminalService terminalService;

    @Autowired
    private PosCashierService cashierService;

    private UUID merchantId;
    private UUID branchA;
    private UUID branchB;

    @BeforeEach
    void setupMerchantAndBranches() {
        merchantId = UUID.randomUUID();
        branchA = UUID.randomUUID();
        branchB = UUID.randomUUID();

        db.sql("""
                INSERT INTO merchant (
                    id, legal_name, trading_name, kind, status, contact_phone,
                    merchant_code, fee_bps, fee_fixed, settlement_delay_hours, created_by
                ) VALUES (
                    :id, 'Phase 5 Bistro Ltd', 'Phase 5 Bistro', 'merchant', 'active', '0241234567',
                    :code, 0, 0, 24, 'system'
                ) ON CONFLICT DO NOTHING
                """)
                .param("id", merchantId)
                .param("code", String.format("%06d", (int) (Math.random() * 900_000 + 100_000)))
                .update();

        db.sql("""
                INSERT INTO merchant_branch (id, merchant_id, name, city, region, country)
                VALUES (:id, :m, :name, 'Accra', 'Greater Accra', 'GH')
                """)
                .param("id", branchA)
                .param("m", merchantId)
                .param("name", "Osu Branch")
                .update();

        db.sql("""
                INSERT INTO merchant_branch (id, merchant_id, name, city, region, country)
                VALUES (:id, :m, :name, 'Kumasi', 'Ashanti', 'GH')
                """)
                .param("id", branchB)
                .param("m", merchantId)
                .param("name", "Adum Branch")
                .update();
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("BANK_TRANSFER settles immediately with reference, rejects missing reference or overpayment, and stays outside shift cash drawer")
    void bankTransferValidationAndShiftCashDrawerExclusion() {
        var terminal = terminalService.registerTerminal(
                merchantId, branchA, "SN-BT-" + UUID.randomUUID().toString().substring(0, 8), "PAX A920");
        var cashier = cashierService.registerCashier(
                merchantId, "Kofi Cashier", "1234", "BG-" + UUID.randomUUID().toString().substring(0, 6));
        var shift = cashierService.openShift(terminal.id(), cashier.id(), merchantId, 10_000L, "Morning shift");

        // Order A paid in CASH (5,000) -> shift expected cash becomes 15,000
        var orderCash = orderService.createOrder(
                merchantId, branchA, terminal.id(), cashier.id(), null,
                List.of(item("Jollof", 5_000L, 1)),
                0L, 0L, 0L, "DINE_IN");
        var cashRes = orderService.initiateTender(
                orderCash.id(),
                new PosOrderService.TenderRequest(TenderType.CASH, 5_000L, null, null, null, null, "CASH-1"),
                "cashier-1");
        assertThat(cashRes.orderStatus()).isEqualTo("PAID");

        // Order B paid via BANK_TRANSFER (8,000)
        var orderBank = orderService.createOrder(
                merchantId, branchA, terminal.id(), cashier.id(), null,
                List.of(item("Grilled Tilapia", 8_000L, 1)),
                0L, 0L, 0L, "DINE_IN");

        // Missing / blank reference must fail with 400
        assertThatThrownBy(() -> orderService.initiateTender(
                orderBank.id(),
                new PosOrderService.TenderRequest(TenderType.BANK_TRANSFER, 8_000L, null, null, null, null, "   "),
                "cashier-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        // Overpayment via BANK_TRANSFER must fail with 400 (no cash change)
        assertThatThrownBy(() -> orderService.initiateTender(
                orderBank.id(),
                new PosOrderService.TenderRequest(TenderType.BANK_TRANSFER, 9_000L, null, null, null, null, "GTB-OVER-1"),
                "cashier-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        // Valid BANK_TRANSFER settles immediately
        var bankRes = orderService.initiateTender(
                orderBank.id(),
                new PosOrderService.TenderRequest(TenderType.BANK_TRANSFER, 8_000L, null, null, null, null, "GTB-FT-20261127-001"),
                "cashier-1");
        assertThat(bankRes.orderStatus()).isEqualTo("PAID");
        assertThat(bankRes.tenderType()).isEqualTo(TenderType.BANK_TRANSFER);
        assertThat(bankRes.paymentStatus()).isEqualTo("paid");

        // Verify persisted tender row
        String recordedType = db.sql("SELECT tender_type FROM pos_tender_payment WHERE order_id = :id")
                .param("id", orderBank.id())
                .query(String.class)
                .single();
        assertThat(recordedType).isEqualTo("BANK_TRANSFER");

        // Shift expected cash MUST be 15,000 (10,000 opening + 5,000 cash), NOT 23,000
        var activeShift = cashierService.getShift(shift.id()).orElseThrow();
        assertThat(activeShift.expectedCashPesewas()).isEqualTo(15_000L);

        var closed = cashierService.closeShift(shift.id(), 15_000L, "Balanced");
        assertThat(closed.cashDiscrepancyPesewas()).isZero();
    }

    @Test
    @DisplayName("Payable even split transitions order to PAID only after all sub-bills are paid and blocks re-splitting mid-payment")
    void payableEvenSplitEndToEnd() {
        var order = orderService.createOrder(
                merchantId, branchA, null, null, null,
                List.of(item("Shared Platter", 10_000L, 1)),
                0L, 0L, 0L, "DINE_IN");

        var plan = splitBillService.createEvenSplit(order.id(), 3);
        assertThat(plan.splitType()).isEqualTo("EVEN");
        assertThat(plan.subBills()).hasSize(3);
        assertThat(plan.subBills().stream().map(PosSplitBillService.SubBill::splitKey))
                .containsExactly("even:1", "even:2", "even:3");
        assertThat(plan.subBills().stream().map(PosSplitBillService.SubBill::amountPesewas))
                .containsExactly(3_334L, 3_333L, 3_333L);

        // Pay sub-bill 1 via CASH -> order is PARTIAL
        var pay1 = splitBillService.paySplit(
                order.id(), "even:1",
                new PosOrderService.TenderRequest(TenderType.CASH, 3_334L, null, null, null, null, "CASH-E1"),
                "cashier-1");
        assertThat(pay1.tender().orderStatus()).isEqualTo("PARTIAL");
        assertThat(pay1.plan().subBills().get(0).status()).isEqualTo("PAID");
        assertThat(pay1.plan().subBills().get(1).status()).isEqualTo("OPEN");

        // Attempting to replace split plan after a sub-bill is paid must fail with 409 split_already_in_progress
        assertThatThrownBy(() -> splitBillService.createEvenSplit(order.id(), 2))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("split_already_in_progress");
                });

        // Paying an already-PAID sub-bill must fail with 409
        assertThatThrownBy(() -> splitBillService.paySplit(
                order.id(), "even:1",
                new PosOrderService.TenderRequest(TenderType.CASH, 3_334L, null, null, null, null, "DUP"),
                "cashier-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.CONFLICT));

        // Pay sub-bill 2 via BANK_TRANSFER -> still PARTIAL
        var pay2 = splitBillService.paySplit(
                order.id(), "even:2",
                new PosOrderService.TenderRequest(TenderType.BANK_TRANSFER, 3_333L, null, null, null, null, "BT-E2"),
                "cashier-1");
        assertThat(pay2.tender().orderStatus()).isEqualTo("PARTIAL");
        assertThat(pay2.plan().subBills().get(1).status()).isEqualTo("PAID");

        // Pay sub-bill 3 via CASH -> order transitions to PAID
        var pay3 = splitBillService.paySplit(
                order.id(), "even:3",
                new PosOrderService.TenderRequest(TenderType.CASH, 3_333L, null, null, null, null, "CASH-E3"),
                "cashier-1");
        assertThat(pay3.tender().orderStatus()).isEqualTo("PAID");
        assertThat(pay3.plan().orderStatus()).isEqualTo("PAID");
        assertThat(pay3.plan().subBills()).allSatisfy(sb -> assertThat(sb.status()).isEqualTo("PAID"));
    }

    @Test
    @DisplayName("Payable seat split via RestaurantSplitBillService preserves order total and settles per seat")
    void payableSeatSplitViaRestaurantService() {
        // subtotal 10,000 - discount 1,000 + tax 500 + tip 500 = 10,000
        var order = orderService.createOrder(
                merchantId, branchA, null, null, null,
                List.of(
                        item("Seat 1 Steak", 6_000L, 1),
                        item("Seat 2 Pasta", 4_000L, 2)
                ),
                1_000L, 500L, 500L, "DINE_IN");

        var plan = restaurantSplitBillService.createPayableSeatSplit(order.id());
        assertThat(plan.splitType()).isEqualTo("SEAT");
        assertThat(plan.subBills()).hasSize(2);
        long sum = plan.subBills().stream().mapToLong(PosSplitBillService.SubBill::amountPesewas).sum();
        assertThat(sum).isEqualTo(order.totalPesewas());

        var seat1Amount = plan.subBills().get(0).amountPesewas();
        var seat2Amount = plan.subBills().get(1).amountPesewas();

        var r1 = restaurantSplitBillService.paySeatSplit(
                order.id(), 1,
                new PosOrderService.TenderRequest(TenderType.CASH, seat1Amount, null, null, null, null, "S1"),
                "waiter-1");
        assertThat(r1.tender().orderStatus()).isEqualTo("PARTIAL");

        var r2 = restaurantSplitBillService.paySeatSplit(
                order.id(), 2,
                new PosOrderService.TenderRequest(TenderType.BANK_TRANSFER, seat2Amount, null, null, null, null, "BT-S2"),
                "waiter-1");
        assertThat(r2.tender().orderStatus()).isEqualTo("PAID");
        assertThat(r2.plan().subBills()).allSatisfy(sb -> assertThat(sb.status()).isEqualTo("PAID"));
    }

    @Test
    @DisplayName("Payable item split validates item coverage, persists item links, and enforces idempotent controller payment")
    void payableItemSplitValidationAndIdempotentPay() {
        String ownerSub = "owner-" + UUID.randomUUID();
        db.sql("INSERT INTO merchant_member (merchant_id, subject, role) VALUES (:m, :s, 'owner')")
                .param("m", merchantId)
                .param("s", ownerSub)
                .update();
        authenticateAsJwt(ownerSub);

        var order = orderService.createOrder(
                merchantId, branchA, null, null, null,
                List.of(
                        item("Item A", 5_000L, 1),
                        item("Item B", 3_000L, 1),
                        item("Item C", 2_000L, 2)
                ),
                0L, 0L, 0L, "DINE_IN");
        List<PosOrderService.PosOrderItem> items = orderService.listOrderItems(order.id());
        UUID itemA = items.stream().filter(i -> "Item A".equals(i.itemName())).findFirst().orElseThrow().id();
        UUID itemB = items.stream().filter(i -> "Item B".equals(i.itemName())).findFirst().orElseThrow().id();
        UUID itemC = items.stream().filter(i -> "Item C".equals(i.itemName())).findFirst().orElseThrow().id();

        // Missing item must fail 400
        assertThatThrownBy(() -> splitBillService.createItemSplit(
                order.id(),
                List.of(new PosSplitBillService.ItemSplitGroupRequest("Bill 1", List.of(itemA, itemB)))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("missing_split_items");
                });

        // Duplicate item must fail 400
        assertThatThrownBy(() -> splitBillService.createItemSplit(
                order.id(),
                List.of(
                        new PosSplitBillService.ItemSplitGroupRequest("Bill 1", List.of(itemA, itemB)),
                        new PosSplitBillService.ItemSplitGroupRequest("Bill 2", List.of(itemB, itemC))
                )))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("duplicate_split_item");
                });

        // Foreign item UUID must fail 400
        assertThatThrownBy(() -> splitBillService.createItemSplit(
                order.id(),
                List.of(
                        new PosSplitBillService.ItemSplitGroupRequest("Bill 1", List.of(itemA, itemB, itemC)),
                        new PosSplitBillService.ItemSplitGroupRequest("Bill 2", List.of(UUID.randomUUID()))
                )))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("invalid_split_item");
                });

        // Valid item split via controller
        var plan = splitBillController.createItemSplit(
                order.id(),
                new PosSplitBillController.ItemSplitRequest(List.of(
                        new PosSplitBillService.ItemSplitGroupRequest("Alice", List.of(itemA, itemB)),
                        new PosSplitBillService.ItemSplitGroupRequest("Bob", List.of(itemC))
                )));
        assertThat(plan.splitType()).isEqualTo("ITEM");
        assertThat(plan.subBills()).hasSize(2);
        assertThat(plan.subBills().get(0).amountPesewas()).isEqualTo(8_000L);
        assertThat(plan.subBills().get(0).itemIds()).containsExactlyInAnyOrder(itemA, itemB);
        assertThat(plan.subBills().get(1).amountPesewas()).isEqualTo(2_000L);
        assertThat(plan.subBills().get(1).itemIds()).containsExactly(itemC);

        // Idempotent pay on item:1
        String idemKey = "split-pay-" + UUID.randomUUID();
        var payReq1 = new PosOrderService.TenderRequest(TenderType.CASH, 8_000L, null, null, null, null, "ITEM-1");
        var firstPay = splitBillController.paySplit(order.id(), "item:1", idemKey, payReq1);
        var replayPay = splitBillController.paySplit(order.id(), "item:1", idemKey, payReq1);

        assertThat(firstPay.tender().orderStatus()).isEqualTo("PARTIAL");
        assertThat(replayPay.tender().orderStatus()).isEqualTo("PARTIAL");
        long tenderCount = db.sql("SELECT count(*) FROM pos_tender_payment WHERE order_id = :id")
                .param("id", order.id())
                .query(Long.class)
                .single();
        assertThat(tenderCount).isEqualTo(1L);

        // Pay item:2 via BANK_TRANSFER -> order PAID
        var finalPay = splitBillController.paySplit(
                order.id(), "item:2", "split-pay-" + UUID.randomUUID(),
                new PosOrderService.TenderRequest(TenderType.BANK_TRANSFER, 2_000L, null, null, null, null, "BT-ITEM-2"));
        assertThat(finalPay.tender().orderStatus()).isEqualTo("PAID");
        assertThat(finalPay.plan().orderStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("Adversarial: branch-scoped cashier in Branch A cannot view, create, or pay splits for Branch B order")
    void branchScopedCashierCannotAccessOtherBranchSplits() {
        var orderInBranchB = orderService.createOrder(
                merchantId, branchB, null, null, null,
                List.of(item("Kumasi Special", 4_000L, 1)),
                0L, 0L, 0L, "DINE_IN");
        UUID itemInBranchB = orderService.listOrderItems(orderInBranchB.id()).getFirst().id();

        String branchACashier = "cashier-branch-a-" + UUID.randomUUID();
        db.sql("INSERT INTO merchant_member (merchant_id, subject, role, branch_id) VALUES (:m, :s, 'cashier', :b)")
                .param("m", merchantId)
                .param("s", branchACashier)
                .param("b", branchA)
                .update();
        authenticateAsJwt(branchACashier);

        assertNotFound(() -> splitBillController.getSplits(orderInBranchB.id()));
        assertNotFound(() -> splitBillController.createEvenSplit(orderInBranchB.id(), 2));
        assertNotFound(() -> splitBillController.createSeatSplit(orderInBranchB.id()));
        assertNotFound(() -> splitBillController.createItemSplit(
                orderInBranchB.id(),
                new PosSplitBillController.ItemSplitRequest(List.of(
                        new PosSplitBillService.ItemSplitGroupRequest("Hijack", List.of(itemInBranchB))))));
        assertNotFound(() -> splitBillController.paySplit(
                orderInBranchB.id(), "even:1", "idem-hijack-" + UUID.randomUUID(),
                new PosOrderService.TenderRequest(TenderType.CASH, 4_000L, null, null, null, null, "HIJACK")));
    }

    @Test
    @DisplayName("Regression: addTenderPayment rejects missing, unknown, or already-paid splitKey when active splits exist")
    void addTenderPaymentRequiresValidSplitKeyWhenActiveSplitsExist() {
        var order = orderService.createOrder(
                merchantId, branchA, null, null, null,
                List.of(item("Shared Platter", 10_000L, 1)),
                0L, 0L, 0L, "DINE_IN");
        splitBillService.createEvenSplit(order.id(), 2);

        // Missing splitKey -> 400 split_key_required
        assertThatThrownBy(() -> orderService.addTenderPayment(order.id(), TenderType.CASH, 5_000L, "NO-SPLIT", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("split_key_required");
                });

        // Unknown splitKey -> 404
        assertNotFound(() -> orderService.addTenderPayment(order.id(), TenderType.CASH, 5_000L, "BAD-SPLIT", "even:99"));

        // Valid splitKey even:1 -> succeeds (PARTIAL)
        var afterFirst = orderService.addTenderPayment(order.id(), TenderType.CASH, 5_000L, "SPLIT-1", "even:1");
        assertThat(afterFirst.status()).isEqualTo("PARTIAL");

        // Re-paying already paid split even:1 -> 409 split_already_paid
        assertThatThrownBy(() -> orderService.addTenderPayment(order.id(), TenderType.CASH, 5_000L, "DUP-1", "even:1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("split_already_paid");
                });

        // Valid splitKey even:2 -> settles order (PAID)
        var afterSecond = orderService.addTenderPayment(order.id(), TenderType.CASH, 5_000L, "SPLIT-2", "even:2");
        assertThat(afterSecond.status()).isEqualTo("PAID");
    }

    private static PosOrderService.OrderItemInput item(String name, long unitPricePesewas, Integer seatNumber) {
        return new PosOrderService.OrderItemInput(
                null, name, BigDecimal.ONE, unitPricePesewas, 0L, 0L, "[]", seatNumber, "MAIN", null);
    }

    private static void authenticateAsJwt(String subject) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
