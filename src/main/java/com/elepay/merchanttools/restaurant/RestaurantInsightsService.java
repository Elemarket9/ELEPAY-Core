package com.elepay.merchanttools.restaurant;

import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Actual POS data only. Gross POS receipts are NOT bank-settled net revenue or taxes.
 * Branch/date scopes are bound SQL parameters, never client-provided SQL fragments.
 */
@Service
public class RestaurantInsightsService {
    private static final ZoneId GHANA = ZoneId.of("Africa/Accra");
    private final JdbcClient db;

    public RestaurantInsightsService(JdbcClient db) { this.db = db; }

    public record Dish(String name, BigDecimal quantity, long grossPesewas) {}
    public record Staff(String identity, long orders, long grossPesewas) {}
    public record Branch(UUID id, long orders, long grossPesewas) {}
    public record LowStock(String itemName, String sku, BigDecimal available, BigDecimal reorderPoint) {}
    public record Report(LocalDate from, LocalDate through, UUID branchId,
                         long paidOrders, long openOrders, long grossPesewas, long discountPesewas,
                         long tipsPesewas, double averagePrepMinutes,
                         List<Dish> bestDishes, List<Dish> leastSoldDishes,
                         List<Staff> staff, List<Branch> branches, List<LowStock> stockAlerts) {}

    private record Range(LocalDate from, LocalDate through, OffsetDateTime start, OffsetDateTime end) {}

    private Range check(LocalDate from, LocalDate through) {
        LocalDate today = LocalDate.now(GHANA);
        LocalDate a = from == null ? today.minusDays(29) : from;
        LocalDate b = through == null ? today : through;
        if (a.isAfter(b) || a.plusDays(90).isBefore(b) || b.isAfter(today.plusDays(1)))
            throw ApiException.badRequest("report_range_invalid", "Select at most 90 days ending no later than tomorrow");
        return new Range(a, b, a.atStartOfDay(GHANA).toOffsetDateTime(),
                b.plusDays(1).atStartOfDay(GHANA).toOffsetDateTime());
    }

    private JdbcClient.StatementSpec scoped(String sql, UUID merchant, UUID branch, Range r) {
        JdbcClient.StatementSpec stmt = db.sql(sql)
                .param("merchant", merchant).param("start", r.start()).param("end", r.end());
        if (branch != null) stmt = stmt.param("branch", branch);
        return stmt;
    }
    private static String scope(UUID branch) {
        return "p.merchant_id = :merchant AND p.created_at >= :start AND p.created_at < :end"
                + (branch == null ? "" : " AND p.branch_id = :branch");
    }

