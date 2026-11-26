package com.elepay.merchanttools.restaurant;

import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kitchen printer jobs. A device on the restaurant's network (the kitchen tablet or the cashier POS)
 * claims jobs, prints them over LAN/Bluetooth/USB and reports back. A job claimed by a device that then
 * goes silent is released for another device after {@link #CLAIM_TIMEOUT_SECONDS}.
 */
@Service
public class KdsPrintService {
    static final int CLAIM_TIMEOUT_SECONDS = 60;
    static final int MAX_ATTEMPTS = 5;

    private final JdbcClient db;

    public KdsPrintService(JdbcClient db) {
        this.db = db;
    }

    public record PrintJob(UUID id, UUID merchantId, UUID stationId, String stationName, String printerAddress,
                           int paperWidth, UUID ticketId, String kind, String content, String status, int attempts,
                           String lastError, OffsetDateTime createdAt) {}

    private static final String JOB = """
            SELECT j.id, j.merchant_id, j.station_id, s.name AS station_name, s.printer_address, s.paper_width,
                   j.ticket_id, j.kind, j.content, j.status, j.attempts, j.last_error, j.created_at
            FROM restaurant_kds_print_job j JOIN restaurant_kitchen_station s ON s.id = j.station_id
            """;

    private record TicketHead(UUID merchantId, Integer ticketNumber, String diningMode, String tableNumber,
                              String serverName, OffsetDateTime createdAt, String notes) {}

    private record ItemRow(UUID stationId, BigDecimal quantity, String itemName, String modifiers, String course) {}

    /** Print jobs for a newly created ticket: printing stations always, screen stations whose screen is down. */
    @Transactional
    public void enqueueForNewTicket(UUID ticketId) {
        List<UUID> stations = db.sql("""
            SELECT DISTINCT s.id
            FROM restaurant_kds_item i JOIN restaurant_kitchen_station s ON s.id = i.station_id
            WHERE i.ticket_id = :t AND s.active AND s.printer_address IS NOT NULL
            """).param("t", ticketId).query(UUID.class).list();
        for (UUID station : stations) {
            String output = db.sql("SELECT output FROM restaurant_kitchen_station WHERE id = :s")
                    .param("s", station).query(String.class).single();
            if (!"SCREEN".equals(output)) {
                enqueue(ticketId, station, "NEW");
            } else if (screenDown(station)) {
                enqueue(ticketId, station, "FALLBACK");
            }
        }
    }

    /** A cancelled ticket prints VOID wherever it was printed, so the kitchen does not cook it. */
    @Transactional
    public void enqueueVoid(UUID ticketId) {
        List<UUID> stations = db.sql("""
            SELECT DISTINCT station_id FROM restaurant_kds_print_job
            WHERE ticket_id = :t AND kind IN ('NEW', 'REPRINT', 'FALLBACK')
            """).param("t", ticketId).query(UUID.class).list();
        for (UUID station : stations) enqueue(ticketId, station, "VOID");
    }

    @Transactional
    public PrintJob reprint(UUID ticketId, UUID stationId) {
        String printer = db.sql("SELECT printer_address FROM restaurant_kitchen_station WHERE id = :s AND active")
                .param("s", stationId).query(String.class).optional().orElse(null);
        if (printer == null)
            throw ApiException.unprocessable("station_has_no_printer", "This station has no printer set up");
        return get(enqueue(ticketId, stationId, "REPRINT")).orElseThrow();
    }

    private boolean screenDown(UUID stationId) {
        return db.sql("""
            SELECT last_seen_at IS NULL OR last_seen_at < now() - make_interval(secs => :stale)
            FROM restaurant_kitchen_station WHERE id = :s
            """).param("s", stationId).param("stale", KitchenStationService.SCREEN_STALE_SECONDS)
                .query(Boolean.class).single();
    }

    private UUID enqueue(UUID ticketId, UUID stationId, String kind) {
        TicketHead head = db.sql("""
            SELECT t.merchant_id, t.ticket_number, t.dining_mode, rt.table_number, t.server_name, t.created_at, t.notes
            FROM restaurant_kds_ticket t LEFT JOIN restaurant_table rt ON rt.id = t.table_id
            WHERE t.id = :t
            """).param("t", ticketId).query(TicketHead.class).single();
        var station = db.sql("SELECT name, paper_width FROM restaurant_kitchen_station WHERE id = :s")
                .param("s", stationId).query((rs, n) -> new Object[]{rs.getString(1), rs.getInt(2)}).single();
        List<KitchenTicketText.Line> lines = db.sql("""
            SELECT station_id, quantity, item_name, modifiers, course FROM restaurant_kds_item
            WHERE ticket_id = :t AND station_id = :s
              AND (item_status <> 'CANCELLED' OR :kind = 'VOID')
            ORDER BY CASE course WHEN 'APPETIZER' THEN 1 WHEN 'MAIN' THEN 2 WHEN 'DESSERT' THEN 3
                                 WHEN 'BEVERAGE' THEN 4 ELSE 5 END, item_name
            """).param("t", ticketId).param("s", stationId).param("kind", kind)
                .query(ItemRow.class).list().stream()
                .map(i -> new KitchenTicketText.Line(i.quantity(), i.itemName(), i.modifiers(), i.course()))
                .toList();
        String content = KitchenTicketText.render(new KitchenTicketText.Ticket(kind, (String) station[0],
                head.ticketNumber(), head.diningMode(), head.tableNumber(), head.serverName(), head.createdAt(),
                head.notes(), lines), (Integer) station[1]);
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO restaurant_kds_print_job (id, merchant_id, station_id, ticket_id, kind, content)
            VALUES (:id, :m, :s, :t, :kind, :content)
            """).param("id", id).param("m", head.merchantId()).param("s", stationId).param("t", ticketId)
                .param("kind", kind).param("content", content).update();
        return id;
    }

    public Optional<PrintJob> get(UUID id) {
        return db.sql(JOB + " WHERE j.id = :id").param("id", id).query(PrintJob.class).optional();
    }

    public UUID printJobBranchId(UUID jobId) {
        return db.sql("""
            SELECT COALESCE(s.branch_id, o.branch_id)
            FROM restaurant_kds_print_job j
            JOIN restaurant_kitchen_station s ON s.id = j.station_id
            LEFT JOIN restaurant_kds_ticket t ON t.id = j.ticket_id
            LEFT JOIN pos_order o ON o.id = t.order_id
            WHERE j.id = :id
            """)
            .param("id", jobId)
            .query(UUID.class)
            .optional()
            .orElse(null);
    }

    /** Oldest printable job for this merchant, claimed by one device only (others skip it while locked). */
    @Transactional
    public Optional<PrintJob> claimNext(UUID merchantId, String deviceId) {
        return claimNext(merchantId, deviceId, null);
    }

    /** Oldest printable job for this merchant (and branch if branch-scoped), claimed by one device only. */
    @Transactional
    public Optional<PrintJob> claimNext(UUID merchantId, String deviceId, UUID branchId) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 64)
            throw ApiException.badRequest("device_id_invalid", "Device id is required (max 64 characters)");
        Optional<UUID> claimed;
        if (branchId == null) {
            claimed = db.sql("""
                UPDATE restaurant_kds_print_job
                SET status = 'CLAIMED', claimed_by = :device, claimed_at = now(), attempts = attempts + 1
                WHERE id = (
                    SELECT id FROM restaurant_kds_print_job
                    WHERE merchant_id = :m
                      AND (status = 'PENDING'
                           OR (status = 'CLAIMED' AND claimed_at < now() - make_interval(secs => :timeout)))
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED)
                RETURNING id
                """).param("device", deviceId).param("m", merchantId).param("timeout", CLAIM_TIMEOUT_SECONDS)
                    .query(UUID.class).optional();
        } else {
            claimed = db.sql("""
                UPDATE restaurant_kds_print_job
                SET status = 'CLAIMED', claimed_by = :device, claimed_at = now(), attempts = attempts + 1
                WHERE id = (
                    SELECT j.id
                    FROM restaurant_kds_print_job j
                    JOIN restaurant_kitchen_station s ON s.id = j.station_id
                    LEFT JOIN restaurant_kds_ticket t ON t.id = j.ticket_id
                    LEFT JOIN pos_order o ON o.id = t.order_id
                    WHERE j.merchant_id = :m
                      AND COALESCE(s.branch_id, o.branch_id) = :branchId
                      AND (j.status = 'PENDING'
                           OR (j.status = 'CLAIMED' AND j.claimed_at < now() - make_interval(secs => :timeout)))
                    ORDER BY j.created_at
                    LIMIT 1
                    FOR UPDATE OF j SKIP LOCKED)
                RETURNING id
                """).param("device", deviceId).param("m", merchantId).param("branchId", branchId)
                    .param("timeout", CLAIM_TIMEOUT_SECONDS)
                    .query(UUID.class).optional();
        }
        return claimed.flatMap(this::get);
    }

    /** Device reports the outcome. Repeated or late reports from another device change nothing. */
    @Transactional
    public PrintJob report(UUID jobId, String deviceId, boolean printed, String error) {
        return report(jobId, deviceId, printed, error, null);
    }

    /** Device reports the outcome, validating branch ownership when the caller is branch-scoped. */
    @Transactional
    public PrintJob report(UUID jobId, String deviceId, boolean printed, String error, UUID callerBranchId) {
        if (callerBranchId != null) {
            UUID jobBranchId = printJobBranchId(jobId);
            if (!callerBranchId.equals(jobBranchId)) {
                throw ApiException.forbidden("Print job does not belong to your branch");
            }
        }
        if (printed) {
            db.sql("""
                UPDATE restaurant_kds_print_job SET status = 'PRINTED', printed_at = now(), last_error = NULL
                WHERE id = :id AND status = 'CLAIMED' AND claimed_by = :device
                """).param("id", jobId).param("device", deviceId).update();
        } else {
            db.sql("""
                UPDATE restaurant_kds_print_job
                SET status = CASE WHEN attempts >= :max THEN 'FAILED' ELSE 'PENDING' END,
                    last_error = left(:error, 500), claimed_by = NULL, claimed_at = NULL
                WHERE id = :id AND status = 'CLAIMED' AND claimed_by = :device
                """).param("id", jobId).param("device", deviceId).param("max", MAX_ATTEMPTS)
                    .param("error", error == null ? "Printer error" : error).update();
        }
        return get(jobId).orElseThrow(() -> ApiException.notFound("Print job not found"));
    }

    /** Jobs that gave up, shown on kitchen screens so staff can fix the printer and reprint. */
    public List<PrintJob> recentFailures(UUID merchantId) {
        return db.sql(JOB + """
             WHERE j.merchant_id = :m AND j.status = 'FAILED' AND j.created_at > now() - interval '3 hours'
             ORDER BY j.created_at DESC LIMIT 20
            """).param("m", merchantId).query(PrintJob.class).list();
    }
}
