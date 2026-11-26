package com.elepay.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Domain 11 & 14: Unit and integration tests for Multi-Merchant Settlement Engine.
 * Verifies multi-recipient split allocations, platform fee recognition,
 * merchant reserve retention, conditional release, and double-entry balance.
 */
@SuppressWarnings("deprecation")
class MultiMerchantSettlementTest extends PostgresTestBase {

    @Autowired Ledger ledger;
    @Autowired MultiMerchantSettlementEngine settlementEngine;

    private UUID customerId;
    private UUID marketplaceId;
    private UUID merchantA;
    private UUID merchantB;
    private UUID riderD;

    @BeforeEach
    void setup() {
        customerId = UUID.randomUUID();
        marketplaceId = UUID.randomUUID();
        merchantA = UUID.randomUUID();
        merchantB = UUID.randomUUID();
        riderD = UUID.randomUUID();

        ledger.open("customer", customerId, "available", Ledger.GHS);
        ledger.open("merchant", merchantA, "pending", Ledger.GHS);
        ledger.open("merchant", merchantA, "available", Ledger.GHS);
        ledger.open("merchant", merchantA, "reserved", Ledger.GHS);
        ledger.open("merchant", merchantB, "pending", Ledger.GHS);
        ledger.open("merchant", merchantB, "available", Ledger.GHS);
        ledger.open("merchant", merchantB, "reserved", Ledger.GHS);
        ledger.open("merchant", marketplaceId, "pending", Ledger.GHS);
        ledger.open("merchant", marketplaceId, "available", Ledger.GHS);
        ledger.open("merchant", riderD, "pending", Ledger.GHS);
        ledger.open("merchant", riderD, "available", Ledger.GHS);
    }

    @Test
    void multiMerchantSplitCalculationPreservesExactMinorUnits() {
        // Line 1: Merchant A - GHS 100.00 (10,000 pesewas), fee=150, commission=800, proc=50, reserve=500 (5%)
        var allocA = new MultiMerchantSettlementEngine.SettlementAllocation(
                merchantA, "Seller A", "sale", 10_000L, 150L, 800L, 50L, 500L, "DELIVERY_CONFIRMED"
        );
        // Line 2: Merchant B - GHS 50.00 (5,000 pesewas), fee=75, commission=400, proc=50, reserve=250 (5%)
        var allocB = new MultiMerchantSettlementEngine.SettlementAllocation(
                merchantB, "Seller B", "sale", 5_000L, 75L, 400L, 50L, 250L, "DELIVERY_CONFIRMED"
        );
        // Line 3: Rider D - GHS 20.00 (2,000 pesewas), fee=0, commission=0, proc=0, reserve=0
        var allocD = new MultiMerchantSettlementEngine.SettlementAllocation(
                riderD, "Rider D", "delivery", 2_000L, 0L, 0L, 0L, 0L, "IMMEDIATE"
        );

        long customerFee = 100L; // GHS 1.00 fee to payer

        var split = settlementEngine.calculateSplit(customerFee, List.of(allocA, allocB, allocD));

        assertThat(split.subtotal()).isEqualTo(17_000L);
        assertThat(split.customerFee()).isEqualTo(100L);
        assertThat(split.totalAmount()).isEqualTo(17_100L);

        // Merchant A net = 10000 - 150 - 850 - 500 = 8500
        assertThat(allocA.netPayable()).isEqualTo(8_500L);
        // Merchant B net = 5000 - 75 - 450 - 250 = 4225
        assertThat(allocB.netPayable()).isEqualTo(4_225L);
        // Rider D net = 2000
        assertThat(allocD.netPayable()).isEqualTo(2_000L);

        assertThat(split.totalNetPayable()).isEqualTo(14_725L);
        assertThat(split.totalPlatformFee()).isEqualTo(1_300L);
        assertThat(split.totalMerchantFee()).isEqualTo(225L);
        assertThat(split.totalReserve()).isEqualTo(750L);

        // Sum invariant: 14725 (net) + 1300 (platform) + 225 (merchant fees) + 750 (reserves) + 100 (customer fee) = 17100
        assertThat(split.totalNetPayable() + split.totalPlatformFee() + split.totalMerchantFee() + split.totalReserve() + split.customerFee())
                .isEqualTo(split.totalAmount());
    }

