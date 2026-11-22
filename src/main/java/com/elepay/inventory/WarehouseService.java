package com.elepay.inventory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseService {
    private final JdbcClient db;

    public WarehouseService(JdbcClient db) {
        this.db = db;
    }

    public record Warehouse(
            UUID id,
            UUID merchantId,
            UUID branchId,
            String code,
            String name,
            String address,
            boolean isActive,
            OffsetDateTime createdAt
    ) {}

    @Transactional
    public Warehouse createWarehouse(UUID merchantId, UUID branchId, String code, String name, String address) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO inventory_warehouse (id, merchant_id, branch_id, code, name, address, is_active, created_at)
            VALUES (:id, :merchantId, :branchId, :code, :name, :address, true, now())
            """)
            .param("id", id)
            .param("merchantId", merchantId)
            .param("branchId", branchId)
            .param("code", code)
            .param("name", name)
            .param("address", address)
            .update();

        return getWarehouse(id).orElseThrow();
    }

    public Optional<Warehouse> getWarehouse(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, branch_id, code, name, address, is_active, created_at
            FROM inventory_warehouse WHERE id = :id
            """)
            .param("id", id)
            .query(Warehouse.class)
            .optional();
    }

    public List<Warehouse> listWarehouses(UUID merchantId) {
        return db.sql("""
            SELECT id, merchant_id, branch_id, code, name, address, is_active, created_at
            FROM inventory_warehouse WHERE merchant_id = :merchantId
            ORDER BY name ASC
            """)
            .param("merchantId", merchantId)
            .query(Warehouse.class)
            .list();
    }

    @Transactional
    public Warehouse updateWarehouse(UUID id, String name, String address, boolean active) {
        db.sql("""
            UPDATE inventory_warehouse
            SET name = :name, address = :address, is_active = :active
            WHERE id = :id
            """)
            .param("id", id)
            .param("name", name)
            .param("address", address)
            .param("active", active)
            .update();

        return getWarehouse(id).orElseThrow();
    }
}
