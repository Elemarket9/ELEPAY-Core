package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 7 owner daily report service.
 * Aggregates sales, discounts, voids, tender mix, order types & AOV, top 10 items,
 * KDS kitchen timing metrics, and shift cash reconciliation for an Africa/Accra business day.
 */
@Service
public class PosDailyReportService {
    public static final ZoneId ACCRA = ZoneId.of("Africa/Accra");
    private static final int TOP_ITEMS_LIMIT = 10;

    private final JdbcClient db;

    public PosDailyReportService(JdbcClient db) {
        this.db = db;
    }

    public record DailyReport(
            UUID merchantId,
            UUID branchId,
            LocalDate businessDate,
            String timezone,
            OffsetDateTime generatedAt,
            SalesSummary sales,
            List<TenderBreakdown> tenderMix,
            List<OrderTypeBreakdown> orderTypes,
            List<TopItem> topItemsByQuantity,
            List<TopItem> topItemsByRevenue,
            KitchenTiming kitchenTiming,
            List<VoidEntry> voids,
            ShiftCashSummary shiftCash
    ) {}

    public record SalesSummary(
            long grossSalesPesewas,
            long discountsPesewas,
            long voidsPesewas,
            long netSalesPesewas,
            long taxPesewas,
            long tipsPesewas,
            long totalCollectedPesewas,
            int orderCount,
            int paidOrderCount,
            int openOrderCount,
            int voidedOrderCount
    ) {}

    public record TenderBreakdown(
            String tenderType,
            int count,
            long amountPesewas
    ) {}

    public record OrderTypeBreakdown(
            String orderType,
            int orderCount,
            long totalPesewas,
            long averageOrderValuePesewas
    ) {}

    public record TopItem(
            String itemName,
            BigDecimal quantity,
            long revenuePesewas
    ) {}

    public record KitchenTiming(
            int ticketCount,
            Long avgFiredToReadySeconds,
            Long avgReadyToServedSeconds
    ) {}

    public record VoidEntry(
            UUID orderId,
            String orderNumber,
            UUID orderItemId,
            String itemName,
            BigDecimal quantity,
            long amountPesewas,
            String reason,
            String voidedBy,
            String approvedBy,
            OffsetDateTime voidedAt
    ) {}

    public record ShiftCashRow(
            UUID shiftId,
            UUID terminalId,
            UUID branchId,
            String cashierName,
            String status,
            long openingBalancePesewas,
            long expectedCashPesewas,
            Long countedCashPesewas,
            Long variancePesewas,
            OffsetDateTime shiftStart,
            OffsetDateTime shiftEnd
    ) {}

    public record ShiftCashSummary(
            long totalExpectedCashPesewas,
            long totalCountedCashPesewas,
            long totalVariancePesewas,
            int openShiftCount,
            int closedShiftCount,
            List<ShiftCashRow> shifts
    ) {}

    private record RawSales(
            long grossSalesPesewas,
            long discountsPesewas,
            long taxPesewas,
            long tipsPesewas,
            int orderCount,
            int paidOrderCount,
            int openOrderCount,
            int voidedOrderCount
    ) {}

