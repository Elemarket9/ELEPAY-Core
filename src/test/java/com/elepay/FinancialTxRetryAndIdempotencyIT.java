package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.common.FinancialTxRetry;
import com.elepay.ledger.Ledger;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies P0 architectural remediations:
 * 1. PostgreSQL transaction-boundary retry rolls back aborted 40P01/40001 transactions cleanly
 *    and succeeds in a fresh transaction without hitting SQLSTATE 25P02.
 * 2. End-to-end reserve-release idempotency (missing key -> 400, identical retry -> same release,
 *    changed payload under same key -> 409).
 * 3. End-to-end refund idempotency (identical retry -> same refund, changed payload -> 409).
 */
class FinancialTxRetryAndIdempotencyIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired FinancialTxRetry txRetry;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;

    TestApi api;
    String ops;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        ops = api.token("fin-ops-" + UUID.randomUUID(), "ops");
    }

    @Test
    void financialTxRetryRollsBackAbortedPostgresTransactionAndSucceedsInFreshTx() {
        UUID marker = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();

        String result = txRetry.execute(3, () -> {
            int n = attempts.incrementAndGet();
            // Force PostgreSQL to mark the current transaction aborted on attempt 1 (simulating 40P01)
            if (n == 1) {
                try {
                    db.sql("SELECT 1/0").query(Integer.class).single();
                } catch (RuntimeException ignored) {
                    // Transaction is now in PostgreSQL aborted state (subsequent SQL in this tx would throw 25P02).
                }
                throw new DeadlockLoserDataAccessException("Simulated deadlock", new SQLException("deadlock detected", "40P01"));
            }
            // Attempt 2 runs in a fresh transaction: must NOT fail with 25P02!
            Integer one = db.sql("SELECT 1").query(Integer.class).single();
            assertThat(one).isEqualTo(1);
            return "committed-" + marker;
        });

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(result).isEqualTo("committed-" + marker);
    }

    @Test
    void reserveReleaseAndRefundEnforceEndToEndIdempotency() {
        String s = UUID.randomUUID().toString();
        String mpOwner = api.token("mp-idem-" + s);
        String sellerOwner = api.token("seller-idem-" + s);
        String buyerToken = api.token("buyer-idem-" + s);

        var mpResp = api.call("POST", "/v1/merchants", mpOwner, null, Map.of(
                "legalName", "Idem Market Ltd", "tradingName", "Idem Market",
                "kind", "marketplace", "contactPhone", TestApi.uniquePhone(), "contactEmail", "mp-" + s + "@elepay.gh"));
        UUID mp = UUID.fromString(mpResp.body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + mp + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", 0, "feeFixed", 0, "settlementDelayHours", 0));

        var sellerResp = api.call("POST", "/v1/merchants", sellerOwner, null, Map.of(
                "legalName", "Idem Seller Ltd", "tradingName", "Idem Seller",
                "kind", "merchant", "contactPhone", TestApi.uniquePhone(), "contactEmail", "sl-" + s + "@elepay.gh"));
        UUID seller = UUID.fromString(sellerResp.body().get("id").asString());
        String sellerCode = sellerResp.body().get("merchantCode").asString();
        api.call("POST", "/v1/ops/merchants/" + seller + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", 0, "feeFixed", 0, "settlementDelayHours", 0));
        assertThat(api.call("POST", "/v1/merchants/" + mp + "/sellers", mpOwner, null,
                Map.of("merchantCode", sellerCode)).status()).isEqualTo(200);
        assertThat(api.call("POST", "/v1/merchants/" + seller + "/marketplaces/" + mp + "/accept",
                sellerOwner, null, null).status()).isEqualTo(200);

        UUID buyer = api.enroll(ops, "buyer-idem-" + s, TestApi.uniquePhone(), "Buyer Idem");
        ledger.post(Ledger.Posting.of("fund-idem", s, "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 50_000L),
                Ledger.Leg.credit(com.elepay.wallet.Wallets.available(buyer), 50_000L))));

        // Create and pay a 10,000p checkout with 20% reserve (2,000p reserved)
        var created = api.call("POST", "/v1/merchants/" + mp + "/checkouts", mpOwner, UUID.randomUUID().toString(),
                Map.of("allocations", List.of(Map.of("merchantId", seller, "kind", "sale", "amount", 10_000L, "reserveBps", 2000))));
        assertThat(created.status()).isEqualTo(200);
        String ref = created.body().get("reference").asString();

        var paid = api.call("POST", "/v1/checkouts/" + ref + "/pay/wallet", buyerToken, UUID.randomUUID().toString(), Map.of());
        assertThat(paid.status()).isEqualTo(200);
        assertThat(ledger.balance(Ledger.code("merchant", seller, "reserved", "GHS"))).isEqualTo(2_000L);

        // 1. Reserve release without Idempotency-Key -> 400
        var missingKey = api.call("POST", "/v1/merchants/" + mp + "/checkouts/" + ref + "/allocations/0/release-reserve",
                ops, null, Map.of("amount", 1_000L, "reason", "partial release"));
        assertThat(missingKey.status()).isEqualTo(400);

        // 2. Partial reserve release (1,000p) retried with same Idempotency-Key -> returns same release, debits reserve ONCE
        String reserveKey = "res-" + UUID.randomUUID();
        var rel1 = api.call("POST", "/v1/merchants/" + mp + "/checkouts/" + ref + "/allocations/0/release-reserve",
                ops, reserveKey, Map.of("amount", 1_000L, "reason", "partial release"));
        var rel2 = api.call("POST", "/v1/merchants/" + mp + "/checkouts/" + ref + "/allocations/0/release-reserve",
                ops, reserveKey, Map.of("amount", 1_000L, "reason", "partial release"));
        assertThat(rel1.status()).isEqualTo(200);
        assertThat(rel2.status()).isEqualTo(200);
        assertThat(rel2.body().get("id").asString()).isEqualTo(rel1.body().get("id").asString());
        assertThat(ledger.balance(Ledger.code("merchant", seller, "reserved", "GHS"))).isEqualTo(1_000L);

        // 3. Reusing same Idempotency-Key with different amount -> 409 idempotency_conflict
        var relConflict = api.call("POST", "/v1/merchants/" + mp + "/checkouts/" + ref + "/allocations/0/release-reserve",
                ops, reserveKey, Map.of("amount", 500L, "reason", "partial release"));
        assertThat(relConflict.status()).isEqualTo(409);

        // 4. Partial refund (2,500p) retried with same Idempotency-Key -> returns same refund, refunds ONCE
        String refundKey = "ref-" + UUID.randomUUID();
        var rf1 = api.call("POST", "/v1/merchants/" + mp + "/checkouts/" + ref + "/allocations/0/refunds",
                mpOwner, refundKey, Map.of("amount", 2_500L, "reason", "partial refund"));
        var rf2 = api.call("POST", "/v1/merchants/" + mp + "/checkouts/" + ref + "/allocations/0/refunds",
                mpOwner, refundKey, Map.of("amount", 2_500L, "reason", "partial refund"));
        assertThat(rf1.status()).isEqualTo(200);
        assertThat(rf2.status()).isEqualTo(200);
        assertThat(rf2.body().get("id").asString()).isEqualTo(rf1.body().get("id").asString());

        // 5. Reusing refund key with different amount -> 409 idempotency_conflict
        var rfConflict = api.call("POST", "/v1/merchants/" + mp + "/checkouts/" + ref + "/allocations/0/refunds",
                mpOwner, refundKey, Map.of("amount", 1_000L, "reason", "partial refund"));
        assertThat(rfConflict.status()).isEqualTo(409);

        assertThat(ledger.integrity().ok()).isTrue();
    }
}
