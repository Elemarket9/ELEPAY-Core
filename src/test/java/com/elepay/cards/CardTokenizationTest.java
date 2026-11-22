package com.elepay.cards;

import com.elepay.PostgresTestBase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CardTokenizationTest extends PostgresTestBase {

    @Autowired
    private CardIssuingService issuingService;

    @Autowired
    private NetworkTokenService tokenService;

    @Test
    @DisplayName("Network Tokenization Lifecycle - Provision, Cryptograms, Suspend, Resume, Expiry, Revoke")
    void testNetworkTokenLifecycle() {
        UUID customerId = UUID.randomUUID();
        var card = issuingService.issueCard(customerId, null, "VIRTUAL", "VISA");

        // 1. Provision Token
        String requesterId = "40010075001"; // Google Pay TRID
        String deviceId = "android-pixel-9-pro";
        NetworkToken token = tokenService.provisionToken(card.id(), requesterId, deviceId);

        assertThat(token.id()).isNotNull();
        assertThat(token.cardId()).isEqualTo(card.id());
        assertThat(token.status()).isEqualTo(TokenStatus.ACTIVE);
        assertThat(token.tokenPan()).startsWith("480001");
        assertThat(token.tokenPan()).hasSize(16);
        assertThat(CardPanVault.isValidLuhn(token.tokenPan())).isTrue();
        assertThat(token.tokenRequesterId()).isEqualTo(requesterId);
        assertThat(token.deviceId()).isEqualTo(deviceId);
        assertThat(token.expiryMonth()).isEqualTo(card.expiryMonth());
        assertThat(token.expiryYear()).isEqualTo(card.expiryYear());

        // Find by Token PAN
        var foundByPan = tokenService.findByTokenPan(token.tokenPan());
        assertThat(foundByPan).isPresent();
        assertThat(foundByPan.get().id()).isEqualTo(token.id());

        // 2. Dynamic Cryptogram Generation & Verification
        long amount = 15000L; // 150 GHS
        long atc = 42L;
        byte[] unpredictableNumber = new byte[]{0x12, 0x34, 0x56, 0x78};

        var cryptogramResult = tokenService.generateCryptogram(token.id(), amount, "GHS", atc, unpredictableNumber);
        assertThat(cryptogramResult.cryptogram()).isNotBlank();
        assertThat(cryptogramResult.eci()).isEqualTo("05"); // EMV 3DS / Token full auth
        assertThat(cryptogramResult.atc()).isEqualTo(atc);

        // Validation of correct cryptogram passes
        boolean valid = tokenService.validateCryptogram(token.id(), amount, "GHS", atc, unpredictableNumber, cryptogramResult.cryptogram());
        assertThat(valid).isTrue();

        // Validation with altered amount fails
        assertThat(tokenService.validateCryptogram(token.id(), 16000L, "GHS", atc, unpredictableNumber, cryptogramResult.cryptogram())).isFalse();

        // Validation with altered ATC fails
        assertThat(tokenService.validateCryptogram(token.id(), amount, "GHS", 43L, unpredictableNumber, cryptogramResult.cryptogram())).isFalse();

        // Validation with altered nonce fails
        byte[] wrongNonce = new byte[]{0x12, 0x34, 0x56, 0x79};
        assertThat(tokenService.validateCryptogram(token.id(), amount, "GHS", atc, wrongNonce, cryptogramResult.cryptogram())).isFalse();

        // Validation with corrupted cryptogram string fails
        assertThat(tokenService.validateCryptogram(token.id(), amount, "GHS", atc, unpredictableNumber, "invalid-cryptogram")).isFalse();

        // 3. Suspend Token
        tokenService.suspendToken(token.id(), "Device reported missing");
        var suspendedToken = tokenService.getTokenById(token.id()).orElseThrow();
        assertThat(suspendedToken.status()).isEqualTo(TokenStatus.SUSPENDED);

        // Generating cryptogram for suspended token fails
        assertThatThrownBy(() -> tokenService.generateCryptogram(token.id(), amount, "GHS", atc, unpredictableNumber))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot generate cryptogram for non-active token: SUSPENDED");

        // 4. Resume Token
        tokenService.resumeToken(token.id());
        var resumedToken = tokenService.getTokenById(token.id()).orElseThrow();
        assertThat(resumedToken.status()).isEqualTo(TokenStatus.ACTIVE);

        // Cannot resume an already active token
        assertThatThrownBy(() -> tokenService.resumeToken(token.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Token is not in SUSPENDED state: ACTIVE");

        // 5. Expiry Management
        tokenService.updateExpiry(token.id(), 6, 2032);
        var updatedToken = tokenService.getTokenById(token.id()).orElseThrow();
        assertThat(updatedToken.expiryMonth()).isEqualTo(6);
        assertThat(updatedToken.expiryYear()).isEqualTo(2032);

        // Reject invalid expiry inputs
        assertThatThrownBy(() -> tokenService.updateExpiry(token.id(), 13, 2032))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokenService.updateExpiry(token.id(), 1, 2020))
                .isInstanceOf(IllegalArgumentException.class);

        // 6. Revoke Token (Terminal State)
        tokenService.revokeToken(token.id(), "Customer replaced device");
        var revokedToken = tokenService.getTokenById(token.id()).orElseThrow();
        assertThat(revokedToken.status()).isEqualTo(TokenStatus.REVOKED);

        // Cannot suspend a revoked token
        assertThatThrownBy(() -> tokenService.suspendToken(token.id(), "misplaced"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot suspend a REVOKED token");

        // Cannot resume a revoked token
        assertThatThrownBy(() -> tokenService.resumeToken(token.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Token is not in SUSPENDED state: REVOKED");

        // Cannot update expiry of a revoked token
        assertThatThrownBy(() -> tokenService.updateExpiry(token.id(), 8, 2033))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot update expiry of REVOKED token");
    }

    @Test
    @DisplayName("Network Tokenization - Provisioning Fails for Non-Active Card")
    void testProvisioningFailsForNonActiveCard() {
        UUID customerId = UUID.randomUUID();
        // PHYSICAL cards start as PENDING_ACTIVATION
        var card = issuingService.issueCard(customerId, null, "PHYSICAL", "MASTERCARD");
        assertThat(card.status()).isEqualTo("PENDING_ACTIVATION");

        assertThatThrownBy(() -> tokenService.provisionToken(card.id(), "40010030273", "device-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot provision token for non-active card: PENDING_ACTIVATION");
    }
}
