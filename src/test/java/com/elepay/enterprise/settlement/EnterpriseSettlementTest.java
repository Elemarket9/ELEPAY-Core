package com.elepay.enterprise.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import com.elepay.enterprise.accounts.EnterpriseManagedAccountService;
import com.elepay.enterprise.checkout.CalculatedMerchantAllocation;
import com.elepay.enterprise.treasury.EnterpriseTreasuryService;
import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Enterprise Conditional Settlement Release Engine Automated Verification Suite.
 */
class EnterpriseSettlementTest extends PostgresTestBase {

    @Autowired
    private DeliveryEscrowService escrowService;

    @Autowired
    private ConditionalSettlementReleaseEngine settlementEngine;

    @Autowired
    private EnterpriseTreasuryService treasuryService;

    @Autowired
    private EnterpriseManagedAccountService enterpriseService;

    @Autowired
    private Ledger ledger;

    private UUID enterpriseId;
    private UUID m1;
    private UUID m2;
    private UUID m3;

    @BeforeEach
    void setUp() {
        var ent = enterpriseService.registerEnterprise(
                "SETTLE-TEST-" + UUID.randomUUID().toString().substring(0, 8),
                "Settlement Test Enterprise",
                "settle@test.com",
                "+233241230002",
                "https://api.test.com/webhooks"
        );
        enterpriseId = ent.id();
        m1 = UUID.randomUUID();
        m2 = UUID.randomUUID();
        m3 = UUID.randomUUID();

        // Seed initial prefunding
        treasuryService.depositPrefunding(enterpriseId, 1_000_000L, "GHS", "PREFUND-" + UUID.randomUUID(), "Settlement test prefund");
    }

