package com.elepay.push;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Expiry also runs while delivery is disabled, so stale alerts cannot accumulate indefinitely. */
@Component
class PushRetention {
    private final JdbcClient db;
    PushRetention(JdbcClient db) { this.db = db; }

    @Scheduled(fixedDelayString = "${elepay.push.cleanup-ms:3600000}")
    @Transactional
    public void cleanup() {
        db.sql("""
                UPDATE push_delivery SET state = 'CANCELLED', last_error = 'EXPIRED'
                WHERE id IN (SELECT id FROM push_delivery WHERE state = 'PENDING' AND expires_at <= now()
                    LIMIT 5000 FOR UPDATE SKIP LOCKED)
                """).update();
        db.sql("""
                DELETE FROM push_delivery WHERE id IN (
                    SELECT id FROM push_delivery WHERE expires_at < now() - interval '7 days'
                    LIMIT 5000 FOR UPDATE SKIP LOCKED)
                """).update();
        db.sql("""
                DELETE FROM push_subscription WHERE id IN (
                    SELECT id FROM push_subscription WHERE expires_at <= now()
                    LIMIT 5000 FOR UPDATE SKIP LOCKED)
                """).update();
    }
}
