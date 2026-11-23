package com.elepay.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class OutboundUrlGuardTest {
    final OutboundUrlGuard prod = new OutboundUrlGuard(false, false);

    @ParameterizedTest
    @ValueSource(strings = {
            "https://169.254.169.254/latest/meta-data/",   // cloud metadata
            "https://127.0.0.1/hook", "https://localhost/hook", "https://10.0.0.5/hook", "https://192.168.1.10/",
            "https://172.16.4.4/", "https://100.64.1.1/", "https://0.0.0.0/", "https://[::1]/", "https://[fd00::1]/",
            "https://[::ffff:127.0.0.1]/", "https://redis.internal/", "http://93.184.216.34/hook",
            "https://user:pass@93.184.216.34/", "https://93.184.216.34:6379/", "ftp://93.184.216.34/", "not a url"})
    void internalAndUnsafeDestinationsAreRefused(String url) {
        assertThat(prod.isSafe(url)).as(url).isFalse();
        assertThatThrownBy(() -> prod.validate(url)).isInstanceOf(ApiException.class);
    }

    @Test
    void publicHttpsIsAllowed() {
        assertThat(prod.problem("https://93.184.216.34/webhooks/elepay")).isNull();
        assertThat(prod.problem("https://93.184.216.34:8443/hook")).isNull();
    }

    @Test
    void developmentMayCallLocalReceivers() {
        assertThat(new OutboundUrlGuard(true, true).problem("http://127.0.0.1:9/hook")).isNull();
    }

    @Test
    void benchmarkRangeNamesTheBlockedAddressInsteadOfADnsMiss() {
        String problem = prod.problem("https://198.18.0.10/hook");
        assertThat(problem).contains("198.18.0.10").contains("blocked address range");
        assertThat(problem).doesNotContain("does not resolve");
        assertThatThrownBy(() -> prod.resolveForConnection("198.18.0.10"))
                .isInstanceOf(java.net.UnknownHostException.class)
                .hasMessageContaining("198.18.0.10")
                .hasMessageContaining("blocked address range");
    }

    @Test
    void explicitCidrAllowsAnEgressProxyRangeAndNothingElse() {
        var proxy = new OutboundUrlGuard(false, false, false, OutboundUrlGuard.DEFAULT_STAGE1_ALLOWED_HOSTS,
                java.util.Set.of("198.18.0.0/15"));
        assertThat(proxy.problem("https://198.18.4.8/integration")).isNull();
        assertThat(proxy.problem("https://198.19.255.1/integration")).isNull();
        assertThat(proxy.problem("https://10.1.1.1/hook")).contains("10.1.1.1").contains("blocked address range");
        assertThat(new OutboundUrlGuard(false, false).problem("https://198.18.4.8/integration"))
                .contains("blocked address range");
        assertThatThrownBy(() -> new OutboundUrlGuard(false, false, false, OutboundUrlGuard.DEFAULT_STAGE1_ALLOWED_HOSTS,
                java.util.Set.of("not-a-cidr"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNameThatDoesNotResolveStillSaysSo() {
        assertThat(prod.problem("https://elepay-no-such-host.invalid/hook")).isEqualTo("The host name does not resolve");
    }

    @Test
    void connectionTimeDnsResolutionRejectsPrivateAndTransitionAddresses() throws Exception {
        for (String host : java.util.List.of("127.0.0.1", "169.254.169.254", "168.63.129.16", "64:ff9b::7f00:1", "2002:7f00:1::")) {
            assertThatThrownBy(() -> prod.resolveForConnection(host)).isInstanceOf(java.net.UnknownHostException.class);
        }
        assertThat(prod.resolveForConnection("93.184.216.34")).hasSize(1);
    }
}