    @Test
    @DisplayName("Delivery Escrow Lifecycle: Register, confirm delivery, and reject item")
    void testDeliveryEscrowRegistrationAndConfirmation() {
        String orderRef = "ORD-ESCROW-" + UUID.randomUUID().toString().substring(0, 8);
        List<CalculatedMerchantAllocation> allocations = List.of(
                new CalculatedMerchantAllocation(0, m1, "Merchant One", "GENERAL", "item-1", 30_000L, 3_000L, 100L, 0L, 26_900L, true),
                new CalculatedMerchantAllocation(1, m2, "Merchant Two", "GENERAL", "item-2", 20_000L, 2_000L, 100L, 0L, 17_900L, true)
        );

        UUID holdTxId = null;
        List<DeliveryEscrowRecord> records = escrowService.registerOrderItems(
                enterpriseId, orderRef, allocations, "GHS", holdTxId
        );

        assertThat(records).hasSize(2);
        assertThat(records.get(0).deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(records.get(0).escrowStatus()).isEqualTo(EscrowStatus.HELD);
        assertThat(records.get(0).isEligibleForRelease()).isFalse();

        // Confirm delivery for item-1
        DeliveryEscrowRecord confirmed = escrowService.confirmDelivery(enterpriseId, orderRef, "item-1", "DHL Express");
        assertThat(confirmed.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(confirmed.escrowStatus()).isEqualTo(EscrowStatus.HELD);
        assertThat(confirmed.isEligibleForRelease()).isTrue();

        // Reject item-2
        DeliveryEscrowRecord rejected = escrowService.rejectItem(enterpriseId, orderRef, "item-2", "Customer rejected");
        assertThat(rejected.deliveryStatus()).isEqualTo(DeliveryStatus.REJECTED);
        assertThat(rejected.escrowStatus()).isEqualTo(EscrowStatus.REFUNDED);
        assertThat(rejected.isEligibleForRelease()).isFalse();
    }

    @Test
    @DisplayName("Missing Delivery Confirmation: Strictly retains funds in HELD escrow, prevents premature payout")
    void testMissingDeliveryConfirmationRetainsEscrowHold() {
        String orderRef = "ORD-MISSING-" + UUID.randomUUID().toString().substring(0, 8);
        List<CalculatedMerchantAllocation> allocations = List.of(
                new CalculatedMerchantAllocation(0, m1, "Delivered Merchant", "GENERAL", "item-del", 30_000L, 3_000L, 0L, 0L, 27_000L, true),
                new CalculatedMerchantAllocation(1, m2, "Missing Merchant", "GENERAL", "item-missing", 20_000L, 2_000L, 0L, 0L, 18_000L, true)
        );

        escrowService.registerOrderItems(enterpriseId, orderRef, allocations, "GHS", null);

        // Merchant 1 delivered
        escrowService.confirmDelivery(enterpriseId, orderRef, "item-del", "Courier A");

        // Merchant 2 missing delivery confirmation
        escrowService.reportMissingDelivery(enterpriseId, orderRef, "item-missing", "No courier tracking scan");

        var missingRecord = escrowService.findEscrowRecord(enterpriseId, orderRef, "item-missing").orElseThrow();
        assertThat(missingRecord.deliveryStatus()).isEqualTo(DeliveryStatus.MISSING_CONFIRMATION);
        assertThat(missingRecord.escrowStatus()).isEqualTo(EscrowStatus.HELD);
        assertThat(missingRecord.isEligibleForRelease()).isFalse();

        // Setup merchant pending accounts in ledger
        ledger.open("merchant", m1, "pending", "GHS");
        ledger.open("merchant", m1, "available", "GHS");
        ledger.open("merchant", m2, "pending", "GHS");
        ledger.open("merchant", m2, "available", "GHS");
        ledger.openSystem("system:clearing:test:GHS", "clearing", "GHS", true);

        ledger.post(com.elepay.ledger.Ledger.Posting.of(
                "test", "SEED-M1-" + UUID.randomUUID(), "seed",
                List.of(
                        com.elepay.ledger.Ledger.Leg.debit("system:clearing:test:GHS", 45_000L),
                        com.elepay.ledger.Ledger.Leg.credit(Ledger.code("merchant", m1, "pending", "GHS"), 27_000L),
                        com.elepay.ledger.Ledger.Leg.credit(Ledger.code("merchant", m2, "pending", "GHS"), 18_000L)
                )
        ));

        // Execute conditional settlement release
        SettlementReleaseResult result = settlementEngine.releaseEligibleSettlements(enterpriseId, "GHS");

        assertThat(result.totalItemsReleased()).isEqualTo(1);
        assertThat(result.totalItemsHeld()).isEqualTo(1);
        assertThat(result.totalNetReleasedPesewas()).isEqualTo(27_000L);
        assertThat(result.status()).isEqualTo("RELEASED");

        // Merchant 1 available balance credited
        assertThat(ledger.balance(Ledger.code("merchant", m1, "available", "GHS"))).isEqualTo(27_000L);
        assertThat(ledger.balance(Ledger.code("merchant", m1, "pending", "GHS"))).isZero();

        // Merchant 2 available balance is still ZERO! (Missing delivery retained in escrow!)
        assertThat(ledger.balance(Ledger.code("merchant", m2, "available", "GHS"))).isZero();
        assertThat(ledger.balance(Ledger.code("merchant", m2, "pending", "GHS"))).isEqualTo(18_000L);
    }

    @Test
    @DisplayName("Treasury Shortfall Hold: Trips when available prefunding is insufficient")
    void testSettlementReleaseBlockedOnShortfall() {
        // Register an enterprise with zero prefunding
        var unfundedEnterprise = enterpriseService.registerEnterprise(
                "NO-FUND-" + UUID.randomUUID().toString().substring(0, 8),
                "Unfunded Enterprise",
                "nofund@test.com",
                "+233241230003",
                null
        );

        String orderRef = "ORD-SHORTFALL-" + UUID.randomUUID().toString().substring(0, 8);
        List<CalculatedMerchantAllocation> allocations = List.of(
                new CalculatedMerchantAllocation(0, m3, "Seller Three", "GENERAL", "item-3", 50_000L, 5_000L, 0L, 0L, 45_000L, true)
        );

        escrowService.registerOrderItems(unfundedEnterprise.id(), orderRef, allocations, "GHS", null);
        escrowService.confirmDelivery(unfundedEnterprise.id(), orderRef, "item-3", "Courier X");

        // Attempt settlement release without prefunding
        SettlementReleaseResult result = settlementEngine.releaseEligibleSettlements(unfundedEnterprise.id(), "GHS");

        assertThat(result.shortfallDetected()).isTrue();
        assertThat(result.status()).isEqualTo("SHORTFALL_HOLD");
        assertThat(result.totalItemsReleased()).isZero();
    }
    @Test
    @DisplayName("Escrow re-registration is idempotent and never overwrites escrowed amounts")
    void escrowReRegistrationDoesNotOverwriteAmounts() {
        String orderRef = "ORD-REREG-" + UUID.randomUUID().toString().substring(0, 8);
        var original = List.of(new CalculatedMerchantAllocation(0, m1, "M1", "GENERAL", "item-r", 30_000L, 3_000L, 0L, 0L, 27_000L, true));
        UUID firstId = escrowService.registerOrderItems(enterpriseId, orderRef, original, "GHS", null).get(0).id();

        // Identical replay returns the same record
        assertThat(escrowService.registerOrderItems(enterpriseId, orderRef, original, "GHS", null).get(0).id()).isEqualTo(firstId);

        // A replay with an inflated net payable is rejected and the stored amount is unchanged
        var inflated = List.of(new CalculatedMerchantAllocation(0, m1, "M1", "GENERAL", "item-r", 30_000L, 0L, 0L, 0L, 30_000L, true));
        assertThatThrownBy(() -> escrowService.registerOrderItems(enterpriseId, orderRef, inflated, "GHS", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("different values");
        assertThat(escrowService.findEscrowRecord(enterpriseId, orderRef, "item-r").orElseThrow().netPayablePesewas())
                .isEqualTo(27_000L);
    }

    @Test
    @DisplayName("Released escrow cannot be released again or cancelled in place")
    void releasedEscrowIsTerminal() {
        String orderRef = "ORD-TERM-" + UUID.randomUUID().toString().substring(0, 8);
        var alloc = List.of(new CalculatedMerchantAllocation(0, m1, "M1", "GENERAL", "item-t", 10_000L, 1_000L, 0L, 0L, 9_000L, true));
        escrowService.registerOrderItems(enterpriseId, orderRef, alloc, "GHS", null);
        escrowService.confirmDelivery(enterpriseId, orderRef, "item-t", "Marketplace instruction");
        seedPending(m1, 9_000L);

        SettlementReleaseResult first = settlementEngine.releaseEligibleSettlements(enterpriseId, "GHS");
        assertThat(first.totalNetReleasedPesewas()).isEqualTo(9_000L);
        UUID escrowId = escrowService.findEscrowRecord(enterpriseId, orderRef, "item-t").orElseThrow().id();

        assertThatThrownBy(() -> escrowService.markReleased(escrowId, first.ledgerTransactionId()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> escrowService.rejectItem(enterpriseId, orderRef, "item-t", "late cancel"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("refund engine");
        assertThat(settlementEngine.releaseEligibleSettlements(enterpriseId, "GHS").totalNetReleasedPesewas()).isZero();
        assertThat(ledger.balance(Ledger.code("merchant", m1, "available", "GHS"))).isEqualTo(9_000L);
    }

    @Test
    @DisplayName("Concurrent settlement workers release each escrow item exactly once")
    void concurrentSettlementWorkersReleaseOnce() throws Exception {
        String orderRef = "ORD-CONC-" + UUID.randomUUID().toString().substring(0, 8);
        var alloc = new java.util.ArrayList<CalculatedMerchantAllocation>();
        for (int i = 0; i < 20; i++) {
            alloc.add(new CalculatedMerchantAllocation(i, m2, "M2", "GENERAL", "item-c" + i, 1_000L, 100L, 0L, 0L, 900L, true));
        }
        escrowService.registerOrderItems(enterpriseId, orderRef, alloc, "GHS", null);
        for (int i = 0; i < 20; i++) {
            escrowService.confirmDelivery(enterpriseId, orderRef, "item-c" + i, "Marketplace instruction");
        }
        seedPending(m2, 18_000L);

        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int w = 0; w < 4; w++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return settlementEngine.releaseEligibleSettlements(enterpriseId, "GHS").totalNetReleasedPesewas();
                    } catch (ApiException e) {
                        return 0L; // a losing worker may be refused; it must not release anything
                    }
                }));
            }
            start.countDown();
            long released = 0;
            for (var f : futures) released += f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(released).isEqualTo(18_000L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledger.balance(Ledger.code("merchant", m2, "available", "GHS"))).isEqualTo(18_000L);
        assertThat(ledger.balance(Ledger.code("merchant", m2, "pending", "GHS"))).isZero();
    }

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient db;

    @Autowired
    private com.elepay.rails.RailTransactions railTransactions;

    @Test
    @DisplayName("Verified settlement destination dispatches real Payouts batch and settles through rail")
    void executeMerchantPayoutBatchDispatchesRealPayoutWhenDestinationVerified() {
        UUID verifiedMerchant = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        db.sql("""
                INSERT INTO settlement_destination
                    (id, owner_type, owner_id, type, network, account_number, account_name,
                     verification, verified_name, status, created_by, cooling_off_until)
                VALUES (:id, 'merchant', :m, 'momo', 'mtn', '233245550005', 'Verified Seller',
                        'verified', 'Verified Seller', 'active', 'test', now() - interval '1 hour')""")
                .param("id", destId)
                .param("m", verifiedMerchant)
                .update();

        String orderRef = "ORD-PAYOUT-" + UUID.randomUUID().toString().substring(0, 8);
        var alloc = List.of(new CalculatedMerchantAllocation(
                0, verifiedMerchant, "Verified Seller", "GENERAL", "item-p1",
                25_000L, 2_500L, 0L, 0L, 22_500L, true));
        escrowService.registerOrderItems(enterpriseId, orderRef, alloc, "GHS", null);
        escrowService.confirmDelivery(enterpriseId, orderRef, "item-p1", "Courier Verified");
        seedPending(verifiedMerchant, 22_500L);

        SettlementReleaseResult release = settlementEngine.releaseEligibleSettlements(enterpriseId, "GHS");
        assertThat(release.status()).isEqualTo("RELEASED");
        assertThat(ledger.balance(Ledger.code("merchant", verifiedMerchant, "available", "GHS"))).isEqualTo(22_500L);

        UUID payoutBatchRef = settlementEngine.executeMerchantPayoutBatch(
                enterpriseId, List.of(verifiedMerchant), "GHS");
        assertThat(payoutBatchRef).isNotNull();

        // Real payout_batch, payout, and rail_transaction were created and submitted to the rail
        String initialStatus = db.sql("""
                SELECT p.status
                FROM payout p
                JOIN payout_batch b ON b.id = p.batch_id
                WHERE b.owner_type = 'merchant' AND b.owner_id = :m
                ORDER BY p.created_at DESC
                LIMIT 1""")
                .param("m", verifiedMerchant)
                .query(String.class)
                .single();
        assertThat(initialStatus).isEqualTo("submitted");
        assertThat(ledger.balance(Ledger.code("merchant", verifiedMerchant, "available", "GHS"))).isZero();
        assertThat(ledger.balance(Ledger.code("merchant", verifiedMerchant, "reserved", "GHS"))).isEqualTo(22_500L);

        // SimulatedRail account ending in '5' settles on rail recovery/polling
        railTransactions.recover(100, true);

        String finalStatus = db.sql("""
                SELECT p.status
                FROM payout p
                JOIN payout_batch b ON b.id = p.batch_id
                WHERE b.owner_type = 'merchant' AND b.owner_id = :m
                ORDER BY p.created_at DESC
                LIMIT 1""")
                .param("m", verifiedMerchant)
                .query(String.class)
                .single();
        assertThat(finalStatus).isEqualTo("succeeded");

        // Available and reserved are both 0 after settlement; ledger remains balanced
        assertThat(ledger.balance(Ledger.code("merchant", verifiedMerchant, "available", "GHS"))).isZero();
        assertThat(ledger.balance(Ledger.code("merchant", verifiedMerchant, "reserved", "GHS"))).isZero();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    private void seedPending(UUID merchant, long amount) {
        ledger.open("merchant", merchant, "pending", "GHS");
        ledger.openSystem("system:clearing:test:GHS", "clearing", "GHS", true);
        ledger.post(Ledger.Posting.of("test", "SEED-" + UUID.randomUUID(), "seed", List.of(
                Ledger.Leg.debit("system:clearing:test:GHS", amount),
                Ledger.Leg.credit(Ledger.code("merchant", merchant, "pending", "GHS"), amount))));
    }
}