    @Transactional(readOnly = true)
    public DailyReport generateDailyReport(UUID merchantId, UUID branchId, LocalDate requestedDate) {
        if (merchantId == null) {
            throw ApiException.badRequest("merchant_required", "Merchant is required");
        }
        LocalDate businessDate = requestedDate != null ? requestedDate : LocalDate.now(ACCRA);
        OffsetDateTime dayStart = businessDate.atStartOfDay(ACCRA).toOffsetDateTime();
        OffsetDateTime dayEnd = businessDate.plusDays(1).atStartOfDay(ACCRA).toOffsetDateTime();

        RawSales rawSales = querySales(merchantId, branchId, dayStart, dayEnd);
        List<TenderBreakdown> tenderMix = queryTenderMix(merchantId, branchId, dayStart, dayEnd);
        List<OrderTypeBreakdown> orderTypes = queryOrderTypes(merchantId, branchId, dayStart, dayEnd);
        List<TopItem> allItems = queryItemTotals(merchantId, branchId, dayStart, dayEnd);
        KitchenTiming kitchenTiming = queryKitchenTiming(merchantId, branchId, dayStart, dayEnd);
        List<VoidEntry> voids = queryVoids(merchantId, branchId, dayStart, dayEnd);
        ShiftCashSummary shiftCash = queryShiftCash(merchantId, branchId, dayStart, dayEnd);

        long totalCollectedPesewas = tenderMix.stream().mapToLong(TenderBreakdown::amountPesewas).sum();
        long voidsPesewas = voids.stream().mapToLong(VoidEntry::amountPesewas).sum();
        long netSalesPesewas = rawSales.grossSalesPesewas() - rawSales.discountsPesewas();

        SalesSummary sales = new SalesSummary(
                rawSales.grossSalesPesewas(),
                rawSales.discountsPesewas(),
                voidsPesewas,
                netSalesPesewas,
                rawSales.taxPesewas(),
                rawSales.tipsPesewas(),
                totalCollectedPesewas,
                rawSales.orderCount(),
                rawSales.paidOrderCount(),
                rawSales.openOrderCount(),
                rawSales.voidedOrderCount()
        );

        List<TopItem> topByQuantity = allItems.stream()
                .sorted(Comparator.comparing(TopItem::quantity).reversed()
                        .thenComparing(Comparator.comparingLong(TopItem::revenuePesewas).reversed())
                        .thenComparing(TopItem::itemName))
                .limit(TOP_ITEMS_LIMIT)
                .toList();

        List<TopItem> topByRevenue = allItems.stream()
                .sorted(Comparator.comparingLong(TopItem::revenuePesewas).reversed()
                        .thenComparing(Comparator.comparing(TopItem::quantity).reversed())
                        .thenComparing(TopItem::itemName))
                .limit(TOP_ITEMS_LIMIT)
                .toList();

        return new DailyReport(
                merchantId,
                branchId,
                businessDate,
                ACCRA.getId(),
                OffsetDateTime.now(ZoneOffset.UTC),
                sales,
                tenderMix,
                orderTypes,
                topByQuantity,
                topByRevenue,
                kitchenTiming,
                voids,
                shiftCash
        );
    }

    private RawSales querySales(UUID merchantId, UUID branchId, OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        String branchClause = branchId != null ? " AND o.branch_id = :branchId" : "";
        var query = db.sql("""
                SELECT
                    COALESCE(SUM(o.subtotal_pesewas) FILTER (WHERE o.status <> 'VOIDED'), 0) AS gross_sales,
                    COALESCE(SUM(o.discount_pesewas) FILTER (WHERE o.status <> 'VOIDED'), 0) AS discounts,
                    COALESCE(SUM(o.tax_pesewas) FILTER (WHERE o.status <> 'VOIDED'), 0) AS tax,
                    COALESCE(SUM(o.tip_pesewas) FILTER (WHERE o.status <> 'VOIDED'), 0) AS tips,
                    COUNT(*) FILTER (WHERE o.status <> 'VOIDED')::int AS order_count,
                    COUNT(*) FILTER (WHERE o.status = 'PAID')::int AS paid_count,
                    COUNT(*) FILTER (WHERE o.status IN ('PENDING', 'PARTIAL'))::int AS open_count,
                    COUNT(*) FILTER (WHERE o.status = 'VOIDED')::int AS voided_count
                FROM pos_order o
                WHERE o.merchant_id = :merchantId
                """ + branchClause + """
                  AND o.created_at >= :dayStart
                  AND o.created_at < :dayEnd
                """)
                .param("merchantId", merchantId)
                .param("dayStart", dayStart)
                .param("dayEnd", dayEnd);
        if (branchId != null) {
            query = query.param("branchId", branchId);
        }
        return query.query((rs, i) -> new RawSales(
                rs.getLong("gross_sales"),
                rs.getLong("discounts"),
                rs.getLong("tax"),
                rs.getLong("tips"),
                rs.getInt("order_count"),
                rs.getInt("paid_count"),
                rs.getInt("open_count"),
                rs.getInt("voided_count")
        )).single();
    }

