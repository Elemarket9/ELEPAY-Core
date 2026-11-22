package com.elepay.reliability;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Registry managing partner-specific circuit breakers.
 */
@Service
public class PartnerCircuitBreakerRegistry {

    private final JdbcClient db;
    private final ConcurrentHashMap<String, PartnerCircuitBreaker> registry = new ConcurrentHashMap<>();

    public PartnerCircuitBreakerRegistry(JdbcClient db) {
        this.db = db;
    }

    public PartnerCircuitBreaker getOrCreate(String partnerId) {
        return registry.computeIfAbsent(partnerId, id -> new PartnerCircuitBreaker(id, 10, 50.0, 3000, 3, db));
    }

    public PartnerCircuitBreaker getOrCreate(String partnerId, int windowSize, double thresholdPct, long waitDurationMs, int probes) {
        return registry.computeIfAbsent(partnerId, id -> new PartnerCircuitBreaker(id, windowSize, thresholdPct, waitDurationMs, probes, db));
    }
}
