package com.elepay.merchanttools.pos;

import com.elepay.common.ProportionalAllocation;

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
            long totalDuePesewas,
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

    /** Itemized share quote including allocated order-level discount/tax/tip. */
    public record ItemSplitPortion(UUID orderItemId, String itemName, long amountPesewas) {}
    public record ItemSplitResult(UUID orderId, long totalPesewas, List<ItemSplitPortion> portions) {}
    public record AmountSplitResult(UUID orderId, long totalPesewas, long firstPesewas, long remainingPesewas) {}

    public ItemSplitResult splitBillByItem(UUID orderId) {
        PosOrderService.PosOrder order = orderService.getOrder(orderId).orElseThrow();
        List<PosOrderService.PosOrderItem> items = orderService.listOrderItems(orderId);
        List<Long> shares = ProportionalAllocation.allocate(order.totalPesewas(),
                items.stream().map(PosOrderService.PosOrderItem::totalPesewas).toList());
        List<ItemSplitPortion> portions = new ArrayList<>();
        for (int i = 0; i < items.size(); i++)
            portions.add(new ItemSplitPortion(items.get(i).id(), items.get(i).itemName(), shares.get(i)));
        return new ItemSplitResult(orderId, order.totalPesewas(), portions);
    }

    public AmountSplitResult splitBillByAmount(UUID orderId, long firstPesewas) {
        PosOrderService.PosOrder order = orderService.getOrder(orderId).orElseThrow();
        if (firstPesewas <= 0 || firstPesewas >= order.totalPesewas())
            throw new IllegalArgumentException("First portion must be above zero and below the bill total");
        return new AmountSplitResult(orderId, order.totalPesewas(), firstPesewas,
                Math.subtractExact(order.totalPesewas(), firstPesewas));
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
        List<Integer> sortedSeats = bySeat.keySet().stream().sorted().toList();
        List<Long> weights = new ArrayList<>();
        for (int seat : sortedSeats)
            weights.add(bySeat.get(seat).stream().mapToLong(PosOrderService.PosOrderItem::totalPesewas).sum());
        List<Long> totals = ProportionalAllocation.allocate(order.totalPesewas(), weights);
        for (int i = 0; i < sortedSeats.size(); i++)
            groups.add(new SeatSplitGroup(sortedSeats.get(i), weights.get(i), totals.get(i),
                    bySeat.get(sortedSeats.get(i))));

        return new SeatSplitResult(orderId, order.totalPesewas(), groups);
    }
}
