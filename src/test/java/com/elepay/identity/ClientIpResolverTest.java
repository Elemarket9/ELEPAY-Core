package com.elepay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {
    @Test
    void forwardedForIsIgnoredUnlessThePeerIsATrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.99");
        assertThat(ClientIpResolver.resolve(request, "")).isEqualTo("127.0.0.1");
        assertThat(ClientIpResolver.resolve(request, "10.0.0.0/8")).isEqualTo("127.0.0.1");
        assertThat(ClientIpResolver.resolve(request, "127.0.0.1")).isEqualTo("198.51.100.99");
    }
}
