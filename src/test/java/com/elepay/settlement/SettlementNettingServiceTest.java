package com.elepay.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.ApiException;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Algorithm 055: Settlement Netting (SettlementNettingService).
 */
class SettlementNettingServiceTest {

    private SettlementNettingService nettingService;

    private static final String ENTITY_GH = "ELEPAY_GH_LTD";
    private static final String ENTITY_US = "ELEPAY_US_INC";
    private static final String GHS = "GHS";
    private static final String USD = "USD";

    @BeforeEach
    void setUp() {
        nettingService = new SettlementNettingService();
    }

    private SettlementNettingService.GrossTransaction createTx(
            String ref, String entity, String currency, String debtor, String creditor, long amount
    ) {
        return new SettlementNettingService.GrossTransaction(
                UUID.randomUUID(), ref, entity, currency, debtor, creditor, amount, "checkout_proceeds", OffsetDateTime.now()
        );
    }

    @Test
    @DisplayName("055: Bilateral netting correctly offsets mutual obligations between two parties")
    void testBilateralNetting() {
        String merchantA = "merch_alpha";
        String railMtn = "rail_mtn_momo";

        // Merchant A owes MTN 20,000 pesewas (e.g. fees / chargebacks)
        // MTN owes Merchant A 50,000 pesewas (collected proceeds)
        var tx1 = createTx("TX-1", ENTITY_GH, GHS, merchantA, railMtn, 20_000);
        var tx2 = createTx("TX-2", ENTITY_GH, GHS, railMtn, merchantA, 50_000);

        var result = nettingService.calculateBilateralNetting(
                ENTITY_GH, GHS, merchantA, railMtn, List.of(tx1, tx2)
        );

        assertThat(result.grossAtoB()).isEqualTo(20_000L);
        assertThat(result.grossBtoA()).isEqualTo(50_000L);
        assertThat(result.totalGrossVolume()).isEqualTo(70_000L);
        // Net amount from A's perspective: 20,000 - 50,000 = -30,000 (A is net creditor, B is net debtor)
        assertThat(result.netAmount()).isEqualTo(-30_000L);
        assertThat(result.netDebtor()).isEqualTo(railMtn);
        assertThat(result.netCreditor()).isEqualTo(merchantA);
        assertThat(result.netSettlementAmount()).isEqualTo(30_000L);
        assertThat(result.nettingSavings()).isEqualTo(40_000L);
        assertThat(result.nettingEfficiencyPct()).isGreaterThan(57.0);
        assertThat(result.grossRecords()).hasSize(2);
    }

