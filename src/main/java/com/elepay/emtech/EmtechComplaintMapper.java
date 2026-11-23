package com.elepay.emtech;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds a {@code consumerComplaints} object. {@code userId} is always a string.
 * {@code MISCONDUCT OTHER} keeps the space the sandbox enum requires.
 */
public final class EmtechComplaintMapper {
    private final ObjectMapper json;

    public EmtechComplaintMapper(ObjectMapper json) {
        this.json = json;
    }

    public ObjectNode toJson(RegulatoryFacts.Complaint fact) {
        ObjectNode node = json.createObjectNode();
        node.put("userId", fact.userId());
        node.put("complaintId", fact.complaintId());
        node.put("complaintType", fact.type());
        node.put("complaintStatus", fact.status());
        node.put("complaintStatusDatetime", fact.statusAt());
        if (fact.typeDescription() != null && !fact.typeDescription().isBlank()) {
            node.put("complaintTypeDescription", fact.typeDescription());
        }
        if (fact.resolution() != null && !fact.resolution().isBlank()) {
            node.put("resolutionDescription", fact.resolution());
        }
        ObjectNode meta = json.createObjectNode();
        meta.put("source", "ELEPAY");
        node.set("meta", meta);
        return node;
    }
}