    @Test
    void executeMultiMerchantPaymentPostsBalancedDoubleEntryTransaction() {
        // Pre-fund customer wallet with 20,000 pesewas
        String customerWallet = Ledger.code("customer", customerId, "available", Ledger.GHS);
        ledger.post(Ledger.Posting.of("funding", UUID.randomUUID().toString(), "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 20_000L),
                Ledger.Leg.credit(customerWallet, 20_000L)
        )));

        var allocA = new MultiMerchantSettlementEngine.SettlementAllocation(
                merchantA, "Seller A", "sale", 10_000L, 150L, 800L, 50L, 500L, "DELIVERY_CONFIRMED"
        );
        var allocB = new MultiMerchantSettlementEngine.SettlementAllocation(
                merchantB, "Seller B", "sale", 5_000L, 75L, 400L, 50L, 250L, "DELIVERY_CONFIRMED"
        );

        UUID checkoutId = UUID.randomUUID();
        String reference = "EP-TEST-SETTLE-001";

        var result = settlementEngine.postMultiMerchantPayment(
                checkoutId, reference, "wallet", customerWallet, 100L,
                List.of(allocA, allocB), marketplaceId
        );

        assertThat(result.journalTxId()).isNotNull();
        assertThat(result.totalAmount()).isEqualTo(15_100L);

        // Verify ledger balances
        assertThat(ledger.balance(customerWallet)).isEqualTo(20_000L - 15_100L);
        assertThat(ledger.balance(Ledger.code("merchant", merchantA, "pending", Ledger.GHS))).isEqualTo(8_500L);
        assertThat(ledger.balance(Ledger.code("merchant", merchantA, "reserved", Ledger.GHS))).isEqualTo(500L);
        assertThat(ledger.balance(Ledger.code("merchant", merchantB, "pending", Ledger.GHS))).isEqualTo(4_225L);
        assertThat(ledger.balance(Ledger.code("merchant", merchantB, "reserved", Ledger.GHS))).isEqualTo(250L);
        assertThat(ledger.balance(Ledger.code("merchant", marketplaceId, "pending", Ledger.GHS))).isEqualTo(1_300L);
        assertThat(ledger.balance(Ledger.DEFERRED_REVENUE)).isGreaterThanOrEqualTo(225L);
        assertThat(ledger.balance(Ledger.REVENUE)).isGreaterThanOrEqualTo(100L);

