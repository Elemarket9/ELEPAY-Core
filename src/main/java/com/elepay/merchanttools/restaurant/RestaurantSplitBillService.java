package com.elepay.merchanttools.restaurant;

import com.elepay.merchanttools.pos.PosOrderService;
import com.elepay.common.ProportionalAllocation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RestaurantSplitBillService {
    private final PosOrderService orderService;

    public RestaurantSplitBillService(PosOrderService orderService) {
        this.orderService = orderService;
    }

    public record SeatBill(
            int seatNumber,
            long itemsTotalPesewas,
            long allocatedDiscountPesewas,
            long allocatedTaxPesewas,
            long allocatedTipPesewas,
            long totalDuePesewas,
            List<PosOrderService.PosOrderItem> items
    ) {}

    public record RestaurantSplitResult(
            UUID orderId,
            long orderTotalPesewas,
            List<SeatBill> seatBills
    ) {}

    public RestaurantSplitResult splitBySeat(UUID orderId) {
        PosOrderService.PosOrder order = orderService.getOrder(orderId).orElseThrow();
        List<PosOrderService.PosOrderItem> items = orderService.listOrderItems(orderId);

        Map<Integer, List<PosOrderService.PosOrderItem>> seatMap = new HashMap<>();

        for (PosOrderService.PosOrderItem item : items) {
            int seat = item.seatNumber() != null ? item.seatNumber() : 1;
            seatMap.computeIfAbsent(seat, k -> new ArrayList<>()).add(item);
        }

        List<Integer> seats = seatMap.keySet().stream().sorted().toList();
        List<Long> weights = seats.stream()
                .map(seat -> seatMap.get(seat).stream().mapToLong(PosOrderService.PosOrderItem::totalPesewas).sum())
                .toList();
        List<Long> discounts = ProportionalAllocation.allocate(order.discountPesewas(), weights);
        List<Long> taxes = ProportionalAllocation.allocate(order.taxPesewas(), weights);
        List<Long> tips = ProportionalAllocation.allocate(order.tipPesewas(), weights);
        List<SeatBill> bills = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            long due = Math.addExact(Math.addExact(Math.subtractExact(weights.get(i), discounts.get(i)),
                    taxes.get(i)), tips.get(i));
            bills.add(new SeatBill(seats.get(i), weights.get(i), discounts.get(i), taxes.get(i),
                    tips.get(i), due, seatMap.get(seats.get(i))));
        }

        return new RestaurantSplitResult(orderId, order.totalPesewas(), bills);
    }
}
