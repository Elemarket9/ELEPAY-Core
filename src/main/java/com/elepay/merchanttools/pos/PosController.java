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
    private final PosOrderService orderService;
    private final PosSplitBillService splitBillService;
    private final PosReceiptService receiptService;
    private final PosOfflineSyncService syncService;
    private final DeviceEvents deviceEvents;

    public PosController(
            MerchantToolAccess access,
            Idempotency idempotency,
            PosTerminalService terminalService,
            PosCashierService cashierService,
            PosOrderService orderService,
            PosSplitBillService splitBillService,
            PosReceiptService receiptService,
            PosOfflineSyncService syncService,
            DeviceEvents deviceEvents
    ) {
        this.access = access;
        this.idempotency = idempotency;
        this.terminalService = terminalService;
        this.cashierService = cashierService;
        this.orderService = orderService;
        this.splitBillService = splitBillService;
        this.receiptService = receiptService;
        this.syncService = syncService;
        this.deviceEvents = deviceEvents;
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

    // Cashier & Shift endpoints
    public record RegisterCashierRequest(UUID merchantId, String name, String pin, String badgeNumber) {}

    @PostMapping("/cashiers/register")
    public PosCashierService.Cashier registerCashier(@RequestBody RegisterCashierRequest req) {
        access.manage(req.merchantId());
        return cashierService.registerCashier(req.merchantId(), req.name(), req.pin(), req.badgeNumber());
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

    // Order & Tender Payment endpoints
    public record CreateOrderRequest(
            UUID merchantId, UUID branchId, UUID terminalId, UUID cashierId, UUID customerId,
            List<PosOrderService.OrderItemInput> items, long discountPesewas, long taxPesewas,
            long tipPesewas, String orderType
    ) {}

    @PostMapping("/orders")
    public PosOrderService.PosOrder createOrder(@RequestBody CreateOrderRequest req) {
        access.manage(req.merchantId());
        verifyOrderInputs(req.merchantId(), req.branchId(), req.terminalId(), req.cashierId(), req.customerId(), req.items());
        return orderService.createOrder(
                req.merchantId(), req.branchId(), req.terminalId(), req.cashierId(), req.customerId(),
                req.items(), req.discountPesewas(), req.taxPesewas(), req.tipPesewas(), req.orderType()
        );
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

    // Split Bills
    @PostMapping("/orders/{orderId}/split/even")
    public PosSplitBillService.EvenSplitResult splitBillEven(@PathVariable UUID orderId, @RequestParam int count) {
        verifyOrder(orderId);
        return splitBillService.splitBillEven(orderId, count);
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
    }

    private PosOrderService.PosOrder verifyOrder(UUID id) {
        access.manage(ORDER, id);
        var order = orderService.getOrder(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        access.branch(order.merchantId(), order.branchId());
        if (order.terminalId() != null) access.belongs(order.merchantId(), TERMINAL, order.terminalId());
        if (order.cashierId() != null) access.belongs(order.merchantId(), CASHIER, order.cashierId());
        return order;
    }
}
