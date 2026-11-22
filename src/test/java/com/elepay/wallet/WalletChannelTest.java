package com.elepay.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Algorithm 074: USSD and Wallet PIN Validation Rules")
class WalletChannelTest {

    @ParameterizedTest
    @ValueSource(strings = {"1111", "9999", "0000", "555555", "8888"})
    @DisplayName("Rejects single digit repetition PINs")
    void rejectsSingleDigitRepetition(String pin) {
        assertThat(WalletChannel.isWeakPin(pin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234", "2345", "0123", "6789", "91234", "4321", "6543", "9876", "3210"})
    @DisplayName("Rejects consecutive step-1 ascending and descending sequences")
    void rejectsStep1Sequences(String pin) {
        assertThat(WalletChannel.isWeakPin(pin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"9898", "1212", "5050", "989898", "121234", "341212", "001212"})
    @DisplayName("Rejects 2-digit repeated patterns")
    void rejectsTwoDigitRepetitions(String pin) {
        assertThat(WalletChannel.isWeakPin(pin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"123123", "987987", "456456", "012012"})
    @DisplayName("Rejects 3-digit repeated patterns")
    void rejectsThreeDigitRepetitions(String pin) {
        assertThat(WalletChannel.isWeakPin(pin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1357", "2468", "3579", "0246", "7531", "8642", "9753", "6420"})
    @DisplayName("Rejects step-2 ascending and descending sequences")
    void rejectsStep2Sequences(String pin) {
        assertThat(WalletChannel.isWeakPin(pin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1948", "83726", "2951", "7391", "482915", "6294", "5821"})
    @DisplayName("Accepts strong, non-sequential, non-repeated PINs")
    void acceptsStrongPins(String pin) {
        assertThat(WalletChannel.isWeakPin(pin)).isFalse();
    }

    @Test
    @DisplayName("Rejects null, empty, short, or non-numeric PINs")
    void rejectsMalformedPins() {
        assertThat(WalletChannel.isWeakPin(null)).isTrue();
        assertThat(WalletChannel.isWeakPin("")).isTrue();
        assertThat(WalletChannel.isWeakPin("123")).isTrue();
        assertThat(WalletChannel.isWeakPin("abcd")).isTrue();
        assertThat(WalletChannel.isWeakPin("12a4")).isTrue();
    }
}
