package com.elepay.security;

import com.elepay.common.OpsQueue;
import com.elepay.common.ScheduledJobLock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily scheduled monitor for KEK rotation lifecycle.
 * Runs under a cluster-wide advisory lock so only one pod checks and raises
 * a high-severity OpsQueue alert when the active encryption key is past its rotation window.
 */
@Component
public class KeyRotationMonitor {
    private static final Logger log = LoggerFactory.getLogger(KeyRotationMonitor.class);

    private final KeyRotationService keyRotationService;
    private final OpsQueue opsQueue;
    private final ScheduledJobLock jobLock;
    private final boolean enabled;

    public KeyRotationMonitor(
            @Autowired(required = false) KeyRotationService keyRotationService,
            OpsQueue opsQueue,
            ScheduledJobLock jobLock,
            @Value("${elepay.security.key-rotation-monitor.enabled:true}") boolean enabled) {
        this.keyRotationService = keyRotationService;
        this.opsQueue = opsQueue;
        this.jobLock = jobLock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${elepay.security.key-rotation-check-ms:86400000}", initialDelay = 60_000)
    public void checkRotationSchedule() {
        if (!enabled || keyRotationService == null) {
            return;
        }
        jobLock.runIfLeader("key-rotation-check", this::evaluateAndAlert);
    }

    public boolean evaluateAndAlert() {
        if (keyRotationService == null) {
            return false;
        }
        KeyRotationService.RotationCheck check = keyRotationService.rotateDue();
        if (check.dueForRotation()) {
            log.warn("Active KEK {} v{} is overdue for rotation (dueAt={})",
                    check.activeKeyId(), check.activeVersion(), check.rotationDueAt());
            opsQueue.raise("kek_rotation_overdue", "high", "security:kek:" + check.activeKeyId(), Map.of(
                    "keyId", String.valueOf(check.activeKeyId()),
                    "version", check.activeVersion(),
                    "dueAt", String.valueOf(check.rotationDueAt())
            ));
            return true;
        }
        return false;
    }
}
