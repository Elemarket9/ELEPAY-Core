package com.elepay.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** An insider with database access can no longer rewrite the audit trail and recompute it to look intact. */
class AuditIntegrityIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Audit audit;
    @Autowired JdbcClient db;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    @Test
    void keyedChainAndSignedCheckpointsDetectAnInsiderRewrite() {
        TestApi api = new TestApi(port);
        String ops = api.token("ops-" + UUID.randomUUID(), "ops");
        audit.recordEntry("ops@elepay", "payout.approved", "payout:PO1", Map.of("amount", 5000));
        var target = audit.recordEntry("ops@elepay", "limit.changed", "customer:C1", Map.of("daily", 100000));
        assertThat(db.sql("SELECT hash_version FROM audit_log WHERE id = :id").param("id", target.id())
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(target.entryHash()).hasSize(128);

        var cp = api.call("POST", "/v1/ops/audit/checkpoints", ops, null, null);
        assertThat(cp.status()).as(cp.body().toString()).isEqualTo(200);
        assertThat(cp.body().get("algorithm").asString()).isEqualTo("Ed25519+ML-DSA-65");
        var report = api.call("GET", "/v1/ops/audit/verify", ops, null, null).body();
        assertThat(report.get("valid").asBoolean()).as(report.toString()).isTrue();
        assertThat(report.get("checkpoints").get(report.get("checkpoints").size() - 1).get("signatureValid").asBoolean())
                .isTrue();

        // Auditors can fetch the verification keys without signing in; responses carry OWASP security headers.
        var keysResponse = api.http.get().uri("/v1/public/integrity/keys").retrieve().toEntity(String.class);
        assertThat(keysResponse.getHeaders().getFirst("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(keysResponse.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(keysResponse.getHeaders().getFirst("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(keysResponse.getHeaders().getFirst("Permissions-Policy")).contains("camera=()");
        var keys = api.call("GET", "/v1/public/integrity/keys", null, null, null).body();
        assertThat(keys.get("keys").toString()).contains(cp.body().get("keyId").asString());

        // The insider changes the limit, then recomputes the hash the way the old, unkeyed chain allowed.
        String forgedDetails = "{\"daily\": 99999999}";
        String forged = Audit.computeHash(target.prevHash(), target.id(), target.action(), target.actor(),
                forgedDetails, target.createdAt().toString());
        // Gate 3 (V57): Even if elepay.test_allow_insider_simulation is set, ALTER TABLE ... DISABLE TRIGGER is blocked with 42501.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                tx.executeWithoutResult(s -> {
                    db.sql("SET LOCAL elepay.test_allow_insider_simulation = 'true'").update();
                    db.sql("ALTER TABLE journal_entry DISABLE TRIGGER journal_entry_immutable").update();
                }))
                .satisfies(ex -> {
                    Throwable cur = ex;
                    boolean found42501 = false;
                    while (cur != null) {
                        if (cur instanceof java.sql.SQLException sqlEx && "42501".equals(sqlEx.getSQLState())) {
                            found42501 = true;
                            break;
                        }
                        cur = cur.getCause();
                    }
                    assertThat(found42501).as("Expected SQLSTATE 42501 from V57 event trigger").isTrue();
                })
                .rootCause()
                .hasMessageContaining("Disabling immutability triggers on financial ledger or audit tables is strictly forbidden");

        try {
            tx.executeWithoutResult(s -> {
                db.sql("SET LOCAL session_replication_role = 'replica'").update();
                db.sql("UPDATE audit_log SET details = CAST(:d AS jsonb), entry_hash = :h WHERE id = :id")
                        .param("d", forgedDetails).param("h", forged).param("id", target.id()).update();
            });
            var tamperedResponse = api.call("GET", "/v1/ops/audit/verify", ops, null, null);
            var tampered = tamperedResponse.body();
            assertThat(tamperedResponse.status()).as(String.valueOf(tampered)).isEqualTo(200);
            assertThat(tampered.get("valid").asBoolean()).isFalse();
            assertThat(tampered.get("failure").asString()).contains("tampered at id " + target.id());

            // Even changing only the subject, which the old chain did not cover, is caught.
            tx.executeWithoutResult(s -> {
                db.sql("SET LOCAL session_replication_role = 'replica'").update();
                db.sql("UPDATE audit_log SET details = CAST(:d AS jsonb), entry_hash = :h, subject = 'customer:C2' WHERE id = :id")
                        .param("d", target.payload()).param("h", target.entryHash()).param("id", target.id()).update();
            });
            assertThat(api.call("GET", "/v1/ops/audit/verify", ops, null, null).body().get("valid").asBoolean()).isFalse();
        } finally {
            tx.executeWithoutResult(s -> {
                db.sql("SET LOCAL session_replication_role = 'replica'").update();
                db.sql("UPDATE audit_log SET details = CAST(:d AS jsonb), entry_hash = :h, subject = 'customer:C1' WHERE id = :id")
                        .param("d", target.payload()).param("h", target.entryHash()).param("id", target.id()).update();
            });
        }
        assertThat(api.call("GET", "/v1/ops/audit/verify", ops, null, null).body().get("valid").asBoolean()).isTrue();
    }
}
