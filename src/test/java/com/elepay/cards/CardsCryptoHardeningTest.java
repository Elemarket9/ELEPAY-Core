package com.elepay.cards;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardsCryptoHardeningTest {

    @Test
    void cardTokenVaultFailsClosedWithoutMasterKey() {
        assertThatThrownBy(() -> new CardTokenVault(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("elepay.vault.master-key");
        assertThatThrownBy(() -> new CardTokenVault("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void cardPanVaultFailsClosedWithoutMasterKey() {
        assertThatThrownBy(() -> new CardPanVault(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("elepay.vault.master-key");
    }

    @Test
    void networkTokenServiceFailsClosedWithoutKeys() {
        assertThatThrownBy(() -> new NetworkTokenService(null, "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("elepay.cards.network-token-key");
    }

    @Test
    void cardTokenVaultHidesEnvelopeAndRawTokenPanFromPublicDtos() {
        CardTokenVault vault = new CardTokenVault("0123456789abcdef0123456789abcdef");
        var card = vault.tokenize(new CardTokenVault.CardInput(
                "4111111111111111", 12, 2030, "Kwame Mensah", "123", UUID.randomUUID()
        ));
        assertThat(card.token()).startsWith("tok_card_");
        assertThat(card.maskedPan()).isEqualTo("411111******1111");

        var netCard = vault.tokenizeNetworkToken(new CardTokenVault.NetworkTokenInput(
                card.id(), "4800012345678901", "40010075001", "pixel-9", 12, 2030
        ));
        assertThat(netCard.maskedTokenPan()).isEqualTo("480001******8901");
    }
}
