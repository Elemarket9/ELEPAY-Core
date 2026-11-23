package com.elepay.merchanttools.restaurant;

import java.time.OffsetDateTime;
import com.elepay.common.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RestaurantTableService {
    private final JdbcClient db;

    public RestaurantTableService(JdbcClient db) {
        this.db = db;
    }

    public record FloorPlan(
            UUID id,
            UUID merchantId,
            String name,
            String layoutJson,
            OffsetDateTime createdAt
    ) {}

    public record RestaurantTable(
            UUID id,
            UUID merchantId,
            UUID sectionId,
            String tableNumber,
            int capacity,
            String status,
            UUID currentOrderId
    ) {}

    @Transactional
    public FloorPlan createFloorPlan(UUID merchantId, String name, String layoutJson) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO restaurant_floor_plan (id, merchant_id, name, layout_json, created_at)
            VALUES (:id, :merchantId, :name, :layoutJson, now())
            """)
            .param("id", id)
            .param("merchantId", merchantId)
            .param("name", name)
            .param("layoutJson", layoutJson == null ? "{}" : layoutJson)
            .update();

        return new FloorPlan(id, merchantId, name, layoutJson, OffsetDateTime.now());
    }

    public List<FloorPlan> listFloorPlans(UUID merchantId) {
        return db.sql("""
            SELECT id, merchant_id, name, layout_json, created_at
            FROM restaurant_floor_plan WHERE merchant_id = :merchantId
            ORDER BY name ASC
            """)
            .param("merchantId", merchantId)
            .query(FloorPlan.class)
            .list();
    }

    @Transactional
    public RestaurantTable createTable(UUID merchantId, UUID sectionId, String tableNumber, int capacity) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO restaurant_table (id, merchant_id, section_id, table_number, capacity, status)
            VALUES (:id, :merchantId, :sectionId, :tableNumber, :capacity, 'AVAILABLE')
            """)
            .param("id", id)
            .param("merchantId", merchantId)
            .param("sectionId", sectionId)
            .param("tableNumber", tableNumber)
            .param("capacity", capacity)
            .update();

        return getTable(id).orElseThrow();
    }

    public Optional<RestaurantTable> getTable(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, section_id, table_number, capacity, status, current_order_id
            FROM restaurant_table WHERE id = :id
            """)
            .param("id", id)
            .query(RestaurantTable.class)
            .optional();
    }

    public List<RestaurantTable> listTables(UUID merchantId) {
        return db.sql("""
            SELECT id, merchant_id, section_id, table_number, capacity, status, current_order_id
            FROM restaurant_table WHERE merchant_id = :merchantId
            ORDER BY table_number ASC
            """)
            .param("merchantId", merchantId)
            .query(RestaurantTable.class)
            .list();
    }

    @Transactional
    public RestaurantTable updateTableStatus(UUID tableId, TableStatus status) {
        if (status == null) throw ApiException.badRequest("status_required", "Choose a table status");
        int updated = db.sql("""
            UPDATE restaurant_table SET status = :status WHERE id = :id
              AND current_order_id IS NULL
            """).param("status", status.name()).param("id", tableId).update();
        if (updated != 1)
            throw ApiException.conflict("table_has_order", "Cannot change an occupied table before its order closes");
        return getTable(tableId).orElseThrow();
    }

    @Transactional
    public RestaurantTable seatTable(UUID tableId, UUID orderId) {
        RestaurantTable table = getTable(tableId).orElseThrow(() -> ApiException.notFound("Table not found"));
        UUID locked = db.sql("SELECT id FROM pos_order WHERE id = :o AND merchant_id = :m FOR UPDATE")
                .param("o", orderId).param("m", table.merchantId()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Order not found for table"));
        long assigned = db.sql("SELECT count(*) FROM restaurant_table WHERE current_order_id = :o")
                .param("o", locked).query(Long.class).single();
        if (assigned != 0) throw ApiException.conflict("order_already_seated", "Order already belongs to another table");
        int updated = db.sql("""
                UPDATE restaurant_table SET status = 'OCCUPIED', current_order_id = :o
                WHERE id = :t AND merchant_id = :m AND status = 'AVAILABLE' AND current_order_id IS NULL
                """).param("o", orderId).param("t", tableId).param("m", table.merchantId()).update();
        if (updated != 1) throw ApiException.conflict("table_unavailable", "Table is not available");
        return getTable(tableId).orElseThrow();
    }

    /**
     * Move a whole open order to another empty table; do not merge financial obligations.
     * KDS tickets are relabelled in the SAME transaction, so the kitchen never sees a stale table.
     */
    @Transactional
    public RestaurantTable moveOrder(UUID sourceId, UUID destinationId) {
        if (sourceId == null || destinationId == null || sourceId.equals(destinationId))
            throw ApiException.badRequest("table_move_invalid", "Select two different tables");
        List<UUID> locked = db.sql("""
                SELECT id FROM restaurant_table WHERE id IN (:source, :dest) ORDER BY id FOR UPDATE
                """).param("source", sourceId).param("dest", destinationId).query(UUID.class).list();
        if (locked.size() != 2) throw ApiException.notFound("Table not found");
        RestaurantTable source = getTable(sourceId).orElseThrow();
        RestaurantTable dest = getTable(destinationId).orElseThrow();
        if (!source.merchantId().equals(dest.merchantId()))
            throw ApiException.notFound("Table not found for merchant");
        if (!"OCCUPIED".equals(source.status()) || source.currentOrderId() == null)
            throw ApiException.conflict("source_table_empty", "Source table has no open order");
        if (!"AVAILABLE".equals(dest.status()) || dest.currentOrderId() != null)
            throw ApiException.conflict("table_unavailable", "Destination table is occupied or unavailable");
        int moved = db.sql("""
                UPDATE restaurant_table SET status = 'OCCUPIED', current_order_id = :order
                WHERE id = :dest AND current_order_id IS NULL AND status = 'AVAILABLE'
                """).param("order", source.currentOrderId()).param("dest", destinationId).update();
        if (moved != 1) throw ApiException.conflict("table_unavailable", "Destination table was taken");
        db.sql("""
                UPDATE restaurant_kds_ticket SET table_id = :dest
                WHERE merchant_id = :merchant AND table_id = :source AND order_id = :order
                """).param("dest", destinationId).param("merchant", source.merchantId())
                .param("source", sourceId).param("order", source.currentOrderId()).update();
        db.sql("""
                UPDATE restaurant_table SET status = 'DIRTY', current_order_id = NULL
                WHERE id = :source AND current_order_id = :order
                """).param("source", sourceId).param("order", source.currentOrderId()).update();
        return getTable(destinationId).orElseThrow();
    }

    /**
     * A customer table cannot be cleared by staff until both the payment and kitchen
     * lifecycle are complete. A separate manager override is not part of this method.
     */
    @Transactional
    public RestaurantTable closeServedAndPaid(UUID tableId) {
        UUID locked = db.sql("SELECT id FROM restaurant_table WHERE id = :table FOR UPDATE")
                .param("table", tableId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Table not found"));
        RestaurantTable table = getTable(locked).orElseThrow();
        if (table.currentOrderId() == null || !"OCCUPIED".equals(table.status()))
            throw ApiException.conflict("table_not_occupied", "Table has no open order");
        String paid = db.sql("SELECT status FROM pos_order WHERE id = :order AND merchant_id = :m FOR UPDATE")
                .param("order", table.currentOrderId()).param("m", table.merchantId())
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("Order not found"));
        if (!"PAID".equals(paid))
            throw ApiException.conflict("order_unpaid", "Settle the complete bill before closing the table");
        long ticketCount = db.sql("SELECT count(*) FROM restaurant_kds_ticket WHERE order_id = :order")
                .param("order", table.currentOrderId()).query(Long.class).single();
        long remaining = db.sql("""
                SELECT count(*) FROM restaurant_kds_ticket WHERE order_id = :order AND status <> 'SERVED'
                """).param("order", table.currentOrderId()).query(Long.class).single();
        if (ticketCount == 0 || remaining != 0)
            throw ApiException.conflict("order_not_served", "Kitchen service must be completed before closing the table");
        db.sql("UPDATE restaurant_table SET status = 'DIRTY', current_order_id = NULL WHERE id = :id")
                .param("id", tableId).update();
        return getTable(tableId).orElseThrow();
    }

    @Transactional
    public RestaurantTable vacateTable(UUID tableId, boolean markDirty) {
        String newStatus = markDirty ? "DIRTY" : "AVAILABLE";
        db.sql("""
            UPDATE restaurant_table
            SET status = :status, current_order_id = NULL
            WHERE id = :id
            """)
            .param("status", newStatus)
            .param("id", tableId)
            .update();

        return getTable(tableId).orElseThrow();
    }
}
