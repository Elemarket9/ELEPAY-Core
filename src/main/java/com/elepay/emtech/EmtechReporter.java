package com.elepay.emtech;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Queues device, event and complaint reports. Callers already hold the business transaction. */
@Service
public class EmtechReporter {
    private final EmtechOutbox outbox;

    public EmtechReporter(EmtechOutbox outbox) {
        this.outbox = outbox;
    }

    @Transactional
    public void device(RegulatoryFacts.Device fact) {
        if (!fact.registrationComplete()) return;
        outbox.enqueue("DEVICE", "device:" + fact.deviceId() + ":" + hash(fact), "device", fact.deviceId(), fact);
    }

    @Transactional
    public void deviceEvent(UUID eventId, RegulatoryFacts.DeviceEvent fact) {
        outbox.enqueue("DEVICE_EVENT", "device-event:" + eventId, "device_event", eventId.toString(), fact);
    }

    @Transactional
    public void complaint(RegulatoryFacts.Complaint fact) {
        outbox.enqueue("COMPLAINT",
                "complaint:" + fact.complaintId() + ":" + fact.status() + ":" + fact.statusAt(),
                "complaint", fact.complaintId(), fact);
    }

    @Transactional
    public void transaction(String sourceType, String sourceId, RegulatoryFacts.Transaction fact) {
        outbox.enqueue("TRANSACTION",
                "transaction:" + fact.transactionId() + ":" + fact.elepayStatus(),
                sourceType, sourceId, fact);
    }

    public static String instant(java.time.OffsetDateTime when) {
        Instant instant = (when == null ? java.time.OffsetDateTime.now() : when).toInstant().truncatedTo(ChronoUnit.SECONDS);
        return instant.toString();
    }

    private static String hash(RegulatoryFacts.Device fact) {
        String canonical = String.join("|",
                fact.deviceId(), fact.deviceType(), fact.city(), fact.region(), fact.country(),
                fact.latitude(), fact.longitude(), fact.connectionType(),
                nullToEmpty(fact.address()), nullToEmpty(fact.manufacturer()),
                String.valueOf(fact.manufactureYear()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
