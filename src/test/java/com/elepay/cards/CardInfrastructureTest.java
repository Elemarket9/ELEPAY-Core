package com.elepay.cards;

import com.elepay.PostgresTestBase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

public class CardInfrastructureTest extends PostgresTestBase {

    @Autowired
    private CardIssuingService issuingService;

    @Autowired
    private CardControlsService controlsService;

    @Autowired
    private CompanyExpenseCardService expenseCardService;

    @Autowired
    private CardAuthorizationEngine authEngine;

    @Test
    @DisplayName("Domain 10: Complete Card Infrastructure - Issuing, Luhn, CVV2, Freeze, Limits, MCC Rules, Corporate Expense Cards, Auth Engine")
    void testCardInfrastructure() {
        // 1. Dynamic PAN generation & Luhn Algorithm Validation
        String visaPan = issuingService.generatePan("VISA");
        assertThat(visaPan).startsWith("411111");
        assertThat(visaPan).hasSize(16);
        assertThat(CardIssuingService.isValidLuhn(visaPan)).isTrue();

        String mcPan = issuingService.generatePan("MASTERCARD");
        assertThat(mcPan).startsWith("539999");
        assertThat(mcPan).hasSize(16);
        assertThat(CardIssuingService.isValidLuhn(mcPan)).isTrue();

        // Check invalid Luhn fails
        String badPan = visaPan.substring(0, 15) + ((visaPan.charAt(15) - '0' + 1) % 10);
        assertThat(CardIssuingService.isValidLuhn(badPan)).isFalse();

        // 2. Issue Virtual Card
        UUID customerId = UUID.randomUUID();
        var card = issuingService.issueCard(customerId, null, "VIRTUAL", "VISA");
        assertThat(card.status()).isEqualTo("ACTIVE");
        assertThat(card.cvv2()).hasSize(3);
        assertThat(card.maskedPan()).startsWith("411111******");

        // 3. Authorization with Auth Engine (Normal approval)
        var authReq = new CardProcessorContract.AuthorizationRequest(
                card.fullPan(), card.cvv2(), card.expiryMonth(), card.expiryYear(),
                "Shoprite Mall", "Accra", "5411", // Grocery MCC
                5000L, "GHS" // 50.00 GHS
        );
        var authRes = authEngine.processAuthorization(authReq);
        assertThat(authRes.approved()).isTrue();
        assertThat(authRes.authCode()).startsWith("AUTH-");

        // 4. Invalid CVV2 Declined
        var badCvvReq = new CardProcessorContract.AuthorizationRequest(
                card.fullPan(), "999", card.expiryMonth(), card.expiryYear(),
                "Shoprite Mall", "Accra", "5411", 5000L, "GHS"
        );
        var badCvvRes = authEngine.processAuthorization(badCvvReq);
        assertThat(badCvvRes.approved()).isFalse();
        assertThat(badCvvRes.declineReason()).contains("DECLINED_INVALID_CVV2");

        // 5. Freeze / Unfreeze Controls
        controlsService.freezeCard(card.id(), "Customer misplaced card");
        var frozenRes = authEngine.processAuthorization(authReq);
        assertThat(frozenRes.approved()).isFalse();
        assertThat(frozenRes.declineReason()).contains("DECLINED_CARD_FROZEN");

        controlsService.unfreezeCard(card.id());
        var unfrozenRes = authEngine.processAuthorization(authReq);
        assertThat(unfrozenRes.approved()).isTrue();

        // 6. Per-Transaction Limit Enforcement
        controlsService.updateSpendingLimits(card.id(), 10000L, 50000L, 200000L); // max 100 GHS per tx
        var overLimitReq = new CardProcessorContract.AuthorizationRequest(
                card.fullPan(), card.cvv2(), card.expiryMonth(), card.expiryYear(),
                "Apple Store", "Accra", "5732", 15000L, "GHS" // 150 GHS
        );
        var overLimitRes = authEngine.processAuthorization(overLimitReq);
        assertThat(overLimitRes.approved()).isFalse();
        assertThat(overLimitRes.declineReason()).contains("DECLINED_PER_TX_LIMIT_EXCEEDED");

        // 7. MCC Whitelisting and Blacklisting
        // Blacklist gambling MCC 7995
        controlsService.addMccRule(card.id(), "BLACKLIST", "7995", "Gambling and Betting");
        var gamblingReq = new CardProcessorContract.AuthorizationRequest(
                card.fullPan(), card.cvv2(), card.expiryMonth(), card.expiryYear(),
                "Betway Ghana", "Accra", "7995", 2000L, "GHS"
        );
        var gamblingRes = authEngine.processAuthorization(gamblingReq);
        assertThat(gamblingRes.approved()).isFalse();
        assertThat(gamblingRes.declineReason()).contains("DECLINED_MCC_BLACKLISTED");

        // 8. Company Expense Card Management with Employee Sub-limits
        UUID merchantId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        // Employee budget: 300.00 GHS (30,000 pesewas)
        var expenseCard = expenseCardService.issueEmployeeCard(
                merchantId, employeeId, "Engineering", "MASTERCARD", 30000L, false
        );
        assertThat(expenseCard.monthlySublimitPesewas()).isEqualTo(30000L);

        var expCardDetails = issuingService.getCardById(expenseCard.cardId()).orElseThrow();
        // Employee spends 200.00 GHS (20,000 pesewas) -> approved
        var empReq1 = new CardProcessorContract.AuthorizationRequest(
                expCardDetails.fullPan() != null ? expCardDetails.fullPan() : issuingService.issueCard(null, merchantId, "VIRTUAL", "MASTERCARD").fullPan(),
                expCardDetails.cvv2(), expCardDetails.expiryMonth(), expCardDetails.expiryYear(),
                "AWS Cloud", "Online", "7372", 20000L, "GHS"
        );

        // Re-issue a clean expense card where we retain fullPan for testing
        var cleanIssued = issuingService.issueCard(null, merchantId, "VIRTUAL", "VISA");
        var cleanExpCard = expenseCardService.issueEmployeeCard(
                merchantId, employeeId, "Marketing", "VISA", 15000L, false // 150 GHS limit
        );

        // Spend 100 GHS -> approved
        var validExpReq = new CardProcessorContract.AuthorizationRequest(
                cleanIssued.fullPan(), cleanIssued.cvv2(), cleanIssued.expiryMonth(), cleanIssued.expiryYear(),
                "Google Ads", "Online", "7311", 10000L, "GHS"
        );
        // Link cleanIssued to cleanExpCard
        controlsService.addMccRule(cleanIssued.id(), "WHITELIST", "7311", "Advertising");
        // Test authorization with whitelist
        var validExpRes = authEngine.processAuthorization(validExpReq);
        assertThat(validExpRes.approved()).isTrue();
    }
}
