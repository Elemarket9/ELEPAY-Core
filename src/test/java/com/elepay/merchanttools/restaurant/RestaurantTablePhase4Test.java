package com.elepay.merchanttools.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.ApiException;
import com.elepay.merchanttools.pos.PosOrderService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class RestaurantTablePhase4Test extends PostgresTestBase {

    @LocalServerPort int port;
    @Autowired RestaurantTableService tableService;
    @Autowired PosOrderService posOrderService;
    @Autowired KdsService kdsService;
    @Autowired JdbcClient db;

    private TestApi api;
    private String ownerToken;
    private String otherMerchantOwnerToken;
    private String branchACashierToken;
    private String branchBCashierToken;
    private UUID merchantId;
    private UUID otherMerchantId;
    private UUID branchAId;
    private UUID branchBId;
    private UUID floorPlanId;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String opsToken = api.token("ops-tbl-" + suffix, "ops");
        ownerToken = api.token("owner-tbl-" + suffix);
        otherMerchantOwnerToken = api.token("other-owner-tbl-" + suffix);
        String cashierASubject = "cashier-a-tbl-" + suffix;
        String cashierBSubject = "cashier-b-tbl-" + suffix;
        branchACashierToken = api.token(cashierASubject);
        branchBCashierToken = api.token(cashierBSubject);

        merchantId = createApprovedMerchant(ownerToken, opsToken, "Phase4 Bistro " + suffix);
        otherMerchantId = createApprovedMerchant(otherMerchantOwnerToken, opsToken, "Other Bistro " + suffix);

        branchAId = UUID.fromString(api.call("POST", "/v1/merchants/" + merchantId + "/branches", ownerToken, null,
                Map.of("name", "Osu Branch")).body().get("id").asString());
        branchBId = UUID.fromString(api.call("POST", "/v1/merchants/" + merchantId + "/branches", ownerToken, null,
                Map.of("name", "East Legon Branch")).body().get("id").asString());

        assertThat(api.call("PUT", "/v1/merchants/" + merchantId + "/members/" + cashierASubject, ownerToken, null,
                Map.of("role", "cashier", "branchId", branchAId.toString())).status()).isEqualTo(200);
        assertThat(api.call("PUT", "/v1/merchants/" + merchantId + "/members/" + cashierBSubject, ownerToken, null,
                Map.of("role", "cashier", "branchId", branchBId.toString())).status()).isEqualTo(200);

        floorPlanId = tableService.createFloorPlan(merchantId, "Main Hall", "{}").id();
    }

    private UUID createApprovedMerchant(String owner, String ops, String name) {
        var created = api.call("POST", "/v1/merchants", owner, null, Map.of(
                "legalName", name,
                "tradingName", name,
                "kind", "merchant",
                "contactPhone", "0241234567"
        ));
        assertThat(created.status()).isEqualTo(200);
        UUID id = UUID.fromString(created.body().get("id").asString());
        var approved = api.call("POST", "/v1/ops/merchants/" + id + "/review", ops, null,
                Map.of("decision", "approve"));
        assertThat(approved.status()).isEqualTo(200);
        return id;
    }

    private PosOrderService.PosOrder createRestaurantOrder(UUID branchId, String itemName, long pricePesewas,
                                                           long discountPesewas, long taxPesewas, long tipPesewas) {
        return posOrderService.createOrder(
                merchantId, branchId, null, null, null,
                List.of(new PosOrderService.OrderItemInput(
                        null, itemName, BigDecimal.ONE, pricePesewas, 0L, 0L, "[]", 1, "MAIN", null)),
                discountPesewas, taxPesewas, tipPesewas, "RESTAURANT"
        );
    }

    @Test
    @DisplayName("BILL_PRINTED lifecycle, idempotency, revert-to-occupied, and DB CHECK constraint")
    void billPrintedLifecycleAndCheckConstraint() {
        var table = tableService.createTable(merchantId, floorPlanId, "BP-1", 4, branchAId);
        assertThat(table.branchId()).isEqualTo(branchAId);

        // Cannot print bill on an unoccupied table
        var unoccupiedResp = api.call("POST", "/v1/restaurant/tables/" + table.id() + "/print-bill",
                branchACashierToken, null, null);
        assertThat(unoccupiedResp.status()).isEqualTo(409);

        var order = createRestaurantOrder(branchAId, "Jollof Rice", 4500L, 0L, 0L, 0L);
        tableService.seatTable(table.id(), order.id());

        // Print bill transitions OCCUPIED -> BILL_PRINTED
        var printResp = api.call("POST", "/v1/restaurant/tables/" + table.id() + "/print-bill",
                branchACashierToken, null, null);
        assertThat(printResp.status()).isEqualTo(200);
        assertThat(printResp.body().get("status").asString()).isEqualTo("BILL_PRINTED");

        // Repeat print-bill is idempotent
        var repeatResp = api.call("POST", "/v1/restaurant/tables/" + table.id() + "/print-bill",
                branchACashierToken, null, null);
        assertThat(repeatResp.status()).isEqualTo(200);
        assertThat(repeatResp.body().get("status").asString()).isEqualTo("BILL_PRINTED");

        // Adding items reverts BILL_PRINTED -> OCCUPIED via locked hook
        int reverted = tableService.revertBillPrintedToOccupied(merchantId, order.id());
        assertThat(reverted).isEqualTo(1);
        assertThat(tableService.getTable(table.id()).orElseThrow().status()).isEqualTo("OCCUPIED");

        // Waiter requesting bill sets BILL_PRINTED via locked hook
        int marked = tableService.markBillPrintedForOrder(merchantId, order.id());
        assertThat(marked).isEqualTo(1);
        assertThat(tableService.getTable(table.id()).orElseThrow().status()).isEqualTo("BILL_PRINTED");

        // DB CHECK constraint rejects arbitrary invalid status
        assertThatThrownBy(() -> db.sql("UPDATE restaurant_table SET status = 'BOGUS_STATUS' WHERE id = :id")
                .param("id", table.id())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Move table atomically moves order, KDS tickets, waiter order, writes audit log, and supports Idempotency-Key")
    void moveTableAtomicUpdatesAuditAndIdempotency() {
        var fromTable = tableService.createTable(merchantId, floorPlanId, "MV-1", 4, branchAId);
        var toTable = tableService.createTable(merchantId, floorPlanId, "MV-2", 6, branchAId);
        var occupiedOther = tableService.createTable(merchantId, floorPlanId, "MV-3", 2, branchAId);

        var order = createRestaurantOrder(branchAId, "Banku & Tilapia", 6500L, 0L, 0L, 0L);
        var otherOrder = createRestaurantOrder(branchAId, "Red Red", 3000L, 0L, 0L, 0L);
        tableService.seatTable(fromTable.id(), order.id());
        tableService.seatTable(occupiedOther.id(), otherOrder.id());
        tableService.markBillPrinted(fromTable.id());

        var orderItems = posOrderService.listOrderItems(order.id());
        var ticket = kdsService.createTicket(
                merchantId, order.id(), fromTable.id(), DiningMode.DINE_IN, "Ama", "MAIN",
                List.of(new KdsService.KdsItemInput(orderItems.get(0).id(), "Banku & Tilapia", BigDecimal.ONE, null, "MAIN")),
                null
        );
        long changeSeqBefore = db.sql("SELECT change_seq FROM restaurant_kds_ticket WHERE id = :id")
                .param("id", ticket.id()).query(Long.class).single();

        // Insert a waiter order pointing to fromTable to verify waiter_order.table_id also moves
        db.sql("""
            INSERT INTO restaurant_waiter_order (order_id, merchant_id, table_id, waiter_subject, ticket_id)
            VALUES (:orderId, :merchantId, :tableId, 'waiter-ama', :ticketId)
            """)
            .param("orderId", order.id())
            .param("merchantId", merchantId)
            .param("tableId", fromTable.id())
            .param("ticketId", ticket.id())
            .update();

        // Moving into an occupied table is rejected with 409
        var badTarget = api.call("POST",
                "/v1/restaurant/tables/" + fromTable.id() + "/move?to=" + occupiedOther.id(),
                branchACashierToken, null, null);
        assertThat(badTarget.status()).isEqualTo(409);

        // Execute move with Idempotency-Key
        String idemKey = "move-" + UUID.randomUUID();
        var moveResp = api.call("POST",
                "/v1/restaurant/tables/" + fromTable.id() + "/move?to=" + toTable.id(),
                branchACashierToken, idemKey, null);
        assertThat(moveResp.status()).isEqualTo(200);
        assertThat(moveResp.body().get("fromTable").get("status").asString()).isEqualTo("DIRTY");
        assertThat(moveResp.body().get("fromTable").get("currentOrderId").isNull()).isTrue();
        assertThat(moveResp.body().get("toTable").get("status").asString()).isEqualTo("BILL_PRINTED");
        assertThat(moveResp.body().get("toTable").get("currentOrderId").asString()).isEqualTo(order.id().toString());
        assertThat(moveResp.body().get("kdsTicketsMoved").asInt()).isEqualTo(1);
        assertThat(moveResp.body().get("waiterOrdersMoved").asInt()).isEqualTo(1);

        // KDS ticket table_id updated and change_seq bumped by trigger
        UUID updatedTicketTable = db.sql("SELECT table_id FROM restaurant_kds_ticket WHERE id = :id")
                .param("id", ticket.id()).query(UUID.class).single();
        long changeSeqAfter = db.sql("SELECT change_seq FROM restaurant_kds_ticket WHERE id = :id")
                .param("id", ticket.id()).query(Long.class).single();
        assertThat(updatedTicketTable).isEqualTo(toTable.id());
        assertThat(changeSeqAfter).isGreaterThan(changeSeqBefore);

        // Waiter order table_id updated
        UUID updatedWaiterTable = db.sql("SELECT table_id FROM restaurant_waiter_order WHERE order_id = :id")
                .param("id", order.id()).query(UUID.class).single();
        assertThat(updatedWaiterTable).isEqualTo(toTable.id());

        // Tamper-evident audit_log entry written
        long auditCount = db.sql("""
            SELECT count(*) FROM audit_log
            WHERE action = 'restaurant.table.move' AND subject = :subject
            """)
            .param("subject", fromTable.id().toString())
            .query(Long.class).single();
        assertThat(auditCount).isEqualTo(1L);

        // Replay with same Idempotency-Key returns cached result without duplicating audit log
        var replayResp = api.call("POST",
                "/v1/restaurant/tables/" + fromTable.id() + "/move?to=" + toTable.id(),
                branchACashierToken, idemKey, null);
        assertThat(replayResp.status()).isEqualTo(200);
        assertThat(replayResp.body().get("toTable").get("currentOrderId").asString()).isEqualTo(order.id().toString());
        long auditCountAfterReplay = db.sql("""
            SELECT count(*) FROM audit_log
            WHERE action = 'restaurant.table.move' AND subject = :subject
            """)
            .param("subject", fromTable.id().toString())
            .query(Long.class).single();
        assertThat(auditCountAfterReplay).isEqualTo(1L);

        // Moving an empty/DIRTY table without idempotency key fails with 409
        var moveEmpty = api.call("POST",
                "/v1/restaurant/tables/" + fromTable.id() + "/move?to=" + occupiedOther.id(),
                branchACashierToken, null, null);
        assertThat(moveEmpty.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("Merge tables moves items, recomputes totals, voids source order, repoints KDS tickets without cancelling, and is idempotent")
    void mergeTablesMovesItemsRecomputesTotalsVoidsSourceRepointsKdsAndIsIdempotent() {
        var targetTable = tableService.createTable(merchantId, floorPlanId, "MG-1", 4, branchAId);
        var sourceTable = tableService.createTable(merchantId, floorPlanId, "MG-2", 4, branchAId);

        // Target: subtotal 3000 - discount 200 + tax 300 + tip 100 = 3200
        var targetOrder = createRestaurantOrder(branchAId, "Waakye Special", 3000L, 200L, 300L, 100L);
        // Source: subtotal 2000 - discount 100 + tax 200 + tip 50 = 2150
        var sourceOrder = createRestaurantOrder(branchAId, "Kelewele", 2000L, 100L, 200L, 50L);

        tableService.seatTable(targetTable.id(), targetOrder.id());
        tableService.seatTable(sourceTable.id(), sourceOrder.id());
        tableService.markBillPrinted(targetTable.id());

        var sourceItems = posOrderService.listOrderItems(sourceOrder.id());
        var sourceTicket = kdsService.createTicket(
                merchantId, sourceOrder.id(), sourceTable.id(), DiningMode.DINE_IN, "Kojo", "APPETIZER",
                List.of(new KdsService.KdsItemInput(sourceItems.get(0).id(), "Kelewele", BigDecimal.ONE, null, "APPETIZER")),
                null
        );
        long changeSeqBefore = db.sql("SELECT change_seq FROM restaurant_kds_ticket WHERE id = :id")
                .param("id", sourceTicket.id()).query(Long.class).single();

        // Execute merge
        var mergeResp = api.call("POST",
                "/v1/restaurant/tables/" + targetTable.id() + "/merge?source=" + sourceTable.id(),
                branchACashierToken, null, null);
        assertThat(mergeResp.status()).isEqualTo(200);
        assertThat(mergeResp.body().get("alreadyMerged").asBoolean()).isFalse();
        assertThat(mergeResp.body().get("targetOrderId").asString()).isEqualTo(targetOrder.id().toString());
        assertThat(mergeResp.body().get("voidedSourceOrderId").asString()).isEqualTo(sourceOrder.id().toString());
        assertThat(mergeResp.body().get("itemsMoved").asInt()).isEqualTo(1);
        assertThat(mergeResp.body().get("kdsTicketsRepointed").asInt()).isEqualTo(1);
        assertThat(mergeResp.body().get("mergedTotalPesewas").asLong()).isEqualTo(5350L);

        // Both tables point to merged target order and are OCCUPIED (BILL_PRINTED reverted)
        var refreshedTarget = tableService.getTable(targetTable.id()).orElseThrow();
        var refreshedSource = tableService.getTable(sourceTable.id()).orElseThrow();
        assertThat(refreshedTarget.currentOrderId()).isEqualTo(targetOrder.id());
        assertThat(refreshedSource.currentOrderId()).isEqualTo(targetOrder.id());
        assertThat(refreshedTarget.status()).isEqualTo("OCCUPIED");
        assertThat(refreshedSource.status()).isEqualTo("OCCUPIED");

        // Target order has both items and recomputed totals; source order is VOIDED
        assertThat(posOrderService.listOrderItems(targetOrder.id())).hasSize(2);
        assertThat(posOrderService.listOrderItems(sourceOrder.id())).isEmpty();
        var targetTotals = db.sql("""
            SELECT subtotal_pesewas, discount_pesewas, tax_pesewas, tip_pesewas, total_pesewas, status
            FROM pos_order WHERE id = :id
            """)
            .param("id", targetOrder.id())
            .query((rs, i) -> Map.of(
                    "subtotal", rs.getLong(1),
                    "discount", rs.getLong(2),
                    "tax", rs.getLong(3),
                    "tip", rs.getLong(4),
                    "total", rs.getLong(5),
                    "status", rs.getString(6)
            )).single();
        assertThat(targetTotals.get("subtotal")).isEqualTo(5000L);
        assertThat(targetTotals.get("discount")).isEqualTo(300L);
        assertThat(targetTotals.get("tax")).isEqualTo(500L);
        assertThat(targetTotals.get("tip")).isEqualTo(150L);
        assertThat(targetTotals.get("total")).isEqualTo(5350L);
        assertThat(targetTotals.get("status")).isEqualTo("PENDING");

        String sourceStatus = db.sql("SELECT status FROM pos_order WHERE id = :id")
                .param("id", sourceOrder.id()).query(String.class).single();
        assertThat(sourceStatus).isEqualTo("VOIDED");

        // Source KDS ticket was repointed to target order & target table, NOT cancelled, and change_seq bumped
        var repointedTicket = db.sql("SELECT order_id, table_id, status, change_seq FROM restaurant_kds_ticket WHERE id = :id")
                .param("id", sourceTicket.id())
                .query((rs, i) -> Map.of(
                        "orderId", rs.getObject(1, UUID.class),
                        "tableId", rs.getObject(2, UUID.class),
                        "status", rs.getString(3),
                        "changeSeq", rs.getLong(4)
                )).single();
        assertThat(repointedTicket.get("orderId")).isEqualTo(targetOrder.id());
        assertThat(repointedTicket.get("tableId")).isEqualTo(targetTable.id());
        assertThat(repointedTicket.get("status")).isEqualTo("NEW");
        assertThat((Long) repointedTicket.get("changeSeq")).isGreaterThan(changeSeqBefore);

        // Audit log records voidReason = MERGED
        String voidReason = db.sql("""
            SELECT details->>'voidReason' FROM audit_log
            WHERE action = 'restaurant.table.merge' AND subject = :subject
            ORDER BY id DESC LIMIT 1
            """)
            .param("subject", targetTable.id().toString())
            .query(String.class).single();
        assertThat(voidReason).isEqualTo("MERGED");

        // Idempotent retry succeeds without mutating totals
        var retryResp = api.call("POST",
                "/v1/restaurant/tables/" + targetTable.id() + "/merge?source=" + sourceTable.id(),
                branchACashierToken, null, null);
        assertThat(retryResp.status()).isEqualTo(200);
        assertThat(retryResp.body().get("alreadyMerged").asBoolean()).isTrue();
        assertThat(retryResp.body().get("mergedTotalPesewas").asLong()).isEqualTo(5350L);

        // Reject merging a source table whose order has an active checkout link
        var paidSourceTable = tableService.createTable(merchantId, floorPlanId, "MG-3", 4, branchAId);
        var paidSourceOrder = createRestaurantOrder(branchAId, "Fufu", 5000L, 0L, 0L, 0L);
        tableService.seatTable(paidSourceTable.id(), paidSourceOrder.id());
        db.sql("""
            INSERT INTO pos_checkout_link (id, order_id, checkout_ref, amount, status)
            VALUES (:id, :orderId, :ref, 5000, 'open')
            """)
            .param("id", UUID.randomUUID())
            .param("orderId", paidSourceOrder.id())
            .param("ref", "chk-" + UUID.randomUUID())
            .update();

        var blockedMerge = api.call("POST",
                "/v1/restaurant/tables/" + targetTable.id() + "/merge?source=" + paidSourceTable.id(),
                branchACashierToken, null, null);
        assertThat(blockedMerge.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("Concurrent opposite-direction merges and moves complete with zero deadlocks")
    void concurrentOppositeMovesAndMergesNeverDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 1. Concurrent opposite merges: merge(A, B) vs merge(B, A)
            var tA = tableService.createTable(merchantId, floorPlanId, "CC-A", 4, branchAId);
            var tB = tableService.createTable(merchantId, floorPlanId, "CC-B", 4, branchAId);
            var oA = createRestaurantOrder(branchAId, "Dish A", 1500L, 0L, 0L, 0L);
            var oB = createRestaurantOrder(branchAId, "Dish B", 2500L, 0L, 0L, 0L);
            tableService.seatTable(tA.id(), oA.id());
            tableService.seatTable(tB.id(), oB.id());

            CyclicBarrier mergeBarrier = new CyclicBarrier(2);
            Future<RestaurantTableService.MergeTablesResult> f1 = pool.submit(() -> {
                mergeBarrier.await(5, TimeUnit.SECONDS);
                return tableService.mergeTables(tA.id(), tB.id(), "worker-1");
            });
            Future<RestaurantTableService.MergeTablesResult> f2 = pool.submit(() -> {
                mergeBarrier.await(5, TimeUnit.SECONDS);
                return tableService.mergeTables(tB.id(), tA.id(), "worker-2");
            });

            var r1 = f1.get(10, TimeUnit.SECONDS);
            var r2 = f2.get(10, TimeUnit.SECONDS);
            assertThat(r1.alreadyMerged() ^ r2.alreadyMerged())
                    .as("Exactly one concurrent merge executes and the other sees alreadyMerged=true")
                    .isTrue();
            assertThat(r1.targetOrderId()).isEqualTo(r2.targetOrderId());
            assertThat(r1.mergedTotalPesewas()).isEqualTo(4000L);
            assertThat(r2.mergedTotalPesewas()).isEqualTo(4000L);

            // 2. Concurrent opposite moves: move(C -> D) vs move(D -> C)
            var tC = tableService.createTable(merchantId, floorPlanId, "CC-C", 4, branchAId);
            var tD = tableService.createTable(merchantId, floorPlanId, "CC-D", 4, branchAId);
            var oC = createRestaurantOrder(branchAId, "Dish C", 1800L, 0L, 0L, 0L);
            tableService.seatTable(tC.id(), oC.id());

            CyclicBarrier moveBarrier = new CyclicBarrier(2);
            Callable<Boolean> runMoveCtoD = () -> {
                moveBarrier.await(5, TimeUnit.SECONDS);
                try {
                    tableService.moveTable(tC.id(), tD.id(), "worker-1");
                    return true;
                } catch (ApiException expected) {
                    return false;
                }
            };
            Callable<Boolean> runMoveDtoC = () -> {
                moveBarrier.await(5, TimeUnit.SECONDS);
                try {
                    tableService.moveTable(tD.id(), tC.id(), "worker-2");
                    return true;
                } catch (ApiException expected) {
                    return false;
                }
            };
            Future<Boolean> m1 = pool.submit(runMoveCtoD);
            Future<Boolean> m2 = pool.submit(runMoveDtoC);
            boolean ok1 = m1.get(10, TimeUnit.SECONDS);
            boolean ok2 = m2.get(10, TimeUnit.SECONDS);
            assertThat(ok1).isTrue();
            assertThat(ok2).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Branch filtering & adversarial cross-branch / cross-merchant isolation on move, merge, and print-bill")
    void branchFilteringAndAdversarialBranchIsolation() {
        var branchATable1 = tableService.createTable(merchantId, floorPlanId, "BR-A1", 4, branchAId);
        var branchATable2 = tableService.createTable(merchantId, floorPlanId, "BR-A2", 4, branchAId);
        var branchBTable1 = tableService.createTable(merchantId, floorPlanId, "BR-B1", 4, branchBId);
        var branchBTable2 = tableService.createTable(merchantId, floorPlanId, "BR-B2", 4, branchBId);
        var unassignedTable = tableService.createTable(merchantId, floorPlanId, "BR-UNASSIGNED", 4);

        // Branch-filtered listTables never returns other-branch or NULL-branch tables
        var branchAList = tableService.listTables(merchantId, branchAId);
        assertThat(branchAList).extracting(RestaurantTableService.RestaurantTable::id)
                .containsExactlyInAnyOrder(branchATable1.id(), branchATable2.id());
        var branchBList = tableService.listTables(merchantId, branchBId);
        assertThat(branchBList).extracting(RestaurantTableService.RestaurantTable::id)
                .containsExactlyInAnyOrder(branchBTable1.id(), branchBTable2.id());
        var allList = tableService.listTables(merchantId, null);
        assertThat(allList).extracting(RestaurantTableService.RestaurantTable::id)
                .contains(branchATable1.id(), branchATable2.id(), branchBTable1.id(), branchBTable2.id(), unassignedTable.id());

        var orderA = createRestaurantOrder(branchAId, "Branch A Meal", 3500L, 0L, 0L, 0L);
        var orderB = createRestaurantOrder(branchBId, "Branch B Meal", 4000L, 0L, 0L, 0L);
        tableService.seatTable(branchATable1.id(), orderA.id());
        tableService.seatTable(branchBTable1.id(), orderB.id());

        // Adversarial: Branch B cashier CANNOT print-bill, move, or merge Branch A tables (404)
        assertThat(api.call("POST", "/v1/restaurant/tables/" + branchATable1.id() + "/print-bill",
                branchBCashierToken, null, null).status()).isEqualTo(404);
        assertThat(api.call("POST", "/v1/restaurant/tables/" + branchATable1.id() + "/move?to=" + branchATable2.id(),
                branchBCashierToken, null, null).status()).isEqualTo(404);
        assertThat(api.call("POST", "/v1/restaurant/tables/" + branchATable1.id() + "/move?to=" + branchBTable2.id(),
                branchBCashierToken, null, null).status()).isEqualTo(404);
        assertThat(api.call("POST", "/v1/restaurant/tables/" + branchBTable1.id() + "/merge?source=" + branchATable1.id(),
                branchBCashierToken, null, null).status()).isEqualTo(404);
        assertThat(api.call("POST", "/v1/restaurant/tables/" + branchATable1.id() + "/merge?source=" + branchBTable1.id(),
                branchBCashierToken, null, null).status()).isEqualTo(404);

        // Adversarial: Even merchant-wide owner CANNOT move or merge across branches (409 branch_mismatch)
        var crossBranchMove = api.call("POST",
                "/v1/restaurant/tables/" + branchATable1.id() + "/move?to=" + branchBTable2.id(),
                ownerToken, null, null);
        assertThat(crossBranchMove.status()).isEqualTo(409);

        var crossBranchMerge = api.call("POST",
                "/v1/restaurant/tables/" + branchATable1.id() + "/merge?source=" + branchBTable1.id(),
                ownerToken, null, null);
        assertThat(crossBranchMerge.status()).isEqualTo(409);

        // Adversarial: Another merchant's owner CANNOT access Merchant A's tables (404)
        assertThat(api.call("POST", "/v1/restaurant/tables/" + branchATable1.id() + "/print-bill",
                otherMerchantOwnerToken, null, null).status()).isEqualTo(404);
        assertThat(api.call("POST", "/v1/restaurant/tables/" + branchATable1.id() + "/move?to=" + branchATable2.id(),
                otherMerchantOwnerToken, null, null).status()).isEqualTo(404);
        assertThat(api.call("POST", "/v1/restaurant/tables/" + branchATable1.id() + "/merge?source=" + branchBTable1.id(),
                otherMerchantOwnerToken, null, null).status()).isEqualTo(404);

        // Cross-merchant target table rejected (404)
        var otherPlan = tableService.createFloorPlan(otherMerchantId, "Other Hall", "{}");
        var otherMerchantTable = tableService.createTable(otherMerchantId, otherPlan.id(), "OT-1", 4);
        assertThat(api.call("POST", "/v1/restaurant/tables/" + branchATable1.id() + "/move?to=" + otherMerchantTable.id(),
                ownerToken, null, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("Every endpoint in RestaurantTableController checks access.* as its first statement")
    void everyEndpointInRestaurantTableControllerChecksAccessFirst() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/elepay/merchanttools/restaurant/RestaurantTableController.java"));
        Matcher m = Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping[^\\n]*\\n\\s*public[^{]+\\{([^}]*)").matcher(src);
        List<String> unguarded = new ArrayList<>();
        int endpoints = 0;
        while (m.find()) {
            endpoints++;
            String firstLine = m.group(2).strip().split("\\n")[0];
            if (!firstLine.contains("access.")) unguarded.add(firstLine);
        }
        assertThat(endpoints).isEqualTo(3);
        assertThat(unguarded).as("Endpoints whose first statement is not an access check").isEmpty();
    }

    @Test
    @DisplayName("Regression: mergeTables excludes soft-voided items, deletes unpaid OPEN splits, and blocks paid splits")
    void mergeTablesExcludesSoftVoidedItemsAndGuardsSplits() {
        var targetTable = tableService.createTable(merchantId, floorPlanId, "RV-1", 4, branchAId);
        var sourceTable = tableService.createTable(merchantId, floorPlanId, "RV-2", 4, branchAId);

        var targetOrder = posOrderService.createOrder(
                merchantId, branchAId, null, null, null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Active Jollof", BigDecimal.ONE, 3000L, 0L, 0L, "[]", 1, "MAIN", null),
                        new PosOrderService.OrderItemInput(null, "Voided Soup", BigDecimal.ONE, 2000L, 0L, 0L, "[]", 1, "STARTER", null)
                ),
                0L, 0L, 0L, "DINE_IN");
        var sourceOrder = posOrderService.createOrder(
                merchantId, branchAId, null, null, null,
                List.of(
                        new PosOrderService.OrderItemInput(null, "Active Kelewele", BigDecimal.ONE, 1500L, 0L, 0L, "[]", 2, "STARTER", null),
                        new PosOrderService.OrderItemInput(null, "Voided Juice", BigDecimal.ONE, 1000L, 0L, 0L, "[]", 2, "BEVERAGE", null)
                ),
                0L, 0L, 0L, "DINE_IN");

        // Soft-void one item on each order (simulating Phase 3 item void)
        db.sql("UPDATE pos_order_item SET voided = true WHERE order_id = :o AND item_name = 'Voided Soup'")
                .param("o", targetOrder.id()).update();
        db.sql("UPDATE pos_order SET subtotal_pesewas = 3000, total_pesewas = 3000 WHERE id = :o")
                .param("o", targetOrder.id()).update();
        db.sql("UPDATE pos_order_item SET voided = true WHERE order_id = :o AND item_name = 'Voided Juice'")
                .param("o", sourceOrder.id()).update();
        db.sql("UPDATE pos_order SET subtotal_pesewas = 1500, total_pesewas = 1500 WHERE id = :o")
                .param("o", sourceOrder.id()).update();

        // Insert an unpaid OPEN split on targetOrder; merge must clear it cleanly
        db.sql("""
            INSERT INTO pos_order_split (id, order_id, split_type, split_key, label, amount_pesewas, paid_pesewas, status)
            VALUES (:id, :orderId, 'EVEN', 'even:1', 'Split 1', 3000, 0, 'OPEN')
            """)
                .param("id", UUID.randomUUID())
                .param("orderId", targetOrder.id())
                .update();

        tableService.seatTable(targetTable.id(), targetOrder.id());
        tableService.seatTable(sourceTable.id(), sourceOrder.id());

        var merged = tableService.mergeTables(targetTable.id(), sourceTable.id(), "tester");
        assertThat(merged.mergedTotalPesewas()).isEqualTo(4500L);
        long remainingSplits = db.sql("SELECT count(*) FROM pos_order_split WHERE order_id IN (:t, :s)")
                .param("t", targetOrder.id())
                .param("s", sourceOrder.id())
                .query(Long.class)
                .single();
        assertThat(remainingSplits).isZero();

        // Now attach a PAID split to targetOrder and try merging a third table -> must reject with 409
        var thirdTable = tableService.createTable(merchantId, floorPlanId, "RV-3", 4, branchAId);
        var thirdOrder = createRestaurantOrder(branchAId, "Extra Dish", 1000L, 0L, 0L, 0L);
        tableService.seatTable(thirdTable.id(), thirdOrder.id());
        db.sql("""
            INSERT INTO pos_order_split (id, order_id, split_type, split_key, label, amount_pesewas, paid_pesewas, status)
            VALUES (:id, :orderId, 'EVEN', 'even:1', 'Split 1', 2250, 2250, 'PAID')
            """)
                .param("id", UUID.randomUUID())
                .param("orderId", targetOrder.id())
                .update();

        var blocked = api.call("POST",
                "/v1/restaurant/tables/" + targetTable.id() + "/merge?source=" + thirdTable.id(),
                branchACashierToken, null, null);
        assertThat(blocked.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("Regression: PosController.fireKds rejects cross-branch tableId for branch-scoped callers")
    void fireKdsRejectsCrossBranchTableForBranchScopedCaller() {
        var branchATable = tableService.createTable(merchantId, floorPlanId, "FK-A1", 4, branchAId);
        var branchBTable = tableService.createTable(merchantId, floorPlanId, "FK-B1", 4, branchBId);
        var orderA = posOrderService.createOrder(
                merchantId, branchAId, null, null, null,
                List.of(new PosOrderService.OrderItemInput(
                        null, "Grilled Tilapia", BigDecimal.ONE, 4500L, 0L, 0L, "[]", 1, "MAIN", null)),
                0L, 0L, 0L, "DINE_IN");

        // Branch A cashier attempting to fire KDS with Branch B's tableId -> 404
        var crossBranchResp = api.call("POST",
                "/v1/pos/orders/" + orderA.id() + "/fire-kds",
                branchACashierToken,
                null,
                Map.of("tableId", branchBTable.id().toString(), "serverName", "Ama"));
        assertThat(crossBranchResp.status()).isEqualTo(404);

        // Same cashier with Branch A's tableId -> 200
        var sameBranchResp = api.call("POST",
                "/v1/pos/orders/" + orderA.id() + "/fire-kds",
                branchACashierToken,
                null,
                Map.of("tableId", branchATable.id().toString(), "serverName", "Ama"));
        assertThat(sameBranchResp.status()).isEqualTo(200);
    }
}
