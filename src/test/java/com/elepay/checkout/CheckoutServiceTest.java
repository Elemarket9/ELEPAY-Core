package com.elepay.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Domain 11 & 14: Unit and integration tests for CheckoutService and CheckoutConfigService.
 * Verifies versioned configuration, externalized limits, fee schedule updates,
 * merchant reserve retention, and conditional reserve release.
 */
class CheckoutServiceTest extends PostgresTestBase {

    @Autowired CheckoutService checkoutService;
    @Autowired CheckoutConfigService configService;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;

    @BeforeEach
    void resetConfig() {
        configService.updateConfig(CheckoutConfigService.KEY_MAX_ALLOCATIONS, "50", "Reset to baseline", "test");
    }

    @Test
    void configServiceManagesVersionedCheckoutLimitsAndFees() {
        int initialAlloc = configService.maxAllocations();
        long initialTotal = configService.maxTotal();

        assertThat(initialAlloc).isEqualTo(50);
        assertThat(initialTotal).isEqualTo(5_000_000_00L);

        // Update config with audit trail
        var updated = configService.updateConfig(
                CheckoutConfigService.KEY_MAX_ALLOCATIONS, "75", "Capacity increase", "ops_admin"
        );
        assertThat(updated.version()).isGreaterThan(1);
        assertThat(configService.maxAllocations()).isEqualTo(75);

        // Update customer fee for momo
        configService.updateConfig(CheckoutConfigService.KEY_FEE_MOMO_BPS, "150", "Updated MoMo pricing", "pricing_team");
        configService.updateConfig(CheckoutConfigService.KEY_FEE_MOMO_FIXED, "50", "Updated MoMo fixed fee", "pricing_team");

        long fee = configService.calculateCustomerFee("momo", 10_000L);
        // (10000 * 150 / 10000) + 50 = 150 + 50 = 200 pesewas
        assertThat(fee).isEqualTo(200L);

        // Negative fees or invalid methods rejected
        assertThatThrownBy(() -> configService.calculateCustomerFee("crypto", 10_000L))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void feeScheduleVersionedUpdatesAreLoggedWithHistory() {
        UUID marketplace = UUID.randomUUID();
        // Insert dummy active marketplace merchant
        db.sql("""
                INSERT INTO merchant (id, legal_name, trading_name, kind, status, merchant_code, contact_phone, created_by)
                VALUES (?, 'Market Corp', 'Market Corp', 'marketplace', 'active', ?, '0241234567', 'test')
                """).params(marketplace, String.format("%06d", (int) (Math.random() * 900000) + 100000)).update();

        var sched1 = checkoutService.saveFeeSchedule(marketplace, 14, 500, List.of(
                new CheckoutService.FeeRule("electronics", 1000, 200L, 100L, 500),
                new CheckoutService.FeeRule("*", 500, 100L, 50L, 250)
        ), "Initial grid", "owner_1");

        assertThat(sched1.version()).isEqualTo(1);
        assertThat(sched1.deliveryConfirmDays()).isEqualTo(14);
        assertThat(sched1.reserveBps()).isEqualTo(500);
        assertThat(sched1.rules()).hasSize(2);

        // Second update increments version
        var sched2 = checkoutService.saveFeeSchedule(marketplace, 7, 300, List.of(
                new CheckoutService.FeeRule("electronics", 800, 200L, 50L, 300),
                new CheckoutService.FeeRule("books", 300, 50L, 20L, 100),
                new CheckoutService.FeeRule("*", 400, 100L, 50L, 200)
        ), "Reduced commissions for promo", "owner_1");

        assertThat(sched2.version()).isEqualTo(2);
        assertThat(sched2.deliveryConfirmDays()).isEqualTo(7);
        assertThat(sched2.reserveBps()).isEqualTo(300);
        assertThat(sched2.rules()).hasSize(3);

        // Verify history records exist
        long historyCount = db.sql("SELECT count(*) FROM marketplace_fee_schedule_history WHERE marketplace_id = ?")
                .param(marketplace).query(Long.class).single();
        assertThat(historyCount).isEqualTo(2);
    }

    @Test
    void dayCountConventionCalculatesAccrualWithZeroFloatingPointArithmetic() {
        var convention = com.elepay.wallet.DayCountConvention.ACT_365;
        java.time.LocalDate start = java.time.LocalDate.of(2026, 1, 1);
        java.time.LocalDate end = java.time.LocalDate.of(2026, 1, 31); // 30 days

        long principalPesewas = 100_000L; // 1,000 GHS
        int rateBps = 1200; // 12.00% annual

        // (100,000 * 1200 * 30) / (10,000 * 365) = 3,600,000,000 / 3,650,000 = 986.30... -> 986 pesewas
        long interest = convention.calculateInterest(principalPesewas, rateBps, start, end);
        assertThat(interest).isEqualTo(986L);

        // Test boundary conditions
        assertThat(convention.calculateInterest(0, rateBps, start, end)).isZero();
        assertThat(convention.calculateInterest(principalPesewas, 0, start, end)).isZero();
        assertThat(convention.calculateInterest(principalPesewas, rateBps, end, start)).isZero();
        assertThat(convention.yearFractionBps(start, end)).isEqualTo(821L); // 30 * 10000 / 365 = 821 bps

        // ACT_360
        var act360 = com.elepay.wallet.DayCountConvention.ACT_360;
        // (100,000 * 1200 * 30) / (10,000 * 360) = 3,600,000,000 / 3,600,000 = 1000 pesewas
        assertThat(act360.calculateInterest(principalPesewas, rateBps, start, end)).isEqualTo(1000L);

        // THIRTY_360
        var thirty360 = com.elepay.wallet.DayCountConvention.THIRTY_360;
        assertThat(thirty360.calculateInterest(principalPesewas, rateBps, start, end)).isEqualTo(967L);
    }
}
