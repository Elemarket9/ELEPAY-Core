package com.elepay.rails;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Test-classpath routing override. Never compiled into the application JAR:
 * isolated tests explicitly select their in-memory network adapter.
 */
@Component
@Primary
@ConditionalOnProperty(name = "elepay.rails.sandbox.enabled", havingValue = "true")
class TestRailRegistry extends RailRegistry {
    TestRailRegistry(List<PaymentRail> adapters,
            @Value("${elepay.rails.default:}") String configuredDefault) {
        super(adapters, configuredDefault);
    }

    @Override
    public String select(String direction, String channel, String network) {
        PaymentRail rail = get("sandbox");
        if (!rail.name().equals(defaultName())) {
            throw new IllegalStateException("Isolated test rail is not configured as the default");
        }
        return rail.name();
    }
}
