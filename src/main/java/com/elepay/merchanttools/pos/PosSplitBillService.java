package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import com.elepay.common.ProportionalAllocation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosSplitBillService {
    private static final Set<String> PAYABLE_STATUSES = Set.of("PENDING", "PARTIAL");

    private final PosOrderService orderService;
    private final JdbcClient db;

    public PosSplitBillService(PosOrderService orderService) {
        this(orderService, null);
    }

    @Autowired
    public PosSplitBillService(PosOrderService orderService, JdbcClient db) {
        this.orderService = orderService;
        this.db = db;
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

    public record ItemSplitGroupRequest(
            String label,
            List<UUID> itemIds
    ) {}

    public record SubBill(
            UUID id,
            String splitKey,
            String label,
            Integer seatNumber,
            long amountPesewas,
            long paidPesewas,
            long remainingPesewas,
            String status,
            List<UUID> itemIds,
            String openCheckoutRef
    ) {}

    public record SplitPlan(
            UUID orderId,
            String orderStatus,
            long totalPesewas,
            long paidPesewas,
            String splitType,
            List<SubBill> subBills
    ) {}

    public record SplitPaymentResult(
            PosOrderService.TenderResult tender,
            SplitPlan plan
    ) {}

    // Even split with minor-unit integer preservation (largest remainder / distribution of penny remainder)
    public static final int MAX_EVEN_SPLITS = 100;

    public EvenSplitResult splitBillEven(UUID orderId, int splitCount) {
        if (splitCount <= 0 || splitCount > MAX_EVEN_SPLITS) {
            throw ApiException.badRequest("invalid_split_count",
                    "Split count must be between 1 and " + MAX_EVEN_SPLITS);
        }

        PosOrderService.PosOrder order = orderService.getOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
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
        PosOrderService.PosOrder order = orderService.getOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        List<PosOrderService.PosOrderItem> items = orderService.listOrderItems(orderId);

        Map<Integer, List<PosOrderService.PosOrderItem>> bySeat = new TreeMap<>();
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

        List<SeatSplitGroup> groups = new ArrayList<>(entries.size());
        if (!entries.isEmpty()) {
            long[] seatTotals;
            if (itemsSum > 0) {
                seatTotals = ProportionalAllocation.allocate(order.totalPesewas(), seatBases);
            } else {
                long[] equalWeights = new long[entries.size()];
                Arrays.fill(equalWeights, 1L);
                seatTotals = ProportionalAllocation.allocate(order.totalPesewas(), equalWeights);
            }
            for (int i = 0; i < entries.size(); i++) {
                groups.add(new SeatSplitGroup(entries.get(i).getKey(), seatTotals[i], entries.get(i).getValue()));
            }
        }

        return new SeatSplitResult(orderId, order.totalPesewas(), groups);
    }

    @Transactional
    public SplitPlan createEvenSplit(UUID orderId, int splitCount) {
        if (splitCount <= 0 || splitCount > MAX_EVEN_SPLITS) {
            throw ApiException.badRequest("invalid_split_count",
                    "Split count must be between 1 and " + MAX_EVEN_SPLITS);
        }
        LockedOrder locked = lockAndPrepareSplittableOrder(orderId);
        long total = locked.totalPesewas();
        long baseAmount = total / splitCount;
        long remainder = total % splitCount;

        for (int i = 0; i < splitCount; i++) {
            int portionIdx = i + 1;
            long portionAmt = baseAmount + (i < remainder ? 1 : 0);
            insertSplitRow(
                    UUID.randomUUID(),
                    orderId,
                    "even:" + portionIdx,
                    "EVEN",
                    "Split " + portionIdx,
                    null,
                    portionAmt
            );
        }
        return listSplits(orderId);
    }

    @Transactional
    public SplitPlan createSeatSplit(UUID orderId) {
        lockAndPrepareSplittableOrder(orderId);
        SeatSplitResult calc = splitBillBySeats(orderId);
        if (calc.seatGroups().isEmpty()) {
            throw ApiException.badRequest("empty_order", "Order has no items to split");
        }
        for (SeatSplitGroup group : calc.seatGroups()) {
            UUID splitId = UUID.randomUUID();
            insertSplitRow(
                    splitId,
                    orderId,
                    "seat:" + group.seatNumber(),
                    "SEAT",
                    "Seat " + group.seatNumber(),
                    group.seatNumber(),
                    group.subtotalPesewas()
            );
            for (PosOrderService.PosOrderItem item : group.items()) {
                insertSplitItem(splitId, item.id());
            }
        }
        return listSplits(orderId);
    }

    @Transactional
    public SplitPlan createItemSplit(UUID orderId, List<ItemSplitGroupRequest> groups) {
        if (groups == null || groups.isEmpty()) {
            throw ApiException.badRequest("invalid_split_groups", "At least one split group is required");
        }
        if (groups.size() > MAX_EVEN_SPLITS) {
            throw ApiException.badRequest("invalid_split_groups",
                    "Split group count must be between 1 and " + MAX_EVEN_SPLITS);
        }

        LockedOrder locked = lockAndPrepareSplittableOrder(orderId);
        List<PosOrderService.PosOrderItem> orderItems = orderService.listOrderItems(orderId);
        if (orderItems.isEmpty()) {
            throw ApiException.badRequest("empty_order", "Order has no items to split");
        }

        Map<UUID, PosOrderService.PosOrderItem> itemById = new LinkedHashMap<>();
        for (PosOrderService.PosOrderItem item : orderItems) {
            itemById.put(item.id(), item);
        }

        Set<UUID> assigned = new HashSet<>();
        long[] groupBases = new long[groups.size()];
        long itemsSum = 0L;

        for (int i = 0; i < groups.size(); i++) {
            ItemSplitGroupRequest g = groups.get(i);
            if (g == null || g.itemIds() == null || g.itemIds().isEmpty()) {
                throw ApiException.badRequest("empty_split_group", "Each split group must contain at least one item");
            }
            long sub = 0L;
            for (UUID itemId : g.itemIds()) {
                if (itemId == null || !itemById.containsKey(itemId)) {
                    throw ApiException.badRequest("invalid_split_item", "Item does not belong to this order");
                }
                if (!assigned.add(itemId)) {
                    throw ApiException.badRequest("duplicate_split_item", "Item cannot be assigned to multiple sub-bills");
                }
                sub = Math.addExact(sub, itemById.get(itemId).totalPesewas());
            }
            groupBases[i] = sub;
            itemsSum = Math.addExact(itemsSum, sub);
        }

        if (assigned.size() != itemById.size()) {
            throw ApiException.badRequest("missing_split_items", "Every order item must be assigned to a sub-bill");
        }

        long[] groupTotals;
        if (itemsSum > 0) {
            groupTotals = ProportionalAllocation.allocate(locked.totalPesewas(), groupBases);
        } else {
            long[] equalWeights = new long[groups.size()];
            Arrays.fill(equalWeights, 1L);
            groupTotals = ProportionalAllocation.allocate(locked.totalPesewas(), equalWeights);
        }

        for (int i = 0; i < groups.size(); i++) {
            ItemSplitGroupRequest g = groups.get(i);
            String label = (g.label() != null && !g.label().isBlank())
                    ? g.label().trim()
                    : "Bill " + (i + 1);
            if (label.length() > 64) {
                label = label.substring(0, 64);
            }
            UUID splitId = UUID.randomUUID();
            insertSplitRow(
                    splitId,
                    orderId,
                    "item:" + (i + 1),
                    "ITEM",
                    label,
                    null,
                    groupTotals[i]
            );
            for (UUID itemId : g.itemIds()) {
                insertSplitItem(splitId, itemId);
            }
        }

        return listSplits(orderId);
    }

    public SplitPlan listSplits(UUID orderId) {
        PosOrderService.PosOrder order = orderService.getOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        if (db == null) {
            return new SplitPlan(order.id(), order.status(), order.totalPesewas(), order.paidPesewas(), null, List.of());
        }

        record SplitRow(
                UUID id,
                String splitKey,
                String splitType,
                String label,
                Integer seatNumber,
                long amountPesewas,
                long paidPesewas,
                String status
        ) {}

        List<SplitRow> rows = db.sql("""
                SELECT id, split_key, split_type, label, seat_number, amount_pesewas, paid_pesewas, status
                FROM pos_order_split
                WHERE order_id = :orderId
                ORDER BY created_at ASC, length(split_key) ASC, split_key ASC
                """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new SplitRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("split_key"),
                        rs.getString("split_type"),
                        rs.getString("label"),
                        (Integer) rs.getObject("seat_number"),
                        rs.getLong("amount_pesewas"),
                        rs.getLong("paid_pesewas"),
                        rs.getString("status")
                ))
                .list();

        if (rows.isEmpty()) {
            return new SplitPlan(order.id(), order.status(), order.totalPesewas(), order.paidPesewas(), null, List.of());
        }

        Map<UUID, List<UUID>> itemsBySplit = new HashMap<>();
        db.sql("""
                SELECT si.split_id, si.order_item_id
                FROM pos_order_split_item si
                JOIN pos_order_split s ON s.id = si.split_id
                WHERE s.order_id = :orderId
                """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> Map.entry(
                        rs.getObject("split_id", UUID.class),
                        rs.getObject("order_item_id", UUID.class)
                ))
                .list()
                .forEach(entry -> itemsBySplit
                        .computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(entry.getValue()));

        Map<String, String> openCheckoutBySplit = new HashMap<>();
        db.sql("""
                SELECT split_key, checkout_ref
                FROM pos_checkout_link
                WHERE order_id = :orderId AND status = 'open' AND split_key IS NOT NULL
                """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> Map.entry(
                        rs.getString("split_key"),
                        rs.getString("checkout_ref")
                ))
                .list()
                .forEach(entry -> openCheckoutBySplit.putIfAbsent(entry.getKey(), entry.getValue()));

        List<SubBill> subBills = new ArrayList<>(rows.size());
        for (SplitRow r : rows) {
            long remaining = Math.max(0L, Math.subtractExact(r.amountPesewas(), r.paidPesewas()));
            subBills.add(new SubBill(
                    r.id(),
                    r.splitKey(),
                    r.label(),
                    r.seatNumber(),
                    r.amountPesewas(),
                    r.paidPesewas(),
                    remaining,
                    r.status(),
                    List.copyOf(itemsBySplit.getOrDefault(r.id(), List.of())),
                    openCheckoutBySplit.get(r.splitKey())
            ));
        }

        return new SplitPlan(
                order.id(),
                order.status(),
                order.totalPesewas(),
                order.paidPesewas(),
                rows.getFirst().splitType(),
                subBills
        );
    }

    @Transactional
    public SplitPaymentResult paySplit(UUID orderId, String splitKey, PosOrderService.TenderRequest req, String actor) {
        if (splitKey == null || splitKey.isBlank()) {
            throw ApiException.badRequest("split_key_required", "Split key is required");
        }
        if (req == null || req.tenderType() == null) {
            throw ApiException.badRequest("tender_required", "Tender type is required");
        }
        String normalizedKey = splitKey.trim();
        PosOrderService.TenderRequest effectiveReq = new PosOrderService.TenderRequest(
                req.tenderType(),
                req.amountPesewas(),
                normalizedKey,
                req.phone(),
                req.network(),
                req.email(),
                req.reference()
        );
        PosOrderService.TenderResult tender = orderService.initiateTender(orderId, effectiveReq, actor);
        SplitPlan plan = listSplits(orderId);
        return new SplitPaymentResult(tender, plan);
    }

    private record LockedOrder(UUID id, String status, long totalPesewas, long paidPesewas) {}

    private LockedOrder lockAndPrepareSplittableOrder(UUID orderId) {
        if (db == null) {
            throw new IllegalStateException("JdbcClient is required for persisted split bills");
        }
        LockedOrder order = db.sql("""
                SELECT id, status, total_pesewas, paid_pesewas
                FROM pos_order
                WHERE id = :id
                FOR UPDATE
                """)
                .param("id", orderId)
                .query((rs, rowNum) -> new LockedOrder(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getLong("total_pesewas"),
                        rs.getLong("paid_pesewas")
                ))
                .optional()
                .orElseThrow(() -> ApiException.notFound("Order not found"));

        if ("PAID".equals(order.status())) {
            throw ApiException.conflict("order_paid", "Order is already fully paid");
        }
        if (!PAYABLE_STATUSES.contains(order.status())) {
            throw ApiException.conflict("order_not_payable", "Order is " + order.status() + " and cannot be split");
        }
        if (order.paidPesewas() > 0) {
            throw ApiException.conflict("split_already_in_progress",
                    "Cannot create or replace split plan after payments have been recorded");
        }

        long paidSplits = db.sql("""
                SELECT count(*) FROM pos_order_split
                WHERE order_id = :id AND paid_pesewas > 0
                """)
                .param("id", orderId)
                .query(Long.class)
                .single();
        if (paidSplits > 0) {
            throw ApiException.conflict("split_already_in_progress",
                    "Cannot replace split plan after a sub-bill has been paid");
        }

        long activeLinks = db.sql("""
                SELECT count(*) FROM pos_checkout_link
                WHERE order_id = :id AND status IN ('open', 'applied')
                """)
                .param("id", orderId)
                .query(Long.class)
                .single();
        if (activeLinks > 0) {
            throw ApiException.conflict("split_already_in_progress",
                    "Cannot replace split plan while a digital checkout is open or applied");
        }

        db.sql("DELETE FROM pos_order_split WHERE order_id = :id")
                .param("id", orderId)
                .update();

        return order;
    }

    private void insertSplitRow(
            UUID id,
            UUID orderId,
            String splitKey,
            String splitType,
            String label,
            Integer seatNumber,
            long amountPesewas
    ) {
        String initialStatus = amountPesewas == 0L ? "PAID" : "OPEN";
        db.sql("""
                INSERT INTO pos_order_split (
                    id, order_id, split_key, split_type, label, seat_number,
                    amount_pesewas, paid_pesewas, status, created_at, paid_at
                ) VALUES (
                    :id, :orderId, :splitKey, :splitType, :label, :seatNumber,
                    :amount, 0, :status, now(), CASE WHEN :status = 'PAID' THEN now() ELSE NULL END
                )
                """)
                .param("id", id)
                .param("orderId", orderId)
                .param("splitKey", splitKey)
                .param("splitType", splitType)
                .param("label", label)
                .param("seatNumber", seatNumber)
                .param("amount", amountPesewas)
                .param("status", initialStatus)
                .update();
    }

    private void insertSplitItem(UUID splitId, UUID orderItemId) {
        db.sql("""
                INSERT INTO pos_order_split_item (split_id, order_item_id)
                VALUES (:splitId, :orderItemId)
                """)
                .param("splitId", splitId)
                .param("orderItemId", orderItemId)
                .update();
    }
}

