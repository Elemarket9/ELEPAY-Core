package com.elepay.merchanttools.pos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PosSplitBillService {
    private final PosOrderService orderService;

    public PosSplitBillService(PosOrderService orderService) {
        this.orderService = orderService;
    }

    public record EvenSplitPortion(
            int portionIndex,
            long amountPesewas
    ) {}

    public record EvenSplitResult(
            UUID orderId,
            long totalPesewas,
            int splitCount,
            List<EvenSplitPortion> portions
    ) {}

    // Even split with minor-unit integer preservation (largest remainder / distribution of penny remainder)
    public EvenSplitResult splitBillEven(UUID orderId, int splitCount) {
        if (splitCount <= 0) {
            throw new IllegalArgumentException("Split count must be positive");
        }

        PosOrderService.PosOrder order = orderService.getOrder(orderId).orElseThrow();
        long total = order.totalPesewas();
        long baseAmount = total / splitCount;
        long remainder = total % splitCount;

        List<EvenSplitPortion> portions = new ArrayList<>();
        for (int i = 0; i < splitCount; i++) {
            long portionAmt = baseAmount + (i < remainder ? 1 : 0);
            portions.add(new EvenSplitPortion(i + 1, portionAmt));
        }

        return new EvenSplitResult(orderId, total, splitCount, portions);
    }

}