    private List<TenderBreakdown> queryTenderMix(UUID merchantId, UUID branchId, OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        String branchClause = branchId != null ? " AND o.branch_id = :branchId" : "";
        var query = db.sql("""
                SELECT p.tender_type, COUNT(*)::int AS cnt, COALESCE(SUM(p.amount_pesewas), 0) AS amt
                FROM pos_tender_payment p
                JOIN pos_order o ON o.id = p.order_id
                WHERE o.merchant_id = :merchantId
                """ + branchClause + """
                  AND o.status <> 'VOIDED'
                  AND p.status = 'SUCCESS'
                  AND p.created_at >= :dayStart
                  AND p.created_at < :dayEnd
                GROUP BY p.tender_type
                ORDER BY amt DESC, p.tender_type ASC
                """)
                .param("merchantId", merchantId)
                .param("dayStart", dayStart)
                .param("dayEnd", dayEnd);
        if (branchId != null) {
            query = query.param("branchId", branchId);
        }
        return query.query((rs, i) -> new TenderBreakdown(
                rs.getString("tender_type"),
                rs.getInt("cnt"),
                rs.getLong("amt")
        )).list();
    }

    private List<OrderTypeBreakdown> queryOrderTypes(UUID merchantId, UUID branchId, OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        String branchClause = branchId != null ? " AND o.branch_id = :branchId" : "";
        var query = db.sql("""
                SELECT o.order_type, COUNT(*)::int AS cnt, COALESCE(SUM(o.total_pesewas), 0) AS total
                FROM pos_order o
                WHERE o.merchant_id = :merchantId
                """ + branchClause + """
                  AND o.status <> 'VOIDED'
                  AND o.created_at >= :dayStart
                  AND o.created_at < :dayEnd
                GROUP BY o.order_type
                ORDER BY total DESC, o.order_type ASC
                """)
                .param("merchantId", merchantId)
                .param("dayStart", dayStart)
                .param("dayEnd", dayEnd);
        if (branchId != null) {
            query = query.param("branchId", branchId);
        }
        return query.query((rs, i) -> {
            int count = rs.getInt("cnt");
            long total = rs.getLong("total");
            long aov = count == 0 ? 0L : total / count;
            return new OrderTypeBreakdown(rs.getString("order_type"), count, total, aov);
        }).list();
    }

    private List<TopItem> queryItemTotals(UUID merchantId, UUID branchId, OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        String branchClause = branchId != null ? " AND o.branch_id = :branchId" : "";
        String voidFilter = hasOrderItemVoidedColumn() ? " AND NOT i.voided" : "";
        var query = db.sql("""
                SELECT i.item_name,
                       COALESCE(SUM(i.quantity), 0) AS qty,
                       COALESCE(SUM(i.total_pesewas), 0) AS rev
                FROM pos_order_item i
                JOIN pos_order o ON o.id = i.order_id
                WHERE o.merchant_id = :merchantId
                """ + branchClause + """
                  AND o.status <> 'VOIDED'
                """ + voidFilter + """
                  AND o.created_at >= :dayStart
                  AND o.created_at < :dayEnd
                GROUP BY i.item_name
                """)
                .param("merchantId", merchantId)
                .param("dayStart", dayStart)
                .param("dayEnd", dayEnd);
        if (branchId != null) {
            query = query.param("branchId", branchId);
        }
        return query.query((rs, i) -> new TopItem(
                rs.getString("item_name"),
                rs.getBigDecimal("qty") == null ? BigDecimal.ZERO : rs.getBigDecimal("qty"),
                rs.getLong("rev")
        )).list();
    }

