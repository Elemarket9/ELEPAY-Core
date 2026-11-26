package com.elepay.merchanttools.restaurant;

import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kitchen Display System. Built for kitchens where the internet drops and power cuts reset routers:
 * <ul>
 *   <li>Tickets carry a device-generated {@code clientRef}, so a resent order never creates a second ticket.</li>
 *   <li>Bumps say which status they expect to move from and carry an {@code actionId}; replayed or
 *       conflicting bumps from several screens are applied at most once and never skip a stage.</li>
 *   <li>Every change moves the ticket forward in a change feed ({@link #sync}) that screens catch up from.</li>
 *   <li>Items are routed to stations; stations with printers (or with a dead screen) get printed tickets.</li>
 * </ul>
 */
@Service
public class KdsService {
    static final List<String> FLOW = List.of("NEW", "PREPARING", "READY", "SERVED");
    /** Rows committed slightly out of sequence order are re-sent within this window; sync is idempotent. */
    static final int SYNC_OVERLAP_SECONDS = 20;
    static final int SYNC_PAGE = 300;

    private final JdbcClient db;
    private final KitchenStationService stations;
    private final KdsPrintService printing;

    public KdsService(JdbcClient db, KitchenStationService stations, KdsPrintService printing) {
        this.db = db;
        this.stations = stations;
        this.printing = printing;
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
            OffsetDateTime servedAt,
            Integer ticketNumber,
            String clientRef,
            OffsetDateTime cancelledAt,
            long changeSeq
    ) {
        public long elapsedSeconds() {
            OffsetDateTime end = servedAt != null ? servedAt : cancelledAt != null ? cancelledAt : OffsetDateTime.now();
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
            String itemStatus,
            UUID stationId
    ) {}

    public record KdsItemInput(
            UUID orderItemId,
            String itemName,
            BigDecimal quantity,
            String modifiers,
            String course
    ) {}

    /** A ticket with its items, as a kitchen screen shows it. */
    public record TicketView(KdsTicket ticket, List<KdsItem> items) {}

    /**
     * Result of a bump. {@code applied=false} is not an error: the screen simply adopts {@code ticket}.
     * Reasons: ALREADY_APPLIED (the same action arrived twice), STATUS_CHANGED (another screen moved it first),
     * NOTHING_TO_ADVANCE (no items of that station were at that stage).
     */
    public record BumpResult(TicketView view, boolean applied, String reason) {}

    public record SyncResult(long cursor, boolean hasMore, OffsetDateTime serverTime, List<TicketView> tickets,
                             List<KdsPrintService.PrintJob> printerFailures) {}

    private static final String TICKET_COLS = """
            id, merchant_id, order_id, table_id, dining_mode, server_name, status, course, notes,
            created_at, started_at, ready_at, served_at, ticket_number, client_ref, cancelled_at, change_seq""";

    private static final String TICKET_COLS_T = """
            t.id, t.merchant_id, t.order_id, t.table_id, t.dining_mode, t.server_name, t.status, t.course, t.notes,
            t.created_at, t.started_at, t.ready_at, t.served_at, t.ticket_number, t.client_ref, t.cancelled_at,
            t.change_seq""";

    private static final String ITEM_COLS = """
            id, ticket_id, order_item_id, item_name, quantity, modifiers, course, item_status, station_id""";

    // ------------------------------------------------------------------ creating tickets

    @Transactional
    public KdsTicket createTicket(UUID merchantId, UUID orderId, UUID tableId, DiningMode diningMode, String serverName,
                                  String course, List<KdsItemInput> items, String notes) {
        return createTicket(merchantId, orderId, tableId, diningMode, serverName, course, items, notes, null);
    }

    /**
     * Creates a kitchen ticket. With a {@code clientRef} (generated by the ordering device and reused on every
     * retry) the same order sent twice returns the first ticket instead of making the kitchen cook it twice.
     */
    @Transactional
    public KdsTicket createTicket(UUID merchantId, UUID orderId, UUID tableId, DiningMode diningMode, String serverName,
                                  String course, List<KdsItemInput> items, String notes, String clientRef) {
        if (items == null || items.isEmpty())
            throw ApiException.badRequest("items_required", "A kitchen ticket needs at least one item");
        if (clientRef != null && (clientRef.isBlank() || clientRef.length() > 64))
            throw ApiException.badRequest("client_ref_invalid", "clientRef must be 1-64 characters");
        for (KdsItemInput item : items) {
            if (item == null || item.itemName() == null || item.itemName().isBlank())
                throw ApiException.badRequest("item_name_required", "Every kitchen item needs a name");
            if (item.quantity() != null && item.quantity().signum() <= 0)
                throw ApiException.badRequest("item_quantity_invalid", "Item quantity must be positive");
            if (item.orderItemId() != null) {
                UUID itemOrderId = db.sql("SELECT order_id FROM pos_order_item WHERE id = :id")
                        .param("id", item.orderItemId())
                        .query(UUID.class)
                        .optional()
                        .orElseThrow(() -> ApiException.badRequest("invalid_order_item", "Order item not found"));
                if (orderId == null || !orderId.equals(itemOrderId)) {
                    throw ApiException.badRequest("order_item_mismatch", "Order item does not belong to the specified order");
                }
            }
        }
        if (clientRef != null) {
            Optional<KdsTicket> existing = findByClientRef(merchantId, clientRef);
            if (existing.isPresent()) return existing.get();
        }
        UUID ticketId = UUID.randomUUID();
        int inserted = db.sql("""
            INSERT INTO restaurant_kds_ticket (
                id, merchant_id, order_id, table_id, dining_mode, server_name,
                status, course, notes, created_at, client_ref
            ) VALUES (
                :id, :merchantId, :orderId, :tableId, :diningMode, :serverName,
                'NEW', :course, :notes, now(), :clientRef
            )
            ON CONFLICT (merchant_id, client_ref) WHERE client_ref IS NOT NULL DO NOTHING
            """)
            .param("id", ticketId)
            .param("merchantId", merchantId)
            .param("orderId", orderId)
            .param("tableId", tableId)
            .param("diningMode", (diningMode == null ? DiningMode.DINE_IN : diningMode).name())
            .param("serverName", serverName)
            .param("course", course == null ? "ALL" : course)
            .param("notes", notes)
            .param("clientRef", clientRef)
            .update();
        if (inserted == 0) return findByClientRef(merchantId, clientRef).orElseThrow();

        Integer number = db.sql("""
            INSERT INTO restaurant_kds_counter (merchant_id, business_day, last_number)
            VALUES (:m, (now() AT TIME ZONE 'Africa/Accra')::date, 1)
            ON CONFLICT (merchant_id, business_day)
            DO UPDATE SET last_number = restaurant_kds_counter.last_number + 1
            RETURNING last_number
            """).param("m", merchantId).query(Integer.class).single();
        db.sql("UPDATE restaurant_kds_ticket SET ticket_number = :n WHERE id = :id")
                .param("n", number).param("id", ticketId).update();

        for (KdsItemInput item : items) {
            UUID variant = item.orderItemId() == null ? null : db.sql(
                    "SELECT variant_id FROM pos_order_item WHERE id = :id")
                    .param("id", item.orderItemId()).query(UUID.class).optional().orElse(null);
            db.sql("""
                INSERT INTO restaurant_kds_item (
                    id, ticket_id, order_item_id, item_name, quantity, modifiers, course, item_status, station_id
                ) VALUES (
                    :id, :ticketId, :orderItemId, :itemName, :quantity, :modifiers, :course, 'NEW', :station
                )
                """)
                .param("id", UUID.randomUUID())
                .param("ticketId", ticketId)
                .param("orderItemId", item.orderItemId())
                .param("itemName", item.itemName().strip())
                .param("quantity", item.quantity() == null ? BigDecimal.ONE : item.quantity())
                .param("modifiers", item.modifiers() == null ? "" : item.modifiers())
                .param("course", item.course() == null ? "MAIN" : item.course())
                .param("station", stations.resolve(merchantId, variant))
                .update();
        }
        printing.enqueueForNewTicket(ticketId);
        return getTicket(ticketId).orElseThrow();
    }

    private Optional<KdsTicket> findByClientRef(UUID merchantId, String clientRef) {
        return db.sql("SELECT " + TICKET_COLS + " FROM restaurant_kds_ticket WHERE merchant_id = :m AND client_ref = :ref")
                .param("m", merchantId).param("ref", clientRef).query(KdsTicket.class).optional();
    }

    // ------------------------------------------------------------------ reading

    public Optional<KdsTicket> getTicket(UUID id) {
        return db.sql("SELECT " + TICKET_COLS + " FROM restaurant_kds_ticket WHERE id = :id")
            .param("id", id)
            .query(KdsTicket.class)
            .optional();
    }

    public List<KdsItem> listTicketItems(UUID ticketId) {
        return db.sql("SELECT " + ITEM_COLS + " FROM restaurant_kds_item WHERE ticket_id = :ticketId ORDER BY item_name, id")
            .param("ticketId", ticketId)
            .query(KdsItem.class)
            .list();
    }

    public TicketView view(UUID ticketId) {
        return new TicketView(getTicket(ticketId).orElseThrow(() -> ApiException.notFound("Ticket not found")),
                listTicketItems(ticketId));
    }

    public List<KdsTicket> getActiveTickets(UUID merchantId) {
        return getActiveTickets(merchantId, null);
    }

    public List<KdsTicket> getActiveTickets(UUID merchantId, UUID branchId) {
        if (branchId == null) {
            return db.sql("SELECT " + TICKET_COLS + """
                 FROM restaurant_kds_ticket
                 WHERE merchant_id = :merchantId AND status IN ('NEW', 'PREPARING', 'READY')
                 ORDER BY created_at ASC
                """)
                .param("merchantId", merchantId)
                .query(KdsTicket.class)
                .list();
        }
        return db.sql("SELECT " + TICKET_COLS_T + """
             FROM restaurant_kds_ticket t
             JOIN pos_order o ON o.id = t.order_id
             WHERE t.merchant_id = :merchantId AND o.branch_id = :branchId
               AND t.status IN ('NEW', 'PREPARING', 'READY')
             ORDER BY t.created_at ASC
            """)
            .param("merchantId", merchantId)
            .param("branchId", branchId)
            .query(KdsTicket.class)
            .list();
    }

    /**
     * Change feed for kitchen screens. {@code since=0} returns all open tickets; afterwards the screen sends the
     * returned cursor and receives every ticket changed since (including served and cancelled ones, so it can
     * remove them). Results are full snapshots, so receiving a ticket twice is harmless.
     */
    @Transactional
    public SyncResult sync(UUID merchantId, long since, UUID stationId) {
        return sync(merchantId, since, stationId, null);
    }

    @Transactional
    public SyncResult sync(UUID merchantId, long since, UUID stationId, UUID branchId) {
        if (since < 0) throw ApiException.badRequest("cursor_invalid", "Cursor must not be negative");
        if (stationId != null) stations.heartbeat(stationId);
        String stationFilter = stationId == null ? "" : """
             AND EXISTS (SELECT 1 FROM restaurant_kds_item i
                         WHERE i.ticket_id = t.id AND (i.station_id = :station OR i.station_id IS NULL))
            """;
        String branchJoin = branchId == null ? "" : " JOIN pos_order o ON o.id = t.order_id";
        String branchFilter = branchId == null ? "" : " AND o.branch_id = :branchId";
        String window = since == 0
                ? " AND t.status IN ('NEW', 'PREPARING', 'READY')"
                : " AND (t.change_seq > :since OR t.updated_at > now() - make_interval(secs => :overlap))";
        var query = db.sql("SELECT " + TICKET_COLS_T
                        + " FROM restaurant_kds_ticket t" + branchJoin
                        + " WHERE t.merchant_id = :m" + branchFilter + window + stationFilter
                        + " ORDER BY t.change_seq LIMIT " + (SYNC_PAGE + 1))
                .param("m", merchantId);
        if (branchId != null) query = query.param("branchId", branchId);
        if (since != 0) query = query.param("since", since).param("overlap", SYNC_OVERLAP_SECONDS);
        if (stationId != null) query = query.param("station", stationId);
        List<KdsTicket> tickets = new ArrayList<>(query.query(KdsTicket.class).list());
        boolean hasMore = tickets.size() > SYNC_PAGE;
        if (hasMore) tickets = new ArrayList<>(tickets.subList(0, SYNC_PAGE));

        Map<UUID, List<KdsItem>> items = new LinkedHashMap<>();
        for (KdsTicket t : tickets) items.put(t.id(), new ArrayList<>());
        if (!tickets.isEmpty()) {
            var itemQuery = db.sql("SELECT " + ITEM_COLS + " FROM restaurant_kds_item WHERE ticket_id IN (:ids)"
                            + (stationId == null ? "" : " AND (station_id = :station OR station_id IS NULL)")
                            + " ORDER BY item_name, id")
                    .param("ids", List.copyOf(items.keySet()));
            if (stationId != null) itemQuery = itemQuery.param("station", stationId);
            for (KdsItem item : itemQuery.query(KdsItem.class).list()) items.get(item.ticketId()).add(item);
        }
        long cursor = tickets.stream().mapToLong(KdsTicket::changeSeq).max().orElse(since);
        if (since == 0 && tickets.isEmpty())
            cursor = db.sql("SELECT COALESCE(max(change_seq), 0) FROM restaurant_kds_ticket WHERE merchant_id = :m")
                    .param("m", merchantId).query(Long.class).single();
        cursor = Math.max(cursor, since);
        List<TicketView> views = tickets.stream().map(t -> new TicketView(t, items.get(t.id()))).toList();
        return new SyncResult(cursor, hasMore, OffsetDateTime.now(), views, printing.recentFailures(merchantId));
    }

    // ------------------------------------------------------------------ bumping

    /** Legacy bump bar: advance one stage from whatever the ticket is at now. Safe under concurrency. */
    @Transactional
    public KdsTicket bumpTicket(UUID ticketId) {
        String current = lockStatus(ticketId);
        if (!FLOW.contains(current) || "SERVED".equals(current))
            throw new IllegalStateException("Ticket cannot be bumped from status: " + current);
        return advanceTicket(ticketId, current, null).view().ticket();
    }

    /**
     * Expo / whole-ticket bump from {@code from} to the next stage. Items behind that stage come along.
     * If another screen already moved the ticket, nothing changes and the current ticket is returned.
     */
    @Transactional
    public BumpResult advanceTicket(UUID ticketId, String from, String actionId) {
        String next = next(from);
        KdsTicket ticket = getTicket(ticketId).orElseThrow(() -> ApiException.notFound("Ticket not found"));
        if (!recordAction(ticket.merchantId(), actionId, ticketId, "ADVANCE_TICKET"))
            return new BumpResult(view(ticketId), false, "ALREADY_APPLIED");
        int moved = db.sql("UPDATE restaurant_kds_ticket SET status = :next, " + stamp(next)
                        + " WHERE id = :id AND status = :from")
                .param("next", next).param("id", ticketId).param("from", from).update();
        if (moved == 0) {
            forgetAction(ticket.merchantId(), actionId);
            return new BumpResult(view(ticketId), false, "STATUS_CHANGED");
        }
        db.sql("UPDATE restaurant_kds_item SET item_status = :next WHERE ticket_id = :id AND item_status IN (:behind)")
                .param("next", next).param("id", ticketId).param("behind", FLOW.subList(0, FLOW.indexOf(next)))
                .update();
        return new BumpResult(view(ticketId), true, null);
    }

    /**
     * Station bump: this station's items on the ticket move from {@code from} to the next stage. The ticket
     * becomes READY only when every station's items are ready.
     */
    @Transactional
    public BumpResult advanceStation(UUID ticketId, UUID stationId, String from, String actionId) {
        String next = next(from);
        KdsTicket ticket = getTicket(ticketId).orElseThrow(() -> ApiException.notFound("Ticket not found"));
        lockStatus(ticketId);
        if (!recordAction(ticket.merchantId(), actionId, ticketId, "ADVANCE_STATION"))
            return new BumpResult(view(ticketId), false, "ALREADY_APPLIED");
        int moved = db.sql("""
            UPDATE restaurant_kds_item SET item_status = :next
            WHERE ticket_id = :id AND item_status = :from AND (station_id = :station OR station_id IS NULL)
            """).param("next", next).param("id", ticketId).param("from", from).param("station", stationId).update();
        if (moved == 0) {
            forgetAction(ticket.merchantId(), actionId);
            return new BumpResult(view(ticketId), false, "NOTHING_TO_ADVANCE");
        }
        deriveTicketStatus(ticketId);
        return new BumpResult(view(ticketId), true, null);
    }

    /** Legacy single-item bump. Safe under concurrency; the ticket follows its items. */
    @Transactional
    public void bumpItem(UUID itemId) {
        Optional<Map.Entry<UUID, String>> row = db.sql(
                "SELECT ticket_id, item_status FROM restaurant_kds_item WHERE id = :id FOR UPDATE")
                .param("id", itemId)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)))
                .optional();
        if (row.isEmpty()) return;
        String cur = row.get().getValue();
        if (!FLOW.contains(cur) || "SERVED".equals(cur)) return;
        lockStatus(row.get().getKey());
        db.sql("UPDATE restaurant_kds_item SET item_status = :status WHERE id = :id")
                .param("status", next(cur)).param("id", itemId).update();
        deriveTicketStatus(row.get().getKey());
    }

    /** Cancel an order in the kitchen. Printed stations get a VOID slip so nobody keeps cooking it. */
    @Transactional
    public BumpResult cancel(UUID ticketId, String actionId, String reason) {
        KdsTicket ticket = getTicket(ticketId).orElseThrow(() -> ApiException.notFound("Ticket not found"));
        if (!recordAction(ticket.merchantId(), actionId, ticketId, "CANCEL"))
            return new BumpResult(view(ticketId), false, "ALREADY_APPLIED");
        int moved = db.sql("""
            UPDATE restaurant_kds_ticket
            SET status = 'CANCELLED', cancelled_at = now(),
                notes = CASE WHEN CAST(:reason AS text) IS NULL OR CAST(:reason AS text) = '' THEN notes
                             ELSE concat_ws(' | ', NULLIF(notes, ''), 'CANCELLED: ' || CAST(:reason AS text)) END
            WHERE id = :id AND status IN ('NEW', 'PREPARING', 'READY')
            """).param("id", ticketId).param("reason", reason == null ? null : reason.strip()).update();
        if (moved == 0) {
            forgetAction(ticket.merchantId(), actionId);
            return new BumpResult(view(ticketId), false, "STATUS_CHANGED");
        }
        db.sql("UPDATE restaurant_kds_item SET item_status = 'CANCELLED' WHERE ticket_id = :id AND item_status <> 'SERVED'")
                .param("id", ticketId).update();
        printing.enqueueVoid(ticketId);
        return new BumpResult(view(ticketId), true, null);
    }

    // Course firing: fire a specific course (e.g. fire APPETIZER or fire MAIN)
    @Transactional
    public void fireCourse(UUID orderId, Course course) {
        db.sql("""
            UPDATE restaurant_kds_ticket
            SET status = 'PREPARING', started_at = COALESCE(started_at, now())
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

    // ------------------------------------------------------------------ helpers

    private String lockStatus(UUID ticketId) {
        return db.sql("SELECT status FROM restaurant_kds_ticket WHERE id = :id FOR UPDATE")
                .param("id", ticketId).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));
    }

    /** Ticket status follows its slowest live item, and only ever moves forward. Caller holds the ticket lock. */
    private void deriveTicketStatus(UUID ticketId) {
        List<String> statuses = db.sql(
                "SELECT item_status FROM restaurant_kds_item WHERE ticket_id = :id AND item_status <> 'CANCELLED'")
                .param("id", ticketId).query(String.class).list();
        if (statuses.isEmpty()) return;
        int slowest = statuses.stream().mapToInt(FLOW::indexOf).min().orElse(0);
        boolean anyStarted = statuses.stream().anyMatch(s -> FLOW.indexOf(s) >= 1);
        String target = FLOW.get(Math.max(slowest, anyStarted ? 1 : 0));
        String current = db.sql("SELECT status FROM restaurant_kds_ticket WHERE id = :id")
                .param("id", ticketId).query(String.class).single();
        if (!FLOW.contains(current) || FLOW.indexOf(target) <= FLOW.indexOf(current)) return;
        StringBuilder stamps = new StringBuilder();
        for (int i = FLOW.indexOf(current) + 1; i <= FLOW.indexOf(target); i++)
            stamps.append(", ").append(stamp(FLOW.get(i)));
        db.sql("UPDATE restaurant_kds_ticket SET status = :s" + stamps + " WHERE id = :id")
                .param("s", target).param("id", ticketId).update();
    }

    private static String next(String from) {
        int i = from == null ? -1 : FLOW.indexOf(from);
        if (i < 0 || i == FLOW.size() - 1)
            throw ApiException.badRequest("status_not_advanceable", "Cannot advance from status " + from);
        return FLOW.get(i + 1);
    }

    private static String stamp(String status) {
        return switch (status) {
            case "PREPARING" -> "started_at = COALESCE(started_at, now())";
            case "READY" -> "ready_at = COALESCE(ready_at, now())";
            case "SERVED" -> "served_at = COALESCE(served_at, now())";
            default -> throw new IllegalArgumentException(status);
        };
    }

    /** True if this action is new. Without an action id every call counts as new. */
    private boolean recordAction(UUID merchantId, String actionId, UUID ticketId, String action) {
        if (actionId == null) return true;
        if (actionId.isBlank() || actionId.length() > 64)
            throw ApiException.badRequest("action_id_invalid", "actionId must be 1-64 characters");
        return db.sql("""
            INSERT INTO restaurant_kds_action (merchant_id, action_id, ticket_id, action)
            VALUES (:m, :a, :t, :action) ON CONFLICT DO NOTHING
            """).param("m", merchantId).param("a", actionId).param("t", ticketId).param("action", action)
                .update() == 1;
    }

    /** An action that changed nothing is forgotten, so it can be retried once the screen has caught up. */
    private void forgetAction(UUID merchantId, String actionId) {
        if (actionId == null) return;
        db.sql("DELETE FROM restaurant_kds_action WHERE merchant_id = :m AND action_id = :a")
                .param("m", merchantId).param("a", actionId).update();
    }
}
