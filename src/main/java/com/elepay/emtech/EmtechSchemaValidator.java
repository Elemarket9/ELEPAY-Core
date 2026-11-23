package com.elepay.emtech;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks a payload against the Emtech OpenAPI component schema before it is sent.
 * The sandbox accepts numeric latitudes and string amounts, so a type mismatch is
 * rejected here even though the remote service would coerce it.
 */
public final class EmtechSchemaValidator {
    private static final Pattern ISO_INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
    private final JsonNode schemas;

    public EmtechSchemaValidator(ObjectMapper json) {
        try (InputStream in = EmtechSchemaValidator.class.getResourceAsStream("/emtech/payment-service-openapi.json")) {
            if (in == null) throw new IllegalStateException("Emtech OpenAPI spec is not on the classpath");
            schemas = json.readTree(in).path("components").path("schemas");
        } catch (EmtechValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read Emtech OpenAPI spec", e);
        }
    }

    public void validate(String schemaName, JsonNode payload) {
        JsonNode schema = schemas.path(schemaName);
        if (schema.isMissingNode()) throw new IllegalArgumentException("Unknown Emtech schema " + schemaName);
        List<String> errors = new ArrayList<>();
        checkObject(schema, payload, errors);
        if (!errors.isEmpty()) throw new EmtechValidationException(errors);
    }

    private static void checkObject(JsonNode schema, JsonNode payload, List<String> errors) {
        if (payload == null || !payload.isObject()) {
            errors.add("body: must be a JSON object");
            return;
        }
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode name : required) {
                JsonNode value = payload.get(name.asString());
                if (value == null || value.isNull()) errors.add(name.asString() + ": must not be null");
            }
        }
        JsonNode properties = schema.path("properties");
        properties.properties().forEach(entry -> {
            JsonNode value = payload.get(entry.getKey());
            if (value == null || value.isNull()) return;
            checkProperty(entry.getKey(), entry.getValue(), value, errors);
        });
    }

    private static void checkProperty(String name, JsonNode property, JsonNode value, List<String> errors) {
        String type = property.path("type").asString(null);
        if ("string".equals(type) && !value.isTextual()) {
            errors.add(name + ": must be a string");
            return;
        }
        if ("number".equals(type) && !value.isNumber()) {
            errors.add(name + ": must be a number");
            return;
        }
        if ("integer".equals(type) && !value.isIntegralNumber()) {
            errors.add(name + ": must be an integer");
            return;
        }
        if ("object".equals(type) && !value.isObject()) {
            errors.add(name + ": must be an object");
            return;
        }
        if (value.isTextual()) {
            JsonNode enumValues = property.path("enum");
            if (enumValues.isArray()) {
                boolean found = false;
                for (JsonNode allowed : enumValues) {
                    if (allowed.asString().equals(value.asString())) found = true;
                }
                if (!found) errors.add(name + ": unexpected value '" + value.asString() + "'");
            }
            String pattern = property.path("pattern").asString(null);
            if (pattern != null && !value.asString().matches(pattern)) {
                errors.add(name + ": does not match " + pattern);
            }
            String format = property.path("format").asString("");
            if (("date-time".equals(format) || "datetime".equals(format)) && !isoInstant(value.asString())) {
                errors.add(name + ": must be an ISO-8601 UTC timestamp");
            }
        }
        if ("integer".equals(type) && property.has("maxLength")) {
            String digits = value.asString();
            if (digits.startsWith("-")) digits = digits.substring(1);
            if (digits.length() > property.get("maxLength").asInt()) {
                errors.add(name + ": exceeds maxLength " + property.get("maxLength").asInt());
            }
        }
    }

    private static boolean isoInstant(String value) {
        if (!ISO_INSTANT.matcher(value).matches()) return false;
        try {
            Instant.parse(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
