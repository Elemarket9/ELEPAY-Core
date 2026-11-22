package com.elepay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {
    int call(RateLimitFilter f, String method, String uri, String ip) throws Exception {
        var req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(ip);
        var res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void cardTestingIsThrottledPerClient() throws Exception {
        var f = new RateLimitFilter(true, 1);
        for (int i = 0; i < 10; i++) assertThat(call(f, "POST", "/v1/rails/card/tokens", "41.66.1.1")).isEqualTo(200);
        assertThat(call(f, "POST", "/v1/rails/card/tokens", "41.66.1.1")).isEqualTo(429);
        // Another client is unaffected, and reading a token is not the throttled action.
        assertThat(call(f, "POST", "/v1/rails/card/tokens", "41.66.1.2")).isEqualTo(200);
        assertThat(call(f, "GET", "/v1/rails/card/tokens/tok_x", "41.66.1.1")).isEqualTo(200);
    }

    @Test
    void recipientLookupsAreThrottledAgainstEnumeration() throws Exception {
        var f = new RateLimitFilter(true, 1);
        for (int i = 0; i < 20; i++) call(f, "POST", "/v1/wallet/recipients/lookup", "41.66.2.2");
        assertThat(call(f, "POST", "/v1/wallet/recipients/lookup", "41.66.2.2")).isEqualTo(429);
    }

    @Test
    void signedRailCallbacksAreNotLimited() throws Exception {
        var f = new RateLimitFilter(true, 1);
        for (int i = 0; i < 500; i++) assertThat(call(f, "POST", "/v1/rails/mtn/callbacks", "10.0.0.1")).isEqualTo(200);
    }
}
