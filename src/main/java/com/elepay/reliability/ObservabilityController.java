package com.elepay.reliability;

import com.elepay.identity.Actor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller exposing financial observability, throughput, p99 latency targets, and ledger health metrics.
 */
@RestController
@RequestMapping("/v1/ops/metrics")
public class ObservabilityController {
    private final ObservabilityService observability;

    public ObservabilityController(ObservabilityService observability) {
        this.observability = observability;
    }

    @GetMapping("/financial")
    public Map<String, Object> financialMetrics() {
        Actor.current().requireOps();
        return observability.getFinancialMetricsSummary();
    }
}
