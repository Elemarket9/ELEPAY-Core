package com.elepay.checkout;

import com.elepay.common.FirebasePushService;
import com.elepay.outbox.Outbox;
import com.elepay.outbox.OutboxConsumer;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Queues an SMS notification and sends a Firebase push notification to each merchant
 * that received money in a paid checkout. SMS rows are unique per (event, channel, recipient),
 * so duplicate deliveries never queue a second message.
 */
@Component
class CheckoutNotifications implements OutboxConsumer {
    private static final Logger log = LoggerFactory.getLogger(CheckoutNotifications.class);

    private final JdbcClient db;
    private final ObjectMapper json;
    private final com.elepay.merchant.MerchantAccess merchants;
    private final FirebasePushService pushService;

    CheckoutNotifications(JdbcClient db, ObjectMapper json, com.elepay.merchant.MerchantAccess merchants) {
        this(db, json, merchants, null);
    }

    @Autowired
    CheckoutNotifications(JdbcClient db, ObjectMapper json, com.elepay.merchant.MerchantAccess merchants,
                          @Autowired(required = false) FirebasePushService pushService) {
        this.merchants = merchants;
        this.db = db;
        this.json = json;
        this.pushService = pushService;
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
            String body = "ELEPAY: payment received GHS %d.%02d ref %s".formatted(amount / 100, amount % 100, reference);
            var phone = merchants.contactPhone(merchant);
            if (phone.isPresent()) {
                db.sql("""
                        INSERT INTO notification (id, event_id, channel, recipient, body)
                        VALUES (:id, :event, 'sms', :phone, :body)
                        ON CONFLICT (event_id, channel, recipient) DO NOTHING""")
                        .param("id", UUID.randomUUID()).param("event", event.id()).param("phone", phone.get())
                        .param("body", body)
                        .update();
            }
            if (pushService != null) {
                try {
                    pushService.sendToMerchant(
                            merchant,
                            "Payment Received",
                            body,
                            Map.of(
                                    "type", "checkout.paid",
                                    "reference", reference,
                                    "amountPesewas", String.valueOf(amount),
                                    "merchantId", merchant.toString()
                            )
                    );
                } catch (Exception e) {
                    log.warn("FCM push dispatch failed for merchant {} ref {}: {}", merchant, reference, e.getMessage());
                }
            }
        }
    }
}
