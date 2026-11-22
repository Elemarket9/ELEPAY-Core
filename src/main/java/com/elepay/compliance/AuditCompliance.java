package com.elepay.compliance;

import com.elepay.common.Audit;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Compliance audit export (Algorithm 080).
 */
public class AuditCompliance extends Audit {
    public AuditCompliance(JdbcClient db, ObjectMapper json) {
        super(db, json);
    }
}
