package com.elepay.push;

import com.elepay.identity.KillSwitchService;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** At-least-once FCM hints. Database claims commit before network I/O. */
@Component
@ConditionalOnProperty(name = "elepay.push.enabled", havingValue = "true")
class PushDispatcher {
    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);
    private static final int MAX_ATTEMPTS = 6;
    private final JdbcClient db;
    private final FirebaseMessaging firebase;
    private final KillSwitchService gates;
    private final boolean financialEnabled;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "elepay-fcm-dispatch");
        t.setDaemon(true);
        return t;
    });
    private record Job(UUID id, UUID subscriptionId, String eventKey, String kind, int attempts, long ttlMs) {}

    PushDispatcher(JdbcClient db, FirebaseMessaging firebase, KillSwitchService gates,
            @Value("${elepay.push.financial-enabled:false}") boolean financialEnabled) {
        this.db = db;
        this.firebase = firebase;
        this.gates = gates;
        this.financialEnabled = financialEnabled;
    }

    @Scheduled(fixedDelayString = "${elepay.push.poll-ms:2000}")
    void dispatch() {
        // Never block the application's shared scheduler or accumulate executor work.
        if (!running.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    for (int i = 0; i < 8; i++) {
                        if (!deliverOne() || Thread.currentThread().isInterrupted()) break;
                    }
                } catch (RuntimeException e) {
                    log.warn("FCM dispatch batch failed: {}", e.getClass().getSimpleName());
                } finally {
                    running.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            running.set(false);
        }
    }

    @PreDestroy
    void close() { worker.shutdownNow(); }

    boolean deliverOne() {
        // A committed claim leases the row. A crashed process is recoverable without holding
        // a connection while talking to Google. Every result is fenced by the attempt number.
        var claimed = db.sql("""
                WITH candidate AS (
                    SELECT id FROM push_delivery
                    WHERE state = 'PENDING' AND next_attempt_at <= now()
                      AND expires_at > now() AND attempts < :max
                    ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE push_delivery d SET attempts = d.attempts + 1,
                    next_attempt_at = now() + interval '90 seconds'
                FROM candidate c WHERE d.id = c.id
                RETURNING d.id, d.subscription_id, d.event_key, d.kind, d.attempts,
                    CAST(EXTRACT(EPOCH FROM (d.expires_at - now())) * 1000 AS bigint)
                """).param("max", MAX_ATTEMPTS)
                .query((rs,n) -> new Job(rs.getObject(1, UUID.class),rs.getObject(2, UUID.class),
                        rs.getString(3),rs.getString(4),rs.getInt(5),rs.getLong(6))).optional();
        if (claimed.isEmpty()) return false;
        Job job = claimed.get();
        if (!enabledFor(job.kind())) {
            finish(job, "CANCELLED", "MODULE_DISABLED");
            return true;
        }
        var token = db.sql("""
                SELECT s.token FROM push_eligible_subscription s
                JOIN push_delivery d ON d.subscription_id = s.id
                WHERE d.id = :id AND d.attempts = :attempt AND d.state = 'PENDING'
                  AND d.expires_at > now()
                """).param("id",job.id()).param("attempt",job.attempts()).query(String.class).optional();
        if (token.isEmpty() || job.ttlMs() <= 0 || !stillRelevant(job)) {
            finish(job, "CANCELLED", "REGISTRATION_OR_EVENT_UNAVAILABLE");
            return true;
        }
        String body = switch (job.kind()) {
            case "KITCHEN_ORDER" -> "New kitchen order. Open ELEPAY to view.";
            case "ORDER_READY" -> "An order is ready. Open ELEPAY to view.";
            case "BILL_REQUESTED" -> "A bill has been requested. Open ELEPAY to view.";
            case "CHECKOUT_PAID" -> "A payment update is available. Open ELEPAY to view.";
            default -> throw new IllegalStateException("Unknown push event kind");
        };
        try {
            firebase.send(Message.builder().setToken(token.get())
                    .setNotification(Notification.builder().setTitle("ELEPAY").setBody(body).build())
                    .putData("kind",job.kind()).putData("eventId",job.id().toString())
                    .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH)
                            .setTtl(Math.min(job.ttlMs(), 120_000))
                            .setNotification(AndroidNotification.builder().setTag(job.id().toString())
                                    .setChannelId("elepay_updates").setSound("default").build())
                            .build())
                    .build());
            finish(job,"SENT",null);
        } catch (FirebaseMessagingException ex) {
            MessagingErrorCode code = ex.getMessagingErrorCode();
            String safeCode = code == null ? "FCM_ERROR" : code.name();
            if (code == MessagingErrorCode.UNREGISTERED) {
                // No FK cascade: rotating a token never locks a claimed delivery.
                db.sql("DELETE FROM push_subscription WHERE id = :id AND token = :token")
                        .param("id",job.subscriptionId()).param("token",token.get()).update();
                finish(job,"DEAD",safeCode);
            } else if (code == MessagingErrorCode.INVALID_ARGUMENT || code == MessagingErrorCode.SENDER_ID_MISMATCH
                    || job.attempts() >= MAX_ATTEMPTS) {
                finish(job,"DEAD",safeCode);
            } else {
                int delay = (code == MessagingErrorCode.QUOTA_EXCEEDED ? 60
                        : Math.min(120, 5 << Math.min(job.attempts()-1,5))) + ThreadLocalRandom.current().nextInt(5);
                db.sql("""
                        UPDATE push_delivery SET last_error = :error,
                            next_attempt_at = now() + make_interval(secs => :delay)
                        WHERE id = :id AND attempts = :attempt AND state = 'PENDING'
                        """).param("error",safeCode).param("delay",delay).param("id",job.id())
                        .param("attempt",job.attempts()).update();
            }
            log.warn("FCM delivery {} failed with {}", job.id(), safeCode);
        }
        return true;
    }

    private boolean enabledFor(String kind) {
        return switch (kind) {
            case "KITCHEN_ORDER" -> gates.isModuleEnabled(KillSwitchService.RESTAURANT);
            case "ORDER_READY", "BILL_REQUESTED" -> gates.isModuleEnabled(KillSwitchService.RESTAURANT)
                    && gates.isModuleEnabled("pos");
            case "CHECKOUT_PAID" -> financialEnabled && gates.isModuleEnabled(KillSwitchService.CHECKOUT);
            default -> false;
        };
    }

    private boolean stillRelevant(Job job) {
        if ("CHECKOUT_PAID".equals(job.kind())) return true;
        UUID resource;
        try { resource = UUID.fromString(job.eventKey().substring(job.eventKey().indexOf(':') + 1)); }
        catch (IllegalArgumentException e) { return false; }
        String sql = switch (job.kind()) {
            case "KITCHEN_ORDER" -> """
                SELECT EXISTS (SELECT 1 FROM push_eligible_subscription s
                    JOIN restaurant_kds_ticket t ON t.merchant_id = s.merchant_id
                    JOIN restaurant_kds_item i ON i.ticket_id = t.id
                    WHERE s.id = :s AND s.app = 'KDS' AND t.id = :r
                      AND t.status IN ('NEW','PREPARING') AND i.item_status = 'NEW'
                      AND (s.station_id IS NULL OR s.station_id = i.station_id))
                """;
            case "ORDER_READY" -> """
                SELECT EXISTS (SELECT 1 FROM push_eligible_subscription s
                    JOIN restaurant_kds_ticket t ON t.merchant_id = s.merchant_id
                    JOIN restaurant_waiter_order w ON w.order_id = t.order_id AND w.merchant_id = t.merchant_id
                    JOIN pos_order o ON o.id = w.order_id
                    WHERE s.id = :s AND s.app = 'WAITER' AND t.id = :r AND t.status = 'READY'
                      AND w.waiter_subject = s.subject AND o.branch_id = s.branch_id)
                """;
            case "BILL_REQUESTED" -> """
                SELECT EXISTS (SELECT 1 FROM push_eligible_subscription s
                    JOIN pos_order o ON o.merchant_id = s.merchant_id
                    JOIN restaurant_waiter_order w ON w.order_id = o.id AND w.merchant_id = o.merchant_id
                    WHERE s.id = :s AND s.app = 'POS' AND o.id = :r
                      AND w.bill_requested_at IS NOT NULL AND o.paid_pesewas < o.total_pesewas
                      AND o.status NOT IN ('PAID','CANCELLED','VOIDED')
                      AND (s.branch_id IS NULL OR s.branch_id = o.branch_id))
                """;
            default -> throw new IllegalStateException("Unknown notification kind");
        };
        return db.sql(sql).param("s",job.subscriptionId()).param("r",resource).query(Boolean.class).single();
    }

    private void finish(Job job, String state, String error) {
        db.sql("""
                UPDATE push_delivery SET state = :state, last_error = :err,
                    sent_at = CASE WHEN :state = 'SENT' THEN now() ELSE sent_at END
                WHERE id = :id AND attempts = :attempt AND state = 'PENDING'
                """).param("state",state).param("err",error).param("id",job.id())
                .param("attempt",job.attempts()).update();
    }
}
