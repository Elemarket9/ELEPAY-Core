package com.elepay.push;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** At-least-once, bounded, multi-instance-safe FCM delivery. The app always refreshes authoritative order data. */
@Component
@ConditionalOnProperty(name = "elepay.push.enabled", havingValue = "true")
class PushDispatcher {
    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);
    private final JdbcClient db;
    private final FirebaseMessaging firebase;
    private final TransactionTemplate tx;
    private final boolean financialEnabled;
    private record Job(UUID id, UUID subscriptionId, String kind, int attempts, long ttlMs) {}

    PushDispatcher(JdbcClient db, FirebaseMessaging firebase, PlatformTransactionManager tm,
            @org.springframework.beans.factory.annotation.Value("${elepay.push.financial-enabled:false}") boolean financialEnabled) {
        this.db = db;
        this.firebase = firebase;
        this.tx = new TransactionTemplate(tm);
        this.financialEnabled = financialEnabled;
    }

    @Scheduled(fixedDelayString = "${elepay.push.poll-ms:2000}")
    void dispatch() {
        // Claim only one delivery per transaction; do not lock checkout/order rows during FCM IO.
        // The SDK uses bounded connect/read timeouts. A crash can duplicate a generic hint, never a sale.
        for (int i = 0; i < 8; i++) {
            if (!Boolean.TRUE.equals(tx.execute(s -> deliverOne()))) break;
        }
    }

    private boolean deliverOne() {
        var claimed = db.sql("""
                SELECT id, subscription_id, kind, attempts,
                       CAST(EXTRACT(EPOCH FROM (expires_at - now())) * 1000 AS bigint) AS ttl_ms
                FROM push_delivery WHERE state = 'PENDING' AND next_attempt_at <= now()
                    AND expires_at > now()
                ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query((rs,n) -> new Job(rs.getObject(1, UUID.class),rs.getObject(2, UUID.class),
                        rs.getString(3),rs.getInt(4),rs.getLong(5))).optional();
        if (claimed.isEmpty()) return false;
        Job job = claimed.get();
        if ("CHECKOUT_PAID".equals(job.kind()) && !financialEnabled) {
            finish(job.id(), "CANCELLED", "FINANCIAL_PUSH_DISABLED");
            return true;
        }
        var token = db.sql("SELECT token FROM push_eligible_subscription WHERE id = :id")
                .param("id",job.subscriptionId()).query(String.class).optional();
        if (token.isEmpty() || job.ttlMs() <= 0) {
            finish(job.id(), "CANCELLED", "REGISTRATION_UNAVAILABLE");
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
                            .setNotification(AndroidNotification.builder()
                                    .setChannelId("elepay_updates").setSound("default").build())
                            .build())
                    .build());
            finish(job.id(),"SENT",null);
        } catch (FirebaseMessagingException ex) {
            MessagingErrorCode code = ex.getMessagingErrorCode();
            String safeCode = code == null ? "FCM_ERROR" : code.name();
            if (code == MessagingErrorCode.UNREGISTERED) {
                // Cascades to all queued deliveries for the invalid installation.
                db.sql("DELETE FROM push_subscription WHERE id = :id").param("id",job.subscriptionId()).update();
            } else if (code == MessagingErrorCode.INVALID_ARGUMENT || code == MessagingErrorCode.SENDER_ID_MISMATCH
                    || job.attempts() >= 5) {
                finish(job.id(),"DEAD",safeCode);
            } else {
                int delay = code == MessagingErrorCode.QUOTA_EXCEEDED ? 60
                        : Math.min(120, 5 << Math.min(job.attempts(), 5));
                db.sql("""
                        UPDATE push_delivery SET attempts = attempts + 1, last_error = :error,
                            next_attempt_at = now() + make_interval(secs => :delay)
                        WHERE id = :id
                        """).param("error",safeCode).param("delay",delay).param("id",job.id()).update();
            }
            log.warn("FCM delivery {} failed with {}", job.id(), safeCode);
        }
        return true;
    }

    private void finish(UUID id, String state, String error) {
        db.sql("""
                UPDATE push_delivery SET state = :state, attempts = attempts + 1, last_error = :err,
                    sent_at = CASE WHEN :state = 'SENT' THEN now() ELSE sent_at END
                WHERE id = :id
                """).param("state",state).param("err",error).param("id",id).update();
    }
}
