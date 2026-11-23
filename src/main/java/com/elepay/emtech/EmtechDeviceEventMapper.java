package com.elepay.emtech;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Builds a {@code deviceEvent} object. The status time is an ISO-8601 string, not a unix number. */
public final class EmtechDeviceEventMapper {
    private final ObjectMapper json;

    public EmtechDeviceEventMapper(ObjectMapper json) {
        this.json = json;
    }

    public ObjectNode toJson(RegulatoryFacts.DeviceEvent fact) {
        ObjectNode node = json.createObjectNode();
        node.put("deviceId", fact.deviceId());
        node.put("deviceHealth", fact.health());
        node.put("deviceStatus", fact.status());
        node.put("deviceStatusDatetime", fact.occurredAt());
        if (fact.description() != null && !fact.description().isBlank()) {
            node.put("deviceStatusDescription", fact.description());
        }
        ObjectNode meta = json.createObjectNode();
        meta.put("source", "ELEPAY");
        node.set("meta", meta);
        return node;
    }
}
