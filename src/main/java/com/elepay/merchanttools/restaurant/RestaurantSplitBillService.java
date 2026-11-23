package com.elepay.merchanttools.restaurant;

import com.elepay.merchanttools.pos.PosOrderService;
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
        long grandItemsTotal = 0;

        for (PosOrderService.PosOrderItem item : items) {
            int seat = item.seatNumber() != null ? item.seatNumber() : 1;
            seatMap.computeIfAbsent(seat, k -> new ArrayList<>()).add(item);
            grandItemsTotal += item.totalPesewas();
        }

        List<SeatBill> bills = new ArrayList<>();
        if (grandItemsTotal == 0) {
            grandItemsTotal = 1; // avoid division by zero
        }

        for (Map.Entry<Integer, List<PosOrderService.PosOrderItem>> entry : seatMap.entrySet()) {
            long seatItemTotal = 0;
            for (PosOrderService.PosOrderItem it : entry.getValue()) {
                seatItemTotal += it.totalPesewas();
            }

            double ratio = (double) seatItemTotal / grandItemsTotal;
            long seatTax = Math.round(order.taxPesewas() * ratio);
            long seatTip = Math.round(order.tipPesewas() * ratio);
            long seatTotal = seatItemTotal + seatTax + seatTip;

            bills.add(new SeatBill(entry.getKey(), seatItemTotal, seatTax, seatTip, seatTotal, entry.getValue()));
        }

        return new RestaurantSplitResult(orderId, order.totalPesewas(), bills);
    }
}
