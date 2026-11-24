package com.elepay.attack;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Attack class E — rate-limit / L7 DoS probes. Drives the real {@code RateLimitFilter} directly
 * (a fresh instance per scenario, multiplier 1, in-memory limiter) so the per-route and per-IP
 * ceilings are deterministic. Asserts floods get 429 + Retry-After while signed rail callbacks are
 * never throttled.
 */
class RateLimitDosTest {

    /** The production filter and its test constructor are package-private; build one reflectively. */
    private static Filter freshFilter() throws Exception {
        Constructor<?> ctor = Class.forName("com.elepay.identity.RateLimitFilter")
                .getDeclaredConstructor(boolean.class, int.class);
        ctor.setAccessible(true);
        return (Filter) ctor.newInstance(true, 1);
    }

    private static MockHttpServletRequest req(String method, String path, String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setRequestURI(path);
        r.setServletPath(path);
        r.setRemoteAddr(ip);
        return r;
    }

    private static int pass(Filter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    @DisplayName("E1: exceeding the per-route public ceiling (120/min) returns 429 with Retry-After")
    void perRouteCeilingReturns429() throws Exception {
        Filter filter = freshFilter();
        String ip = "10.0.0.11";
        for (int i = 0; i < 120; i++) {
            assertThat(pass(filter, req("GET", "/v1/public/checkouts/ref-" + i, ip))).isNotEqualTo(429);
        }
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(req("GET", "/v1/public/checkouts/over-limit", ip), blocked, new MockFilterChain());
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("E2: exceeding the coarse per-IP unauthenticated ceiling (300/min) returns 429")
    void perIpCeilingReturns429() throws Exception {
        Filter filter = freshFilter();
        String ip = "10.0.0.12";
        // A path with no per-route rule, so only the coarse per-IP flood breaker applies.
        for (int i = 0; i < 300; i++) {
            assertThat(pass(filter, req("GET", "/favicon.svg", ip))).isNotEqualTo(429);
        }
        assertThat(pass(filter, req("GET", "/favicon.svg", ip))).isEqualTo(429);
    }

    @Test
    @DisplayName("E3: signed rail callbacks (POST /v1/rails/mtn/callbacks) are NEVER throttled, even under a flood far beyond every ceiling")
    void signedRailCallbacksNotThrottled() throws Exception {
        Filter filter = freshFilter();
        String ip = "10.0.0.13";
        for (int i = 0; i < 2000; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(req("POST", "/v1/rails/mtn/callbacks", ip), response, chain);
            // Never rate-limited, and always forwarded down the chain (money-confirmation hot path).
            assertThat(response.getStatus()).isNotEqualTo(429);
            assertThat(chain.getRequest()).as("callback must be forwarded, not short-circuited").isNotNull();
        }
    }
}
