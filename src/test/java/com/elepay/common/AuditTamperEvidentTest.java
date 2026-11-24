package com.elepay.common;

import static org.junit.jupiter.api.Assertions.*;

import com.elepay.PostgresTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuditTamperEvidentTest extends PostgresTestBase {

    @Autowired
    private Audit audit;

    @Autowired
    private JdbcClient db;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate tx;

    @org.junit.jupiter.api.BeforeEach
    void cleanAuditLog() {
        tx.executeWithoutResult(s -> {
            db.sql("SET LOCAL session_replication_role = 'replica'").update();
            db.sql("TRUNCATE audit_log, audit_checkpoint").update();
            db.sql("ALTER SEQUENCE audit_log_id_seq RESTART WITH 1").update();
        });
    }

    @Test
    void hashChainCreationAndTamperDetection() {
        // Record multiple valid entries
        var e1 = audit.recordEntry("admin@elepay.com", "customer.enrolled", "customer:101", Map.of("tier", 1));
        assertNotNull(e1.entryHash());
        assertEquals(Audit.GENESIS_HASH, e1.prevHash());

        var e2 = audit.recordEntry("system", "wallet.transfer", "transfer:202", Map.of("amount", 5000));
        assertNotNull(e2.entryHash());
        assertEquals(e1.entryHash(), e2.prevHash());

        var e3 = audit.recordEntry("admin@elepay.com", "customer.frozen", "customer:101", Map.of("reason", "kyc"));
        assertEquals(e2.entryHash(), e3.prevHash());

        // Verify chain passes
        Audit.VerificationResult validResult = audit.verifyChain();
        assertTrue(validResult.valid());
        assertTrue(validResult.entriesVerified() >= 3);

        String originalDetails = e2.payload();
        try {
            tx.executeWithoutResult(s -> {
                db.sql("SET LOCAL session_replication_role = 'replica'").update();
                db.sql("UPDATE audit_log SET details = CAST('{\"amount\": 999999}' AS jsonb) WHERE id = :id")
                        .param("id", e2.id()).update();
            });

            // Verification must catch the tampering and throw AuditTamperedException
            Audit.AuditTamperedException ex = assertThrows(Audit.AuditTamperedException.class, () -> audit.verifyChain());
            assertTrue(ex.getMessage().contains("tampered at id " + e2.id()) || ex.getMessage().contains("broken"));
        } finally {
            tx.executeWithoutResult(s -> {
                db.sql("SET LOCAL session_replication_role = 'replica'").update();
                db.sql("UPDATE audit_log SET details = CAST(:d AS jsonb) WHERE id = :id")
                        .param("d", originalDetails).param("id", e2.id()).update();
            });
        }
    }

    @Test
    void nullEntryHashThrowsAuditTamperedException() {
        audit.recordEntry("admin@elepay.com", "action.1", "subject:1", Map.of("k", "v1"));
        var e2 = audit.recordEntry("admin@elepay.com", "action.2", "subject:2", Map.of("k", "v2"));

        try {
            tx.executeWithoutResult(s -> {
                db.sql("SET LOCAL session_replication_role = 'replica'").update();
                db.sql("UPDATE audit_log SET entry_hash = NULL WHERE id = :id")
                        .param("id", e2.id()).update();
            });

            Audit.AuditTamperedException ex = assertThrows(Audit.AuditTamperedException.class, () -> audit.verifyChain());
            assertTrue(ex.getMessage().contains("null entry_hash"));
        } finally {
            tx.executeWithoutResult(s -> {
                db.sql("SET LOCAL session_replication_role = 'replica'").update();
                db.sql("UPDATE audit_log SET entry_hash = :h WHERE id = :id")
                        .param("h", e2.entryHash()).param("id", e2.id()).update();
            });
        }
    }

    @Test
    void nullPrevHashAfterGenesisThrowsAuditTamperedException() {
        audit.recordEntry("admin@elepay.com", "action.1", "subject:1", Map.of("k", "v1"));
        var e2 = audit.recordEntry("admin@elepay.com", "action.2", "subject:2", Map.of("k", "v2"));

        try {
            tx.executeWithoutResult(s -> {
                db.sql("SET LOCAL session_replication_role = 'replica'").update();
                db.sql("UPDATE audit_log SET prev_hash = NULL WHERE id = :id")
                        .param("id", e2.id()).update();
            });

            Audit.AuditTamperedException ex = assertThrows(Audit.AuditTamperedException.class, () -> audit.verifyChain());
            assertTrue(ex.getMessage().contains("null prev_hash"));
        } finally {
            tx.executeWithoutResult(s -> {
                db.sql("SET LOCAL session_replication_role = 'replica'").update();
                db.sql("UPDATE audit_log SET prev_hash = :h WHERE id = :id")
                        .param("h", e2.prevHash()).param("id", e2.id()).update();
            });
        }
    }
}