    public Report report(UUID merchant, UUID branch, LocalDate from, LocalDate through) {
        Range range = check(from, through);
        String where = scope(branch);
        var totals = scoped("""
                SELECT count(*) FILTER (WHERE p.status = 'PAID') AS paid,
                       count(*) FILTER (WHERE p.status IN ('PENDING','PARTIAL')) AS open,
                       coalesce(sum(p.total_pesewas) FILTER (WHERE p.status = 'PAID'), 0)::bigint AS gross,
                       coalesce(sum(p.discount_pesewas) FILTER (WHERE p.status = 'PAID'), 0)::bigint AS discounts,
                       coalesce(sum(p.tip_pesewas) FILTER (WHERE p.status = 'PAID'), 0)::bigint AS tips
                FROM pos_order p WHERE
                """ + where, merchant, branch, range)
                .query((rs, n) -> new long[]{rs.getLong("paid"), rs.getLong("open"),
                        rs.getLong("gross"), rs.getLong("discounts"), rs.getLong("tips")}).single();
        double avgMinutes = scoped("""
                SELECT coalesce(avg(extract(epoch from t.ready_at - t.created_at) / 60), 0)
                FROM restaurant_kds_ticket t JOIN pos_order p ON p.id = t.order_id
                WHERE t.ready_at IS NOT NULL AND
                """ + where, merchant, branch, range).query(BigDecimal.class).single().doubleValue();
        String dishSql = """
                SELECT i.item_name AS item_name, sum(i.quantity) AS qty,
                       sum(i.total_pesewas)::bigint AS sales
                FROM pos_order_item i JOIN pos_order p ON p.id = i.order_id
                WHERE p.status = 'PAID' AND
                """ + where + " GROUP BY i.item_name ORDER BY sales ";
        var dishesTop = scoped(dishSql + "DESC, item_name ASC LIMIT 10",
                merchant, branch, range)
                .query((rs,n) -> new Dish(rs.getString("item_name"),
                        rs.getBigDecimal("qty"),rs.getLong("sales"))).list();
        var dishesLow = scoped(dishSql + "ASC, item_name ASC LIMIT 10",
                merchant, branch, range)
                .query((rs,n) -> new Dish(rs.getString("item_name"),
                        rs.getBigDecimal("qty"),rs.getLong("sales"))).list();
        var staff = scoped("""
                SELECT coalesce(nullif(p.created_by_subject, ''), 'Unattributed') AS operator,
                       count(*) AS orders, sum(p.total_pesewas)::bigint AS sales
                FROM pos_order p WHERE p.status = 'PAID' AND
                """ + where + """
                GROUP BY operator ORDER BY sales DESC LIMIT 30
                """, merchant, branch, range)
                .query((rs,n) -> new Staff(rs.getString("operator"),
                        rs.getLong("orders"),rs.getLong("sales"))).list();
        var branches = scoped("""
                SELECT p.branch_id AS branch, count(*) AS orders, sum(p.total_pesewas)::bigint AS sales
                FROM pos_order p WHERE p.status = 'PAID' AND
                """ + where + """
                GROUP BY p.branch_id ORDER BY sales DESC LIMIT 100
                """, merchant, branch, range)
                .query((rs,n) -> new Branch(rs.getObject("branch", UUID.class),
                        rs.getLong("orders"),rs.getLong("sales"))).list();
        String stockSql = """
                SELECT coalesce(v.variant_name, prod.name) AS item_name, v.sku,
                       sum(s.quantity_on_hand - s.quantity_reserved) AS available,
                       sum(s.reorder_point) AS reorder_point
                FROM inventory_stock s
                JOIN inventory_warehouse w ON w.id = s.warehouse_id
                JOIN inventory_variant v ON v.id = s.variant_id
                JOIN inventory_product prod ON prod.id = v.product_id
                WHERE w.merchant_id = :merchant AND w.is_active = true
                """ + (branch == null ? "" : " AND w.branch_id = :branch") + """
                GROUP BY v.id, v.variant_name, v.sku, prod.name
                HAVING sum(s.quantity_on_hand - s.quantity_reserved) <= sum(s.reorder_point)
                ORDER BY available ASC LIMIT 30
                """;
        JdbcClient.StatementSpec stockStmt = db.sql(stockSql).param("merchant", merchant);
        if (branch != null) stockStmt = stockStmt.param("branch", branch);
        var lowStock = stockStmt.query((rs,n) -> new LowStock(rs.getString("item_name"),
                rs.getString("sku"),rs.getBigDecimal("available"),rs.getBigDecimal("reorder_point"))).list();
        return new Report(range.from(), range.through(), branch, totals[0], totals[1],
                totals[2], totals[3], totals[4], avgMinutes,
                dishesTop, dishesLow, staff, branches, lowStock);
    }

    public String csv(UUID merchant, UUID branch, LocalDate from, LocalDate through) {
        Range range = check(from, through);
        var rows = scoped("""
                SELECT p.order_number, p.order_type, p.status, p.branch_id,
                       coalesce(p.created_by_subject,'') AS actor, p.total_pesewas,
                       p.paid_pesewas, p.created_at
                FROM pos_order p WHERE
                """ + scope(branch) + """
                ORDER BY p.created_at DESC, p.id DESC LIMIT 10001
                """, merchant, branch, range)
                .query((rs,n) -> new String[]{
                        rs.getString("order_number"), rs.getString("order_type"),
                        rs.getString("status"), String.valueOf(rs.getObject("branch_id")),
                        rs.getString("actor"), String.valueOf(rs.getLong("total_pesewas")),
                        String.valueOf(rs.getLong("paid_pesewas")),
                        rs.getObject("created_at", OffsetDateTime.class).toString()}).list();
        if (rows.size() > 10000)
            throw ApiException.badRequest("report_too_large", "Select a shorter reporting interval");
        StringBuilder csv = new StringBuilder("Order,Channel,Status,Branch,Staff,TotalPesewas,PaidPesewas,CreatedAt\r\n");
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) csv.append(',');
                csv.append(csvCell(row[i]));
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    /** Prevent formula execution when a manager opens a CSV in Excel or Sheets. */
    static String csvCell(String value) {
        String safe = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
        if (!safe.isEmpty() && "=+-@\t".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
