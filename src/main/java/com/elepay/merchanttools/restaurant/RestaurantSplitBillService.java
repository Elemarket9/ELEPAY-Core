package com.elepay.merchanttools.restaurant;

import com.elepay.merchanttools.pos.PosOrderService;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
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
        long grandItemsTotal = 0L;

        for (PosOrderService.PosOrderItem item : items) {
            int seat = item.seatNumber() != null ? item.seatNumber() : 1;
            seatMap.computeIfAbsent(seat, k -> new ArrayList<>()).add(item);
            grandItemsTotal = Math.addExact(grandItemsTotal, item.totalPesewas());
        }

        List<Integer> seats = new ArrayList<>(seatMap.keySet());
        seats.sort(Integer::compareTo);

        List<Long> seatSubtotals = new ArrayList<>(seats.size());
        for (Integer seat : seats) {
            long seatItemTotal = 0L;
            for (PosOrderService.PosOrderItem it : seatMap.get(seat)) {
                seatItemTotal = Math.addExact(seatItemTotal, it.totalPesewas());
            }
            seatSubtotals.add(seatItemTotal);
        }

        long[] allocatedDiscounts = allocateLargestRemainder(order.discountPesewas(), seatSubtotals, grandItemsTotal);
        long[] allocatedTaxes = allocateLargestRemainder(order.taxPesewas(), seatSubtotals, grandItemsTotal);
        long[] allocatedTips = allocateLargestRemainder(order.tipPesewas(), seatSubtotals, grandItemsTotal);

        List<SeatBill> bills = new ArrayList<>(seats.size());
        for (int i = 0; i < seats.size(); i++) {
            int seat = seats.get(i);
            long seatItemTotal = seatSubtotals.get(i);
            long seatDiscount = allocatedDiscounts[i];
            long seatTax = allocatedTaxes[i];
            long seatTip = allocatedTips[i];
            long seatTotal = Math.addExact(Math.addExact(Math.subtractExact(seatItemTotal, seatDiscount), seatTax), seatTip);
            bills.add(new SeatBill(seat, seatItemTotal, seatTax, seatTip, seatTotal, seatMap.get(seat)));
        }

        return new RestaurantSplitResult(orderId, order.totalPesewas(), bills);
    }

    private static long[] allocateLargestRemainder(long totalToAllocate, List<Long> weights, long totalWeight) {
        int n = weights.size();
        long[] result = new long[n];
        if (n == 0 || totalToAllocate == 0 || totalWeight <= 0) {
            return result;
        }
        BigInteger totalBig = BigInteger.valueOf(totalToAllocate);
        BigInteger weightSumBig = BigInteger.valueOf(totalWeight);
        BigInteger[] remainders = new BigInteger[n];
        long allocatedSum = 0L;

        for (int i = 0; i < n; i++) {
            BigInteger numerator = totalBig.multiply(BigInteger.valueOf(weights.get(i)));
            BigInteger[] divRem = numerator.divideAndRemainder(weightSumBig);
            result[i] = divRem[0].longValueExact();
            remainders[i] = divRem[1];
            allocatedSum = Math.addExact(allocatedSum, result[i]);
        }

        long leftover = Math.subtractExact(totalToAllocate, allocatedSum);
        if (leftover > 0) {
            List<Integer> order = new ArrayList<>(n);
            for (int i = 0; i < n; i++) order.add(i);
            order.sort(Comparator.<Integer, BigInteger>comparing(i -> remainders[i]).reversed().thenComparingInt(i -> i));
            for (int k = 0; k < leftover; k++) {
                int idx = order.get(k % n);
                result[idx] = Math.addExact(result[idx], 1L);
            }
        }
        return result;
    }
}
