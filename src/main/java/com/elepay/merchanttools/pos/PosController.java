package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import com.elepay.emtech.DeviceEvents;
import com.elepay.common.Idempotency;
import com.elepay.merchant.MerchantAccess;
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
    private final MerchantAccess merchants;

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
            MerchantAccess merchants
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
        this.merchants = merchants;
    }

    // Terminal endpoints
    public record RegisterTerminalRequest(UUID merchantId, UUID branchId, String serialNumber, String model,
                                          String city, String region, String country, String address, String location,
                                          String latitude, String longitude, String connectionType, String manufacturer,
                                          Integer manufactureYear, String deviceName, String deviceDescription) {}

    @PostMapping("/terminals/register")
    public PosTerminalService.RegisteredTerminal registerTerminal(@RequestBody RegisterTerminalRequest req) {
        access.manage(req.merchantId());
        access.branch(req.merchantId(), req.branchId());
        return terminalService.registerTerminalWithPairingCode(req.merchantId(), req.branchId(), req.serialNumber(), req.model(),
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

    public record PairTerminalRequest(
            String serialNumber,
            String pairingCode,
            String hardwareFingerprint,
            String merchantCode,
            String attestationPublicKey,
            String attestationChallenge,
            String attestationSignature,
            String keyAlgorithm
    ) {
        public PairTerminalRequest(String serialNumber, String pairingCode, String hardwareFingerprint) {
            this(serialNumber, pairingCode, hardwareFingerprint, null, null, null, null, null);
        }
    }

    @PostMapping("/terminals/pair")
    public PosTerminalService.PairingResult pairTerminal(@RequestBody PairTerminalRequest req) {
        var terminal = terminalService.findBySerialNumber(req.serialNumber()).orElseThrow(() -> ApiException.notFound("Terminal not found"));
        access.manage(terminal.merchantId());
        boolean hasAttestation = (req.attestationPublicKey() != null && !req.attestationPublicKey().isBlank())
                || (req.attestationSignature() != null && !req.attestationSignature().isBlank())
                || (req.attestationChallenge() != null && !req.attestationChallenge().isBlank())
                || (req.keyAlgorithm() != null && !req.keyAlgorithm().isBlank());
        if (hasAttestation) {
            String merchantCode = (req.merchantCode() != null && !req.merchantCode().isBlank())
                    ? req.merchantCode().trim()
                    : merchants.find(terminal.merchantId()).map(MerchantAccess.Merchant::merchantCode).orElse(null);
            String expectedChallenge = "pos-pair:" + merchantCode + ":" + req.serialNumber() + ":" + (req.pairingCode() == null ? "" : req.pairingCode());
            if (req.attestationChallenge() != null && !req.attestationChallenge().isBlank()
                    && !expectedChallenge.equals(req.attestationChallenge())) {
                throw ApiException.badRequest("invalid_attestation_challenge", "Attestation challenge does not match expected pairing payload");
            }
            return terminalService.pairTerminal(
                    merchantCode,
                    req.serialNumber(),
                    req.pairingCode(),
                    req.hardwareFingerprint(),
                    req.attestationPublicKey(),
                    req.keyAlgorithm(),
                    req.attestationSignature()
            );
        }
        return terminalService.pairTerminal(req.serialNumber(), req.pairingCode(), req.hardwareFingerprint());
    }

    @GetMapping("/terminals/merchant/{merchantId}")
    public List<PosTerminalService.PosTerminal> listTerminals(@PathVariable UUID merchantId) {
        var caller = access.viewAccess(merchantId);
        List<PosTerminalService.PosTerminal> terminals = terminalService.listTerminals(merchantId);
        if (caller.branchScoped()) {
            return terminals.stream()
                    .filter(t -> java.util.Objects.equals(caller.branchId(), t.branchId()))
                    .toList();
        }
        return terminals;
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
    public ResponseEntity<PosCashierService.Cashier> authenticateCashier(@RequestBody AuthenticateCashierRequest req) {
        access.operateAccess(req.merchantId());
        boolean valid = cashierService.authenticateCashier(req.merchantId(), req.badgeNumber(), req.pin());
        if (!valid) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        PosCashierService.Cashier cashier = cashierService.findByBadge(req.merchantId(), req.badgeNumber())
                .map(PosCashierService.Cashier::withoutSecret)
                .orElseThrow(() -> new com.elepay.common.ApiException(HttpStatus.NOT_FOUND, "not_found", "Cashier not found"));
        return ResponseEntity.ok(cashier);
    }

    public record OpenShiftRequest(UUID terminalId, UUID cashierId, UUID merchantId, long openingBalancePesewas, String notes) {}

    @PostMapping("/shifts/open")
    public PosCashierService.Shift openShift(@RequestBody OpenShiftRequest req) {
        var caller = access.operateAccess(req.merchantId());
        access.belongs(req.merchantId(), TERMINAL, req.terminalId());
        access.belongs(req.merchantId(), CASHIER, req.cashierId());
        var terminal = terminalService.getTerminal(req.terminalId())
                .orElseThrow(() -> ApiException.notFound("Terminal not found"));
        access.requireBranch(caller, terminal.branchId());
        return cashierService.openShift(req.terminalId(), req.cashierId(), req.merchantId(), req.openingBalancePesewas(), req.notes());
    }

    public record CloseShiftRequest(long closingBalancePesewas, String notes) {}

    @PostMapping("/shifts/{shiftId}/close")
    public PosCashierService.Shift closeShift(@PathVariable UUID shiftId, @RequestBody CloseShiftRequest req) {
        var caller = access.operateAccess(SHIFT, shiftId);
        var shift = cashierService.getShift(shiftId)
                .orElseThrow(() -> ApiException.notFound("Shift not found"));
        var terminal = terminalService.getTerminal(shift.terminalId())
                .orElseThrow(() -> ApiException.notFound("Terminal not found"));
        access.requireBranch(caller, terminal.branchId());
        return cashierService.closeShift(shiftId, req.closingBalancePesewas(), req.notes());
    }

    @GetMapping("/shifts/active/{terminalId}")
    public ResponseEntity<PosCashierService.Shift> getActiveShift(@PathVariable UUID terminalId) {
        var caller = access.viewAccess(TERMINAL, terminalId);
        var terminal = terminalService.getTerminal(terminalId)
                .orElseThrow(() -> ApiException.notFound("Terminal not found"));
        access.requireBranch(caller, terminal.branchId());
        return cashierService.getActiveShift(terminalId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Held Cart endpoints
    public record HoldCartRequest(UUID terminalId, UUID cashierId, UUID merchantId, String customerName, String cartPayloadJson, long totalPesewas) {}

    @PostMapping("/carts/hold")
    public PosCartService.HeldCart holdCart(@RequestBody HoldCartRequest req) {
        var caller = access.operateAccess(req.merchantId());
        access.belongs(req.merchantId(), TERMINAL, req.terminalId());
        access.belongs(req.merchantId(), CASHIER, req.cashierId());
        var terminal = terminalService.getTerminal(req.terminalId())
                .orElseThrow(() -> ApiException.notFound("Terminal not found"));
        access.requireBranch(caller, terminal.branchId());
        return cartService.holdCart(req.terminalId(), req.cashierId(), req.merchantId(), req.customerName(), req.cartPayloadJson(), req.totalPesewas());
    }

    @GetMapping("/carts/held/{terminalId}")
    public List<PosCartService.HeldCart> listHeldCarts(@PathVariable UUID terminalId) {
        var caller = access.viewAccess(TERMINAL, terminalId);
        var terminal = terminalService.getTerminal(terminalId)
                .orElseThrow(() -> ApiException.notFound("Terminal not found"));
        access.requireBranch(caller, terminal.branchId());
        return cartService.listHeldCarts(terminalId);
    }

    @PostMapping("/carts/held/{cartId}/recall")
    public PosCartService.HeldCart recallHeldCart(@PathVariable UUID cartId) {
        var caller = access.operateAccess(CART, cartId);
        var cart = cartService.getHeldCart(cartId)
                .orElseThrow(() -> ApiException.notFound("Held cart not found"));
        var terminal = terminalService.getTerminal(cart.terminalId())
                .orElseThrow(() -> ApiException.notFound("Terminal not found"));
        access.requireBranch(caller, terminal.branchId());
        return cartService.recallHeldCart(cartId);
    }

    // Order & Tender Payment endpoints
    public record CreateOrderRequest(
            UUID merchantId, UUID branchId, UUID terminalId, UUID cashierId, UUID customerId,
            List<PosOrderService.OrderItemInput> items, long discountPesewas, long taxPesewas,
            long tipPesewas, String orderType
    ) {}

    @PostMapping("/orders")
    @Transactional
    public PosOrderService.PosOrder createOrder(
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody CreateOrderRequest req) {
        var caller = access.operateAccess(req.merchantId());
        access.requireBranch(caller, req.branchId());
        verifyOrderInputs(req.merchantId(), req.branchId(), req.terminalId(), req.cashierId(), req.customerId(), req.items());
        return idempotency.execute("pos-order:" + req.merchantId(), key, req,
                PosOrderService.PosOrder.class,
                () -> orderService.createOrder(
                        req.merchantId(), req.branchId(), req.terminalId(), req.cashierId(), req.customerId(),
                        req.items(), req.discountPesewas(), req.taxPesewas(), req.tipPesewas(), req.orderType()
                ));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<PosOrderService.PosOrder> getOrder(@PathVariable UUID orderId) {
        verifyOrder(orderId);
        return orderService.getOrder(orderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/orders/{orderId}/items")
    public List<PosOrderService.PosOrderItem> listOrderItems(@PathVariable UUID orderId) {
        var caller = access.viewAccess(ORDER, orderId);
        var order = orderService.getOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        access.requireBranch(caller, order.branchId());
        return orderService.listOrderItems(orderId);
    }

    @PostMapping("/orders/{orderId}/void")
    public PosOrderService.PosOrder voidOrder(@PathVariable UUID orderId) {
        verifyOrder(orderId);
        return orderService.voidOrder(orderId, com.elepay.identity.Actor.current().subject());
    }

    public record TenderPaymentRequest(TenderType tenderType, long amountPesewas, String reference) {}

    @PostMapping("/orders/{orderId}/payments")
    @Transactional
    public PosOrderService.PosOrder addPayment(@PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody TenderPaymentRequest req) {
        var order = verifyOrder(orderId);
        if (req.tenderType() != TenderType.CASH)
            throw ApiException.unprocessable("digital_payment_use_tenders",
                    "Use POST /v1/pos/orders/{orderId}/tenders (MoMo/GhQR/Card) or /nfc-tap for digital payments");
        return idempotency.execute("pos-cash:" + order.merchantId() + ":" + orderId, key, req,
                PosOrderService.PosOrder.class,
                () -> orderService.addTenderPayment(orderId, req.tenderType(), req.amountPesewas(), req.reference()));
    }

    @PostMapping("/orders/{orderId}/tenders")
    @Transactional
    public PosOrderService.TenderResult initiateTender(
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody PosOrderService.TenderRequest req) {
        var order = verifyOrder(orderId);
        String actor = com.elepay.identity.Actor.current().subject();
        return idempotency.execute("pos-tender:" + order.merchantId() + ":" + orderId, key, req,
                PosOrderService.TenderResult.class,
                () -> orderService.initiateTender(orderId, req, actor));
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
        access.manage(payload.merchantId());
        verifyOrderInputs(payload.merchantId(), payload.branchId(), payload.terminalId(), payload.cashierId(), payload.customerId(), payload.items());
        return syncService.syncOfflineOrder(payload);
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
        var caller = access.operateAccess(ORDER, id);
        var order = orderService.getOrder(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        access.requireBranch(caller, order.branchId());
        access.branch(order.merchantId(), order.branchId());
        if (order.terminalId() != null) access.belongs(order.merchantId(), TERMINAL, order.terminalId());
        if (order.cashierId() != null) access.belongs(order.merchantId(), CASHIER, order.cashierId());
        orderService.listOrderItems(id).forEach(item -> {
            if (item.variantId() != null) access.belongs(order.merchantId(), VARIANT, item.variantId());
        });
        return order;
    }
}
