package com.elepay.common;

import com.elepay.PostgresTestBase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuditSealerIT extends PostgresTestBase {
    @Autowired JdbcClient db;
    @Autowired Audit audit;
    @Autowired AuditSealer sealer;

    @Test void sealsExistingAndNewEventsWithoutModifyingAuditLog() {
        String subject = "test:" + UUID.randomUUID();
        audit.record("operator-a", "test.created", subject, Map.of("amount", 123));
        audit.record("operator-b", "test.reviewed", subject, Map.of("result", "accepted"));
        for (int i = 0; i < 20; i++) {
            sealer.seal(5000);
            long pending = db.sql("""
                    SELECT count(*) FROM audit_log a WHERE a.subject=:s
                    AND NOT EXISTS(SELECT 1 FROM audit_chain c WHERE c.audit_id=a.id)
                    """).param("s", subject).query(Long.class).single();
            if (pending == 0) break;
            try { Thread.sleep(25); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertThat(db.sql("""
                SELECT count(*) FROM audit_chain c JOIN audit_log a ON a.id=c.audit_id WHERE a.subject=:s
                """).param("s", subject).query(Long.class).single()).isEqualTo(2);
        assertThat(db.sql("SELECT count(*) FROM audit_chain_violations").query(Long.class).single()).isZero();
        assertThatThrownBy(() -> db.sql("DELETE FROM audit_chain WHERE audit_id IN (SELECT id FROM audit_log WHERE subject=:s)")
                .param("s", subject).update()).hasMessageContaining("immutable");
    }

    @Test void digestBindsContentPositionAndPreviousHash() {
        String sql = """
                SELECT encode(elepay_audit_digest(1,1,'actor','action','subject','{"amount":1}'::jsonb,
                    '2026-01-01T00:00:00Z'::timestamptz, decode(repeat('00',32),'hex')), 'hex')
                """;
        String first = db.sql(sql).query(String.class).single();
        assertThat(first).hasSize(64).isEqualTo(db.sql(sql).query(String.class).single());
        assertThat(db.sql(sql.replace("amount\":1","amount\":2")).query(String.class).single()).isNotEqualTo(first);
        assertThat(db.sql(sql.replace("digest(1,1", "digest(2,1")).query(String.class).single()).isNotEqualTo(first);
        assertThat(db.sql(sql.replace("repeat('00',32)", "repeat('01',32)")).query(String.class).single()).isNotEqualTo(first);
    }
}
