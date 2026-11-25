package com.elepay.merchanttools.restaurant;

import com.elepay.common.OpsQueue;
import com.elepay.merchanttools.pos.PosOrderService;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * When the kitchen cancels a waiter order (for example "out of jollof"), the guests must not be charged for food
 * that will never arrive and the table must not stay locked. Runs in the cancelling transaction.
 *
 * <ul>
 *   <li>Every kitchen ticket of the order cancelled and nothing paid: the order is voided and the table freed,
 *       so the waiter can take a new order for the same guests.</li>
 *   <li>Something already paid (or a digital payment applied): the order is left alone and a high-priority ops
 *       item asks for a refund decision. Money is never moved automatically here.</li>
 *   <li>Other tickets of the order still active: nothing changes yet.</li>
 * </ul>
 */
@Component
public class WaiterOrderCancellation {
    private static final Logger log = LoggerFactory.getLogger(WaiterOrderCancellation.class);

    private final JdbcClient db;
    private final PosOrderService orders;
    private final OpsQueue ops;

    public WaiterOrderCancellation(JdbcClient db, PosOrderService orders, OpsQueue ops) {
        this.db = db;
        this.orders = orders;
        this.ops = ops;
    }

    @EventListener
    public void onTicketCancelled(KdsService.TicketCancelled event) {
        UUID tableId = db.sql("SELECT table_id FROM restaurant_waiter_order WHERE order_id = :o AND merchant_id = :m")
                .param("o", event.orderId()).param("m", event.merchantId()).query(UUID.class).optional().orElse(null);
        if (tableId == null) return; // not a waiter order
        long stillActive = db.sql("""
                SELECT count(*) FROM restaurant_kds_ticket WHERE order_id = :o AND status <> 'CANCELLED'
                """).param("o", event.orderId()).query(Long.class).single();
        if (stillActive > 0) return;

        var order = orders.getOrder(event.orderId()).orElse(null);
        if (order == null) return;
        // Checked up front: a refused void inside this transaction would roll back the kitchen's cancel too.
        if (orders.voidable(order.id())) {
            orders.voidOrder(order.id(), "kitchen-cancel");
            db.sql("""
                    UPDATE restaurant_table SET status = 'AVAILABLE', current_order_id = NULL
                    WHERE id = :t AND current_order_id = :o
                    """).param("t", tableId).param("o", order.id()).update();
            log.info("Voided kitchen-cancelled waiter order {} and freed its table", order.id());
            return;
        }
        if (!"VOIDED".equals(order.status())) {
            ops.raise("restaurant_cancelled_order_payment", "high", "pos_order:" + order.id(), Map.of(
                    "merchantId", event.merchantId(),
                    "orderNumber", order.orderNumber(),
                    "status", order.status(),
                    "paidPesewas", order.paidPesewas(),
                    "totalPesewas", order.totalPesewas(),
                    "reason", event.reason() == null ? "" : event.reason()));
        }
    }
}
