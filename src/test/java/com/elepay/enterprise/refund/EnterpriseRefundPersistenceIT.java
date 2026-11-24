package com.elepay.enterprise.refund;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import com.elepay.enterprise.accounts.EnterpriseManagedAccountService;
import com.elepay.enterprise.checkout.*;
import com.elepay.enterprise.wallet.ExternalWalletEngine;
import com.elepay.ledger.Ledger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnterpriseRefundPersistenceIT extends PostgresTestBase {

    @Autowired private EnterpriseManagedAccountService enterpriseService;
    @Autowired private EnterpriseCheckoutService checkoutService;
    @Autowired private EnterpriseMultiMerchantSplitter splitter;
    @Autowired private EnterpriseItemRefundCalculator refundCalculator;
    @Autowired private EnterpriseRefundLiabilityTracker liabilityTracker;
    @Autowired private ExternalWalletEngine walletEngine;
    @Autowired private Ledger ledger;
    @Autowired private JdbcClient db;

    @Test
    @DisplayName("Restart simulation: DB persists enterprise order gross, enforces cumulative refund cap, and replays idempotently")
    void testRestartPersistenceIdempotencyAndCap() {
        String code = "REF-PERSIST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var enterprise = enterpriseService.registerEnterprise(
                code, "Refund Persist Ent", "ops@persist.gh", "+233302000222", "https://example.com/hook");
        UUID enterpriseId = enterprise.id();
        UUID merchantId = UUID.randomUUID();
        String orderRef = "ORD-PERSIST-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Ingest checkout for 10,000 pesewas (GH₵100.00)
        checkoutService.ingestExternalPayment(new EnterpriseCheckoutPaymentRequest(
                "ingest-" + orderRef,
                enterpriseId,
                orderRef,
                "ext-pay-" + orderRef,
                "ENTERPRISE_WALLET",
                Ledger.GHS,
                10_000L,
                List.of(new SubMerchantItemAllocation(
                        merchantId, "Seller One", 10_000L, "GENERAL", "Item 1",
                        1000, 0L, 0L, 50, 0L, 0, 0L, false)),
                List.of(),
                Map.of()
        ));

        // 2. First refund of 6,000 pesewas
        EnterpriseRefundService serviceBeforeRestart = new EnterpriseRefundService(
                ledger, refundCalculator, liabilityTracker, checkoutService, walletEngine, db);
        String refundKey = "rf-key-" + orderRef;
        var first = serviceBeforeRestart.processRefund(new EnterpriseItemRefundRequest(
                refundKey, enterpriseId, orderRef, null, "cust-1",
                RefundDestination.DOMESTIC_MOMO, Map.of("network", "mtn"),
                Ledger.GHS, "Partial refund",
                List.of(EnterpriseRefundItemSpecification.of("Item 1", merchantId, 6_000L, 1000, "Partial")),
                null, Map.of()
        ));
        assertThat(first.replayed()).isFalse();
        assertThat(first.totalRefundPesewas()).isEqualTo(6_000L);

        // 3. Simulate restart: fresh EnterpriseCheckoutService (empty in-memory maps) + fresh EnterpriseRefundService
        EnterpriseCheckoutService freshCheckoutService = new EnterpriseCheckoutService(ledger, splitter, db);
        EnterpriseRefundService serviceAfterRestart = new EnterpriseRefundService(
                ledger, refundCalculator, liabilityTracker, freshCheckoutService, walletEngine, db);

        // 3a. Idempotent retry after restart must hit DB and return replayed = true
        var replay = serviceAfterRestart.processRefund(new EnterpriseItemRefundRequest(
                refundKey, enterpriseId, orderRef, null, "cust-1",
                RefundDestination.DOMESTIC_MOMO, Map.of("network", "mtn"),
                Ledger.GHS, "Partial refund",
                List.of(EnterpriseRefundItemSpecification.of("Item 1", merchantId, 6_000L, 1000, "Partial")),
                null, Map.of()
        ));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.refundId()).isEqualTo(first.refundId());

        // 3b. Refund exceeding remaining 4,000 pesewas (e.g. 5,000) must be rejected even after restart
        assertThatThrownBy(() -> serviceAfterRestart.processRefund(new EnterpriseItemRefundRequest(
                "rf-over-" + orderRef, enterpriseId, orderRef, null, "cust-1",
                RefundDestination.DOMESTIC_MOMO, Map.of("network", "mtn"),
                Ledger.GHS, "Over refund",
                List.of(EnterpriseRefundItemSpecification.of("Item 1", merchantId, 5_000L, 1000, "Over")),
                null, Map.of()
        ))).isInstanceOf(ApiException.class)
                .hasMessageContaining("exceeds original captured amount");
    }

    @Test
    @DisplayName("Multi-currency USD checkout and refund use system:revenue:USD and maintain ledger integrity")
    void testUsdCheckoutAndRefundRevenueAccount() {
        String code = "USD-ENT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var enterprise = enterpriseService.registerEnterprise(
                code, "USD Ent", "ops@usd.gh", "+233302000333", "https://example.com/hook");
        UUID enterpriseId = enterprise.id();
        UUID merchantId = UUID.randomUUID();
        String orderRef = "ORD-USD-" + UUID.randomUUID().toString().substring(0, 8);

        long revUsdBefore = ledger.balance("system:revenue:USD");

        checkoutService.ingestExternalPayment(new EnterpriseCheckoutPaymentRequest(
                "ingest-" + orderRef,
                enterpriseId,
                orderRef,
                "ext-pay-" + orderRef,
                "CARD",
                "USD",
                10_000L,
                List.of(new SubMerchantItemAllocation(
                        merchantId, "USD Seller", 10_000L, "GENERAL", "USD Item",
                        1000, 0L, 0L, 100, 0L, 0, 0L, false)),
                List.of(),
                Map.of()
        ));

        assertThat(ledger.balance("system:revenue:USD")).isEqualTo(revUsdBefore + 100L);

        EnterpriseRefundService refundService = new EnterpriseRefundService(
                ledger, refundCalculator, liabilityTracker, checkoutService, walletEngine, db);
        var refund = refundService.processRefund(new EnterpriseItemRefundRequest(
                "rf-usd-" + orderRef, enterpriseId, orderRef, null, "cust-usd",
                RefundDestination.BANK_ACCOUNT, Map.of(),
                "USD", "USD refund",
                List.of(new EnterpriseRefundItemSpecification(
                        "USD Item", merchantId, 10_000L, null, null, null, null, 0L,
                        1000, 100, 0, "Full USD refund")),
                null, Map.of()
        ));

        assertThat(refund.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(ledger.balance("system:revenue:USD")).isEqualTo(revUsdBefore);
        assertThat(ledger.integrity().ok()).isTrue();
    }
}
