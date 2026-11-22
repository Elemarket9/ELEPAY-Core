package com.elepay.cards;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardVaultSecurityTest {

    private final CardPanVault vault = new CardPanVault("dGVzdC1vbmx5LWNhcmQtdmF1bHQta2V5LTMyLWJ5dGU=");

    @Test
    @DisplayName("Envelope Encryption - Roundtrip PAN Encryption and Decryption")
    void testEnvelopeEncryptionRoundtrip() {
        UUID cardId = UUID.randomUUID();
        String pan = "4022340123456789";

        String encrypted = vault.encryptPan(cardId, pan);
        assertThat(encrypted).startsWith("v1:env:");
        assertThat(encrypted).doesNotContain(pan);
        assertThat(encrypted).doesNotContain("ENC:");

        String decrypted = vault.decryptPan(cardId, encrypted);
        assertThat(decrypted).isEqualTo(pan);
    }

    @Test
    @DisplayName("Cryptographic Integrity - AAD Card ID Mismatch Fails Decryption")
    void testAadMismatchFailsDecryption() {
        UUID card1 = UUID.randomUUID();
        UUID card2 = UUID.randomUUID();
        String pan = "4022340123456789";

        String encryptedForCard1 = vault.encryptPan(card1, pan);

        // Attempting to decrypt card1's ciphertext using card2's context must fail due to AAD tag mismatch
        assertThatThrownBy(() -> vault.decryptPan(card2, encryptedForCard1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Envelope decryption failed or authentication tag mismatch");
    }

    @Test
    @DisplayName("Cryptographic Integrity - Tampered Ciphertext Fails Authentication Tag Verification")
    void testTamperedCiphertextFails() {
        UUID cardId = UUID.randomUUID();
        String pan = "5241280123456784";

        String encrypted = vault.encryptPan(cardId, pan);
        // Tamper with last character of base64 ciphertext
        char lastChar = encrypted.charAt(encrypted.length() - 1);
        char tamperedChar = lastChar == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, encrypted.length() - 1) + tamperedChar;

        assertThatThrownBy(() -> vault.decryptPan(cardId, tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PCI DSS Policy - Rejects Insecure Pseudo-Encryption ENC: Prefix")
    void testRejectsInsecureEncPrefix() {
        UUID cardId = UUID.randomUUID();
        String legacyEnc = "ENC:4022340123456789";

        assertThatThrownBy(() -> vault.decryptPan(cardId, legacyEnc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid or unauthenticated envelope ciphertext");
    }

    @Test
    @DisplayName("PCI DSS 4.0.1 - Dynamic CVV2 Derivation & Constant-Time Verification")
    void testCvv2DerivationAndVerification() {
        String pan = "4022340123456789";
        int month = 12;
        int year = 2028;

        String cvv2 = vault.computeCvv2(pan, month, year);
        assertThat(cvv2).hasSize(3);
        assertThat(cvv2).matches("^\\d{3}$");

        // Verification with matching CVV2 succeeds
        assertThat(vault.verifyCvv2(pan, month, year, cvv2)).isTrue();

        // Verification with wrong CVV2 fails
        assertThat(vault.verifyCvv2(pan, month, year, "000".equals(cvv2) ? "001" : "000")).isFalse();
        assertThat(vault.verifyCvv2(pan, month, year, null)).isFalse();
        assertThat(vault.verifyCvv2(pan, month, year, "")).isFalse();
        assertThat(vault.verifyCvv2(pan, month, year, "12")).isFalse();

        // Verification with wrong expiry date fails
        assertThat(vault.verifyCvv2(pan, 11, year, cvv2)).isFalse();
        assertThat(vault.verifyCvv2(pan, month, 2029, cvv2)).isFalse();
    }

    @Test
    @DisplayName("Production BIN Policy - Test BINs Prohibited, Production BINs Permitted")
    void testBinValidation() {
        assertThatThrownBy(() -> CardPanVault.assertProductionBin("411111"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Test BIN prohibited in production: 411111");

        assertThatThrownBy(() -> CardPanVault.assertProductionBin("539999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Test BIN prohibited in production: 539999");

        assertThatThrownBy(() -> CardPanVault.assertProductionBin("400000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Test BIN prohibited in production: 400000");

        // Ghanaian Production BINs pass without exception
        CardPanVault.assertProductionBin("402234"); // Ecobank Ghana Visa
        CardPanVault.assertProductionBin("524128"); // Stanbic Bank Ghana Mastercard
        CardPanVault.assertProductionBin("428421"); // Stanbic Bank Ghana Visa
    }

    @Test
    @DisplayName("Luhn Algorithm Checksum and Check Digit Calculation")
    void testLuhnAlgorithm() {
        // Check digit calculation
        String prefix15 = "402234012345678";
        int checkDigit = CardPanVault.calculateLuhnCheckDigit(prefix15);
        String validPan = prefix15 + checkDigit;
        assertThat(CardPanVault.isValidLuhn(validPan)).isTrue();

        // Mutated check digit fails
        String invalid = prefix15 + ((checkDigit + 1) % 10);
        assertThat(CardPanVault.isValidLuhn(invalid)).isFalse();

        // Length boundaries
        assertThat(CardPanVault.isValidLuhn(null)).isFalse();
        assertThat(CardPanVault.isValidLuhn("12345")).isFalse();
        assertThat(CardPanVault.isValidLuhn("12345678901234567890")).isFalse();
    }
}
