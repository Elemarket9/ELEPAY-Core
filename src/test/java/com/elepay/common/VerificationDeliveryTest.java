package com.elepay.common;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

class VerificationDeliveryTest {
    private static WireMockServer wireMock;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(0);
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void dispatchesBothEmailAndSmsViaConfiguredProviders() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo("/sms")).willReturn(okJson("{\"status\":\"ok\"}")));
        wireMock.stubFor(post(urlEqualTo("/email")).willReturn(okJson("{\"id\":\"msg_1\"}")));

        String base = "http://127.0.0.1:" + wireMock.port();
        var env = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        env.setActiveProfiles("prod");

        var delivery = new VerificationDelivery(
                RestClient.builder(),
                env,
                "arkesel", base + "/sms", "sms-key-123", "", "ELEPAY",
                "resend", base + "/email", "email-key-456", "no-reply@elepay.gh"
        );

        var receipt = delivery.sendBoth(
                "ama@elepay.gh", "111222",
                "+233241234567", "333444",
                "Verify ELEPAY", "sign-in");

        assertThat(receipt.emailSent()).isTrue();
        assertThat(receipt.smsSent()).isTrue();
        assertThat(receipt.emailChannel()).isEqualTo("resend");
        assertThat(receipt.smsChannel()).isEqualTo("arkesel");
        assertThat(receipt.maskedEmail()).isEqualTo("a***a@elepay.gh");
        assertThat(receipt.maskedPhone()).isEqualTo("+23****567");

        wireMock.verify(postRequestedFor(urlEqualTo("/sms"))
                .withHeader("api-key", equalTo("sms-key-123"))
                .withRequestBody(containing("333444"))
                .withRequestBody(containing("+233241234567")));
        wireMock.verify(postRequestedFor(urlEqualTo("/email"))
                .withHeader("Authorization", equalTo("Bearer email-key-456"))
                .withRequestBody(containing("111222"))
                .withRequestBody(containing("ama@elepay.gh")));
    }

    @Test
    void failsClosedInProductionWhenProviderIsLog() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");

        var delivery = new VerificationDelivery(
                RestClient.builder(),
                env,
                "log", "", "", "", "ELEPAY",
                "log", "", "", "no-reply@elepay.gh"
        );

        assertThatThrownBy(() -> delivery.sendBoth(
                "ama@elepay.gh", "111222",
                "+233241234567", "333444",
                "Verify ELEPAY", "sign-in"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production email verification provider must be configured");
    }

    @Test
    void recordsBothDispatchedCodesInNonProdLogMode() {
        var env = new MockEnvironment();
        env.setActiveProfiles("test");

        var delivery = new VerificationDelivery(
                RestClient.builder(),
                env,
                "log", "", "", "", "ELEPAY",
                "log", "", "", "no-reply@elepay.gh"
        );

        var receipt = delivery.sendBoth(
                "owner@elepay.gh", "654321",
                "+233241234567", "123456",
                "Verify ELEPAY", "registration");

        assertThat(receipt.emailSent()).isTrue();
        assertThat(receipt.smsSent()).isTrue();
        assertThat(delivery.lastDispatchedForTest("email", "owner@elepay.gh").code()).isEqualTo("654321");
        assertThat(delivery.lastDispatchedForTest("sms", "+233241234567").code()).isEqualTo("123456");
    }
}
