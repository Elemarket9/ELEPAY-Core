package com.elepay.emtech;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Heartbeat and failure records for a registered device. Each event is reported once. */
@Service
public class DeviceEvents {
    public static final Set<String> HEALTH = Set.of("OPERATIONAL", "FAILED", "IMPAIRED");
    public static final Set<String> STATUS = Set.of(
            "HARDWARE_FAILURE", "SOFTWARE_FAILURE", "NETWORK_FAILURE", "CASH_EMPTY", "MAINTENANCE", "ONLINE", "OTHER");

    private final JdbcClient db;
    private final EmtechReporter reporter;

    public DeviceEvents(JdbcClient db, EmtechReporter reporter) {
        this.db = db;
        this.reporter = reporter;
    }

    public record Event(UUID id, String deviceId, String health, String status, String description, OffsetDateTime occurredAt) {}

    @Transactional
    public Event record(UUID terminalId, UUID virtualDeviceId, String deviceId, String health, String status,
                        String description, OffsetDateTime occurredAt) {
        if (!HEALTH.contains(health)) {
            throw com.elepay.common.ApiException.badRequest("invalid_health", "deviceHealth must be OPERATIONAL, FAILED or IMPAIRED");
        }
        if (!STATUS.contains(status)) {
            throw com.elepay.common.ApiException.badRequest("invalid_status", "deviceStatus is not an Emtech status");
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime when = occurredAt == null ? OffsetDateTime.now() : occurredAt;
        db.sql("""
                INSERT INTO device_event (id, device_id, terminal_id, virtual_device_id, health, status, description, occurred_at)
                VALUES (:id, :device, :terminal, :virtual, :health, :status, :description, :at)""")
                .param("id", id).param("device", deviceId).param("terminal", terminalId).param("virtual", virtualDeviceId)
                .param("health", health).param("status", status).param("description", description).param("at", when).update();
        reporter.deviceEvent(id, new RegulatoryFacts.DeviceEvent(deviceId, health, status, EmtechReporter.instant(when), description));
        return new Event(id, deviceId, health, status, description, when);
    }
}
