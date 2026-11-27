package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 6 live order management query service.
 * Cursor-paged listing of POS orders enriched with item counts, table info, server/cashier name,
 * and aggregated kitchen status from KDS tickets.
 */
@Service
public class PosOrderQueryService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final Set<String> VALID_STATUSES = Set.of("PENDING", "PARTIAL", "PAID", "VOIDED", "OPEN", "ALL");
    private static final Set<String> VALID_TYPES = Set.of("DINE_IN", "TAKEAWAY", "DELIVERY", "STANDARD", "ALL");

    private final JdbcClient db;

    public PosOrderQueryService(JdbcClient db) {
        this.db = db;
    }

    public record LiveOrderPage(
            String nextCursor,
            boolean hasMore,
            OffsetDateTime serverTime,
            List<LiveOrderSummary> orders
    ) {}

    public record LiveOrderSummary(
            UUID id,
            String orderNumber,
            String orderType,
            UUID branchId,
            UUID tableId,
            String tableNumber,
            String serverName,
            int itemCount,
            BigDecimal totalQuantity,
            long subtotalPesewas,
            long discountPesewas,
            long taxPesewas,
            long tipPesewas,
            long totalPesewas,
            long paidPesewas,
            long balancePesewas,
            String paymentStatus,
            String kitchenStatus,
            int kdsTicketCount,
            OffsetDateTime createdAt,
            long ageSeconds
    ) {}

    private record OrderRow(
            UUID id,
            String orderNumber,
            String orderType,
            UUID branchId,
            String cashierName,
            UUID waiterTableId,
            String waiterSubject,
            long subtotalPesewas,
            long discountPesewas,
            long taxPesewas,
            long tipPesewas,
            long totalPesewas,
            long paidPesewas,
            String status,
            OffsetDateTime createdAt
    ) {}

    private record ItemAgg(int itemCount, BigDecimal totalQuantity) {}

    private record KdsTicketSummary(UUID orderId, String status, UUID tableId, String serverName) {}

    private record DecodedCursor(OffsetDateTime createdAt, UUID id) {}

    @Transactional(readOnly = true)
    public LiveOrderPage listOrders(
            UUID merchantId,
            UUID branchId,
            String status,
            String type,
            OffsetDateTime since,
            String cursor,
            Integer limit
    ) {
        if (merchantId == null) {
            throw ApiException.badRequest("merchant_required", "Merchant is required");
        }
        String normalizedStatus = normalizeStatus(status);
        String normalizedType = normalizeType(type);
        int pageSize = clampLimit(limit);
        DecodedCursor decodedCursor = decodeCursor(cursor);

        StringBuilder sql = new StringBuilder("""
                SELECT o.id, o.order_number, o.order_type, o.branch_id,
                       c.name AS cashier_name,
                       rwo.table_id AS waiter_table_id,
                       rwo.waiter_subject,
                       o.subtotal_pesewas, o.discount_pesewas, o.tax_pesewas, o.tip_pesewas,
                       o.total_pesewas, o.paid_pesewas, o.status, o.created_at
                FROM pos_order o
                LEFT JOIN pos_cashier c ON c.id = o.cashier_id
                LEFT JOIN restaurant_waiter_order rwo ON rwo.order_id = o.id
                WHERE o.merchant_id = :merchantId
                """);

        if (branchId != null) {
            sql.append(" AND o.branch_id = :branchId");
        }
        if ("OPEN".equals(normalizedStatus)) {
            sql.append(" AND o.status IN ('PENDING', 'PARTIAL')");
        } else if (normalizedStatus != null) {
            sql.append(" AND o.status = :status");
        }
        if (normalizedType != null) {
            sql.append(" AND o.order_type = :orderType");
        }
        if (since != null) {
            sql.append(" AND o.created_at >= :since");
        }
        if (decodedCursor != null) {
            sql.append(" AND (o.created_at < :cursorTime OR (o.created_at = :cursorTime AND o.id < :cursorId))");
        }
        sql.append(" ORDER BY o.created_at DESC, o.id DESC LIMIT :limitPlusOne");

        var query = db.sql(sql.toString())
                .param("merchantId", merchantId)
                .param("limitPlusOne", pageSize + 1);
        if (branchId != null) {
            query = query.param("branchId", branchId);
        }
        if (normalizedStatus != null && !"OPEN".equals(normalizedStatus)) {
            query = query.param("status", normalizedStatus);
        }
        if (normalizedType != null) {
            query = query.param("orderType", normalizedType);
        }
        if (since != null) {
            query = query.param("since", since);
        }
        if (decodedCursor != null) {
            query = query.param("cursorTime", decodedCursor.createdAt())
                    .param("cursorId", decodedCursor.id());
        }

        List<OrderRow> rows = new ArrayList<>(query.query((rs, i) -> new OrderRow(
                rs.getObject("id", UUID.class),
                rs.getString("order_number"),
                rs.getString("order_type"),
                rs.getObject("branch_id", UUID.class),
                rs.getString("cashier_name"),
                rs.getObject("waiter_table_id", UUID.class),
                rs.getString("waiter_subject"),
                rs.getLong("subtotal_pesewas"),
                rs.getLong("discount_pesewas"),
                rs.getLong("tax_pesewas"),
                rs.getLong("tip_pesewas"),
                rs.getLong("total_pesewas"),
                rs.getLong("paid_pesewas"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class)
        )).list());

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, pageSize));
        }

        OffsetDateTime serverTime = OffsetDateTime.now(ZoneOffset.UTC);
        if (rows.isEmpty()) {
            return new LiveOrderPage(null, false, serverTime, List.of());
        }

        List<UUID> orderIds = rows.stream().map(OrderRow::id).toList();
        Map<UUID, ItemAgg> itemAggs = fetchItemAggs(orderIds);
        Map<UUID, List<KdsTicketSummary>> ticketsByOrder = fetchKdsTickets(orderIds);

        // Resolve tables: active table assignment first, then waiter order / KDS ticket fallback.
        Map<UUID, UUID> activeTableIdByOrder = new HashMap<>();
        Map<UUID, String> tableNumberById = new HashMap<>();
        db.sql("""
                SELECT current_order_id, id, table_number
                FROM restaurant_table
                WHERE current_order_id IN (:ids)
                """)
                .param("ids", orderIds)
                .query((rs, i) -> {
                    UUID ordId = rs.getObject(1, UUID.class);
                    UUID tblId = rs.getObject(2, UUID.class);
                    String tblNum = rs.getString(3);
                    activeTableIdByOrder.put(ordId, tblId);
                    tableNumberById.put(tblId, tblNum);
                    return tblId;
                })
                .list();

        Set<UUID> unresolvedTableIds = new HashSet<>();
        for (OrderRow row : rows) {
            if (!activeTableIdByOrder.containsKey(row.id())) {
                UUID candidate = row.waiterTableId();
                if (candidate == null) {
                    List<KdsTicketSummary> tks = ticketsByOrder.getOrDefault(row.id(), List.of());
                    for (KdsTicketSummary tk : tks) {
                        if (tk.tableId() != null) {
                            candidate = tk.tableId();
                            break;
                        }
                    }
                }
                if (candidate != null && !tableNumberById.containsKey(candidate)) {
                    unresolvedTableIds.add(candidate);
                }
            }
        }
        if (!unresolvedTableIds.isEmpty()) {
            db.sql("SELECT id, table_number FROM restaurant_table WHERE id IN (:tableIds)")
                    .param("tableIds", List.copyOf(unresolvedTableIds))
                    .query((rs, i) -> {
                        tableNumberById.put(rs.getObject(1, UUID.class), rs.getString(2));
                        return null;
                    })
                    .list();
        }

        List<LiveOrderSummary> summaries = new ArrayList<>(rows.size());
        for (OrderRow row : rows) {
            ItemAgg agg = itemAggs.getOrDefault(row.id(), new ItemAgg(0, BigDecimal.ZERO));
            List<KdsTicketSummary> tickets = ticketsByOrder.getOrDefault(row.id(), List.of());

            UUID tableId = activeTableIdByOrder.get(row.id());
            if (tableId == null) {
                tableId = row.waiterTableId();
            }
            if (tableId == null) {
                for (KdsTicketSummary tk : tickets) {
                    if (tk.tableId() != null) {
                        tableId = tk.tableId();
                        break;
                    }
                }
            }
            String tableNumber = tableId != null ? tableNumberById.get(tableId) : null;

            String serverName = resolveServerName(row.cashierName(), tickets, row.waiterSubject());
            String kitchenStatus = aggregateKitchenStatus(tickets);
            long balancePesewas = "VOIDED".equals(row.status())
                    ? 0L
                    : Math.max(0L, row.totalPesewas() - row.paidPesewas());
            long ageSeconds = row.createdAt() == null
                    ? 0L
                    : Math.max(0L, Duration.between(row.createdAt().toInstant(), serverTime.toInstant()).getSeconds());

            summaries.add(new LiveOrderSummary(
                    row.id(),
                    row.orderNumber(),
                    row.orderType(),
                    row.branchId(),
                    tableId,
                    tableNumber,
                    serverName,
                    agg.itemCount(),
                    agg.totalQuantity(),
                    row.subtotalPesewas(),
                    row.discountPesewas(),
                    row.taxPesewas(),
                    row.tipPesewas(),
                    row.totalPesewas(),
                    row.paidPesewas(),
                    balancePesewas,
                    row.status(),
                    kitchenStatus,
                    tickets.size(),
                    row.createdAt(),
                    ageSeconds
            ));
        }

        OrderRow last = rows.get(rows.size() - 1);
        String nextCursor = hasMore ? encodeCursor(last.createdAt(), last.id()) : null;
        return new LiveOrderPage(nextCursor, hasMore, serverTime, summaries);
    }

    private Map<UUID, ItemAgg> fetchItemAggs(List<UUID> orderIds) {
        String voidFilter = hasOrderItemVoidedColumn() ? " AND NOT voided" : "";
        Map<UUID, ItemAgg> result = new HashMap<>();
        db.sql("""
                SELECT order_id, COUNT(*)::int AS cnt, COALESCE(SUM(quantity), 0) AS qty
                FROM pos_order_item
                WHERE order_id IN (:ids)
                """ + voidFilter + " GROUP BY order_id")
                .param("ids", orderIds)
                .query((rs, i) -> {
                    UUID ordId = rs.getObject(1, UUID.class);
                    int cnt = rs.getInt(2);
                    BigDecimal qty = rs.getBigDecimal(3);
                    result.put(ordId, new ItemAgg(cnt, qty == null ? BigDecimal.ZERO : qty));
                    return ordId;
                })
                .list();
        return result;
    }

    private Map<UUID, List<KdsTicketSummary>> fetchKdsTickets(List<UUID> orderIds) {
        Map<UUID, List<KdsTicketSummary>> result = new LinkedHashMap<>();
        db.sql("""
                SELECT order_id, status, table_id, server_name
                FROM restaurant_kds_ticket
                WHERE order_id IN (:ids)
                ORDER BY created_at ASC, id ASC
                """)
                .param("ids", orderIds)
                .query((rs, i) -> new KdsTicketSummary(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getObject(3, UUID.class),
                        rs.getString(4)
                ))
                .list()
                .forEach(tk -> result.computeIfAbsent(tk.orderId(), k -> new ArrayList<>()).add(tk));
        return result;
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

    static String aggregateKitchenStatus(List<KdsTicketSummary> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return "NONE";
        }
        List<KdsTicketSummary> active = tickets.stream()
                .filter(t -> !"CANCELLED".equals(t.status()))
                .toList();
        if (active.isEmpty()) {
            return "CANCELLED";
        }
        boolean allServed = active.stream().allMatch(t -> "SERVED".equals(t.status()));
        if (allServed) {
            return "SERVED";
        }
        boolean allNew = active.stream().allMatch(t -> "NEW".equals(t.status()));
        if (allNew) {
            return "NEW";
        }
        boolean anyNewOrPreparing = active.stream()
                .anyMatch(t -> "NEW".equals(t.status()) || "PREPARING".equals(t.status()));
        if (anyNewOrPreparing) {
            return "PREPARING";
        }
        return "READY";
    }

    private static String resolveServerName(
            String cashierName,
            List<KdsTicketSummary> tickets,
            String waiterSubject
    ) {
        if (cashierName != null && !cashierName.isBlank()) {
            return cashierName.strip();
        }
        for (KdsTicketSummary tk : tickets) {
            if (tk.serverName() != null && !tk.serverName().isBlank()) {
                return tk.serverName().strip();
            }
        }
        if (waiterSubject != null && !waiterSubject.isBlank()) {
            return waiterSubject.strip();
        }
        return null;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String upper = status.strip().toUpperCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(upper)) {
            throw ApiException.badRequest("invalid_status", "Unsupported status filter: " + status);
        }
        return "ALL".equals(upper) ? null : upper;
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String upper = type.strip().toUpperCase(Locale.ROOT);
        if (!VALID_TYPES.contains(upper)) {
            throw ApiException.badRequest("invalid_order_type", "Unsupported order type filter: " + type);
        }
        return "ALL".equals(upper) ? null : upper;
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    static String encodeCursor(OffsetDateTime createdAt, UUID id) {
        Instant inst = createdAt.toInstant();
        long epochMicros = inst.getEpochSecond() * 1_000_000L + (inst.getNano() / 1_000L);
        String raw = epochMicros + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static DecodedCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            if (sep <= 0 || sep >= raw.length() - 1) {
                throw ApiException.badRequest("cursor_invalid", "Invalid cursor");
            }
            long epochMicros = Long.parseLong(raw.substring(0, sep));
            UUID id = UUID.fromString(raw.substring(sep + 1));
            long secs = Math.floorDiv(epochMicros, 1_000_000L);
            long micros = Math.floorMod(epochMicros, 1_000_000L);
            OffsetDateTime createdAt = OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(secs, micros * 1_000L), ZoneOffset.UTC);
            return new DecodedCursor(createdAt, id);
        } catch (ApiException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("cursor_invalid", "Invalid cursor");
        }
    }
}
