package com.elepay.growth;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 099: Notification timing and quiet-hours scheduling.
 * Prioritizes urgent security/fraud notifications for instant delivery while deferring
 * marketing and non-urgent notices during quiet hours (e.g. 21:00 - 07:00 UTC/GMT).
 */
@Service
public class NotificationTimingService {

    private final JdbcClient db;

    public NotificationTimingService(JdbcClient db) {
        this.db = db;
    }

    public enum Urgency {
        URGENT,         // Bypasses quiet hours immediately (e.g., OTP, fraud alerts)
        TRANSACTIONAL,  // Standard receipt; bypasses unless strictly nocturnal
        MARKETING       // Defers until morning after quiet hours
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

    private static final LocalTime QUIET_HOURS_START = LocalTime.of(21, 0); // 9:00 PM
    private static final LocalTime QUIET_HOURS_END = LocalTime.of(7, 0);   // 7:00 AM

    public boolean isQuietHours(LocalTime time) {
        return time.isAfter(QUIET_HOURS_START) || time.isBefore(QUIET_HOURS_END);
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
            // Defer to next 08:00 AM
            OffsetDateTime target = currentTime.toLocalDate().atTime(8, 0).atOffset(ZoneOffset.UTC);
            if (timeOfDay.isAfter(QUIET_HOURS_START)) {
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

        return new QueuedNotification(id, customerId, title, message, urgency, immediate, deliveryTime, status);
    }
}
