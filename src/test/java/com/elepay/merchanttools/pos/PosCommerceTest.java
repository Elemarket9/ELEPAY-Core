package com.elepay.merchanttools.pos;

import com.elepay.PostgresTestBase;
import com.elepay.merchanttools.inventory.MovementType;
import com.elepay.merchanttools.inventory.ProductCatalogService;
import com.elepay.merchanttools.inventory.StockService;
import com.elepay.merchanttools.inventory.WarehouseService;
import com.elepay.merchanttools.restaurant.KdsService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

public class PosCommerceTest extends PostgresTestBase {

    @Autowired
    private PosTerminalService terminalService;

    @Autowired
    private PosCashierService cashierService;

    @Autowired
    private PosCartService cartService;

    @Autowired
    private PosOrderService orderService;

    @Autowired
    private PosSplitBillService splitBillService;

    @Autowired
    private PosReceiptService receiptService;

    @Autowired
    private PosOfflineSyncService syncService;

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private ProductCatalogService catalogService;

    @Autowired
    private StockService stockService;

    @Autowired
    private JdbcClient db;

    private UUID merchantId;
    private WarehouseService.Warehouse defaultWarehouse;

    @BeforeEach
    void setup() {
        merchantId = UUID.randomUUID();
        db.sql("""
            INSERT INTO merchant (
                id, legal_name, trading_name, kind, status, contact_phone,
                merchant_code, fee_bps, fee_fixed, settlement_delay_hours, created_by
            ) VALUES (
                :id, 'POS Merchant Retail', 'POS Mart', 'merchant', 'active', '0249998877',
                :code, 0, 0, 24, 'system'
            ) ON CONFLICT DO NOTHING
            """)
            .param("id", merchantId)
            .param("code", String.format("%06d", (int)(Math.random() * 900000 + 100000)))
            .update();

        defaultWarehouse = warehouseService.createWarehouse(merchantId, null, "WH-MAIN-" + UUID.randomUUID().toString().substring(0, 5), "Store Main Hub", "Accra");
    }

    @Test
    @DisplayName("Domain 1: Terminal registration, pairing, cashier PIN authentication, and shift lifecycle")
    void testTerminalPairingCashierAndShifts() {
        String sn1 = "SN-PAX-" + UUID.randomUUID().toString().substring(0, 8);
        String badge1 = "B-" + UUID.randomUUID().toString().substring(0, 6);

        // 1. Terminal registration & pairing
        var terminal = terminalService.registerTerminal(merchantId, null, sn1, "PAX A920 Pro");
        assertThat(terminal.status()).isEqualTo("INACTIVE");
        assertThat(terminal.pairingCode()).hasSize(6);

        var paired = terminalService.pairTerminal(sn1, terminal.pairingCode(), "HW-FP-MAC-AA:BB:CC:11:22");
        assertThat(paired.status()).isEqualTo("ACTIVE");
        assertThat(paired.authToken()).startsWith("POS_");

        // 2. Cashier registration & salted PIN authentication
        var cashier = cashierService.registerCashier(merchantId, "Kwame Mensah", "1234", badge1);
        assertThat(cashier.badgeNumber()).isEqualTo(badge1);

        boolean authSuccess = cashierService.authenticateCashier(merchantId, badge1, "1234");
        assertThat(authSuccess).isTrue();

        boolean authFail = cashierService.authenticateCashier(merchantId, badge1, "9999");
        assertThat(authFail).isFalse();

        // 3. Shift lifecycle: Open shift with 200 GHS (20,000 pesewas) opening balance
        var shift = cashierService.openShift(terminal.id(), cashier.id(), merchantId, 20000L, "Morning Shift");
        assertThat(shift.status()).isEqualTo("OPEN");
        assertThat(shift.openingBalancePesewas()).isEqualTo(20000L);
        assertThat(shift.expectedCashPesewas()).isEqualTo(20000L);

        // Record 50 GHS cash sale
        cashierService.recordCashTender(shift.id(), 5000L);
        var activeShift = cashierService.getActiveShift(terminal.id()).orElseThrow();
        assertThat(activeShift.expectedCashPesewas()).isEqualTo(25000L);

        // Close shift: Cashier counted 248 GHS (24,800 pesewas) -> 2 GHS discrepancy (shortage)
        var closedShift = cashierService.closeShift(shift.id(), 24800L, "2 GHS minor coin shortage");
        assertThat(closedShift.status()).isEqualTo("CLOSED");
        assertThat(closedShift.cashDiscrepancyPesewas()).isEqualTo(-200L);
    }