        // Verify ledger integrity
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void conditionalReserveReleaseMovesFundsWithBalancedPosting() {
        String reserved = Ledger.code("merchant", merchantA, "reserved", Ledger.GHS);
        String available = Ledger.code("merchant", merchantA, "available", Ledger.GHS);
        String pending = Ledger.code("merchant", merchantA, "pending", Ledger.GHS);

        // Initialize reserve with 1000 pesewas
        ledger.post(Ledger.Posting.of("init-res", UUID.randomUUID().toString(), "init", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 1_000L),
                Ledger.Leg.credit(reserved, 1_000L)
        )));

        UUID checkoutId = UUID.randomUUID();

        // Release 400 to available (when line was already settled)
        var res1 = settlementEngine.releaseMerchantReserve(
                checkoutId, 0, merchantA, 400L, true, "ops_user", "Dispute period elapsed"
        );
        assertThat(res1.status()).isEqualTo("RELEASED");
        assertThat(ledger.balance(reserved)).isEqualTo(600L);
        assertThat(ledger.balance(available)).isEqualTo(400L);

        // Release 300 to pending (when line is still awaiting delivery / settlement delay)
        var res2 = settlementEngine.releaseMerchantReserve(
                checkoutId, 0, merchantA, 300L, false, "ops_user", "Early reserve concession"
        );
        assertThat(res2.status()).isEqualTo("RELEASED");
        assertThat(ledger.balance(reserved)).isEqualTo(300L);
        assertThat(ledger.balance(pending)).isEqualTo(300L);

        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void feeRecognitionAndBatchSettlementPostBalancedTransactions() {
        String sellerPending = Ledger.code("merchant", merchantB, "pending", Ledger.GHS);
        String sellerAvailable = Ledger.code("merchant", merchantB, "available", Ledger.GHS);

        // Pre-fund seller pending and deferred revenue
        ledger.post(Ledger.Posting.of("fund-pending", UUID.randomUUID().toString(), "fund", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 5_000L),
                Ledger.Leg.credit(sellerPending, 4_800L),
                Ledger.Leg.credit(Ledger.DEFERRED_REVENUE, 200L)
        )));

        UUID batchId = UUID.randomUUID();

        // Settle merchant funds
        var settleResult = settlementEngine.settleMerchantFunds(batchId, merchantB, 4_800L, "ST-TEST-001");
        assertThat(settleResult.journalTxId()).isNotNull();
        assertThat(ledger.balance(sellerPending)).isZero();
        assertThat(ledger.balance(sellerAvailable)).isEqualTo(4_800L);

        // Recognize deferred fee revenue
        long revBefore = ledger.balance(Ledger.REVENUE);
        long defBefore = ledger.balance(Ledger.DEFERRED_REVENUE);
        var feeResult = settlementEngine.recognizeEarnedFees(batchId, 200L, "ST-TEST-001");
        assertThat(feeResult.journalTxId()).isNotNull();
        assertThat(ledger.balance(Ledger.DEFERRED_REVENUE)).isEqualTo(defBefore - 200L);
        assertThat(ledger.balance(Ledger.REVENUE)).isEqualTo(revBefore + 200L);

        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void invalidDeductionsOrNegativeAmountsAreRejected() {
        assertThatThrownBy(() -> new MultiMerchantSettlementEngine.SettlementAllocation(
                merchantA, "Bad Seller", "sale", 5_000L, 3_000L, 2_000L, 500L, 0L, "IMMEDIATE"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Total deductions exceed allocation amount");

        assertThatThrownBy(() -> new MultiMerchantSettlementEngine.SettlementAllocation(
                merchantA, "Bad Seller", "sale", -100L, 0L, 0L, 0L, 0L, "IMMEDIATE"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alignedInvariantsRejectNullCollectorForPlatformFeesAndDeduplicateIdempotentReserveRelease() {
        String customerWallet = Ledger.code("customer", customerId, "available", Ledger.GHS);
        var allocWithPlatformFee = new MultiMerchantSettlementEngine.SettlementAllocation(
                merchantA, "Seller A", "sale", 10_000L, 100L, 500L, 0L, 0L, "DELIVERY_CONFIRMED"
        );
        assertThatThrownBy(() -> settlementEngine.postMultiMerchantPayment(
                UUID.randomUUID(), "EP-NO-COLL", "wallet", customerWallet, 0L,
                List.of(allocWithPlatformFee), null
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Collector ID is required");

        String reserved = Ledger.code("merchant", merchantA, "reserved", Ledger.GHS);
        ledger.post(Ledger.Posting.of("init-idem-res", UUID.randomUUID().toString(), "init", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 1_000L),
                Ledger.Leg.credit(reserved, 1_000L)
        )));

        UUID checkoutId = UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();
        var r1 = settlementEngine.releaseMerchantReserve(checkoutId, 0, merchantA, 400L, true, "ops", "reason", key);
        var r2 = settlementEngine.releaseMerchantReserve(checkoutId, 0, merchantA, 400L, true, "ops", "reason", key);
        assertThat(r2.journalTxId()).isEqualTo(r1.journalTxId());
        assertThat(ledger.balance(reserved)).isEqualTo(600L);
        assertThat(ledger.integrity().ok()).isTrue();
    }
}
