package com.elepay.cards;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualCardIssuingAndSpendingControlTest extends PostgresTestBase {

    @Autowired
    private VirtualCardIssuingService virtualCardService;

    @Autowired
    private CardVelocitySpendingControlService velocityService;

    @Autowired
    private CardControlsService controlsService;

    @Autowired
    private JdbcClient db;

    @Test
    @DisplayName("Pillar 3: Virtual card issuance with Luhn validity, custom limits, and zero CVV2 DB storage")
    void testIssueVirtualCardWithCustomLimits() {
        UUID customerId = UUID.randomUUID();
        long perTx = 10_000L;    // 100 GHS
        long daily = 50_000L;    // 500 GHS
        long monthly = 200_000L; // 2000 GHS

        var card = virtualCardService.issueVirtualCard(
                customerId, null, "MASTERCARD", perTx, daily, monthly, false
        );

        assertThat(card).isNotNull();
        assertThat(card.cardId()).isNotNull();
        assertThat(card.status()).isEqualTo("ACTIVE");
        assertThat(card.scheme()).isEqualTo("MASTERCARD");
        assertThat(card.perTxLimitPesewas()).isEqualTo(perTx);
        assertThat(card.dailyLimitPesewas()).isEqualTo(daily);
        assertThat(card.monthlyLimitPesewas()).isEqualTo(monthly);
        assertThat(card.fullPan()).hasSize(16);
        assertThat(card.maskedPan()).startsWith("524128");
        assertThat(card.cvv2()).isNotBlank();
        assertThat(CardIssuingService.isValidLuhn(card.fullPan())).isTrue();

        // Verify PCI DSS compliance: CVV2 must NEVER be stored in the database
        String dbCvv2 = db.sql("SELECT cvv2 FROM card_registry WHERE id = :id")
                .param("id", card.cardId())
                .query(String.class)
                .optional()
                .orElse(null);
        assertThat(dbCvv2).isNull();
    }

    @Test
    @DisplayName("Pillar 3: Burner card issuance for bounded single-use spend")
    void testBurnerCard() {
        UUID customerId = UUID.randomUUID();
        long maxSpend = 5_000L; // 50 GHS

        var burner = virtualCardService.issueBurnerCard(
                new VirtualCardIssuingService.BurnerCardRequest(customerId, maxSpend, 7, "VISA")
        );

        assertThat(burner).isNotNull();
        assertThat(burner.isSingleUse()).isTrue();
        assertThat(burner.perTxLimitPesewas()).isEqualTo(maxSpend);
        assertThat(burner.dailyLimitPesewas()).isEqualTo(maxSpend);
        assertThat(CardIssuingService.isValidLuhn(burner.fullPan())).isTrue();
    }

    @Test
    @DisplayName("Pillar 3: Batch virtual card provisioning for merchant departments")
    void testBatchIssueCards() {
        UUID merchantId = UUID.randomUUID();
        var cards = virtualCardService.batchIssueVirtualCards(
                new VirtualCardIssuingService.BatchIssueRequest(merchantId, "ENGINEERING", 3, 20_000L, "VISA")
        );

        assertThat(cards).hasSize(3);
        assertThat(cards.get(0).cardId()).isNotEqualTo(cards.get(1).cardId());
        assertThat(cards.get(0).fullPan()).isNotEqualTo(cards.get(1).fullPan());
    }

    @Test
    @DisplayName("Pillar 3: Virtual card rotation on compromise")
    void testCardRotation() {
        UUID customerId = UUID.randomUUID();
        var initial = virtualCardService.issueVirtualCard(
                customerId, null, "MASTERCARD", 5_000L, 20_000L, 50_000L, false
        );

        var rotated = virtualCardService.rotateVirtualCard(initial.cardId(), "Suspected Phishing Compromise");

        assertThat(rotated).isNotNull();
        assertThat(rotated.cardId()).isNotEqualTo(initial.cardId());
        assertThat(rotated.fullPan()).isNotEqualTo(initial.fullPan());
        assertThat(rotated.status()).isEqualTo("ACTIVE");
        assertThat(rotated.perTxLimitPesewas()).isEqualTo(initial.perTxLimitPesewas());

        // Verify old card status is REVOKED
        String oldStatus = db.sql("SELECT status FROM card_registry WHERE id = :id")
                .param("id", initial.cardId())
                .query(String.class)
                .single();
        assertThat(oldStatus).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("Pillar 3: Real-time velocity spending control: approval, limit breaches, and MCC blocking")
    void testVelocitySpendingControl() {
        UUID customerId = UUID.randomUUID();
        // Limits: 100 GHS per tx, 150 GHS daily, 500 GHS monthly
        var card = virtualCardService.issueVirtualCard(
                customerId, null, "VISA", 10_000L, 15_000L, 50_000L, false
        );

        // 1. Valid purchase of 80 GHS (within limits) -> Approved
        var eval1 = velocityService.evaluateAndCommitSpend(
                card.cardId(), "ONLINE MARKETPLACE", "ACCRA", "5411", 8_000L, "GHS"
        );
        assertThat(eval1.approved()).isTrue();
        assertThat(eval1.authCode()).startsWith("AUTH-");
        assertThat(eval1.daySpentAfterPesewas()).isEqualTo(8_000L);

        // 2. Single transaction exceeding per-tx limit (120 GHS > 100 GHS) -> Declined
        var eval2 = velocityService.evaluateAndCommitSpend(
                card.cardId(), "APPLE STORE", "ACCRA", "5732", 12_000L, "GHS"
        );
        assertThat(eval2.approved()).isFalse();
        assertThat(eval2.declineReason()).isEqualTo("PER_TX_LIMIT_EXCEEDED");

        // 3. Transaction pushing daily spend over 150 GHS (80 GHS already spent + 80 GHS = 160 GHS) -> Declined
        var eval3 = velocityService.evaluateAndCommitSpend(
                card.cardId(), "SUPERMARKET", "ACCRA", "5411", 8_000L, "GHS"
        );
        assertThat(eval3.approved()).isFalse();
        assertThat(eval3.declineReason()).isEqualTo("DAILY_LIMIT_EXCEEDED");

        // 4. Blacklisted MCC rule (Gambling MCC 7995)
        controlsService.addMccRule(card.cardId(), "BLACKLIST", "7995", "Block gambling transactions");
        var eval4 = velocityService.evaluateAndCommitSpend(
                card.cardId(), "CASINO ONLINE", "ACCRA", "7995", 2_000L, "GHS"
        );
        assertThat(eval4.approved()).isFalse();
        assertThat(eval4.declineReason()).isEqualTo("MCC_BLACKLISTED");

        // 5. Freeze card and attempt spend -> Declined
        controlsService.freezeCard(card.cardId(), "Suspicious activity detected");
        var eval5 = velocityService.evaluateAndCommitSpend(
                card.cardId(), "COFFEE SHOP", "ACCRA", "5814", 1_000L, "GHS"
        );
        assertThat(eval5.approved()).isFalse();
        assertThat(eval5.declineReason()).isEqualTo("CARD_FROZEN");
    }
}