    @Test
    @DisplayName("Domain 1: Quick-sale cart, held carts, orders, multi-tender payment, and inventory stock deduction")
    void testCartOrdersAndMultiTenderPayments() {
        String sn2 = "SN-TERM-" + UUID.randomUUID().toString().substring(0, 8);
        String badge2 = "B-" + UUID.randomUUID().toString().substring(0, 6);

        var terminal = terminalService.registerTerminal(merchantId, null, sn2, "Sunmi V2 Pro");
        terminalService.pairTerminal(sn2, terminal.pairingCode(), "HW-FP-SUNMI-002");
        var cashier = cashierService.registerCashier(merchantId, "Abena Osei", "4321", badge2);
        var shift = cashierService.openShift(terminal.id(), cashier.id(), merchantId, 10000L, "Shift A");

        // Set up product in inventory
        var category = catalogService.createCategory(merchantId, "Bakery");
        var product = catalogService.createProduct(merchantId, category.id(), "Meat Pie", "Fresh baked", "EleBakery");
        var variant = catalogService.createVariant(merchantId, product.id(), "PIE-BEEF", "9901234567890", "Beef Meat Pie", "{}", 800L, 1500L, true);
        stockService.adjustStock(defaultWarehouse.id(), variant.id(), BigDecimal.valueOf(30), MovementType.RECEIVED, null, "Stock");

        // 1. Held cart test (suspend and recall)
        var held = cartService.holdCart(terminal.id(), cashier.id(), merchantId, "Customer Kojo", "[{\"item\":\"Beef Pie\",\"qty\":2}]", 3000L);
        assertThat(held.customerName()).isEqualTo("Customer Kojo");
        var heldList = cartService.listHeldCarts(terminal.id());
        assertThat(heldList).hasSize(1);

        var recalled = cartService.recallHeldCart(held.id());
        assertThat(recalled.id()).isEqualTo(held.id());
        assertThat(cartService.listHeldCarts(terminal.id())).isEmpty();

        // 2. Create POS Order: 2 Beef Pies @ 15.00 GHS each = 30.00 GHS (3000 pesewas)
        var order = orderService.createOrder(
                merchantId, null, terminal.id(), cashier.id(), null,
                List.of(new PosOrderService.OrderItemInput(
                        variant.id(), "Beef Meat Pie", BigDecimal.valueOf(2),
                        1500L, 0L, 0L, "[]", 1, "MAIN", "No onion"
                )),
                0L, 0L, 0L, "STANDARD"
        );
        assertThat(order.status()).isEqualTo("PENDING");
        assertThat(order.totalPesewas()).isEqualTo(3000L);

        // 3. Cash payment in two parts: 10.00 GHS + 25.00 GHS (total 35.00 GHS) -> 5.00 GHS change
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderService.addTenderPayment(order.id(), TenderType.MOMO, 1000L, "UNVERIFIED"))
                .isInstanceOf(com.elepay.common.ApiException.class);
        orderService.addTenderPayment(order.id(), TenderType.CASH, 1000L, "CASH-PARTIAL");
        var updatedOrder1 = orderService.getOrder(order.id()).orElseThrow();
        assertThat(updatedOrder1.status()).isEqualTo("PARTIAL");
        assertThat(updatedOrder1.paidPesewas()).isEqualTo(1000L);

        var finalOrder = orderService.addTenderPayment(order.id(), TenderType.CASH, 2500L, "CASH-PAID");
        assertThat(finalOrder.status()).isEqualTo("PAID");
        assertThat(finalOrder.paidPesewas()).isEqualTo(3500L);
        assertThat(finalOrder.changePesewas()).isEqualTo(500L); // 5.00 GHS change
        assertThat(cashierService.getActiveShift(terminal.id()).orElseThrow().expectedCashPesewas()).isEqualTo(13000L);

