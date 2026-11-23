package com.elepay.operations;

import com.elepay.common.ApiException;
import com.elepay.emtech.ConsumerComplaints;
import com.elepay.emtech.DeviceEvents;
import com.elepay.identity.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Customer and operations complaints. Coordinates are not collected. */
@RestController
class EmtechComplaintApi {
    private final ConsumerComplaints complaints;
    private final DeviceEvents events;
    private final JdbcClient db;

    EmtechComplaintApi(ConsumerComplaints complaints, DeviceEvents events, JdbcClient db) {
        this.complaints = complaints;
        this.events = events;
        this.db = db;
    }

    record OpenRequest(@NotBlank String type, @NotBlank @Size(max = 2000) String description, UUID customerId) {}

    record StatusRequest(@NotBlank String status, @Size(max = 2000) String resolution) {}

    @PostMapping("/v1/complaints")
    public ConsumerComplaints.Complaint open(@Valid @RequestBody OpenRequest request) {
        Actor actor = Actor.current();
        UUID customerId;
        String userId;
        if (actor.isOps()) {
            customerId = request.customerId();
            userId = customerId == null ? actor.subject() : customerId.toString();
        } else {
            actor.requireUser();
            customerId = db.sql("SELECT id FROM customer WHERE subject = :s AND status = 'active'")
                    .param("s", actor.subject()).query(UUID.class).optional()
                    .orElseThrow(() -> ApiException.notFound("Active customer wallet not found"));
            userId = customerId.toString();
        }
        return complaints.open(customerId, userId, request.type(), request.description(), actor.subject());
    }

    @PostMapping("/v1/complaints/{id}/status")
    public ConsumerComplaints.Complaint status(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        Actor actor = Actor.current();
        if (!actor.isOps()) {
            UUID customerId = db.sql("SELECT customer_id FROM consumer_complaint WHERE id = :id")
                    .param("id", id).query(UUID.class).optional()
                    .orElseThrow(() -> ApiException.notFound("Complaint not found"));
            UUID mine = db.sql("SELECT id FROM customer WHERE subject = :s")
                    .param("s", actor.subject()).query(UUID.class).optional().orElse(null);
            if (mine == null || !mine.equals(customerId)) throw ApiException.forbidden("Complaint not found");
        }
        return complaints.transition(id, request.status(), request.resolution(), actor.subject());
    }

    @PostMapping("/v1/ops/emtech/device-events")
    public DeviceEvents.Event deviceEvent(@RequestBody Map<String, String> body) {
        Actor.current().requireOps();
        return events.record(null, null, body.get("deviceId"), body.get("health"), body.get("status"),
                body.get("description"), null);
    }
}
