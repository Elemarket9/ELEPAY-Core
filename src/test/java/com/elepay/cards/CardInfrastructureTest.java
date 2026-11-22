package com.elepay.cards;

import com.elepay.PostgresTestBase;
import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CardInfrastructureTest extends PostgresTestBase {

    @Autowired
    private CardIssuingService issuingService;

    @Autowired
    private CardControlsService controlsService;

    @Autowired
    private CompanyExpenseCardService expenseCardService;

    @Autowired
    private CardAuthorizationEngine authEngine;

    @Autowired
    private NetworkTokenService networkTokenService;

    @Autowired
    private Ledger ledger;

    @Autowired
    private JdbcClient db;

    private void fundAccount(String ownerType, UUID ownerId, long amountPesewas) {
        ledger.openSystem("system:clearing:test:GHS", "clearing", Ledger.GHS, true);
        ledger.open(ownerType, ownerId, "available", Ledger.GHS);
        ledger.open(ownerType, ownerId, "reserved", Ledger.GHS);
        ledger.post(new Ledger.Posting(
                "test-fund:" + ownerId,
                UUID.randomUUID().toString(),
                "test:funding",
                "Fund test account",
                List.of(
                        Ledger.Leg.debit("system:clearing:test:GHS", amountPesewas),
                        Ledger.Leg.credit(Ledger.code(ownerType, ownerId, "available", Ledger.GHS), amountPesewas)
                ),
                null,
                Map.of()
        ));
    }

    @Test
    @DisplayName("Domain 10: Complete Card Infrastructure - PCI DSS 4.0.1, Envelope Encryption, Ledger Balance Hold, Network Tokens")
    void testCardInfrastructure() {
        // 1. Dynamic PAN generation with Production Ghanaian BINs & Luhn Validation
        String visaPan = issuingService.generatePan("VISA");
        assertThat(visaPan).startsWith(CardIssuingService.PRODUCTION_VISA_BIN);
        assertThat(visaPan).hasSize(16);
        assertThat(CardIssuingService.isValidLuhn(visaPan)).isTrue();

        String mcPan = issuingService.generatePan("MASTERCARD");
        assertThat(mcPan).startsWith(CardIssuingService.PRODUCTION_MASTERCARD_BIN);
        assertThat(mcPan).hasSize(16);
        assertThat(CardIssuingService.isValidLuhn(mcPan)).isTrue();

        // Check invalid Luhn fails
        String badPan = visaPan.substring(0, 15) + ((visaPan.charAt(15) - '0' + 1) % 10);
        assertThat(CardIssuingService.isValidLuhn(badPan)).isFalse();

        // Verify test BINs (411111, 539999) are strictly prohibited
        assertThatThrownBy(() -> CardPanVault.assertProductionBin("411111"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Test BIN prohibited");
        assertThatThrownBy(() -> CardPanVault.assertProductionBin("539999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Test BIN prohibited");

        // 2. Issue Virtual Card with PCI DSS 4.0.1 Envelope Encryption
        UUID customerId = UUID.randomUUID();
        // Prefund customer available balance: 1,000.00 GHS (100,000 pesewas)
        fundAccount("customer", customerId, 100000L);

        var card = issuingService.issueCard(customerId, null, "VIRTUAL", "VISA");
        assertThat(card.status()).isEqualTo("ACTIVE");
        assertThat(card.cvv2()).hasSize(3);
        assertThat(card.maskedPan()).startsWith(CardIssuingService.PRODUCTION_VISA_BIN.substring(0, 6) + "******");

        // Verify Database Security Invariants:
        // A. CVV2 column in card_registry is strictly NULL (never stored)
        var rowSecrets = db.sql("SELECT cvv2, encrypted_pan FROM card_registry WHERE id = ?")
                .param(card.id())
                .query((rs, rowNum) -> Map.of(
                        "cvv2", rs.getString("cvv2") == null ? "NULL" : rs.getString("cvv2"),
                        "encrypted_pan", rs.getString("encrypted_pan")
                )).single();
        assertThat(rowSecrets.get("cvv2")).isEqualTo("NULL");

        // B. AES-256-GCM Envelope Encryption (begins with v1:env:, does not use pseudo ENC:)
        String encryptedPan = rowSecrets.get("encrypted_pan");
        assertThat(encryptedPan).startsWith("v1:env:").doesNotContain("ENC:").doesNotContain(card.fullPan());

        // C. Decryption succeeds and matches fullPan
        String decryptedPan = issuingService.decryptPan(card.id(), encryptedPan);
        assertThat(decryptedPan).isEqualTo(card.fullPan());

        // 3. Authorization with Authoritative Ledger Balance Hold Reservation
        long initialAvailable = ledger.balance(Ledger.code("customer", customerId, "available", "GHS"));
        long initialReserved = ledger.balance(Ledger.code("customer", customerId, "reserved", "GHS"));
        assertThat(initialAvailable).isEqualTo(100000L);
        assertThat(initialReserved).isEqualTo(0L);

        var authReq = new CardProcessorContract.AuthorizationRequest(
                card.fullPan(), card.cvv2(), card.expiryMonth(), card.expiryYear(),
                "Shoprite Mall", "Accra", "5411", // Grocery MCC
                5000L, "GHS" // 50.00 GHS
        );
        var authRes = authEngine.processAuthorization(authReq);
        assertThat(authRes.approved()).isTrue();
        assertThat(authRes.authCode()).startsWith("AUTH-");

        // Verify atomic hold placed on Ledger: available decremented by 5000, reserved incremented by 5000
        long postAvailable = ledger.balance(Ledger.code("customer", customerId, "available", "GHS"));
        long postReserved = ledger.balance(Ledger.code("customer", customerId, "reserved", "GHS"));
        assertThat(postAvailable).isEqualTo(95000L);
        assertThat(postReserved).isEqualTo(5000L);

        // 4. Insufficient Funds Declined by Authoritative Ledger
        var overBalanceReq = new CardProcessorContract.AuthorizationRequest(
                card.fullPan(), card.cvv2(), card.expiryMonth(), card.expiryYear(),
                "Luxury Electronics", "Accra", "5732",
                200000L, "GHS" // 2,000 GHS exceeds available 950 GHS
        );
        var overBalanceRes = authEngine.processAuthorization(overBalanceReq);
        assertThat(overBalanceRes.approved()).isFalse();
        assertThat(overBalanceRes.responseCode()).isEqualTo("51");
        assertThat(overBalanceRes.declineReason()).contains("DECLINED_INSUFFICIENT_FUNDS");

        // 5. Invalid CVV2 Declined (dynamically verified via secret CVK)
        var badCvvReq = new CardProcessorContract.AuthorizationRequest(
                card.fullPan(), "999", card.expiryMonth(), card.expiryYear(),
                "Shoprite Mall", "Accra", "5411", 5000L, "GHS"
        );
        var badCvvRes = authEngine.processAuthorization(badCvvReq);
        assertThat(badCvvRes.approved()).isFalse();
        assertThat(badCvvRes.declineReason()).contains("DECLINED_INVALID_CVV2");

        // 6. Freeze / Unfreeze Controls
        controlsService.freezeCard(card.id(), "Customer misplaced card");
        var frozenRes = authEngine.processAuthorization(authReq);
        assertThat(frozenRes.approved()).isFalse();
        assertThat(frozenRes.declineReason()).contains("DECLINED_CARD_FROZEN");

        controlsService.unfreezeCard(card.id());
        var unfrozenRes = authEngine.processAuthorization(authReq);
        assertThat(unfrozenRes.approved()).isTrue();

        // 7. Per-Transaction Limit Enforcement
        controlsService.updateSpendingLimits(card.id(), 10000L, 50000L, 200000L); // max 100 GHS per tx
        var overLimitReq = new CardProcessorContract.AuthorizationRequest(
                card.fullPan(), card.cvv2(), card.expiryMonth(), card.expiryYear(),
                "Apple Store", "Accra", "5732", 15000L, "GHS" // 150 GHS
        );
        var overLimitRes = authEngine.processAuthorization(overLimitReq);
        assertThat(overLimitRes.approved()).isFalse();
        assertThat(overLimitRes.declineReason()).contains("DECLINED_PER_TX_LIMIT_EXCEEDED");

        // 8. MCC Whitelisting and Blacklisting
        controlsService.addMccRule(card.id(), "BLACKLIST", "7995", "Gambling and Betting");
        var gamblingReq = new CardProcessorContract.AuthorizationRequest(
                card.fullPan(), card.cvv2(), card.expiryMonth(), card.expiryYear(),
                "Betway Ghana", "Accra", "7995", 2000L, "GHS"
        );
        var gamblingRes = authEngine.processAuthorization(gamblingReq);
        assertThat(gamblingRes.approved()).isFalse();
        assertThat(gamblingRes.declineReason()).contains("DECLINED_MCC_BLACKLISTED");

        // 9. Company Expense Card Management with Employee Sub-limits backed by Merchant Ledger
        UUID merchantId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        fundAccount("merchant", merchantId, 500000L); // 5,000 GHS merchant balance

        var cleanIssued = issuingService.issueCard(null, merchantId, "VIRTUAL", "VISA");
        var cleanExpCard = expenseCardService.issueEmployeeCard(
                merchantId, employeeId, "Marketing", "VISA", 15000L, false // 150 GHS sublimit
        );
        // Link cleanIssued to cleanExpCard
        controlsService.addMccRule(cleanIssued.id(), "WHITELIST", "7311", "Advertising");

        var validExpReq = new CardProcessorContract.AuthorizationRequest(
                cleanIssued.fullPan(), cleanIssued.cvv2(), cleanIssued.expiryMonth(), cleanIssued.expiryYear(),
                "Google Ads", "Online", "7311", 10000L, "GHS"
        );
        var validExpRes = authEngine.processAuthorization(validExpReq);
        assertThat(validExpRes.approved()).isTrue();

        // 10. EMVCo Network Tokenization Boundary, Cryptograms, and Lifecycle
        NetworkToken networkToken = networkTokenService.provisionToken(
                card.id(), "40010030273", "device-iphone-16-pro"
        );
        assertThat(networkToken.status()).isEqualTo(TokenStatus.ACTIVE);
        assertThat(networkToken.tokenPan()).hasSize(16);
        assertThat(CardPanVault.isValidLuhn(networkToken.tokenPan())).isTrue();

        // Dynamic EMVCo Cryptogram generation & validation
        byte[] nonce = new byte[]{0x01, 0x02, 0x03, 0x04};
        var cryptogram = networkTokenService.generateCryptogram(
                networkToken.id(), 5000L, "GHS", 1L, nonce
        );
        assertThat(cryptogram.cryptogram()).isNotBlank();
        assertThat(cryptogram.eci()).isEqualTo("05");

        boolean isValidCrypto = networkTokenService.validateCryptogram(
                networkToken.id(), 5000L, "GHS", 1L, nonce, cryptogram.cryptogram()
        );
        assertThat(isValidCrypto).isTrue();

        // Token Authorization through CardAuthorizationEngine
        var tokenAuthReq = new CardProcessorContract.AuthorizationRequest(
                networkToken.tokenPan(), null, networkToken.expiryMonth(), networkToken.expiryYear(),
                "Bolt Ride", "Accra", "4121", 2000L, "GHS"
        );
        var tokenAuthRes = authEngine.processAuthorization(tokenAuthReq);
        assertThat(tokenAuthRes.approved()).isTrue();

        // Suspend Token
        networkTokenService.suspendToken(networkToken.id(), "Cardholder phone misplaced");
        var suspendedAuthRes = authEngine.processAuthorization(tokenAuthReq);
        assertThat(suspendedAuthRes.approved()).isFalse();
        assertThat(suspendedAuthRes.declineReason()).contains("DECLINED_TOKEN_SUSPENDED");

        // Resume Token
        networkTokenService.resumeToken(networkToken.id());
        var resumedAuthRes = authEngine.processAuthorization(tokenAuthReq);
        assertThat(resumedAuthRes.approved()).isTrue();

        // Expiry Update
        networkTokenService.updateExpiry(networkToken.id(), 11, 2031);
        var updatedToken = networkTokenService.getTokenById(networkToken.id()).orElseThrow();
        assertThat(updatedToken.expiryMonth()).isEqualTo(11);
        assertThat(updatedToken.expiryYear()).isEqualTo(2031);

        // Revoke Token (terminal lifecycle state)
        networkTokenService.revokeToken(networkToken.id(), "Card reissued");
        var revokedAuthRes = authEngine.processAuthorization(tokenAuthReq);
        assertThat(revokedAuthRes.approved()).isFalse();
        assertThat(revokedAuthRes.declineReason()).contains("DECLINED_TOKEN_REVOKED");

        // Cannot resume a revoked token
        assertThatThrownBy(() -> networkTokenService.resumeToken(networkToken.id()))
                .isInstanceOf(IllegalStateException.class);
    }
}
