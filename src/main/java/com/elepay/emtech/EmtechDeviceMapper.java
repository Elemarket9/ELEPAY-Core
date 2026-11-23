package com.elepay.emtech;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Builds a {@code deviceV2} object. Latitude and longitude are strings, the manufacture year is an integer. */
public final class EmtechDeviceMapper {
    private final ObjectMapper json;

    public EmtechDeviceMapper(ObjectMapper json) {
        this.json = json;
    }

    public ObjectNode toJson(RegulatoryFacts.Device fact) {
        ObjectNode node = json.createObjectNode();
        put(node, "deviceId", fact.deviceId());
        put(node, "deviceName", fact.deviceName());
        put(node, "deviceType", fact.deviceType());
        put(node, "deviceCity", fact.city());
        put(node, "deviceRegion", fact.region());
        put(node, "deviceCountry", fact.country());
        put(node, "deviceAddress", fact.address());
        put(node, "deviceLocation", fact.location());
        put(node, "deviceLatitude", fact.latitude());
        put(node, "deviceLongitude", fact.longitude());
        put(node, "deviceModelId", fact.modelId());
        put(node, "deviceManufacturer", fact.manufacturer());
        put(node, "deviceDescription", fact.description());
        put(node, "deviceInserviceDate", fact.inserviceDate());
        put(node, "deviceConnectionType", fact.connectionType());
        if (fact.manufactureYear() != null) node.put("deviceManufactureYear", fact.manufactureYear());
        ObjectNode meta = json.createObjectNode();
        meta.put("source", "ELEPAY");
        node.set("meta", meta);
        return node;
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) node.put(field, value);
    }
}
