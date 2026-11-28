package com.elepay.growth;

import com.elepay.common.FirebasePushService;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 099: Notification timing and quiet-hours scheduling.
 * Prioritizes urgent security/fraud notifications for instant delivery while deferring
 * routine non-promotional financial notices during quiet hours (e.g. 21:00 - 07:00 UTC/GMT).
 */
@Service
public class NotificationTimingService {
    private static final Logger log = LoggerFactory.getLogger(NotificationTimingService.class);

    private final JdbcClient db;
    private final FirebasePushService pushService;

    public NotificationTimingService(JdbcClient db) {
        this(db, null);
    }

    @Autowired
    public NotificationTimingService(JdbcClient db, @Autowired(required = false) FirebasePushService pushService) {
        this.db = db;
        this.pushService = pushService;
    }

    public enum Urgency {
        URGENT,         // Bypasses quiet hours immediately (e.g., OTP, fraud alerts)
        TRANSACTIONAL,  // Standard receipt; bypasses unless strictly nocturnal
        ROUTINE         // Routine account notices defer until quiet hours end
    }

    public record QueuedNotification(
            UUID id,
            UUID customerId,
            String title,
            String message,
            Urgency urgency,
            boolean sentImmediately,
            OffsetDateTime scheduledDeliveryTime,
            String status
    ) {}

    private record DueNotificationRow(UUID id, UUID customerId, String title, String message, String urgency) {}

    private static final LocalTime QUIET_HOURS_START = LocalTime.of(21, 0); // 9:00 PM
    private static final LocalTime QUIET_HOURS_END = LocalTime.of(7, 0);   // 7:00 AM

    public boolean isQuietHours(LocalTime time) {
        if (time == null) return false;
        // Quiet hours: 21:00 to 07:00 (inclusive of transition points 21:00 and 07:00)
        return !time.isBefore(QUIET_HOURS_START) || !time.isAfter(QUIET_HOURS_END);
    }

    /**
     * Schedules or delivers notification respecting quiet hours and urgency tier.
     */
    @Transactional
    public QueuedNotification enqueueNotification(UUID customerId, String title, String message, Urgency urgency, OffsetDateTime currentTime) {
        LocalTime timeOfDay = currentTime.toLocalTime();
        boolean inQuietHours = isQuietHours(timeOfDay);

        OffsetDateTime deliveryTime;
        boolean immediate;
        String status;

        if (urgency == Urgency.URGENT || !inQuietHours) {
            deliveryTime = currentTime;
            immediate = true;
            status = "SENT";
        } else {
            // Defer to morning (08:00 AM) following quiet hours
            OffsetDateTime target = currentTime.toLocalDate().atTime(8, 0).atOffset(ZoneOffset.UTC);
            if (!timeOfDay.isBefore(QUIET_HOURS_START)) {
                target = target.plusDays(1);
            }
            deliveryTime = target;
            immediate = false;
            status = "QUEUED";
        }

        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO notification_delivery_queue
            (id, customer_id, title, message, urgency, quiet_hours_allowed, scheduled_delivery_time, status, sent_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(id, customerId, title, message, urgency.name(), inQuietHours, deliveryTime,
                status, immediate ? currentTime : null)
        .update();

        if (immediate) {
            dispatchPushSafely(id, customerId, title, message, urgency.name());
        }

        return new QueuedNotification(id, customerId, title, message, urgency, immediate, deliveryTime, status);
    }

    /**
     * Scheduled background poller and dispatcher to deliver queued notifications when their scheduled time arrives.
     * Eliminates the operational black hole where queued notifications remained stuck indefinitely.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${elepay.growth.notification-poll-ms:60000}")
    @Transactional
    public int dispatchDueNotifications() {
        return dispatchDueNotifications(OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Dispatches queued notifications that are due as of the specified timestamp using row-level locking.
     */
    @Transactional
    public int dispatchDueNotifications(OffsetDateTime asOfTime) {
        List<DueNotificationRow> dueRows = db.sql("""
            SELECT id, customer_id, title, message, urgency
            FROM notification_delivery_queue
            WHERE status = 'QUEUED' AND scheduled_delivery_time <= :asOf
            FOR UPDATE SKIP LOCKED
        """).param("asOf", asOfTime)
                .query((rs, rowNum) -> new DueNotificationRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("message"),
                        rs.getString("urgency")
                ))
                .list();

        if (dueRows.isEmpty()) {
            return 0;
        }

        List<UUID> dueIds = dueRows.stream().map(DueNotificationRow::id).toList();
        int updated = db.sql("""
            UPDATE notification_delivery_queue
            SET status = 'SENT', sent_at = :now
            WHERE id IN (:ids)
        """).param("now", asOfTime).param("ids", dueIds).update();

        for (DueNotificationRow row : dueRows) {
            dispatchPushSafely(row.id(), row.customerId(), row.title(), row.message(), row.urgency());
        }
        return updated;
    }

    private void dispatchPushSafely(UUID notificationId, UUID customerId, String title, String message, String urgency) {
        if (pushService == null || customerId == null) {
            return;
        }
        try {
            pushService.sendToSubject(
                    customerId.toString(),
                    title,
                    message,
                    Map.of(
                            "type", "customer.notification",
                            "notificationId", notificationId.toString(),
                            "urgency", urgency == null ? "ROUTINE" : urgency
                    )
            );
        } catch (Exception e) {
            log.warn("FCM push dispatch failed for notification {} customer {}: {}",
                    notificationId, customerId, e.getMessage());
        }
    }
}
