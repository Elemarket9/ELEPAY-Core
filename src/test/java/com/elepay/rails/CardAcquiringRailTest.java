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

    @Test
    void nonLiveCallbacksAreNeverAuthoritative() {
        CardAcquiringRail rail = new CardAcquiringRail(
                CardAcquiringRail.ExecutionMode.PARTNER_TEST,
                URI.create("https://acquirer.example.com"), "acq-1", "sec-1", "wh-secret",
                null, null, null, null,
                new ObjectMapper(), null
        );
        assertThat(rail.callbacksAreAuthoritative()).isFalse();
    }

    @Test
    void rejectsExpiredCardTokenWithoutCallingAcquirer() {
        CardTokenVault mockVault = org.mockito.Mockito.mock(CardTokenVault.class);
        var expiredCard = new CardTokenVault.TokenizedCard(
                java.util.UUID.randomUUID(), "tok_card_expired", null,
                "408408******4081", "visa", "debit", "Test Bank",
                1, 2020, "Test User", "active", java.time.OffsetDateTime.now()
        );
        org.mockito.Mockito.when(mockVault.findByToken("tok_card_expired"))
                .thenReturn(java.util.Optional.of(expiredCard));

        CardAcquiringRail rail = new CardAcquiringRail(
                CardAcquiringRail.ExecutionMode.LIVE,
                URI.create("https://acquirer.example.com"), "acq-1", "sec-1", "wh-sec",
                mockVault, null, null, null,
                new ObjectMapper(), null
        );
        PaymentRail.Result res = rail.charge(new PaymentRail.Charge(
                "ref-exp", 5000L, "GHS", "card", null, "tok_card_expired", "a@b.com", "Kwame", "Test", null
        ));
        assertThat(res.status()).isEqualTo(PaymentRail.Status.FAILED);
        assertThat(res.message()).contains("expired");
        org.mockito.Mockito.verify(mockVault, org.mockito.Mockito.never()).decryptPanForRail(org.mockito.Mockito.anyString());
    }
}
