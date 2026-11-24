package com.elepay.rails;

import java.net.URI;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardAcquiringRailTest {

    @Test
    void nameIsCardAndSupportsCardAndNfcCollectionsAndRefunds() {
        CardAcquiringRail rail = new CardAcquiringRail(
                CardAcquiringRail.ExecutionMode.LIVE,
                null, null, null, null,
                null, null, null, null,
                new ObjectMapper(), null
        );
        assertThat(rail.name()).isEqualTo("card");
        assertThat(rail.supports("charge", "card", "visa")).isTrue();
        assertThat(rail.supports("charge", "nfc", "mastercard")).isTrue();
        assertThat(rail.supports("refund", "card", "visa")).isTrue();
        assertThat(rail.supports("payout", "card", "visa")).isFalse();
    }

    @Test
    void liveModeFailsClosedWhenUnconfiguredAndNeverFabricatesSuccess() {
        CardAcquiringRail rail = new CardAcquiringRail(
                CardAcquiringRail.ExecutionMode.LIVE,
                null, "", "", "",
                null, null, null, null,
                new ObjectMapper(), null
        );
        PaymentRail.Result res = rail.charge(new PaymentRail.Charge(
                "ref-1", 5000L, "GHS", "card", null, null, "a@b.com", "Kwame", "Test", null
        ));
        assertThat(res.status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
    }

    @Test
    void verifyCallbackFailsClosedWithoutWebhookSecret() {
        CardAcquiringRail rail = new CardAcquiringRail(
                CardAcquiringRail.ExecutionMode.LIVE,
                URI.create("https://acquirer.example.com"), "acq-1", "sec-1", "",
                null, null, null, null,
                new ObjectMapper(), null
        );
        assertThat(rail.callbacksAreAuthoritative()).isFalse();
        assertThatThrownBy(() -> rail.verifyCallback(java.util.Map.of(), "{}"))
                .isInstanceOf(PaymentRail.InvalidCallbackException.class);
    }
}
