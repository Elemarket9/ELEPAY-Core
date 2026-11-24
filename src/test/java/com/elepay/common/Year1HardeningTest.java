package com.elepay.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.elepay.security.KeyRotationMonitor;
import com.elepay.security.KeyRotationService;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

class Year1HardeningTest {

    @Test
    void opsQueueDispatchesRealTimeAlertOnlyForHighAndCritical() {
        JdbcClient db = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(db.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(spec);
        when(spec.update()).thenReturn(1);

        AlertDispatcher alerts = mock(AlertDispatcher.class);
        OpsQueue ops = new OpsQueue(db, new ObjectMapper(), null, alerts);

        ops.raise("minor_notice", "low", "sub:1", Map.of("k", "v"));
        verify(alerts, never()).notifyHighSeverity(anyString(), anyString(), anyMap());

        ops.raise("ledger_drift", "high", "sub:2", Map.of("diff", 100));
        verify(alerts).notifyHighSeverity(eq("ledger_drift"), eq("sub:2"), anyMap());

        ops.raise("audit_tamper", "critical", "sub:3", Map.of("id", 42));
        verify(alerts).notifyHighSeverity(eq("audit_tamper"), eq("sub:3"), anyMap());
    }

    @Test
    void keyRotationMonitorRaisesHighSeverityAlertWhenOverdue() {
        KeyRotationService rotationService = mock(KeyRotationService.class);
        OpsQueue ops = mock(OpsQueue.class);
        ScheduledJobLock lock = mock(ScheduledJobLock.class);

        Instant past = Instant.now().minusSeconds(3600);
        when(rotationService.rotateDue()).thenReturn(
                new KeyRotationService.RotationCheck(true, "kek-main", 1, past.minusSeconds(86400), past)
        );

        KeyRotationMonitor monitor = new KeyRotationMonitor(rotationService, ops, lock, true);
        assertTrue(monitor.evaluateAndAlert());
        verify(ops).raise(eq("kek_rotation_overdue"), eq("high"), eq("security:kek:kek-main"), anyMap());
    }

    @Test
    void keyRotationMonitorDoesNotAlertWhenKeyIsFresh() {
        KeyRotationService rotationService = mock(KeyRotationService.class);
        OpsQueue ops = mock(OpsQueue.class);
        ScheduledJobLock lock = mock(ScheduledJobLock.class);

        Instant future = Instant.now().plusSeconds(86400);
        when(rotationService.rotateDue()).thenReturn(
                new KeyRotationService.RotationCheck(false, "kek-main", 2, Instant.now(), future)
        );

        KeyRotationMonitor monitor = new KeyRotationMonitor(rotationService, ops, lock, true);
        assertFalse(monitor.evaluateAndAlert());
        verify(ops, never()).raise(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void scheduledJobLockRunsTaskWhenLeaderAndSkipsWhenAnotherPodHoldsLock() {
        JdbcClient db = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Boolean> querySpec = mock(JdbcClient.MappedQuerySpec.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(mock(TransactionStatus.class));

        when(db.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(spec);
        when(spec.query(Boolean.class)).thenReturn(querySpec);

        ScheduledJobLock lock = new ScheduledJobLock(db, tm);

        // Pod wins leadership
        when(querySpec.single()).thenReturn(true);
        AtomicBoolean ran = new AtomicBoolean(false);
        assertTrue(lock.runIfLeader("payout-auto-run", () -> ran.set(true)));
        assertTrue(ran.get());

        // Another pod holds the lock
        when(querySpec.single()).thenReturn(false);
        AtomicBoolean ranSecond = new AtomicBoolean(false);
        assertFalse(lock.runIfLeader("payout-auto-run", () -> ranSecond.set(true)));
        assertFalse(ranSecond.get());
    }
}
