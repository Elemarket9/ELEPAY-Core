package com.elepay.common;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuditCheckpointsAlertTest {

    @Test
    void scheduledCheckpointRaisesCriticalOpsAlertOnFailure() {
        JdbcClient db = mock(JdbcClient.class);
        IntegrityKeys keys = mock(IntegrityKeys.class);
        Audit audit = mock(Audit.class);
        OpsQueue ops = mock(OpsQueue.class);

        when(db.sql(org.mockito.ArgumentMatchers.anyString())).thenThrow(new RuntimeException("DB unavailable"));

        AuditCheckpoints checkpoints = new AuditCheckpoints(db, keys, audit, ops, true);
        checkpoints.scheduledCheckpoint();

        verify(ops).raise(eq("audit_checkpoint_failed"), eq("critical"), eq("audit:checkpoint"), anyMap());
    }
}
