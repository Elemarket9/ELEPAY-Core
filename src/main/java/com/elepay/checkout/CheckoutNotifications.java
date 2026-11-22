package com.elepay.checkout;

import com.elepay.outbox.Outbox;
import com.elepay.outbox.OutboxConsumer;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Queues an SMS notification to each merchant that received money in a paid
 * checkout. Rows are unique per (event, channel, recipient), so duplicate
 * deliveries never queue a second message. An SMS gateway drains the queue.
 */
@Component
class CheckoutNotifications implements OutboxConsumer {
    private final JdbcClient db;
    private final ObjectMapper json;

    private final com.elepay.merchant.MerchantAccess merchants;

    CheckoutNotifications(JdbcClient db, ObjectMapper json, com.elepay.merchant.MerchantAccess merchants) {
        this.merchants = merchants;
        this.db = db;
        this.json = json;
    }

    @Override public String name() { return "checkout-notifications"; }

    @Override public boolean accepts(String eventType) { return eventType.equals("checkout.paid"); }

    @Override
    public void handle(Outbox.Event event) {
        JsonNode data = json.readTree(event.payload()).path("data");
        String reference = data.path("reference").asString();
        for (JsonNode allocation : data.path("allocations")) {
            UUID merchant = UUID.fromString(allocation.path("merchantId").asString());
            long amount = allocation.path("amount").asLong();
            var phone = merchants.contactPhone(merchant);
            if (phone.isEmpty()) continue;
            db.sql("""
                    INSERT INTO notification (id, event_id, channel, recipient, body)
                    VALUES (:id, :event, 'sms', :phone, :body)
                    ON CONFLICT (event_id, channel, recipient) DO NOTHING""")
                    .param("id", UUID.randomUUID()).param("event", event.id()).param("phone", phone.get())
                    .param("body", "ELEPAY: payment received GHS %d.%02d ref %s".formatted(amount / 100, amount % 100, reference))
                    .update();
        }
    }
}