    private KitchenTiming queryKitchenTiming(UUID merchantId, UUID branchId, OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        String branchClause = branchId != null ? " AND o.branch_id = :branchId" : "";
        var query = db.sql("""
                SELECT
                    COUNT(*)::int AS ticket_count,
                    ROUND(AVG(EXTRACT(EPOCH FROM (t.ready_at - t.created_at))) FILTER (WHERE t.ready_at IS NOT NULL))::bigint AS avg_fired_to_ready,
                    ROUND(AVG(EXTRACT(EPOCH FROM (t.served_at - t.ready_at))) FILTER (WHERE t.served_at IS NOT NULL AND t.ready_at IS NOT NULL))::bigint AS avg_ready_to_served
                FROM restaurant_kds_ticket t
                JOIN pos_order o ON o.id = t.order_id
                WHERE t.merchant_id = :merchantId
                """ + branchClause + """
                  AND t.status <> 'CANCELLED'
                  AND t.created_at >= :dayStart
                  AND t.created_at < :dayEnd
                """)
                .param("merchantId", merchantId)
                .param("dayStart", dayStart)
                .param("dayEnd", dayEnd);
        if (branchId != null) {
            query = query.param("branchId", branchId);
        }
        return query.query((rs, i) -> new KitchenTiming(
                rs.getInt("ticket_count"),
                rs.getObject("avg_fired_to_ready", Long.class),
                rs.getObject("avg_ready_to_served", Long.class)
        )).single();
    }

