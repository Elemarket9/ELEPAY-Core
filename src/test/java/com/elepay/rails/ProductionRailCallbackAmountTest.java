package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.elepay.common.CallbackAuthenticity;
import com.elepay.common.HmacVerification;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Production Rail Callback Major-Unit Amount Parsing Tests")
class ProductionRailCallbackAmountTest {
    private static final String SECRET = "test-webhook-secret-key";
    private final ObjectMapper json = new ObjectMapper();
    private NetworkHealthDetector health;

    @BeforeEach
    void setUp() {
        CallbackAuthenticity.resetForTesting();
        health = mock(NetworkHealthDetector.class);
    }

    private Map<String, String> signedHeaders(String sigHeader, String body) {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String sig = HmacVerification.computeHex(SECRET, ts + "." + nonce + "." + body);
        return Map.of(
                "x-callback-timestamp", ts,
                "x-callback-nonce", nonce,
                sigHeader, sig
        );
    }

    @Test
    void telecelParsesMajorUnitDecimalAmountAndRejectsInvalid() {
        var rail = new TelecelCashProductionRail(
                json, health, URI.create("https://api.telecel.com.gh"),
                new TelecelCashProductionRail.Credentials("c", "s", "m", SECRET),
                HttpClient.newHttpClient());

        String validBody = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"TEL-1\",\"status\":\"SUCCESSFUL\",\"amount\":\"10.50\",\"currency\":\"GHS\",\"railReference\":\"TEL-R1\"}}";
        var cb = rail.verifyCallback(signedHeaders("x-telecel-signature", validBody), validBody);
        assertThat(cb.result().amount()).isEqualTo(1050L);
        assertThat(cb.result().status()).isEqualTo(PaymentRail.Status.SUCCEEDED);

        for (String badAmount : new String[]{"\"0.00\"", "\"-5.00\"", "\"not-a-number\"", "null"}) {
            String badBody = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"TEL-1\",\"status\":\"SUCCESSFUL\",\"amount\":" + badAmount + ",\"currency\":\"GHS\"}}";
            assertThatThrownBy(() -> rail.verifyCallback(signedHeaders("x-telecel-signature", badBody), badBody))
                    .isInstanceOf(PaymentRail.InvalidCallbackException.class);
        }
    }

    @Test
    void airtelTigoParsesMajorUnitDecimalAmountAndRejectsInvalid() {
        var rail = new AirtelTigoProductionRail(
                json, health, URI.create("https://api.airteltigo.com.gh"),
                new AirtelTigoProductionRail.Credentials("c", "s", "m", SECRET),
                HttpClient.newHttpClient());

        String validBody = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"AT-1\",\"status\":\"COMPLETED\",\"amount\":\"25.75\",\"currency\":\"GHS\",\"railReference\":\"AT-R1\"}}";
        var cb = rail.verifyCallback(signedHeaders("x-airteltigo-signature", validBody), validBody);
        assertThat(cb.result().amount()).isEqualTo(2575L);
        assertThat(cb.result().status()).isEqualTo(PaymentRail.Status.SUCCEEDED);

        for (String badAmount : new String[]{"\"0\"", "\"-1.25\"", "\"bad\"", "\"\""}) {
            String badBody = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"AT-1\",\"status\":\"COMPLETED\",\"amount\":" + badAmount + ",\"currency\":\"GHS\"}}";
            assertThatThrownBy(() -> rail.verifyCallback(signedHeaders("x-airteltigo-signature", badBody), badBody))
                    .isInstanceOf(PaymentRail.InvalidCallbackException.class);
        }
    }

    @Test
    void ghipssParsesMajorUnitDecimalAmountAndRejectsInvalid() {
        var rail = new GhipssInstantPayRail(
                json, health, URI.create("https://gip.ghipss.com"),
                new GhipssInstantPayRail.Credentials("c", "s", "inst", SECRET),
                HttpClient.newHttpClient());

        String validBody = "{\"event\":\"payout.success\",\"data\":{\"reference\":\"GIP-1\",\"status\":\"00\",\"amount\":\"100.05\",\"currency\":\"GHS\",\"railReference\":\"GIP-R1\"}}";
        var cb = rail.verifyCallback(signedHeaders("x-ghipss-signature", validBody), validBody);
        assertThat(cb.result().amount()).isEqualTo(10005L);
        assertThat(cb.result().status()).isEqualTo(PaymentRail.Status.SUCCEEDED);

        for (String badAmount : new String[]{"\"0.00\"", "\"-10.00\"", "\"abc\""}) {
            String badBody = "{\"event\":\"payout.success\",\"data\":{\"reference\":\"GIP-1\",\"status\":\"00\",\"amount\":" + badAmount + ",\"currency\":\"GHS\"}}";
            assertThatThrownBy(() -> rail.verifyCallback(signedHeaders("x-ghipss-signature", badBody), badBody))
                    .isInstanceOf(PaymentRail.InvalidCallbackException.class);
        }
    }
}
