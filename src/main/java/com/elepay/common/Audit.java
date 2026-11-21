package com.elepay.common;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Append-only audit trail for sensitive actions. */
@Component
public class Audit {
    private final JdbcClient db;
    private final ObjectMapper json;

    public Audit(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public void record(String actor, String action, String subject, Map<String, ?> details) {
        db.sql("INSERT INTO audit_log (actor, action, subject, details) VALUES (:a, :action, :s, CAST(:d AS jsonb))")
                .param("a", actor).param("action", action).param("s", subject)
                .param("d", json.writeValueAsString(details)).update();
    }
}
