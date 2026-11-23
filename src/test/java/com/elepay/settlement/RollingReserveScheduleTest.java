package com.elepay.settlement;

import com.elepay.PostgresTestBase;
import com.elepay.ledger.Ledger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RollingReserveScheduleTest extends PostgresTestBase {

    @Autowired
    private RollingReserveScheduleService reserveService;

    @Autowired
    private Ledger ledger;

    @Autowired
    private JdbcClient db;

    @Test
    @DisplayName("Pillar 2: Rolling reserve retention haircut, balance isolation, impairment, and maturity release")
    void testRollingReserveLifecycle() {
        UUID merchantId = UUID.randomUUID();
        String merchantCode = String.format("%06d", (int) (Math.random() * 900000) + 100000);

        // 1. Seed merchant profile
        db.sql("""
            INSERT INTO merchant (
                id, legal_name, trading_name, kind, status, merchant_code, contact_phone, created_by
            ) VALUES (
                :id, 'Rolling Reserve Merchant Ltd', 'ReserveMerch', 'merchant', 'active', :code, '+233240001122', 'test'
            )
        """)
        .param("id", merchantId)
        .param("code", merchantCode)
        .update();

        // 2. Open and fund merchant available balance with GHS 10,000.00 (1,000,000 pesewas)
        ledger.open("merchant", merchantId, "available", Ledger.GHS);
        ledger.open("merchant", merchantId, "reserved", Ledger.GHS);
        ledger.openSystem("system:clearing:test_funding", "clearing", Ledger.GHS, true);

        ledger.post(Ledger.Posting.of(
                "fund_merchant",
                "FUND-" + merchantId,
                "MERCHANT_SEED",
                List.of(
                        Ledger.Leg.debit("system:clearing:test_funding", 1_000_000L),
                        Ledger.Leg.credit(Ledger.code("merchant", merchantId, "available", Ledger.GHS), 1_000_000L)
                )
        ));

        assertThat(ledger.balance(Ledger.code("merchant", merchantId, "available", Ledger.GHS))).isEqualTo(1_000_000L);
        assertThat(ledger.balance(Ledger.code("merchant", merchantId, "reserved", Ledger.GHS))).isEqualTo(0L);

        // 3. Create a 10.0% (1000 bps) rolling reserve tranche on GHS 5,000.00 gross payout with 90-day horizon
        var tranche = reserveService.createReserveTranche(
                merchantId,
                Ledger.GHS,
                500_000L, // 5,000 GHS
                1000,     // 10.0%
                90        // 90 days
        );

        assertThat(tranche.reserveAmount()).isEqualTo(50_000L); // 500 GHS retained
        assertThat(tranche.status()).isEqualTo("HELD");

        // Verify ledger: 50,000 moved from available to reserve
        assertThat(ledger.balance(Ledger.code("merchant", merchantId, "available", Ledger.GHS))).isEqualTo(950_000L);
        assertThat(ledger.balance(Ledger.code("merchant", merchantId, "reserved", Ledger.GHS))).isEqualTo(50_000L);

        // 4. Impair GHS 100.00 (10,000 pesewas) from reserve to cover a customer fraud loss
        var impairedTranche = reserveService.impairReserve(tranche.id(), 10_000L, "Fraud chargeback compensation");
        assertThat(impairedTranche.impairedAmount()).isEqualTo(10_000L);
        assertThat(impairedTranche.netRemainingReserve()).isEqualTo(40_000L);
        assertThat(ledger.balance(Ledger.code("merchant", merchantId, "reserved", Ledger.GHS))).isEqualTo(40_000L);

        // 5. Release matured tranches as of 91 days later
        List<RollingReserveScheduleService.ReserveTranche> released = reserveService.releaseMaturedTranches(
                OffsetDateTime.now().plusDays(91)
        ).stream().filter(t -> t.merchantId().equals(merchantId)).toList();

        assertThat(released).hasSize(1);
        var matured = released.get(0);
        assertThat(matured.id()).isEqualTo(tranche.id());
        assertThat(matured.status()).isEqualTo("IMPAIRED"); // Impaired because not full reserve was released
        assertThat(matured.releasedAmount()).isEqualTo(40_000L);

        // Verify ledger balance: remaining 40,000 pesewas returned to available balance
        assertThat(ledger.balance(Ledger.code("merchant", merchantId, "reserved", Ledger.GHS))).isEqualTo(0L);
        assertThat(ledger.balance(Ledger.code("merchant", merchantId, "available", Ledger.GHS))).isEqualTo(990_000L);
    }
}