    private List<VoidEntry> queryVoids(UUID merchantId, UUID branchId, OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        List<VoidEntry> entries = new ArrayList<>();
        boolean hasVoidLog = hasVoidLogTable();

        if (hasVoidLog) {
            String branchClause = branchId != null ? " AND COALESCE(v.branch_id, o.branch_id) = :branchId" : "";
            var logQuery = db.sql("""
                    SELECT v.order_id, o.order_number, v.order_item_id, v.item_name, v.quantity,
                           v.amount_pesewas, v.reason, v.voided_by,
                           COALESCE(v.approved_by_name, c.name) AS approved_by,
                           v.created_at
                    FROM pos_void_log v
                    JOIN pos_order o ON o.id = v.order_id
                    LEFT JOIN pos_cashier c ON c.id = v.approved_by
                    WHERE v.merchant_id = :merchantId
                    """ + branchClause + """
                      AND v.created_at >= :dayStart
                      AND v.created_at < :dayEnd
                    ORDER BY v.created_at DESC
                    """)
                    .param("merchantId", merchantId)
                    .param("dayStart", dayStart)
                    .param("dayEnd", dayEnd);
            if (branchId != null) {
                logQuery = logQuery.param("branchId", branchId);
            }
            entries.addAll(logQuery.query((rs, i) -> new VoidEntry(
                    rs.getObject("order_id", UUID.class),
                    rs.getString("order_number"),
                    rs.getObject("order_item_id", UUID.class),
                    rs.getString("item_name"),
                    rs.getBigDecimal("quantity"),
                    rs.getLong("amount_pesewas"),
                    rs.getString("reason"),
                    rs.getString("voided_by"),
                    rs.getString("approved_by"),
                    rs.getObject("created_at", OffsetDateTime.class)
            )).list());
        }

        // Fallback: include VOIDED orders that do not have an order-level row in pos_void_log
        String orderBranchClause = branchId != null ? " AND o.branch_id = :branchId" : "";
        String notInLogClause = hasVoidLog
                ? " AND NOT EXISTS (SELECT 1 FROM pos_void_log v WHERE v.order_id = o.id AND v.order_item_id IS NULL)"
                : "";
        var fallbackQuery = db.sql("""
                SELECT o.id, o.order_number, o.total_pesewas, o.created_at, c.name AS cashier_name
                FROM pos_order o
                LEFT JOIN pos_cashier c ON c.id = o.cashier_id
                WHERE o.merchant_id = :merchantId
                """ + orderBranchClause + """
                  AND o.status = 'VOIDED'
                  AND o.created_at >= :dayStart
                  AND o.created_at < :dayEnd
                """ + notInLogClause + """
                ORDER BY o.created_at DESC
                """)
                .param("merchantId", merchantId)
                .param("dayStart", dayStart)
                .param("dayEnd", dayEnd);
        if (branchId != null) {
            fallbackQuery = fallbackQuery.param("branchId", branchId);
        }
        entries.addAll(fallbackQuery.query((rs, i) -> new VoidEntry(
                rs.getObject("id", UUID.class),
                rs.getString("order_number"),
                null,
                null,
                null,
                rs.getLong("total_pesewas"),
                "Order voided",
                rs.getString("cashier_name"),
                null,
                rs.getObject("created_at", OffsetDateTime.class)
        )).list());

        entries.sort(Comparator.comparing(
                VoidEntry::voidedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return entries;
    }

    private ShiftCashSummary queryShiftCash(UUID merchantId, UUID branchId, OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        String branchClause = branchId != null ? " AND t.branch_id = :branchId" : "";
        var query = db.sql("""
                SELECT s.id, s.terminal_id, t.branch_id, c.name AS cashier_name, s.status,
                       s.opening_balance_pesewas, s.expected_cash_pesewas,
                       s.closing_balance_pesewas, s.cash_discrepancy_pesewas,
                       s.shift_start, s.shift_end
                FROM pos_shift s
                JOIN pos_terminal t ON t.id = s.terminal_id
                LEFT JOIN pos_cashier c ON c.id = s.cashier_id
                WHERE s.merchant_id = :merchantId
                """ + branchClause + """
                  AND s.shift_start >= :dayStart
                  AND s.shift_start < :dayEnd
                ORDER BY s.shift_start ASC
                """)
                .param("merchantId", merchantId)
                .param("dayStart", dayStart)
                .param("dayEnd", dayEnd);
        if (branchId != null) {
            query = query.param("branchId", branchId);
        }
        List<ShiftCashRow> shifts = query.query((rs, i) -> new ShiftCashRow(
                rs.getObject("id", UUID.class),
                rs.getObject("terminal_id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("cashier_name"),
                rs.getString("status"),
                rs.getLong("opening_balance_pesewas"),
                rs.getLong("expected_cash_pesewas"),
                rs.getObject("closing_balance_pesewas", Long.class),
                rs.getObject("cash_discrepancy_pesewas", Long.class),
                rs.getObject("shift_start", OffsetDateTime.class),
                rs.getObject("shift_end", OffsetDateTime.class)
        )).list();

        long totalExpected = 0L;
        long totalCounted = 0L;
        long totalVariance = 0L;
        int openCount = 0;
        int closedCount = 0;
        for (ShiftCashRow s : shifts) {
            totalExpected += s.expectedCashPesewas();
            if (s.countedCashPesewas() != null) {
                totalCounted += s.countedCashPesewas();
            }
            if (s.variancePesewas() != null) {
                totalVariance += s.variancePesewas();
            }
            if ("OPEN".equals(s.status())) {
                openCount++;
            } else {
                closedCount++;
            }
        }
        return new ShiftCashSummary(totalExpected, totalCounted, totalVariance, openCount, closedCount, shifts);
    }

    private boolean hasVoidLogTable() {
        String regclass = db.sql("SELECT to_regclass('public.pos_void_log')::text")
                .query(String.class)
                .optional()
                .orElse(null);
        return regclass != null && !regclass.isBlank();
    }

    private boolean hasOrderItemVoidedColumn() {
        Boolean exists = db.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_attribute
                    WHERE attrelid = 'pos_order_item'::regclass
                      AND attname = 'voided'
                      AND NOT attisdropped
                )
                """)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }
}
