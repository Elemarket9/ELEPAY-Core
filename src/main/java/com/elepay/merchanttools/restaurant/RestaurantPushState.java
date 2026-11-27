package com.elepay.merchanttools.restaurant;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Read-only restaurant state API. The notification module never queries restaurant tables directly. */
@Service
public class RestaurantPushState {
    private final JdbcClient db;
    public RestaurantPushState(JdbcClient db) { this.db = db; }

    public boolean kitchenOrderPending(UUID merchant, UUID ticket, UUID station) {
        return db.sql("""
                SELECT EXISTS (SELECT 1 FROM restaurant_kds_ticket t
                    JOIN restaurant_kds_item i ON i.ticket_id = t.id
                    WHERE t.merchant_id = :m AND t.id = :r
                      AND t.status IN ('NEW','PREPARING') AND i.item_status = 'NEW'
                      AND (CAST(:st AS uuid) IS NULL OR i.station_id = CAST(:st AS uuid)))
                """).param("m",merchant).param("r",ticket).param("st",station).query(Boolean.class).single();
    }

    public boolean readyForWaiter(UUID merchant, UUID ticket, String subject, UUID branch) {
        return db.sql("""
                SELECT EXISTS (SELECT 1 FROM restaurant_kds_ticket t
                    JOIN restaurant_waiter_order w ON w.order_id = t.order_id AND w.merchant_id = t.merchant_id
                    JOIN pos_order o ON o.id = w.order_id AND o.merchant_id = t.merchant_id
                    WHERE t.merchant_id = :m AND t.id = :r AND t.status = 'READY'
                      AND w.waiter_subject = :s AND o.branch_id = CAST(:b AS uuid))
                """).param("m",merchant).param("r",ticket).param("s",subject).param("b",branch)
                .query(Boolean.class).single();
    }

    public boolean billOutstanding(UUID merchant, UUID order, UUID branch) {
        return db.sql("""
                SELECT EXISTS (SELECT 1 FROM pos_order o
                    JOIN restaurant_waiter_order w ON w.order_id = o.id AND w.merchant_id = o.merchant_id
                    WHERE o.merchant_id = :m AND o.id = :r AND w.bill_requested_at IS NOT NULL
                      AND o.paid_pesewas < o.total_pesewas AND o.status NOT IN ('PAID','CANCELLED','VOIDED')
                      AND (CAST(:b AS uuid) IS NULL OR o.branch_id = CAST(:b AS uuid)))
                """).param("m",merchant).param("r",order).param("b",branch).query(Boolean.class).single();
    }
}