        // Verify stock automatically deducted by 2 pies: 30 - 2 = 28 pies
        var stockAfterSale = stockService.getOrCreateStock(defaultWarehouse.id(), variant.id());
        assertThat(stockAfterSale.quantityOnHand()).isEqualByComparingTo("28");

        // 4. Generate Printed & Digital Receipt
        var receipt = receiptService.generateReceipt(order.id());
        assertThat(receipt.receiptNumber()).startsWith("REC-");
        assertThat(receipt.digitalReceiptUrl()).as("no invented receipt page").isNull();
        assertThat(receipt.escposFormattedText()).contains("POS MART").doesNotContain("ELEPAY COMMERCE");
        assertThat(receipt.escposFormattedText()).contains("TOTAL DUE:");
        assertThat(receipt.escposFormattedText()).contains("CHANGE GIVEN:");
    }

    @Test
    @DisplayName("Domain 1: Bill splitting (even splits with integer remainder & seat splits)")
    void testBillSplitting() {
        String sn3 = "SN-TERM-" + UUID.randomUUID().toString().substring(0, 8);
        String badge3 = "B-" + UUID.randomUUID().toString().substring(0, 6);

        var terminal = terminalService.registerTerminal(merchantId, null, sn3, "Verifone V400m");
        terminalService.pairTerminal(sn3, terminal.pairingCode(), "HW-FP-VERI-003");
        var cashier = cashierService.registerCashier(merchantId, "Esi Mensah", "5678", badge3);

        // Create order with 3 items on different seats: Total = 100.00 GHS (10,000 pesewas)
        var order = orderService.createOrder(
                merchantId, null, terminal.id(), cashier.id(), null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Fried Rice", BigDecimal.ONE, 4000L, 0L, 0L, "[]", 1, "MAIN", null),
                        new PosOrderService.OrderItemInput(null, "Grilled Chicken", BigDecimal.ONE, 3500L, 0L, 0L, "[]", 2, "MAIN", null),
                        new PosOrderService.OrderItemInput(null, "Cocktail", BigDecimal.ONE, 2500L, 0L, 0L, "[]", 2, "BEVERAGE", null)
                ),
                0L, 0L, 0L, "STANDARD"
        );

        // 1. Even Split across 3 people: 10,000 pesewas / 3 = 3334, 3333, 3333
        var evenSplit = splitBillService.splitBillEven(order.id(), 3);
        assertThat(evenSplit.portions()).hasSize(3);
        long sumPortions = evenSplit.portions().stream().mapToLong(PosSplitBillService.EvenSplitPortion::amountPesewas).sum();
        assertThat(sumPortions).isEqualTo(10000L);
        assertThat(evenSplit.portions().get(0).amountPesewas()).isEqualTo(3334L);
        assertThat(evenSplit.portions().get(1).amountPesewas()).isEqualTo(3333L);

        // 2. Seat Split: Seat 1 has 40.00 GHS, Seat 2 has 60.00 GHS
        var seatSplit = splitBillService.splitBillBySeats(order.id());
        assertThat(seatSplit.seatGroups()).hasSize(2);
        var seat1 = seatSplit.seatGroups().stream().filter(g -> g.seatNumber() == 1).findFirst().orElseThrow();
        var seat2 = seatSplit.seatGroups().stream().filter(g -> g.seatNumber() == 2).findFirst().orElseThrow();
        assertThat(seat1.subtotalPesewas()).isEqualTo(4000L);
        assertThat(seat2.subtotalPesewas()).isEqualTo(6000L);
    }

    @Test
    @DisplayName("Domain 1: Offline transaction sync queue with idempotency and conflict resolution")
    void testOfflineSyncQueueAndConflictResolution() {
        String snOff = "SN-OFF-" + UUID.randomUUID().toString().substring(0, 8);
        String badgeOff = "B-" + UUID.randomUUID().toString().substring(0, 6);

        var terminal = terminalService.registerTerminal(merchantId, null, snOff, "PAX A920 Pro");
        terminalService.pairTerminal(snOff, terminal.pairingCode(), "HW-FP-OFFLINE-001");
        var cashier = cashierService.registerCashier(merchantId, "Kofi Addo", "1111", badgeOff);

        // Payload with 1 item: 20.00 GHS (2000 pesewas)
        var item = new PosOrderService.OrderItemInput(null, "Bottled Water 1.5L", BigDecimal.valueOf(2), 1000L, 0L, 0L, "[]", 1, "MAIN", null);
        var payment = new PosOfflineSyncService.OfflinePayment(TenderType.CASH, 2000L, "OFFLINE-CASH");

        String txId = "OFFLINE-TX-" + UUID.randomUUID();
        String idempotencyKey = "IDEM-KEY-" + UUID.randomUUID();

        var payload = new PosOfflineSyncService.OfflineOrderPayload(
                txId, idempotencyKey, merchantId, null, terminal.id(), cashier.id(), null,
                List.of(item), 0L, 0L, 0L, 2000L, List.of(payment), "ACCEPT_LOCAL_FORCE"
        );

        // 1. Initial Sync
        var result1 = syncService.syncOfflineOrder(payload);
        assertThat(result1.status()).isEqualTo("SYNCED");
        assertThat(result1.serverOrderId()).isNotNull();

        // 2. Duplicate Sync with same idempotency key
        var resultDuplicate = syncService.syncOfflineOrder(payload);
        assertThat(resultDuplicate.status()).isEqualTo("SYNCED");
        assertThat(resultDuplicate.message()).contains("Already processed");

        // 3. Conflict resolution test: Total mismatch with REJECT_FLAGGED strategy
        var conflictPayload = new PosOfflineSyncService.OfflineOrderPayload(
                "OFFLINE-TX-" + UUID.randomUUID(), "IDEM-KEY-" + UUID.randomUUID(), merchantId, null, terminal.id(), cashier.id(), null,
                List.of(item), 0L, 0L, 0L, 5000L, // local claims 5000 but calculation is 2000
                List.of(payment), "REJECT_FLAGGED"
        );
        var conflictResult = syncService.syncOfflineOrder(conflictPayload);
        assertThat(conflictResult.status()).isEqualTo("REJECTED");
        assertThat(conflictResult.resolutionApplied()).isEqualTo("REJECT_FLAGGED");
    }

    @Autowired private com.elepay.ledger.Ledger ledger;
    @Autowired(required = false) private com.elepay.identity.KillSwitchService killSwitchService;
    @Autowired private KdsService kdsService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PosController posController;
    @org.springframework.boot.test.web.server.LocalServerPort private int port;

    @Test
    void linkedDigitalCheckoutRequiresLedgerPaymentAndCannotBeAppliedTwice() {
        boolean prevWallet = killSwitchService != null && killSwitchService.isModuleEnabled("consumer-wallet");
        boolean prevW2w = killSwitchService != null && killSwitchService.isModuleEnabled("consumer-w2w");
        if (killSwitchService != null) {
            killSwitchService.setModuleEnabled("consumer-wallet", true, "pos digital checkout test", "test");
            killSwitchService.setModuleEnabled("consumer-w2w", true, "pos digital checkout test", "test");
        }
        try {
            ledger.open("merchant", merchantId, "pending", "GHS");
            ledger.open("merchant", merchantId, "available", "GHS");
            var order = orderService.createOrder(merchantId, null, null, null, null,
                    List.of(new PosOrderService.OrderItemInput(null, "Retail item", BigDecimal.ONE, 3000L, 0L, 0L, "[]", null, null, null)),
                    0L, 0L, 0L, "STANDARD");
            orderService.addTenderPayment(order.id(), TenderType.CASH, 1000L, "CASH");
            var link = orderService.startDigital(order.id(), "integration-test");
            assertThat(link.amount()).isEqualTo(2000L);
            assertThat(orderService.startDigital(order.id(), "integration-test").reference()).isEqualTo(link.reference());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderService.confirmDigital(order.id()))
                    .isInstanceOf(com.elepay.common.ApiException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderService.addTenderPayment(order.id(), TenderType.CASH, 2000L, "SECOND"))
                    .isInstanceOf(com.elepay.common.ApiException.class);

            var api = new com.elepay.TestApi(port);
            String subject = UUID.randomUUID().toString();
            String ops = api.token(subject + "-ops", "ops");
            String customerToken = api.token(subject);
            UUID customer = api.enroll(ops, subject, com.elepay.TestApi.uniquePhone(), "POS test customer");
            ledger.post(com.elepay.ledger.Ledger.Posting.of("pos-test", subject, "topup", List.of(
                    com.elepay.ledger.Ledger.Leg.debit(com.elepay.ledger.Ledger.clearing("sandbox"), 2000L),
                    com.elepay.ledger.Ledger.Leg.credit(com.elepay.wallet.CustomerDirectory.available(customer), 2000L))));
            var paid = api.call("POST", "/v1/checkouts/" + link.reference() + "/pay/wallet", customerToken,
                    UUID.randomUUID().toString(), null);
            assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
            var confirmed = orderService.confirmDigital(order.id());
            assertThat(confirmed.status()).isEqualTo("PAID");
            assertThat(confirmed.paidPesewas()).isEqualTo(3000L);
            assertThat(orderService.confirmDigital(order.id())).isEqualTo(confirmed);
            assertThat(orderService.listTenderPayments(order.id())).hasSize(2);
            assertThat(ledger.integrity().ok()).isTrue();
        } finally {
            if (killSwitchService != null) {
                killSwitchService.setModuleEnabled("consumer-wallet", prevWallet, "restore test", "test");
                killSwitchService.setModuleEnabled("consumer-w2w", prevW2w, "restore test", "test");
            }
        }
    }

    @Test
    void cashCannotBypassOpenDigitalCheckoutThroughEitherTenderEndpoint() {
        var order = orderService.createOrder(merchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Item", BigDecimal.ONE, 4500L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "STANDARD");
        orderService.startDigital(order.id(), "cashier-test");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderService.addTenderPayment(order.id(), TenderType.CASH, 4500L, null))
                .isInstanceOf(com.elepay.common.ApiException.class).hasMessageContaining("linked digital checkouts");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderService.initiateTender(order.id(),
                new PosOrderService.TenderRequest(TenderType.CASH, 4500L, null, null, null, null, null), "cashier-test"))
                .isInstanceOf(com.elepay.common.ApiException.class).hasMessageContaining("linked digital checkouts");
        assertThat(orderService.getOrder(order.id()).orElseThrow().paidPesewas()).isZero();
    }

    @Test
    void existingSplitCheckoutCannotBeReplacedByDifferentAmountOrWholeBalance() {
        var order = orderService.createOrder(merchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Item", BigDecimal.ONE, 4500L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "STANDARD");
        orderService.initiateTender(order.id(),
                new PosOrderService.TenderRequest(TenderType.WALLET, 2000L, "split-1", null, null, null, null), "cashier-test");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderService.startDigital(order.id(), "cashier-test"))
                .isInstanceOf(com.elepay.common.ApiException.class).hasMessageContaining("split checkouts");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderService.initiateTender(order.id(),
                new PosOrderService.TenderRequest(TenderType.WALLET, 2500L, "split-1", null, null, null, null), "cashier-test"))
                .isInstanceOf(com.elepay.common.ApiException.class).hasMessageContaining("different amount");
        assertThat(orderService.getOrder(order.id()).orElseThrow().paidPesewas()).isZero();
    }

    @Test
    @DisplayName("GHQR tender wires checkout rail attempt and settles order + KDS ticket on CheckoutPaidEvent")
    void ghqrTenderWiresCheckoutRailAndSettlesOrderWithKdsTicket() {
        var order = orderService.createOrder(
                merchantId, null, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Jollof & Chicken", BigDecimal.ONE, 4500L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "TAKEAWAY");

        var tender = orderService.initiateTender(
                order.id(),
                new PosOrderService.TenderRequest(TenderType.GHQR, 4500L, null, null, null, "payer@elepay.gh", null),
                "cashier-ghqr");

        assertThat(tender.tenderType()).isEqualTo(TenderType.GHQR);
        assertThat(tender.checkoutRef()).isNotBlank();
        assertThat(tender.attemptId()).isNotNull();
        assertThat(tender.qrReference()).startsWith("GHQR-");
        assertThat(tender.qrPayload()).isNotBlank();

        String attemptMethod = db.sql("SELECT method FROM payment_attempt WHERE id = :id")
                .param("id", tender.attemptId())
                .query(String.class)
                .single();
        assertThat(attemptMethod).isEqualTo("ghqr");

        // Simulate rail settlement event reaching PosOrderService.onCheckoutPaid with a real journal_transaction
        ledger.open("merchant", merchantId, "pending", "GHS");
        var posted = ledger.post(com.elepay.ledger.Ledger.Posting.of("pos-ghqr-test", tender.checkoutRef(), "ghqr_sale", List.of(
                com.elepay.ledger.Ledger.Leg.debit(com.elepay.ledger.Ledger.clearing("sandbox"), 4500L),
                com.elepay.ledger.Ledger.Leg.credit(com.elepay.ledger.Ledger.code("merchant", merchantId, "pending", "GHS"), 4500L))));
        eventPublisher.publishEvent(new com.elepay.checkout.PosCheckoutGateway.CheckoutPaidEvent(
                tender.checkoutRef(), merchantId, null, "ghqr", 4500L, posted.transactionId()));

        var settled = orderService.getOrder(order.id()).orElseThrow();
        assertThat(settled.status()).isEqualTo("PAID");
        assertThat(settled.paidPesewas()).isEqualTo(4500L);
        assertThat(orderService.listTenderPayments(order.id()))
                .singleElement()
                .satisfies(p -> assertThat(p.tenderType()).isEqualTo("GHQR"));

        var tickets = kdsService.sync(merchantId, 0, null).tickets();
        assertThat(tickets).anySatisfy(v -> assertThat(v.ticket().orderId()).isEqualTo(order.id()));
    }

    @Test
    @DisplayName("PosController.pairTerminal supports ECDSA P-256 attestation and PosDevicePaths allows order nfc-tap")
    void pairTerminalEndpointSupportsEcdsaP256Attestation() throws Exception {
        assertThat(com.elepay.identity.PosDevicePaths.allowed("POST", "/v1/pos/orders/ord-1/nfc-tap")).isTrue();
        assertThat(com.elepay.identity.PosDevicePaths.allowed("POST", "/v1/pos/terminals/term-1/nfc-tap")).isTrue();

        String serial = "SN-ATT-" + UUID.randomUUID().toString().substring(0, 8);
        var registered = terminalService.registerTerminal(merchantId, null, serial, "PAX A920 Pro");
        String merchantCode = db.sql("SELECT merchant_code FROM merchant WHERE id = :id")
                .param("id", merchantId)
                .query(String.class)
                .single();

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        String pubBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        String challenge = "pos-pair:" + merchantCode + ":" + serial + ":" + registered.pairingCode();
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(kp.getPrivate());
        sig.update(challenge.getBytes(StandardCharsets.UTF_8));
        String sigBase64 = Base64.getEncoder().encodeToString(sig.sign());

        String ownerSubject = "pos-owner-" + UUID.randomUUID();
        db.sql("INSERT INTO merchant_member (merchant_id, subject, role) VALUES (:m, :s, 'owner')")
                .param("m", merchantId).param("s", ownerSubject).update();
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(ownerSubject)
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(300))
                .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                        jwt, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
        PosTerminalService.PairingResult res;
        try {
            res = posController.pairTerminal(new PosController.PairTerminalRequest(
                    serial, registered.pairingCode(), "HW-FP-ATT-01", null,
                    pubBase64, challenge, sigBase64, "ECDSA_P256"));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        assertThat(res.status()).isEqualTo("ACTIVE");
        assertThat(res.initialKsn()).hasSize(20);

        String storedFmt = db.sql("SELECT attestation_format FROM pos_terminal WHERE id = :id")
                .param("id", registered.id())
                .query(String.class)
                .single();
        assertThat(storedFmt).isEqualTo("ECDSA_P256");
    }
}
