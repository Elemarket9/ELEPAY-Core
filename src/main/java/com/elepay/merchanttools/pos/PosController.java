package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import com.elepay.emtech.DeviceEvents;
import com.elepay.common.Idempotency;
import com.elepay.merchanttools.MerchantToolAccess;
import static com.elepay.merchanttools.MerchantToolAccess.Resource.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/pos")
public class PosController {
    private final MerchantToolAccess access;
    private final Idempotency idempotency;
    private final PosTerminalService terminalService;
    private final PosCashierService cashierService;
    private final PosCartService cartService;
    private final PosOrderService orderService;
    private final PosSplitBillService splitBillService;
    private final PosReceiptService receiptService;
    private final PosOfflineSyncService syncService;
    private final DeviceEvents deviceEvents;
    private final PosTerminalAcceptanceService cardPresent;
    private final PosPaymentCodeService paymentCodes;

    public PosController(
            MerchantToolAccess access,
            Idempotency idempotency,
            PosTerminalService terminalService,
            PosCashierService cashierService,
            PosCartService cartService,
            PosOrderService orderService,
            PosSplitBillService splitBillService,
            PosReceiptService receiptService,
            PosOfflineSyncService syncService,
            DeviceEvents deviceEvents,
            PosTerminalAcceptanceService cardPresent,
            PosPaymentCodeService paymentCodes
    ) {
        this.access = access;
        this.idempotency = idempotency;
        this.terminalService = terminalService;
        this.cashierService = cashierService;
        this.cartService = cartService;
        this.orderService = orderService;
        this.splitBillService = splitBillService;
        this.receiptService = receiptService;
        this.syncService = syncService;
        this.deviceEvents = deviceEvents;
        this.cardPresent = cardPresent;
        this.paymentCodes = paymentCodes;
    }

    // Terminal endpoints
    public record RegisterTerminalRequest(UUID merchantId, UUID branchId, String serialNumber, String model,
                                          String city, String region, String country, String address, String location,
                                          String latitude, String longitude, String connectionType, String manufacturer,
                                          Integer manufactureYear, String deviceName, String deviceDescription) {}

    @PostMapping("/terminals/register")
    public PosTerminalService.PosTerminal registerTerminal(@RequestBody RegisterTerminalRequest req) {
        access.manage(req.merchantId());
        access.branch(req.merchantId(), req.branchId());
        return terminalService.registerTerminal(req.merchantId(), req.branchId(), req.serialNumber(), req.model(),
                new PosTerminalService.RegulatoryProfile(req.city(), req.region(), req.country(), req.address(),
                        req.location(), req.latitude(), req.longitude(), req.connectionType(), req.manufacturer(),
                        req.manufactureYear(), req.deviceName(), req.deviceDescription()));
    }

    public record DeviceEventRequest(String health, String status, String description) {}

    @PostMapping("/terminals/{terminalId}/events")
    public DeviceEvents.Event terminalEvent(@PathVariable UUID terminalId, @RequestBody DeviceEventRequest req) {
        access.manage(com.elepay.merchanttools.MerchantToolAccess.Resource.TERMINAL, terminalId);
        var terminal = terminalService.getTerminal(terminalId).orElseThrow(() -> ApiException.notFound("Terminal not found"));
        return deviceEvents.record(terminalId, null, terminal.id().toString(), req.health(), req.status(),
                req.description(), null);
    }

    @PutMapping("/terminals/{terminalId}/regulatory")
    public PosTerminalService.PosTerminal saveRegulatory(@PathVariable UUID terminalId,
                                                         @RequestBody PosTerminalService.RegulatoryProfile profile) {
        access.manage(com.elepay.merchanttools.MerchantToolAccess.Resource.TERMINAL, terminalId);
        return terminalService.saveRegulatory(terminalId, profile);
    }

    public record PairTerminalRequest(String serialNumber, String pairingCode, String hardwareFingerprint) {}

