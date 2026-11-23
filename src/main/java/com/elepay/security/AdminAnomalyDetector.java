package com.elepay.security;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Administrative anomaly detector (Algorithm 081).
 * Monitors privileged operator activity for anomalous behaviors such as
 * off-hours access, impossible travel, bulk customer enumeration, and self-approval attempts.
 */
public class AdminAnomalyDetector {

    public enum AnomalyType {
        OFF_HOURS_ACCESS,
        IMPOSSIBLE_TRAVEL,
        BULK_ENUMERATION,
        SELF_APPROVAL
    }

    public enum Severity {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }

    public record AdminActionEvent(
            UUID eventId,
            UUID operatorId,
            String operatorRole,
            String action,
            String resourceType,
            String resourceId,
            String ipAddress,
            String userAgent,
            Double latitude,
            Double longitude,
            UUID initiatorId, // For self-approval checking
            Instant timestamp
    ) {}

    public record AdminSecurityAlert(
            UUID alertId,
            UUID operatorId,
            List<AnomalyType> anomalies,
            Severity severity,
            String description,
            Instant timestamp
    ) {}

    private final JdbcClient db;
    private final Map<UUID, List<AdminActionEvent>> operatorHistory = new ConcurrentHashMap<>();
    private final LocalTime offHoursStart = LocalTime.of(22, 0); // 10 PM
    private final LocalTime offHoursEnd = LocalTime.of(5, 30);   // 5:30 AM
    private static final int BULK_ENUMERATION_THRESHOLD = 10;
    private static final Duration BULK_ENUMERATION_WINDOW = Duration.ofMinutes(5);

    public AdminAnomalyDetector(JdbcClient db) {
        this.db = db;
    }

    public List<AnomalyType> detectAnomalies(AdminActionEvent event) {
        List<AnomalyType> detected = new ArrayList<>();

        // 1. Off-hours privileged access detection (Ghana time is UTC)
        LocalTime eventTime = event.timestamp().atZone(ZoneOffset.UTC).toLocalTime();
        if (eventTime.isAfter(offHoursStart) || eventTime.isBefore(offHoursEnd)) {
            detected.add(AnomalyType.OFF_HOURS_ACCESS);
        }

        // 2. Self-approval detection (maker cannot be checker)
        if (event.initiatorId() != null && Objects.equals(event.operatorId(), event.initiatorId())
                && (event.action().toLowerCase().contains("approve") || event.action().toLowerCase().contains("release"))) {
            detected.add(AnomalyType.SELF_APPROVAL);
        }

        // History-based rules
        List<AdminActionEvent> history = operatorHistory.computeIfAbsent(event.operatorId(), k -> new ArrayList<>());
        synchronized (history) {
            // 3. Impossible travel detection
            if (!history.isEmpty()) {
                AdminActionEvent previous = history.get(history.size() - 1);
                if (event.latitude() != null && event.longitude() != null
                        && previous.latitude() != null && previous.longitude() != null) {
                    double distanceKm = calculateHaversineDistanceKm(previous.latitude(), previous.longitude(), event.latitude(), event.longitude());
                    double elapsedHours = Duration.between(previous.timestamp(), event.timestamp()).toMillis() / 3600000.0;
                    if (elapsedHours > 0.001) {
                        double speedKmH = distanceKm / elapsedHours;
                        if (speedKmH > 900.0) { // Faster than commercial airliner
                            detected.add(AnomalyType.IMPOSSIBLE_TRAVEL);
                        }
                    } else if (distanceKm > 50.0) {
                        detected.add(AnomalyType.IMPOSSIBLE_TRAVEL);
                    }
                }
            }

            // 4. Bulk enumeration detection (> 10 resource lookups within 5 minutes)
            Instant windowStart = event.timestamp().minus(BULK_ENUMERATION_WINDOW);
            long recentLookups = history.stream()
                    .filter(h -> h.timestamp().isAfter(windowStart) && "VIEW".equalsIgnoreCase(h.action()))
                    .count();
            if ("VIEW".equalsIgnoreCase(event.action()) && recentLookups >= BULK_ENUMERATION_THRESHOLD) {
                detected.add(AnomalyType.BULK_ENUMERATION);
            }

            history.add(event);
            if (history.size() > 100) {
                history.remove(0);
            }
        }

        // Persist audit record in database if db is available
        if (db != null) {
            try {
                db.sql("""
                    INSERT INTO administrative_audit_event
                    (id, operator_id, operator_role, action, resource_type, resource_id, ip_address, user_agent, is_anomalous, anomaly_reasons, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(
                            event.eventId() != null ? event.eventId() : UUID.randomUUID(),
                            event.operatorId(),
                            event.operatorRole(),
                            event.action(),
                            event.resourceType(),
                            event.resourceId(),
                            event.ipAddress(),
                            event.userAgent(),
                            !detected.isEmpty(),
                            detected.isEmpty() ? null : detected.toString(),
                            event.timestamp() != null ? event.timestamp().atOffset(ZoneOffset.UTC) : java.time.OffsetDateTime.now(ZoneOffset.UTC)
                    )
                    .update();
            } catch (Exception ignored) {
                // Keep resilient if running in memory test
            }
        }

        return detected;
    }

    public AdminSecurityAlert evaluateAndAlert(AdminActionEvent event) {
        List<AnomalyType> anomalies = detectAnomalies(event);
        if (anomalies.isEmpty()) {
            return null;
        }

        Severity severity;
        if (anomalies.contains(AnomalyType.SELF_APPROVAL) || anomalies.contains(AnomalyType.IMPOSSIBLE_TRAVEL)) {
            severity = Severity.CRITICAL;
        } else if (anomalies.contains(AnomalyType.BULK_ENUMERATION)) {
            severity = Severity.HIGH;
        } else {
            severity = Severity.MEDIUM;
        }

        return new AdminSecurityAlert(
                UUID.randomUUID(),
                event.operatorId(),
                anomalies,
                severity,
                "Administrative anomaly detected for operator " + event.operatorId() + ": " + anomalies,
                event.timestamp()
        );
    }

    private double calculateHaversineDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
