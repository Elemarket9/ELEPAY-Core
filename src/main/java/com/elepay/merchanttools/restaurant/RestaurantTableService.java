package com.elepay.merchanttools.restaurant;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.Idempotency;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RestaurantTableService {
    private static final String TABLE_COLUMNS = """
            id, merchant_id, section_id, table_number, capacity, status, current_order_id,
            COALESCE(branch_id, waiter_branch_id) AS branch_id
            """;

    private final JdbcClient db;
    private final Audit audit;
    private final Idempotency idempotency;

    public RestaurantTableService(JdbcClient db, Audit audit, Idempotency idempotency) {
        this.db = db;
        this.audit = audit;
        this.idempotency = idempotency;
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
            UUID currentOrderId,
            UUID branchId
    ) {}

    public record MoveTableResult(
            RestaurantTable fromTable,
            RestaurantTable toTable,
            UUID orderId,
            int kdsTicketsMoved,
            int waiterOrdersMoved
    ) {}

    public record MergeTablesResult(
            RestaurantTable targetTable,
            RestaurantTable sourceTable,
            UUID targetOrderId,
            UUID voidedSourceOrderId,
            long mergedTotalPesewas,
            int itemsMoved,
            int kdsTicketsRepointed,
            boolean alreadyMerged
    ) {}

    private record LockedOrder(
            UUID id,
            UUID merchantId,
            UUID branchId,
            String status,
            long subtotalPesewas,
            long discountPesewas,
            long taxPesewas,
            long tipPesewas,
            long totalPesewas,
            long paidPesewas
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
        return createTable(merchantId, sectionId, tableNumber, capacity, null);
    }

    @Transactional
    public RestaurantTable createTable(UUID merchantId, UUID sectionId, String tableNumber, int capacity, UUID branchId) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Table capacity must be positive");
        }
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO restaurant_table (id, merchant_id, section_id, table_number, capacity, status, branch_id)
            VALUES (:id, :merchantId, :sectionId, :tableNumber, :capacity, 'AVAILABLE', :branchId)
            """)
            .param("id", id)
            .param("merchantId", merchantId)
            .param("sectionId", sectionId)
            .param("tableNumber", tableNumber)
            .param("capacity", capacity)
            .param("branchId", branchId)
            .update();

        return getTable(id).orElseThrow();
    }

    public UUID tableBranchId(UUID tableId) {
        return db.sql("""
            SELECT COALESCE(t.branch_id, t.waiter_branch_id, o.branch_id)
            FROM restaurant_table t
            LEFT JOIN pos_order o ON o.id = t.current_order_id
            WHERE t.id = :id
            """)
            .param("id", tableId)
            .query(UUID.class)
            .optional()
            .orElse(null);
    }

    public Optional<RestaurantTable> getTable(UUID id) {
        return db.sql("SELECT " + TABLE_COLUMNS + " FROM restaurant_table WHERE id = :id")
            .param("id", id)
            .query(RestaurantTable.class)
            .optional();
    }

    public List<RestaurantTable> listTables(UUID merchantId) {
        return listTables(merchantId, null);
    }

    public List<RestaurantTable> listTables(UUID merchantId, UUID branchId) {
        if (branchId == null) {
            return db.sql("SELECT " + TABLE_COLUMNS + " FROM restaurant_table WHERE merchant_id = :merchantId ORDER BY table_number ASC")
                .param("merchantId", merchantId)
                .query(RestaurantTable.class)
                .list();
        }
        return db.sql("""
            SELECT id, merchant_id, section_id, table_number, capacity, status, current_order_id,
                   COALESCE(branch_id, waiter_branch_id) AS branch_id
            FROM restaurant_table
            WHERE merchant_id = :merchantId
              AND COALESCE(branch_id, waiter_branch_id) = :branchId
            ORDER BY table_number ASC
            """)
            .param("merchantId", merchantId)
            .param("branchId", branchId)
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
        if (changed != 1) throw ApiException.conflict("table_in_use", "Table already has an order");

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
        if (changed != 1) throw ApiException.conflict("table_in_use", "Cannot vacate an unpaid waiter order");

        return getTable(tableId).orElseThrow();
    }

    @Transactional
    public RestaurantTable markBillPrinted(UUID tableId) {
        RestaurantTable table = db.sql("SELECT " + TABLE_COLUMNS + " FROM restaurant_table WHERE id = :id FOR UPDATE")
            .param("id", tableId)
            .query(RestaurantTable.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("Table not found"));

        if (table.currentOrderId() == null) {
            throw ApiException.conflict("table_not_occupied", "Table does not have an active order");
        }
        if ("BILL_PRINTED".equals(table.status())) {
            return table;
        }
        if (!"OCCUPIED".equals(table.status())) {
            throw ApiException.conflict("invalid_table_status", "Bill can only be printed for an occupied table");
        }

        db.sql("""
            UPDATE restaurant_table
            SET status = 'BILL_PRINTED'
            WHERE merchant_id = :merchantId
              AND current_order_id = :orderId
              AND status = 'OCCUPIED'
            """)
            .param("merchantId", table.merchantId())
            .param("orderId", table.currentOrderId())
            .update();

        return getTable(tableId).orElseThrow();
    }

    @Transactional
    public int markBillPrintedForOrder(UUID merchantId, UUID orderId) {
        if (merchantId == null || orderId == null) {
            return 0;
        }
        return db.sql("""
            UPDATE restaurant_table
            SET status = 'BILL_PRINTED'
            WHERE merchant_id = :merchantId
              AND current_order_id = :orderId
              AND status = 'OCCUPIED'
            """)
            .param("merchantId", merchantId)
            .param("orderId", orderId)
            .update();
    }

    @Transactional
    public int revertBillPrintedToOccupied(UUID merchantId, UUID orderId) {
        if (merchantId == null || orderId == null) {
            return 0;
        }
        return db.sql("""
            UPDATE restaurant_table
            SET status = 'OCCUPIED'
            WHERE merchant_id = :merchantId
              AND current_order_id = :orderId
              AND status = 'BILL_PRINTED'
            """)
            .param("merchantId", merchantId)
            .param("orderId", orderId)
            .update();
    }

    @Transactional
    public MoveTableResult moveTable(UUID fromTableId, UUID toTableId, String actor) {
        return moveTable(fromTableId, toTableId, actor, null);
    }

    @Transactional
    public MoveTableResult moveTable(UUID fromTableId, UUID toTableId, String actor, String idempotencyKey) {
        if (fromTableId == null || toTableId == null) {
            throw ApiException.badRequest("table_required", "Source and destination tables are required");
        }
        if (fromTableId.equals(toTableId)) {
            throw ApiException.badRequest("same_table", "Source and destination tables must be different");
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotency.execute(
                    "restaurant-table-move:" + fromTableId,
                    idempotencyKey.trim(),
                    Map.of("fromTableId", fromTableId.toString(), "toTableId", toTableId.toString()),
                    MoveTableResult.class,
                    () -> doMoveTable(fromTableId, toTableId, actor)
            );
        }
        return doMoveTable(fromTableId, toTableId, actor);
    }

    private MoveTableResult doMoveTable(UUID fromTableId, UUID toTableId, String actor) {
        List<RestaurantTable> locked = lockTablesInOrder(fromTableId, toTableId);
        RestaurantTable from = findById(locked, fromTableId)
                .orElseThrow(() -> ApiException.notFound("Table not found"));
        RestaurantTable to = findById(locked, toTableId)
                .orElseThrow(() -> ApiException.notFound("Table not found"));

        if (!from.merchantId().equals(to.merchantId())) {
            throw ApiException.notFound("Table not found");
        }

        UUID fromBranch = effectiveBranchId(from);
        UUID toBranch = effectiveBranchId(to);
        if (fromBranch != null && toBranch != null && !fromBranch.equals(toBranch)) {
            throw ApiException.conflict("branch_mismatch", "Cannot move a table across different branches");
        }

        if (from.currentOrderId() == null || (!"OCCUPIED".equals(from.status()) && !"BILL_PRINTED".equals(from.status()))) {
            throw ApiException.conflict("source_table_not_occupied", "Source table must be occupied with an active order");
        }
        if (to.currentOrderId() != null || (!"AVAILABLE".equals(to.status()) && !"RESERVED".equals(to.status()))) {
            throw ApiException.conflict("target_table_unavailable", "Destination table must be available or reserved");
        }

        UUID orderId = from.currentOrderId();
        String preservedStatus = from.status();

        db.sql("""
            UPDATE restaurant_table
            SET status = :status, current_order_id = :orderId
            WHERE id = :toId
            """)
            .param("status", preservedStatus)
            .param("orderId", orderId)
            .param("toId", toTableId)
            .update();

        db.sql("""
            UPDATE restaurant_table
            SET status = 'DIRTY', current_order_id = NULL
            WHERE id = :fromId
            """)
            .param("fromId", fromTableId)
            .update();

        int kdsTicketsMoved = db.sql("""
            UPDATE restaurant_kds_ticket
            SET table_id = :toId
            WHERE order_id = :orderId
              AND (table_id = :fromId OR table_id IS NULL)
            """)
            .param("toId", toTableId)
            .param("orderId", orderId)
            .param("fromId", fromTableId)
            .update();

        int waiterOrdersMoved = db.sql("""
            UPDATE restaurant_waiter_order
            SET table_id = :toId
            WHERE order_id = :orderId
              AND table_id = :fromId
            """)
            .param("toId", toTableId)
            .param("orderId", orderId)
            .param("fromId", fromTableId)
            .update();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("merchantId", from.merchantId().toString());
        details.put("fromTableId", fromTableId.toString());
        details.put("fromTableNumber", from.tableNumber());
        details.put("toTableId", toTableId.toString());
        details.put("toTableNumber", to.tableNumber());
        details.put("orderId", orderId.toString());
        details.put("preservedStatus", preservedStatus);
        details.put("kdsTicketsMoved", kdsTicketsMoved);
        details.put("waiterOrdersMoved", waiterOrdersMoved);
        audit.record(actor != null && !actor.isBlank() ? actor : "system",
                "restaurant.table.move", fromTableId.toString(), details);

        return new MoveTableResult(
                getTable(fromTableId).orElseThrow(),
                getTable(toTableId).orElseThrow(),
                orderId,
                kdsTicketsMoved,
                waiterOrdersMoved
        );
    }

    @Transactional
    public MergeTablesResult mergeTables(UUID targetTableId, UUID sourceTableId, String actor) {
        if (targetTableId == null || sourceTableId == null) {
            throw ApiException.badRequest("table_required", "Target and source tables are required");
        }
        if (targetTableId.equals(sourceTableId)) {
            throw ApiException.badRequest("same_table", "Target and source tables must be different");
        }

        List<RestaurantTable> locked = lockTablesInOrder(targetTableId, sourceTableId);
        RestaurantTable target = findById(locked, targetTableId)
                .orElseThrow(() -> ApiException.notFound("Table not found"));
        RestaurantTable source = findById(locked, sourceTableId)
                .orElseThrow(() -> ApiException.notFound("Table not found"));

        if (!target.merchantId().equals(source.merchantId())) {
            throw ApiException.notFound("Table not found");
        }

        UUID targetBranch = effectiveBranchId(target);
        UUID sourceBranch = effectiveBranchId(source);
        if (targetBranch != null && sourceBranch != null && !targetBranch.equals(sourceBranch)) {
            throw ApiException.conflict("branch_mismatch", "Cannot merge tables across different branches");
        }

        if (target.currentOrderId() == null || source.currentOrderId() == null
                || (!"OCCUPIED".equals(target.status()) && !"BILL_PRINTED".equals(target.status()))
                || (!"OCCUPIED".equals(source.status()) && !"BILL_PRINTED".equals(source.status()))) {
            throw ApiException.conflict("table_not_occupied", "Both target and source tables must have active orders");
        }

        UUID targetOrderId = target.currentOrderId();
        UUID sourceOrderId = source.currentOrderId();

        // Idempotent retry: both tables already point to the same merged order.
        if (targetOrderId.equals(sourceOrderId)) {
            long currentTotal = db.sql("SELECT total_pesewas FROM pos_order WHERE id = :id")
                    .param("id", targetOrderId)
                    .query(Long.class)
                    .optional()
                    .orElse(0L);
            return new MergeTablesResult(target, source, targetOrderId, null, currentTotal, 0, 0, true);
        }

        List<LockedOrder> lockedOrders = lockOrdersInOrder(targetOrderId, sourceOrderId);
        LockedOrder targetOrder = lockedOrders.stream()
                .filter(o -> o.id().equals(targetOrderId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Target order not found"));
        LockedOrder sourceOrder = lockedOrders.stream()
                .filter(o -> o.id().equals(sourceOrderId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Source order not found"));

        if (!target.merchantId().equals(targetOrder.merchantId()) || !target.merchantId().equals(sourceOrder.merchantId())) {
            throw ApiException.notFound("Order not found");
        }
        if (targetOrder.branchId() != null && sourceOrder.branchId() != null
                && !targetOrder.branchId().equals(sourceOrder.branchId())) {
            throw ApiException.conflict("branch_mismatch", "Cannot merge orders from different branches");
        }

        if (!"PENDING".equals(targetOrder.status()) && !"PARTIAL".equals(targetOrder.status())) {
            throw ApiException.conflict("target_order_not_open", "Target order must be PENDING or PARTIAL");
        }
        if (!"PENDING".equals(sourceOrder.status()) || sourceOrder.paidPesewas() != 0L) {
            throw ApiException.conflict("source_order_has_payments", "Source order must be PENDING with no payments recorded");
        }

        long activeCheckouts = db.sql("""
            SELECT count(*) FROM pos_checkout_link
            WHERE order_id = :sourceOrderId
              AND status IN ('open', 'applied')
            """)
            .param("sourceOrderId", sourceOrderId)
            .query(Long.class)
            .single();
        if (activeCheckouts > 0) {
            throw ApiException.conflict("source_order_has_active_checkout", "Source order has an active or applied checkout link");
        }

        long paidSplits = db.sql("""
            SELECT count(*) FROM pos_order_split
            WHERE order_id IN (:targetOrderId, :sourceOrderId)
              AND (paid_pesewas > 0 OR status = 'PAID')
            """)
            .param("targetOrderId", targetOrderId)
            .param("sourceOrderId", sourceOrderId)
            .query(Long.class)
            .single();
        if (paidSplits > 0) {
            throw ApiException.conflict("split_already_in_progress",
                    "Cannot merge tables while split bill payments are in progress");
        }
        db.sql("""
            DELETE FROM pos_order_split
            WHERE order_id IN (:targetOrderId, :sourceOrderId)
              AND status = 'OPEN'
              AND paid_pesewas = 0
            """)
            .param("targetOrderId", targetOrderId)
            .param("sourceOrderId", sourceOrderId)
            .update();

        int itemsMoved = db.sql("""
            UPDATE pos_order_item
            SET order_id = :targetOrderId
            WHERE order_id = :sourceOrderId
            """)
            .param("targetOrderId", targetOrderId)
            .param("sourceOrderId", sourceOrderId)
            .update();

        long newSubtotal = db.sql("""
            SELECT COALESCE(SUM(total_pesewas), 0)
            FROM pos_order_item
            WHERE order_id = :targetOrderId
              AND NOT voided
            """)
            .param("targetOrderId", targetOrderId)
            .query(Long.class)
            .single();

        long combinedDiscount;
        long combinedTax;
        long combinedTip;
        long newTotal;
        try {
            combinedDiscount = Math.addExact(targetOrder.discountPesewas(), sourceOrder.discountPesewas());
            combinedTax = Math.addExact(targetOrder.taxPesewas(), sourceOrder.taxPesewas());
            combinedTip = Math.addExact(targetOrder.tipPesewas(), sourceOrder.tipPesewas());
            if (combinedDiscount > newSubtotal) {
                throw ApiException.conflict("discount_exceeds_subtotal", "Combined discount exceeds merged subtotal");
            }
            newTotal = Math.addExact(Math.addExact(Math.subtractExact(newSubtotal, combinedDiscount), combinedTax), combinedTip);
        } catch (ArithmeticException ex) {
            throw ApiException.badRequest("amount_overflow", "Merged order amount exceeds supported range");
        }

        db.sql("""
            UPDATE pos_order
            SET subtotal_pesewas = :subtotal,
                discount_pesewas = :discount,
                tax_pesewas = :tax,
                tip_pesewas = :tip,
                total_pesewas = :total
            WHERE id = :targetOrderId
            """)
            .param("subtotal", newSubtotal)
            .param("discount", combinedDiscount)
            .param("tax", combinedTax)
            .param("tip", combinedTip)
            .param("total", newTotal)
            .param("targetOrderId", targetOrderId)
            .update();

        db.sql("""
            UPDATE pos_order
            SET status = 'VOIDED'
            WHERE id = :sourceOrderId
            """)
            .param("sourceOrderId", sourceOrderId)
            .update();

        int kdsTicketsRepointed = db.sql("""
            UPDATE restaurant_kds_ticket
            SET order_id = :targetOrderId,
                table_id = :targetTableId
            WHERE order_id = :sourceOrderId
            """)
            .param("targetOrderId", targetOrderId)
            .param("targetTableId", targetTableId)
            .param("sourceOrderId", sourceOrderId)
            .update();

        db.sql("""
            UPDATE restaurant_table
            SET status = 'OCCUPIED',
                current_order_id = :targetOrderId
            WHERE merchant_id = :merchantId
              AND (id IN (:targetTableId, :sourceTableId)
                   OR current_order_id IN (:targetOrderId, :sourceOrderId))
            """)
            .param("targetOrderId", targetOrderId)
            .param("merchantId", target.merchantId())
            .param("targetTableId", targetTableId)
            .param("sourceTableId", sourceTableId)
            .param("sourceOrderId", sourceOrderId)
            .update();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("merchantId", target.merchantId().toString());
        details.put("targetTableId", targetTableId.toString());
        details.put("targetTableNumber", target.tableNumber());
        details.put("sourceTableId", sourceTableId.toString());
        details.put("sourceTableNumber", source.tableNumber());
        details.put("targetOrderId", targetOrderId.toString());
        details.put("voidedSourceOrderId", sourceOrderId.toString());
        details.put("voidReason", "MERGED");
        details.put("itemsMoved", itemsMoved);
        details.put("kdsTicketsRepointed", kdsTicketsRepointed);
        details.put("mergedTotalPesewas", newTotal);
        audit.record(actor != null && !actor.isBlank() ? actor : "system",
                "restaurant.table.merge", targetTableId.toString(), details);

        return new MergeTablesResult(
                getTable(targetTableId).orElseThrow(),
                getTable(sourceTableId).orElseThrow(),
                targetOrderId,
                sourceOrderId,
                newTotal,
                itemsMoved,
                kdsTicketsRepointed,
                false
        );
    }

    private List<RestaurantTable> lockTablesInOrder(UUID a, UUID b) {
        return db.sql("SELECT " + TABLE_COLUMNS + " FROM restaurant_table WHERE id IN (:a, :b) ORDER BY id ASC FOR UPDATE")
                .param("a", a)
                .param("b", b)
                .query(RestaurantTable.class)
                .list();
    }

    private List<LockedOrder> lockOrdersInOrder(UUID o1, UUID o2) {
        return db.sql("""
            SELECT id, merchant_id, branch_id, status, subtotal_pesewas, discount_pesewas,
                   tax_pesewas, tip_pesewas, total_pesewas, paid_pesewas
            FROM pos_order
            WHERE id IN (:o1, :o2)
            ORDER BY id ASC
            FOR UPDATE
            """)
            .param("o1", o1)
            .param("o2", o2)
            .query(LockedOrder.class)
            .list();
    }

    private Optional<RestaurantTable> findById(List<RestaurantTable> tables, UUID id) {
        return tables.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    private UUID effectiveBranchId(RestaurantTable table) {
        if (table.branchId() != null) {
            return table.branchId();
        }
        if (table.currentOrderId() == null) {
            return null;
        }
        return db.sql("SELECT branch_id FROM pos_order WHERE id = :id")
                .param("id", table.currentOrderId())
                .query(UUID.class)
                .optional()
                .orElse(null);
    }
}