    @PostMapping("/terminals/pair")
    public PosTerminalService.PairingResult pairTerminal(@RequestBody PairTerminalRequest req) {
        var terminal = terminalService.findBySerialNumber(req.serialNumber()).orElseThrow(() -> ApiException.notFound("Terminal not found"));
        access.manage(terminal.merchantId());
        return terminalService.pairTerminal(req.serialNumber(), req.pairingCode(), req.hardwareFingerprint());
    }

    @GetMapping("/terminals/merchant/{merchantId}")
    public List<PosTerminalService.PosTerminal> listTerminals(@PathVariable UUID merchantId) {
        access.manage(merchantId);
        return terminalService.listTerminals(merchantId);
    }

    /** Only paired active terminals belonging to the operator's branch are offered for payment. */
    public record TerminalChoice(UUID id, String serialNumber, String model, UUID branchId) {}

    @GetMapping("/terminals/available/{merchantId}")
    public List<TerminalChoice> availableTerminals(@PathVariable UUID merchantId) {
        UUID allowedBranch = access.operatingBranch(merchantId);
        return terminalService.listTerminals(merchantId).stream()
                .filter(t -> "ACTIVE".equals(t.status()))
                .filter(t -> allowedBranch == null || java.util.Objects.equals(allowedBranch, t.branchId()))
                .map(t -> new TerminalChoice(t.id(), t.serialNumber(), t.model(), t.branchId()))
                .toList();
    }

    // Cashier & Shift endpoints
    public record RegisterCashierRequest(UUID merchantId, String name, String pin, String badgeNumber) {}

    @PostMapping("/cashiers/register")
    public PosCashierService.Cashier registerCashier(@RequestBody RegisterCashierRequest req) {
        access.manage(req.merchantId());
        return cashierService.registerCashier(req.merchantId(), req.name(), req.pin(), req.badgeNumber()).withoutSecret();
    }

    public record AuthenticateCashierRequest(UUID merchantId, String badgeNumber, String pin) {}

