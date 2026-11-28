package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayName("Algorithm 017: Card BIN Lookup Service")
class CardBinLookupTest {

    private JdbcClient db;
    private CardBinLookupService binService;

    @BeforeEach
    void setUp() {
        db = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        binService = new CardBinLookupService(db);

        // Pre-populate standard BINs for offline lookup tests
        binService.registerBin(new CardBinLookupService.CardBin(
                "411111", "visa", "debit", "Test Issuer A", "GH", "GHS", "2026.1"
        ));
        binService.registerBin(new CardBinLookupService.CardBin(
                "512345", "mastercard", "debit", "Test Issuer A", "GH", "GHS", "2026.1"
        ));
        binService.registerBin(new CardBinLookupService.CardBin(
                "402234", "visa", "debit", "Test Issuer B", "GH", "GHS", "2026.1"
        ));
        binService.registerBin(new CardBinLookupService.CardBin(
                "900101", "gh-link", "debit", "GhIPSS Gh-Link", "GH", "GHS", "2026.1"
        ));
        binService.registerBin(new CardBinLookupService.CardBin(
                "40000012", "visa", "credit", "Test Issuer C", "US", "USD", "2026.1"
        ));
    }

    @Test
    @DisplayName("Successfully resolves Ghanaian Visa debit BIN")
    void resolvesGhanaianVisaBin() {
        Optional<CardBinLookupService.CardBin> result = binService.lookup("4111110022334455");
        assertThat(result).isPresent();
        CardBinLookupService.CardBin bin = result.get();
        assertThat(bin.bin()).isEqualTo("411111");
        assertThat(bin.scheme()).isEqualTo("visa");
        assertThat(bin.cardType()).isEqualTo("debit");
        assertThat(bin.issuerBank()).isEqualTo("Test Issuer A");
        assertThat(bin.countryCode()).isEqualTo("GH");
        assertThat(bin.currency()).isEqualTo("GHS");
    }

    @Test
    @DisplayName("Successfully resolves Gh-Link domestic card scheme")
    void resolvesGhLinkCard() {
        Optional<CardBinLookupService.CardBin> result = binService.lookup("9001019988776655");
        assertThat(result).isPresent();
        assertThat(result.get().scheme()).isEqualTo("gh-link");
        assertThat(result.get().countryCode()).isEqualTo("GH");
    }

    @Test
    @DisplayName("Prefers 8-digit BIN prefix over 6-digit when available")
    void prefers8DigitBinPrefix() {
        Optional<CardBinLookupService.CardBin> result = binService.lookup("4000001299998888");
        assertThat(result).isPresent();
        assertThat(result.get().bin()).isEqualTo("40000012");
        assertThat(result.get().issuerBank()).isEqualTo("Test Issuer C");
        assertThat(result.get().countryCode()).isEqualTo("US");
    }

    @Test
    @DisplayName("Returns empty for unknown or malformed BIN")
    void returnsEmptyForUnknownBin() {
        assertThat(binService.lookup("123456")).isEmpty();
        assertThat(binService.lookup("")).isEmpty();
        assertThat(binService.lookup(null)).isEmpty();
        assertThat(binService.lookup("abc")).isEmpty();
    }

    @Test
    @DisplayName("Cleans formatted PAN string with dashes and spaces")
    void cleansFormattedPanString() {
        Optional<CardBinLookupService.CardBin> result = binService.lookup("4111-1100 2233-4455");
        assertThat(result).isPresent();
        assertThat(result.get().bin()).isEqualTo("411111");
    }
}
