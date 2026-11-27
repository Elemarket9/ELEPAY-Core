package com.elepay.push;

import com.elepay.outbox.Outbox;
import com.elepay.outbox.OutboxConsumer;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.util.HashSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Separately gated financial notifications: consume only the existing committed checkout.paid event. */
@Component
class CheckoutPushConsumer implements OutboxConsumer {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final boolean enabled;
    private final boolean financial;
    CheckoutPushConsumer(JdbcClient db, ObjectMapper json, @Value("${elepay.push.enabled:false}") boolean enabled,
            @Value("${elepay.push.financial-enabled:false}") boolean financial) {
        this.db = db;
        this.json = json;
        this.enabled = enabled;
        this.financial = financial;
    }
    @Override public String name() { return "firebase-checkout-push"; }
    @Override public boolean accepts(String type) { return enabled && financial && "checkout.paid".equals(type); }
    @Override public void handle(Outbox.Event event) {
        if (!accepts(event.type()) || event.createdAt() == null
                || event.createdAt().isBefore(OffsetDateTime.now().minusMinutes(15))) return;
        // Match the actual paid allocations, not other participants included for event visibility.
        var recipients = new HashSet<UUID>();
        for (JsonNode allocation : json.readTree(event.payload()).path("data").path("allocations")) {
            if (allocation.path("amount").asLong() <= 0) continue;
            recipients.add(UUID.fromString(allocation.path("merchantId").asString()));
        }
        for (UUID merchant : recipients) {
            db.sql("""
                    INSERT INTO push_delivery(subscription_id,event_key,kind)
                    SELECT id,:key,'CHECKOUT_PAID' FROM push_eligible_subscription
                    WHERE merchant_id = :m AND app = 'MERCHANT'
                    ON CONFLICT(subscription_id,event_key) DO NOTHING
                    """).param("key","checkout:" + event.id()).param("m",merchant).update();
        }
    }
}
