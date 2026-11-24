package com.elepay.emtech;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.ApiException;
import com.elepay.common.OutboundUrlGuard;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("EmtechClient Partner Egress Guard Tests")
class EmtechEgressGuardTest {

    @Test
    void rejectsNonAllowlistedPublicHostEvenWhenStrictAllowlistDisabled() throws Exception {
        // strictAllowlistEnabled = false (default merchant webhook mode), but partner allowlist is still enforced
        // allowPrivateHosts = true avoids live DNS lookup in offline test environments while still enforcing partner egress allowlist
        OutboundUrlGuard guard = new OutboundUrlGuard(
                false, true, false,
                Set.of("api.emtech.com"), Set.of(), Set.of("sandbox.emtech.com")
        );
        EmtechSettings settings = new EmtechSettings();
        settings.setEnabled(true);
        settings.setBaseUrl("https://untrusted-partner.example.com");
        settings.setClientId("client-id");
        settings.setClientSecret("client-secret");
        settings.setConnectTimeout(Duration.ofSeconds(2));
        settings.setReadTimeout(Duration.ofSeconds(2));
        settings.setMaxRetries(0);
        settings.setRetryBaseMs(100L);

        ObjectMapper json = new ObjectMapper();
        EmtechClient client = new EmtechClient(settings, guard, json);
        var payload = json.readTree(
                "{\"deviceId\":\"pos-1\",\"deviceType\":\"POS\",\"deviceCity\":\"Accra\",\"deviceRegion\":\"GH-AA\",\"deviceCountry\":\"GH\","
                        + "\"deviceLatitude\":\"5.6\",\"deviceLongitude\":\"-0.18\",\"deviceInserviceDate\":\"2026-01-15T09:00:00Z\","
                        + "\"deviceConnectionType\":\"NETWORK\"}");

        assertThatThrownBy(() -> client.submit("DEVICE", payload))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ApiException) e).code())
                        .isEqualTo("egress_host_not_allowlisted"));
    }
}
