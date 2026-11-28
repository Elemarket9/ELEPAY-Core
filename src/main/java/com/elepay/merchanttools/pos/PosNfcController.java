package com.elepay.merchanttools.pos;

import com.elepay.identity.Actor;
import com.elepay.merchanttools.MerchantToolAccess;
import static com.elepay.merchanttools.MerchantToolAccess.Resource.ORDER;
import static com.elepay.merchanttools.MerchantToolAccess.Resource.TERMINAL;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/pos")
public class PosNfcController {

    private final MerchantToolAccess access;
    private final PosDukptKeyManagementService dukptService;
    private final PosNfcPaymentService nfcPaymentService;
    private final PosOrderService orderService;

    public PosNfcController(
            MerchantToolAccess access,
            PosDukptKeyManagementService dukptService,
            PosNfcPaymentService nfcPaymentService,
            PosOrderService orderService) {
        this.access = access;
        this.dukptService = dukptService;
        this.nfcPaymentService = nfcPaymentService;
        this.orderService = orderService;
    }

    public record ProvisionDukptRequest(String bdkId) {}

    @PostMapping("/terminals/{terminalId}/dukpt/provision")
    public PosDukptKeyManagementService.TerminalDukptProfile provisionDukpt(
            @PathVariable UUID terminalId,
            @RequestBody(required = false) ProvisionDukptRequest req) {
        UUID merchantId = access.manage(TERMINAL, terminalId);
        return dukptService.provisionTerminal(terminalId, merchantId, req != null ? req.bdkId() : null);
    }

    @PostMapping("/orders/{orderId}/nfc-tap")
    public PosNfcPaymentService.NfcTapResult nfcTap(
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PosNfcPaymentService.NfcTapRequest req) {
        var caller = access.operateAccess(ORDER, orderId);
        access.requireOrderBranch(caller, orderId);
        if (req != null && req.terminalId() != null) {
            access.requireTerminal(caller, req.terminalId());
        }
        orderService.getOrder(orderId).ifPresent(order -> {
            if (order.terminalId() != null) {
                access.requireTerminal(caller, order.terminalId());
            }
        });
        return nfcPaymentService.processNfcTap(orderId, idempotencyKey, req, Actor.current().subject());
    }
}
