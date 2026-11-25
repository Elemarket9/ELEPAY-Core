package com.elepay.merchanttools.restaurant;

import java.time.OffsetDateTime;
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
        db.sql("""
            UPDATE restaurant_table
            SET status = :status
            WHERE id = :id
            """)
            .param("status", status.name())
            .param("id", tableId)
            .update();

        return getTable(tableId).orElseThrow();
    }

    @Transactional
    public RestaurantTable seatTable(UUID tableId, UUID orderId) {
        int changed = db.sql("""
            UPDATE restaurant_table
            SET status = 'OCCUPIED', current_order_id = :orderId
            WHERE id = :id AND (current_order_id IS NULL OR current_order_id = :orderId)
            """)
            .param("orderId", orderId)
            .param("id", tableId)
            .update();
        if (changed != 1) throw com.elepay.common.ApiException.conflict("table_in_use", "Table already has an order");

        return getTable(tableId).orElseThrow();
    }

    @Transactional
    public RestaurantTable vacateTable(UUID tableId, boolean markDirty) {
        String newStatus = markDirty ? "DIRTY" : "AVAILABLE";
        int changed = db.sql("""
            UPDATE restaurant_table t
            SET status = :status, current_order_id = NULL
            WHERE id = :id AND NOT EXISTS (
                SELECT 1 FROM restaurant_waiter_order w JOIN pos_order o ON o.id = w.order_id
                WHERE w.order_id = t.current_order_id AND o.status NOT IN ('PAID', 'VOIDED'))
            """)
            .param("status", newStatus)
            .param("id", tableId)
            .update();
        if (changed != 1) throw com.elepay.common.ApiException.conflict("table_in_use", "Cannot vacate an unpaid waiter order");

        return getTable(tableId).orElseThrow();
    }
}
