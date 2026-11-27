package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.identity.Actor;
import com.elepay.merchanttools.MerchantToolAccess;
import static com.elepay.merchanttools.MerchantToolAccess.Resource.ORDER;
import static com.elepay.merchanttools.MerchantToolAccess.Resource.TERMINAL;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/pos/orders/{orderId}/splits")
public class PosSplitBillController {
    private final MerchantToolAccess access;
    private final Idempotency idempotency;
    private final PosOrderService orderService;
    private final PosSplitBillService splitBillService;

    public PosSplitBillController(
            MerchantToolAccess access,
            Idempotency idempotency,
            PosOrderService orderService,
            PosSplitBillService splitBillService
    ) {
        this.access = access;
        this.idempotency = idempotency;
        this.orderService = orderService;
        this.splitBillService = splitBillService;
    }

    public record ItemSplitRequest(List<PosSplitBillService.ItemSplitGroupRequest> groups) {}

    @GetMapping
    public PosSplitBillService.SplitPlan getSplits(@PathVariable UUID orderId) {
        verifyViewOrder(orderId);
        return splitBillService.listSplits(orderId);
    }

    @PostMapping("/even")
    public PosSplitBillService.SplitPlan createEvenSplit(
            @PathVariable UUID orderId,
            @RequestParam int count
    ) {
        verifyOperateOrder(orderId);
        return splitBillService.createEvenSplit(orderId, count);
    }

    @PostMapping("/seats")
    public PosSplitBillService.SplitPlan createSeatSplit(@PathVariable UUID orderId) {
        verifyOperateOrder(orderId);
        return splitBillService.createSeatSplit(orderId);
    }

    @PostMapping("/items")
    public PosSplitBillService.SplitPlan createItemSplit(
            @PathVariable UUID orderId,
            @RequestBody ItemSplitRequest req
    ) {
        verifyOperateOrder(orderId);
        return splitBillService.createItemSplit(orderId, req != null ? req.groups() : null);
    }

    @PostMapping("/{splitKey}/pay")
    @Transactional
    public PosSplitBillService.SplitPaymentResult paySplit(
            @PathVariable UUID orderId,
            @PathVariable String splitKey,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody PosOrderService.TenderRequest req
    ) {
        var order = verifyOperateOrder(orderId);
        String actor = Actor.current().subject();
        return idempotency.execute(
                "pos-split-pay:" + order.merchantId() + ":" + orderId + ":" + splitKey,
                key,
                req,
                PosSplitBillService.SplitPaymentResult.class,
                () -> splitBillService.paySplit(orderId, splitKey, req, actor)
        );
    }

    private PosOrderService.PosOrder verifyViewOrder(UUID orderId) {
        var caller = access.viewAccess(ORDER, orderId);
        var order = orderService.getOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        access.requireBranch(caller, order.branchId());
        if (order.terminalId() != null) {
            access.requireTerminal(caller, order.terminalId());
        }
        return order;
    }

    private PosOrderService.PosOrder verifyOperateOrder(UUID orderId) {
        var caller = access.operateAccess(ORDER, orderId);
        var order = orderService.getOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        access.requireBranch(caller, order.branchId());
        access.branch(order.merchantId(), order.branchId());
        if (order.terminalId() != null) {
            access.belongs(order.merchantId(), TERMINAL, order.terminalId());
            access.requireTerminal(caller, order.terminalId());
        }
        return order;
    }
}
