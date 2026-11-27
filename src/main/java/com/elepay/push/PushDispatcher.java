package com.elepay.push;

import com.elepay.identity.KillSwitchService;
import com.elepay.merchanttools.restaurant.RestaurantPushState;
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
    private final RestaurantPushState restaurant;
    private final boolean financialEnabled;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "elepay-fcm-dispatch");
        t.setDaemon(true);
        return t;
    });
    private record Job(UUID id, UUID subscriptionId, String eventKey, String kind, int attempts, long ttlMs) {}
    private record Target(String token, UUID merchant, String subject, UUID branch, UUID station) {
        @Override public String toString() { return "PushTarget[redacted]"; }
    }

    PushDispatcher(JdbcClient db, FirebaseMessaging firebase, KillSwitchService gates, RestaurantPushState restaurant,
            @Value("${elepay.push.financial-enabled:false}") boolean financialEnabled) {
        this.db = db;
        this.firebase = firebase;
        this.gates = gates;
        this.restaurant = restaurant;
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
        var target = db.sql("""
                SELECT s.token, s.merchant_id, s.subject, s.branch_id, s.station_id FROM push_eligible_subscription s
                JOIN push_delivery d ON d.subscription_id = s.id
                WHERE d.id = :id AND d.attempts = :attempt AND d.state = 'PENDING'
                  AND d.expires_at > now()
                """).param("id",job.id()).param("attempt",job.attempts()).query((rs,n) -> new Target(rs.getString(1),rs.getObject(2, UUID.class),
                        rs.getString(3),rs.getObject(4, UUID.class),rs.getObject(5, UUID.class))).optional();
        if (target.isEmpty() || job.ttlMs() <= 0 || !stillRelevant(job, target.get())) {
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
            firebase.send(Message.builder().setToken(target.get().token())
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
                        .param("id",job.subscriptionId()).param("token",target.get().token()).update();
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

    private boolean stillRelevant(Job job, Target target) {
        if ("CHECKOUT_PAID".equals(job.kind())) return true;
        UUID resource;
        try { resource = UUID.fromString(job.eventKey().substring(job.eventKey().indexOf(':') + 1)); }
        catch (IllegalArgumentException e) { return false; }
        return switch (job.kind()) {
            case "KITCHEN_ORDER" -> restaurant.kitchenOrderPending(target.merchant(), resource, target.station());
            case "ORDER_READY" -> restaurant.readyForWaiter(target.merchant(), resource, target.subject(), target.branch());
            case "BILL_REQUESTED" -> restaurant.billOutstanding(target.merchant(), resource, target.branch());
            default -> false;
        };
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
