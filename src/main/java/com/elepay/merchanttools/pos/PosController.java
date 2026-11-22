package com.elepay.merchanttools.pos;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/pos")
public class PosController {
    private final PosTerminalService terminalService;
    private final PosCashierService cashierService;
    private final PosCartService cartService;
    private final PosOrderService orderService;
    private final PosSplitBillService splitBillService;
    private final PosReceiptService receiptService;
    private final PosOfflineSyncService syncService;

    public PosController(
            PosTerminalService terminalService,
            PosCashierService cashierService,
            PosCartService cartService,
            PosOrderService orderService,
            PosSplitBillService splitBillService,
            PosReceiptService receiptService,
            PosOfflineSyncService syncService
    ) {
        this.terminalService = terminalService;
        this.cashierService = cashierService;
        this.cartService = cartService;
        this.orderService = orderService;
        this.splitBillService = splitBillService;
        this.receiptService = receiptService;
        this.syncService = syncService;
    }

    // Terminal endpoints
    public record RegisterTerminalRequest(UUID merchantId, UUID branchId, String serialNumber, String model) {}

    @PostMapping("/terminals/register")
    public PosTerminalService.PosTerminal registerTerminal(@RequestBody RegisterTerminalRequest req) {
        return terminalService.registerTerminal(req.merchantId(), req.branchId(), req.serialNumber(), req.model());
    }

    public record PairTerminalRequest(String serialNumber, String pairingCode, String hardwareFingerprint) {}

    @PostMapping("/terminals/pair")
    public PosTerminalService.PairingResult pairTerminal(@RequestBody PairTerminalRequest req) {
        return terminalService.pairTerminal(req.serialNumber(), req.pairingCode(), req.hardwareFingerprint());
    }

    @GetMapping("/terminals/merchant/{merchantId}")
    public List<PosTerminalService.PosTerminal> listTerminals(@PathVariable UUID merchantId) {
        return terminalService.listTerminals(merchantId);
    }

    // Cashier & Shift endpoints
    public record RegisterCashierRequest(UUID merchantId, String name, String pin, String badgeNumber) {}

    @PostMapping("/cashiers/register")
    public PosCashierService.Cashier registerCashier(@RequestBody RegisterCashierRequest req) {
        return cashierService.registerCashier(req.merchantId(), req.name(), req.pin(), req.badgeNumber());
    }

    public record AuthenticateCashierRequest(UUID merchantId, String badgeNumber, String pin) {}

    @PostMapping("/cashiers/auth")
    public ResponseEntity<Void> authenticateCashier(@RequestBody AuthenticateCashierRequest req) {
        boolean valid = cashierService.authenticateCashier(req.merchantId(), req.badgeNumber(), req.pin());
        return valid ? ResponseEntity.ok().build() : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    public record OpenShiftRequest(UUID terminalId, UUID cashierId, UUID merchantId, long openingBalancePesewas, String notes) {}

    @PostMapping("/shifts/open")
    public PosCashierService.Shift openShift(@RequestBody OpenShiftRequest req) {
        return cashierService.openShift(req.terminalId(), req.cashierId(), req.merchantId(), req.openingBalancePesewas(), req.notes());
    }

    public record CloseShiftRequest(long closingBalancePesewas, String notes) {}

    @PostMapping("/shifts/{shiftId}/close")
    public PosCashierService.Shift closeShift(@PathVariable UUID shiftId, @RequestBody CloseShiftRequest req) {
        return cashierService.closeShift(shiftId, req.closingBalancePesewas(), req.notes());
    }

    @GetMapping("/shifts/active/{terminalId}")
    public ResponseEntity<PosCashierService.Shift> getActiveShift(@PathVariable UUID terminalId) {
        return cashierService.getActiveShift(terminalId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Held Cart endpoints
    public record HoldCartRequest(UUID terminalId, UUID cashierId, UUID merchantId, String customerName, String cartPayloadJson, long totalPesewas) {}

    @PostMapping("/carts/hold")
    public PosCartService.HeldCart holdCart(@RequestBody HoldCartRequest req) {
        return cartService.holdCart(req.terminalId(), req.cashierId(), req.merchantId(), req.customerName(), req.cartPayloadJson(), req.totalPesewas());
    }

    @GetMapping("/carts/held/{terminalId}")
    public List<PosCartService.HeldCart> listHeldCarts(@PathVariable UUID terminalId) {
        return cartService.listHeldCarts(terminalId);
    }

    @PostMapping("/carts/held/{cartId}/recall")
    public PosCartService.HeldCart recallHeldCart(@PathVariable UUID cartId) {
        return cartService.recallHeldCart(cartId);
    }

    // Order & Tender Payment endpoints
    public record CreateOrderRequest(
            UUID merchantId, UUID branchId, UUID terminalId, UUID cashierId, UUID customerId,
            List<PosOrderService.OrderItemInput> items, long discountPesewas, long taxPesewas,
            long tipPesewas, String orderType
    ) {}

    @PostMapping("/orders")
    public PosOrderService.PosOrder createOrder(@RequestBody CreateOrderRequest req) {
        return orderService.createOrder(
                req.merchantId(), req.branchId(), req.terminalId(), req.cashierId(), req.customerId(),
                req.items(), req.discountPesewas(), req.taxPesewas(), req.tipPesewas(), req.orderType()
        );
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<PosOrderService.PosOrder> getOrder(@PathVariable UUID orderId) {
        return orderService.getOrder(orderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    public record TenderPaymentRequest(TenderType tenderType, long amountPesewas, String reference) {}

    @PostMapping("/orders/{orderId}/payments")
    public PosOrderService.PosOrder addPayment(@PathVariable UUID orderId, @RequestBody TenderPaymentRequest req) {
        return orderService.addTenderPayment(orderId, req.tenderType(), req.amountPesewas(), req.reference());
    }

    // Split Bills
    @PostMapping("/orders/{orderId}/split/even")
    public PosSplitBillService.EvenSplitResult splitBillEven(@PathVariable UUID orderId, @RequestParam int count) {
        return splitBillService.splitBillEven(orderId, count);
    }

    @PostMapping("/orders/{orderId}/split/seats")
    public PosSplitBillService.SeatSplitResult splitBillBySeats(@PathVariable UUID orderId) {
        return splitBillService.splitBillBySeats(orderId);
    }

    // Receipts
    @PostMapping("/orders/{orderId}/receipt")
    public PosReceiptService.PosReceipt generateReceipt(@PathVariable UUID orderId) {
        return receiptService.generateReceipt(orderId);
    }

    @GetMapping("/orders/{orderId}/receipt")
    public ResponseEntity<PosReceiptService.PosReceipt> getReceipt(@PathVariable UUID orderId) {
        return receiptService.findByOrderId(orderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Offline Sync
    @PostMapping("/sync")
    public PosOfflineSyncService.SyncResult syncOfflineOrder(@RequestBody PosOfflineSyncService.OfflineOrderPayload payload) {
        return syncService.syncOfflineOrder(payload);
    }
}
