package com.elepay.restaurant;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KdsService {
    private final JdbcClient db;

    public KdsService(JdbcClient db) {
        this.db = db;
    }

    public record KdsTicket(
            UUID id,
            UUID merchantId,
            UUID orderId,
            UUID tableId,
            String diningMode,
            String serverName,
            String status,
            String course,
            String notes,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime readyAt,
            OffsetDateTime servedAt
    ) {
        public long elapsedSeconds() {
            OffsetDateTime end = servedAt != null ? servedAt : OffsetDateTime.now();
            return Duration.between(createdAt, end).getSeconds();
        }

        public String urgencyLevel() {
            if ("SERVED".equals(status) || "CANCELLED".equals(status)) {
                return "COMPLETED";
            }
            long minutes = elapsedSeconds() / 60;
            if (minutes >= 25) return "CRITICAL";
            if (minutes >= 15) return "WARNING";
            return "NORMAL";
        }
    }

    public record KdsItem(
            UUID id,
            UUID ticketId,
            UUID orderItemId,
            String itemName,
            BigDecimal quantity,
            String modifiers,
            String course,
            String itemStatus
    ) {}

    public record KdsItemInput(
            UUID orderItemId,
            String itemName,
            BigDecimal quantity,
            String modifiers,
            String course
    ) {}

    @Transactional
    public KdsTicket createTicket(
            UUID merchantId,
            UUID orderId,
            UUID tableId,
            DiningMode diningMode,
            String serverName,
            String course,
            List<KdsItemInput> items,
            String notes
    ) {
        UUID ticketId = UUID.randomUUID();
        db.sql("""
            INSERT INTO restaurant_kds_ticket (
                id, merchant_id, order_id, table_id, dining_mode, server_name,
                status, course, notes, created_at
            ) VALUES (
                :id, :merchantId, :orderId, :tableId, :diningMode, :serverName,
                'NEW', :course, :notes, now()
            )
            """)
            .param("id", ticketId)
            .param("merchantId", merchantId)
            .param("orderId", orderId)
            .param("tableId", tableId)
            .param("diningMode", diningMode.name())
            .param("serverName", serverName)
            .param("course", course == null ? "ALL" : course)
            .param("notes", notes)
            .update();

        for (KdsItemInput item : items) {
            UUID itemId = UUID.randomUUID();
            db.sql("""
                INSERT INTO restaurant_kds_item (
                    id, ticket_id, order_item_id, item_name, quantity, modifiers, course, item_status
                ) VALUES (
                    :id, :ticketId, :orderItemId, :itemName, :quantity, :modifiers, :course, 'NEW'
                )
                """)
                .param("id", itemId)
                .param("ticketId", ticketId)
                .param("orderItemId", item.orderItemId())
                .param("itemName", item.itemName())
                .param("quantity", item.quantity())
                .param("modifiers", item.modifiers() == null ? "" : item.modifiers())
                .param("course", item.course() == null ? "MAIN" : item.course())
                .update();
        }

        return getTicket(ticketId).orElseThrow();
    }

    public Optional<KdsTicket> getTicket(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, order_id, table_id, dining_mode, server_name,
                   status, course, notes, created_at, started_at, ready_at, served_at
            FROM restaurant_kds_ticket WHERE id = :id
            """)
            .param("id", id)
            .query(KdsTicket.class)
            .optional();
    }

    public List<KdsItem> listTicketItems(UUID ticketId) {
        return db.sql("""
            SELECT id, ticket_id, order_item_id, item_name, quantity, modifiers, course, item_status
            FROM restaurant_kds_item WHERE ticket_id = :ticketId
            """)
            .param("ticketId", ticketId)
            .query(KdsItem.class)
            .list();
    }

    public List<KdsTicket> getActiveTickets(UUID merchantId) {
        return db.sql("""
            SELECT id, merchant_id, order_id, table_id, dining_mode, server_name,
                   status, course, notes, created_at, started_at, ready_at, served_at
            FROM restaurant_kds_ticket
            WHERE merchant_id = :merchantId AND status IN ('NEW', 'PREPARING', 'READY')
            ORDER BY created_at ASC
            """)
            .param("merchantId", merchantId)
            .query(KdsTicket.class)
            .list();
    }

    // Kitchen bump bar state machine advancement
    @Transactional
    public KdsTicket bumpTicket(UUID ticketId) {
        KdsTicket ticket = getTicket(ticketId).orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));

        String currentStatus = ticket.status();
        String nextStatus;
        String updateTimeCol;

        switch (currentStatus) {
            case "NEW" -> {
                nextStatus = "PREPARING";
                updateTimeCol = "started_at = now()";
            }
            case "PREPARING" -> {
                nextStatus = "READY";
                updateTimeCol = "ready_at = now()";
            }
            case "READY" -> {
                nextStatus = "SERVED";
                updateTimeCol = "served_at = now()";
            }
            default -> throw new IllegalStateException("Ticket cannot be bumped from status: " + currentStatus);
        }

        db.sql("UPDATE restaurant_kds_ticket SET status = :status, " + updateTimeCol + " WHERE id = :id")
                .param("status", nextStatus)
                .param("id", ticketId)
                .update();

        // Advance all items if not yet at that stage
        db.sql("UPDATE restaurant_kds_item SET item_status = :status WHERE ticket_id = :id")
                .param("status", nextStatus)
                .param("id", ticketId)
                .update();

        return getTicket(ticketId).orElseThrow();
    }

    @Transactional
    public void bumpItem(UUID itemId) {
        var currentOpt = db.sql("SELECT item_status FROM restaurant_kds_item WHERE id = :id")
                .param("id", itemId)
                .query(String.class)
                .optional();

        if (currentOpt.isEmpty()) return;
        String cur = currentOpt.get();
        String next = switch (cur) {
            case "NEW" -> "PREPARING";
            case "PREPARING" -> "READY";
            case "READY" -> "SERVED";
            default -> cur;
        };

        db.sql("UPDATE restaurant_kds_item SET item_status = :status WHERE id = :id")
                .param("status", next)
                .param("id", itemId)
                .update();
    }

    // Course firing: fire a specific course (e.g. fire APPETIZER or fire MAIN)
    @Transactional
    public void fireCourse(UUID orderId, Course course) {
        db.sql("""
            UPDATE restaurant_kds_ticket
            SET status = 'PREPARING', started_at = now()
            WHERE order_id = :orderId AND course = :course AND status = 'NEW'
            """)
            .param("orderId", orderId)
            .param("course", course.name())
            .update();

        db.sql("""
            UPDATE restaurant_kds_item
            SET item_status = 'PREPARING'
            WHERE ticket_id IN (SELECT id FROM restaurant_kds_ticket WHERE order_id = :orderId)
              AND course = :course AND item_status = 'NEW'
            """)
            .param("orderId", orderId)
            .param("course", course.name())
            .update();
    }
}
