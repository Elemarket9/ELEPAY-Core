package com.elepay.reliability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityServiceTest {

    private MeterRegistry registry;
    private ObservabilityService observability;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        observability = new ObservabilityService(registry, null);
    }

    @Test
    @DisplayName("Recording transactions increments throughput, volume and records latency")
    void recordFinancialTransactions() {
        observability.recordTransaction("wallet_transfer", "GHS", 2500, 45, true);
        observability.recordTransaction("wallet_transfer", "GHS", 5000, 60, true);
        observability.recordTransaction("checkout_sale", "GHS", 10000, 120, false);

        assertThat(registry.find("elepay.financial.throughput").counters()).hasSize(2);
        assertThat(registry.find("elepay.financial.volume.minor_units").counters()).hasSize(2);
        assertThat(registry.find("elepay.financial.latency").timers()).hasSize(2);

        Map<String, Object> summary = observability.getFinancialMetricsSummary();
        assertThat(summary).containsKey("ledgerHealthy");
        assertThat(summary).containsKey("p99LatencyMsTarget");
        assertThat((Double) summary.get("throughputTotal")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("TraceContextFilter injects X-Trace-Id and X-Span-Id headers into response")
    void traceContextFilterHeaders() throws Exception {
        TraceContextFilter filter = new TraceContextFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/wallet/balance");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(TraceContextFilter.HEADER_TRACE_ID)).isNotBlank();
        assertThat(response.getHeader(TraceContextFilter.HEADER_SPAN_ID)).isNotBlank();
    }

    @Test
    @DisplayName("TraceContextFilter propagates existing incoming X-Trace-Id")
    void traceContextFilterPropagatesIncomingTrace() throws Exception {
        TraceContextFilter filter = new TraceContextFilter();
        String externalTraceId = "trace-upstream-123456789";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payouts");
        request.addHeader(TraceContextFilter.HEADER_TRACE_ID, externalTraceId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(TraceContextFilter.HEADER_TRACE_ID)).isEqualTo(externalTraceId);
        assertThat(response.getHeader(TraceContextFilter.HEADER_SPAN_ID)).isNotBlank();
    }
}
