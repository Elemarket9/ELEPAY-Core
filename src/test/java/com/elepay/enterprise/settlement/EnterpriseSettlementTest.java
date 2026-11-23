package com.elepay.enterprise.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
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
}