    @Test
    @DisplayName("055: Bilateral netting with equal mutual obligations nets to zero")
    void testBilateralNettingExactMatch() {
        String partyA = "party_a";
        String partyB = "party_b";

        var tx1 = createTx("TX-1", ENTITY_GH, GHS, partyA, partyB, 100_000);
        var tx2 = createTx("TX-2", ENTITY_GH, GHS, partyB, partyA, 100_000);

        var result = nettingService.calculateBilateralNetting(
                ENTITY_GH, GHS, partyA, partyB, List.of(tx1, tx2)
        );

        assertThat(result.netAmount()).isZero();
        assertThat(result.netSettlementAmount()).isZero();
        assertThat(result.netDebtor()).isNull();
        assertThat(result.netCreditor()).isNull();
        assertThat(result.nettingSavings()).isEqualTo(200_000L);
        assertThat(result.nettingEfficiencyPct()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("055: Multilateral netting computes zero-sum positions and optimal minimal transfers")
    void testMultilateralNetting() {
        // 4 participants in circular obligations:
        // A owes B: 100,000
        // B owes C: 70,000
        // C owes D: 50,000
        // D owes A: 80,000
        // Total gross: 300,000
        String a = "participant_A";
        String b = "participant_B";
        String c = "participant_C";
        String d = "participant_D";

        var tx1 = createTx("TX-AB", ENTITY_GH, GHS, a, b, 100_000);
        var tx2 = createTx("TX-BC", ENTITY_GH, GHS, b, c, 70_000);
        var tx3 = createTx("TX-CD", ENTITY_GH, GHS, c, d, 50_000);
        var tx4 = createTx("TX-DA", ENTITY_GH, GHS, d, a, 80_000);

        var batch = nettingService.calculateMultilateralNetting(
                ENTITY_GH, GHS, List.of(tx1, tx2, tx3, tx4)
        );

        assertThat(batch.totalGrossVolume()).isEqualTo(300_000L);
        assertThat(batch.grossTransactionCount()).isEqualTo(4);

        // Verify individual net positions:
        // A: receivable 80,000, payable 100,000 -> net -20,000 (debtor)
        // B: receivable 100,000, payable 70,000 -> net +30,000 (creditor)
        // C: receivable 70,000, payable 50,000 -> net +20,000 (creditor)
        // D: receivable 50,000, payable 80,000 -> net -30,000 (debtor)
        var posA = batch.participantPositions().get(a);
        var posB = batch.participantPositions().get(b);
        var posC = batch.participantPositions().get(c);
        var posD = batch.participantPositions().get(d);

        assertThat(posA.netAmount()).isEqualTo(-20_000L);
        assertThat(posB.netAmount()).isEqualTo(30_000L);
        assertThat(posC.netAmount()).isEqualTo(20_000L);
        assertThat(posD.netAmount()).isEqualTo(-30_000L);

        // Zero-sum multilateral invariant
        long netSum = batch.participantPositions().values().stream()
                .mapToLong(SettlementNettingService.ParticipantPosition::netAmount).sum();
        assertThat(netSum).isZero();

        // Optimized transfers must settle all obligations with fewer transactions
        // Total net settlement volume: 50,000 vs gross 300,000
        assertThat(batch.totalNetSettlementVolume()).isEqualTo(50_000L);
        assertThat(batch.nettingSavings()).isEqualTo(250_000L);
        assertThat(batch.nettingEfficiencyPct()).isGreaterThan(83.0);

        // Verify transfers clear everyone's balance
        Map<String, Long> postSettlementBalance = new HashMap<>();
        for (var pos : batch.participantPositions().values()) {
            postSettlementBalance.put(pos.participantId(), pos.netAmount());
        }

        for (var xfer : batch.settlementTransfers()) {
            postSettlementBalance.merge(xfer.debtorId(), xfer.amount(), Long::sum);
            postSettlementBalance.merge(xfer.creditorId(), -xfer.amount(), Long::sum);
        }

        for (long balance : postSettlementBalance.values()) {
            assertThat(balance).isZero();
        }

        // Full gross transaction audit records preserved
        assertThat(batch.auditRecords()).hasSize(4);
    }

    @Test
    @DisplayName("055: Rejects netting crossing currency boundaries")
    void testRejectsCurrencyBoundaryViolation() {
        var tx1 = createTx("TX-1", ENTITY_GH, GHS, "A", "B", 10_000);
        var tx2 = createTx("TX-2", ENTITY_GH, USD, "B", "A", 1_000);

        assertThatThrownBy(() -> nettingService.calculateBilateralNetting(
                ENTITY_GH, GHS, "A", "B", List.of(tx1, tx2)
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("currency");

        assertThatThrownBy(() -> nettingService.calculateMultilateralNetting(
                ENTITY_GH, GHS, List.of(tx1, tx2)
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("055: Rejects netting crossing legal entity boundaries")
    void testRejectsLegalEntityBoundaryViolation() {
        var tx1 = createTx("TX-1", ENTITY_GH, GHS, "A", "B", 10_000);
        var tx2 = createTx("TX-2", ENTITY_US, GHS, "B", "A", 10_000);

        assertThatThrownBy(() -> nettingService.calculateBilateralNetting(
                ENTITY_GH, GHS, "A", "B", List.of(tx1, tx2)
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("legal entity");

        assertThatThrownBy(() -> nettingService.calculateMultilateralNetting(
                ENTITY_GH, GHS, List.of(tx1, tx2)
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("legal entity");
    }

    @Test
    @DisplayName("055: Rejects self-obligations and invalid amounts")
    void testRejectsInvalidTransactions() {
        assertThatThrownBy(() -> new SettlementNettingService.GrossTransaction(
                UUID.randomUUID(), "TX-SELF", ENTITY_GH, GHS, "A", "A", 10_000, "fee", OffsetDateTime.now()
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("Debtor and creditor cannot be the same");

        assertThatThrownBy(() -> new SettlementNettingService.GrossTransaction(
                UUID.randomUUID(), "TX-ZERO", ENTITY_GH, GHS, "A", "B", 0, "fee", OffsetDateTime.now()
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("055: Handles empty transaction batches gracefully")
    void testEmptyBatch() {
        var bilateral = nettingService.calculateBilateralNetting(ENTITY_GH, GHS, "A", "B", List.of());
        assertThat(bilateral.totalGrossVolume()).isZero();
        assertThat(bilateral.netSettlementAmount()).isZero();

        var multilateral = nettingService.calculateMultilateralNetting(ENTITY_GH, GHS, List.of());
        assertThat(multilateral.totalGrossVolume()).isZero();
        assertThat(multilateral.settlementTransfers()).isEmpty();
    }
}
