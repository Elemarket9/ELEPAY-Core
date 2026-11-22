package com.elepay.pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public record SeatSplitGroup(
            int seatNumber,
            long subtotalPesewas,
            List<PosOrderService.PosOrderItem> items
    ) {}

    public record SeatSplitResult(
            UUID orderId,
            long totalPesewas,
            List<SeatSplitGroup> seatGroups
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

    // Split bill by seat numbers
    public SeatSplitResult splitBillBySeats(UUID orderId) {
        PosOrderService.PosOrder order = orderService.getOrder(orderId).orElseThrow();
        List<PosOrderService.PosOrderItem> items = orderService.listOrderItems(orderId);

        Map<Integer, List<PosOrderService.PosOrderItem>> bySeat = new HashMap<>();
        for (PosOrderService.PosOrderItem item : items) {
            int seat = item.seatNumber() != null ? item.seatNumber() : 1;
            bySeat.computeIfAbsent(seat, k -> new ArrayList<>()).add(item);
        }

        List<SeatSplitGroup> groups = new ArrayList<>();
        for (Map.Entry<Integer, List<PosOrderService.PosOrderItem>> entry : bySeat.entrySet()) {
            long subtotal = 0;
            for (PosOrderService.PosOrderItem it : entry.getValue()) {
                subtotal += it.totalPesewas();
            }
            groups.add(new SeatSplitGroup(entry.getKey(), subtotal, entry.getValue()));
        }

        return new SeatSplitResult(orderId, order.totalPesewas(), groups);
    }
}
