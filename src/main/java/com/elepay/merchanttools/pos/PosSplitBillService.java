package com.elepay.merchanttools.pos;

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
    public static final int MAX_EVEN_SPLITS = 100;

    public EvenSplitResult splitBillEven(UUID orderId, int splitCount) {
        if (splitCount <= 0 || splitCount > MAX_EVEN_SPLITS) {
            throw com.elepay.common.ApiException.badRequest("invalid_split_count",
                    "Split count must be between 1 and " + MAX_EVEN_SPLITS);
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

    // Split bill by seat numbers, proportionally distributing order-level tax/tip/discount so sum(seats) == order.totalPesewas()
    public SeatSplitResult splitBillBySeats(UUID orderId) {
        PosOrderService.PosOrder order = orderService.getOrder(orderId).orElseThrow();
        List<PosOrderService.PosOrderItem> items = orderService.listOrderItems(orderId);

        Map<Integer, List<PosOrderService.PosOrderItem>> bySeat = new java.util.TreeMap<>();
        for (PosOrderService.PosOrderItem item : items) {
            int seat = item.seatNumber() != null ? item.seatNumber() : 1;
            bySeat.computeIfAbsent(seat, k -> new ArrayList<>()).add(item);
        }

        long itemsSum = 0L;
        List<Map.Entry<Integer, List<PosOrderService.PosOrderItem>>> entries = new ArrayList<>(bySeat.entrySet());
        long[] seatBases = new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            long sub = 0L;
            for (PosOrderService.PosOrderItem it : entries.get(i).getValue()) {
                sub = Math.addExact(sub, it.totalPesewas());
            }
            seatBases[i] = sub;
            itemsSum = Math.addExact(itemsSum, sub);
        }

        long adjustment = Math.subtractExact(order.totalPesewas(), itemsSum);
        long distributed = 0L;
        List<SeatSplitGroup> groups = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            long share;
            if (i == entries.size() - 1) {
                share = Math.subtractExact(adjustment, distributed);
            } else if (itemsSum > 0) {
                share = (adjustment * seatBases[i]) / itemsSum;
            } else {
                share = adjustment / entries.size();
            }
            distributed = Math.addExact(distributed, share);
            long seatTotal = Math.max(0L, Math.addExact(seatBases[i], share));
            groups.add(new SeatSplitGroup(entries.get(i).getKey(), seatTotal, entries.get(i).getValue()));
        }

        return new SeatSplitResult(orderId, order.totalPesewas(), groups);
    }
}