    @PostMapping("/cashiers/auth")
    public ResponseEntity<Void> authenticateCashier(@RequestBody AuthenticateCashierRequest req) {
        access.manage(req.merchantId());
        boolean valid = cashierService.authenticateCashier(req.merchantId(), req.badgeNumber(), req.pin());
        return valid ? ResponseEntity.ok().build() : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    public record OpenShiftRequest(UUID terminalId, UUID cashierId, UUID merchantId, long openingBalancePesewas, String notes) {}

    @PostMapping("/shifts/open")
    public PosCashierService.Shift openShift(@RequestBody OpenShiftRequest req) {
        access.manage(req.merchantId());
        access.belongs(req.merchantId(), TERMINAL, req.terminalId());
        access.belongs(req.merchantId(), CASHIER, req.cashierId());
        return cashierService.openShift(req.terminalId(), req.cashierId(), req.merchantId(), req.openingBalancePesewas(), req.notes());
    }

    public record CloseShiftRequest(long closingBalancePesewas, String notes) {}

    @PostMapping("/shifts/{shiftId}/close")
    public PosCashierService.Shift closeShift(@PathVariable UUID shiftId, @RequestBody CloseShiftRequest req) {
        access.manage(SHIFT, shiftId);
        return cashierService.closeShift(shiftId, req.closingBalancePesewas(), req.notes());
    }

    @GetMapping("/shifts/active/{terminalId}")
    public ResponseEntity<PosCashierService.Shift> getActiveShift(@PathVariable UUID terminalId) {
        access.manage(TERMINAL, terminalId);
        return cashierService.getActiveShift(terminalId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Held Cart endpoints
    public record HoldCartRequest(UUID terminalId, UUID cashierId, UUID merchantId, String customerName, String cartPayloadJson, long totalPesewas) {}

    @PostMapping("/carts/hold")
    public PosCartService.HeldCart holdCart(@RequestBody HoldCartRequest req) {
        access.manage(req.merchantId());
        access.belongs(req.merchantId(), TERMINAL, req.terminalId());
        access.belongs(req.merchantId(), CASHIER, req.cashierId());
        return cartService.holdCart(req.terminalId(), req.cashierId(), req.merchantId(), req.customerName(), req.cartPayloadJson(), req.totalPesewas());
    }

    @GetMapping("/carts/held/{terminalId}")
    public List<PosCartService.HeldCart> listHeldCarts(@PathVariable UUID terminalId) {
        access.manage(TERMINAL, terminalId);
        return cartService.listHeldCarts(terminalId);
    }

    @PostMapping("/carts/held/{cartId}/recall")
    public PosCartService.HeldCart recallHeldCart(@PathVariable UUID cartId) {
        access.manage(CART, cartId);
        return cartService.recallHeldCart(cartId);
    }

    // Order & Tender Payment endpoints
    public record CreateOrderRequest(
            UUID merchantId, UUID branchId, UUID terminalId, UUID cashierId, UUID customerId,
            List<PosOrderService.OrderItemInput> items, long discountPesewas, long taxPesewas,
            long tipPesewas, String orderType, UUID tableId
    ) {}

    @PostMapping("/orders")
    public PosOrderService.PosOrder createOrder(@RequestBody CreateOrderRequest req) {
        access.operate(req.merchantId());
        verifyOrderInputs(req.merchantId(), req.branchId(), req.terminalId(), req.cashierId(), req.customerId(), req.items());
        if (req.tableId() != null) access.belongs(req.merchantId(), TABLE, req.tableId());
        return orderService.createOrder(
                req.merchantId(), req.branchId(), req.terminalId(), req.cashierId(), req.customerId(),
                req.items(), req.discountPesewas(), req.taxPesewas(), req.tipPesewas(), req.orderType(),
                req.tableId(), com.elepay.identity.Actor.current().subject()
        );
    }

    public record AddRoundRequest(List<PosOrderService.OrderItemInput> items) {}

    @PostMapping("/orders/{orderId}/items")
    @Transactional
    public PosOrderService.PosOrder addRound(@PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody AddRoundRequest req) {
        var order = verifyOrder(orderId);
        if (req == null || req.items() == null || req.items().isEmpty())
            throw ApiException.badRequest("items_required", "Choose items for the new round");
        for (var item : req.items()) {
            if (item == null) throw ApiException.badRequest("item_required", "Invalid order item");
            if (item.variantId() != null) access.belongs(order.merchantId(), VARIANT, item.variantId());
        }
        return idempotency.execute("pos-add-items:" + order.merchantId() + ":" + orderId, key, req,
                PosOrderService.PosOrder.class,
                () -> orderService.addItems(orderId, req.items(), key));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<PosOrderService.PosOrder> getOrder(@PathVariable UUID orderId) {
        verifyOrder(orderId);
        return orderService.getOrder(orderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    public record TenderPaymentRequest(TenderType tenderType, long amountPesewas, String reference) {}

    @PostMapping("/orders/{orderId}/payments")
    @Transactional
    public PosOrderService.PosOrder addPayment(@PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody TenderPaymentRequest req) {
        var order = verifyOrder(orderId);
        if (req.tenderType() != TenderType.CASH)
            throw ApiException.unprocessable("digital_payment_unverified", "Use ELEPAY checkout for digital payment; POS capture is not connected yet");
        return idempotency.execute("pos-cash:" + order.merchantId() + ":" + orderId, key, req,
                PosOrderService.PosOrder.class,
                () -> orderService.addTenderPayment(orderId, req.tenderType(), req.amountPesewas(), req.reference()));
    }

    @PostMapping("/orders/{orderId}/digital-checkout")
    public PosOrderService.DigitalCheckout startDigital(@PathVariable UUID orderId) {
        verifyOrder(orderId);
        return orderService.startDigital(orderId, com.elepay.identity.Actor.current().subject());
    }

    @PostMapping("/orders/{orderId}/digital-checkout/confirm")
    public PosOrderService.PosOrder confirmDigital(@PathVariable UUID orderId) {
        verifyOrder(orderId);
        return orderService.confirmDigital(orderId);
    }

    @PostMapping("/orders/{orderId}/digital-checkout/cancel")
    public ResponseEntity<Void> cancelDigital(@PathVariable UUID orderId) {
        verifyOrder(orderId);
        orderService.cancelDigital(orderId, com.elepay.identity.Actor.current().subject());
        return ResponseEntity.noContent().build();
    }

    /** Generate merchant-presented QR/2D-barcode payment data bound to a real pending checkout.
     * Scanning the code opens the customer's hosted checkout; it never means payment succeeded.
     */
    public record PaymentCode(String checkoutReference, String barcodeValue, String qrPayload,
                              long amountPesewas, String status) {}

    @PostMapping("/orders/{orderId}/payment-code")
    public PaymentCode paymentCode(@PathVariable UUID orderId) {
        var order = verifyOrder(orderId);
        var checkout = orderService.startDigital(order.id(), com.elepay.identity.Actor.current().subject());
        if (!"open".equals(checkout.status()))
            throw ApiException.conflict("checkout_not_open", "Payment code is no longer active");
        return new PaymentCode(checkout.reference(), "ELEPAY-PAY:" + checkout.reference(),
                checkout.payUrl(), checkout.amount(), "PENDING");
    }

    public record ScanPaymentCodeRequest(String barcodeValue) {}

    /**
     * Cashier/scanner may resolve a merchant-presented Code128 reference to an existing
     * pending checkout; it cannot mark an order paid without the payment rail.
     */
    @PostMapping("/payment-codes/resolve")
    public PaymentCode resolvePaymentCode(@RequestBody ScanPaymentCodeRequest req) {
        String value = req.barcodeValue();
        if (value == null || !value.startsWith("ELEPAY-PAY:") || value.length() > 160)
            throw ApiException.badRequest("payment_barcode_invalid", "Scan a valid ELEPAY payment barcode");
        String reference = value.substring("ELEPAY-PAY:".length());
        if (reference.isBlank()) throw ApiException.badRequest("payment_barcode_invalid", "Payment barcode has no reference");
        UUID orderId = paymentCodes.orderForReference(reference);
        var order = verifyOrder(orderId);
        var checkout = orderService.startDigital(order.id(), com.elepay.identity.Actor.current().subject());
        if (!"open".equals(checkout.status()) || !reference.equals(checkout.reference()))
            throw ApiException.conflict("checkout_not_open", "Payment barcode is no longer active");
        return new PaymentCode(reference, value, checkout.payUrl(), checkout.amount(), "PENDING");
    }

    public record TerminalPaymentRequest(UUID terminalId, PosTerminalAcceptanceService.Mode mode) {}

    @PostMapping("/orders/{orderId}/terminal-payments")
    @Transactional
    public PosTerminalAcceptanceService.TerminalSession startTerminalPayment(
            @PathVariable UUID orderId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody TerminalPaymentRequest req) {
        var order = verifyOrder(orderId);
        UUID allowedBranch = access.operatingBranch(order.merchantId());
        if (allowedBranch != null && !java.util.Objects.equals(allowedBranch, order.branchId()))
            throw ApiException.forbidden("Order belongs to another branch");
        if (req.terminalId() == null)
            throw ApiException.badRequest("terminal_required", "Select a registered payment terminal");
        access.belongs(order.merchantId(), TERMINAL, req.terminalId());
        return idempotency.execute("pos-terminal:" + order.merchantId() + ":" + orderId, key, req,
                PosTerminalAcceptanceService.TerminalSession.class,
                () -> cardPresent.begin(orderId, req.terminalId(), req.mode(),
                        com.elepay.identity.Actor.current().subject()));
    }

    // Split Bills
    @PostMapping("/orders/{orderId}/split/even")
    public PosSplitBillService.EvenSplitResult splitBillEven(@PathVariable UUID orderId, @RequestParam int count) {
        verifyOrder(orderId);
        return splitBillService.splitBillEven(orderId, count);
    }

    @PostMapping("/orders/{orderId}/split/seats")
    public PosSplitBillService.SeatSplitResult splitBillBySeats(@PathVariable UUID orderId) {
        verifyOrder(orderId);
        return splitBillService.splitBillBySeats(orderId);
    }

    @PostMapping("/orders/{orderId}/split/items")
    public PosSplitBillService.ItemSplitResult splitBillByItem(@PathVariable UUID orderId) {
        verifyOrder(orderId);
        return splitBillService.splitBillByItem(orderId);
    }

    @PostMapping("/orders/{orderId}/split/amount")
    public PosSplitBillService.AmountSplitResult splitBillByAmount(@PathVariable UUID orderId,
            @RequestParam long firstPesewas) {
        verifyOrder(orderId);
        return splitBillService.splitBillByAmount(orderId, firstPesewas);
    }

    // Receipts
    @PostMapping("/orders/{orderId}/receipt")
    public PosReceiptService.PosReceipt generateReceipt(@PathVariable UUID orderId) {
        var order = verifyOrder(orderId);
        if (!"PAID".equals(order.status())) throw ApiException.conflict("order_unpaid", "Payment is not complete");
        return receiptService.generateReceipt(orderId);
    }

    @GetMapping("/orders/{orderId}/receipt")
    public ResponseEntity<PosReceiptService.PosReceipt> getReceipt(@PathVariable UUID orderId) {
        verifyOrder(orderId);
        return receiptService.findByOrderId(orderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Offline Sync
    @PostMapping("/sync")
    public PosOfflineSyncService.SyncResult syncOfflineOrder(@RequestBody PosOfflineSyncService.OfflineOrderPayload payload) {
        access.operate(payload.merchantId());
        verifyOrderInputs(payload.merchantId(), payload.branchId(), payload.terminalId(), payload.cashierId(), payload.customerId(), payload.items());
        return syncService.syncOfflineOrder(payload, com.elepay.identity.Actor.current().subject());
    }
    private void verifyOrderInputs(UUID merchant, UUID branch, UUID terminal, UUID cashier, UUID customer,
            List<PosOrderService.OrderItemInput> items) {
        access.branch(merchant, branch);
        if (terminal != null) {
            access.belongs(merchant, TERMINAL, terminal);
            var device = terminalService.getTerminal(terminal).orElseThrow();
            if (!java.util.Objects.equals(branch, device.branchId()))
                throw ApiException.notFound("Terminal not found in branch");
        }
        if (cashier != null) access.belongs(merchant, CASHIER, cashier);
        if (customer != null) throw ApiException.badRequest("customer_link_unverified", "POS customer linking is not enabled");
        if (items == null || items.isEmpty()) throw ApiException.badRequest("items_required", "Order items required");
        items.forEach(item -> { if (item.variantId() != null) access.belongs(merchant, VARIANT, item.variantId()); });
    }

    private PosOrderService.PosOrder verifyOrder(UUID id) {
        access.operate(ORDER, id);
        var order = orderService.getOrder(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        access.branch(order.merchantId(), order.branchId());
        if (order.terminalId() != null) access.belongs(order.merchantId(), TERMINAL, order.terminalId());
        if (order.cashierId() != null) access.belongs(order.merchantId(), CASHIER, order.cashierId());
        orderService.listOrderItems(id).forEach(item -> {
            if (item.variantId() != null) access.belongs(order.merchantId(), VARIANT, item.variantId());
        });
        return order;
    }
}
