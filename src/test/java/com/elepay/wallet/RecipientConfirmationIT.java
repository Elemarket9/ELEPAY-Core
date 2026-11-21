package com.elepay.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.ledger.Ledger;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Acceptance tests 1 and 2: recipient name confirmation and wallet-to-wallet transfers. */
class RecipientConfirmationIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;

    TestApi api;
    String ops;
    String senderToken;
    UUID sender;
    String recipientPhone;
    UUID recipient;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString();
        ops = api.token("ops-" + s, "ops");
        senderToken = api.token("sender-" + s);
        sender = api.enroll(ops, "sender-" + s, TestApi.uniquePhone(), "Kofi Sender");
        recipientPhone = TestApi.uniquePhone();
        recipient = api.enroll(ops, "recipient-" + s, recipientPhone, "Ama Mensah");
        ledger.post(Ledger.Posting.of("test-funding", s, "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 15_000),
                Ledger.Leg.credit(available(sender), 15_000))));
    }

    static String available(UUID id) {
        return Ledger.code("customer", id, "available", "GHS");
    }

    TestApi.Response lookup(String token, String phone) {
        return api.call("POST", "/v1/wallet/recipients/lookup", token, null, Map.of("rail", "wallet", "phone", phone));
    }

    @Test
    void localAndInternationalFormatsResolveToTheSameVerifiedName() {
        String local = "0" + recipientPhone.substring(3);
        String international = "+" + recipientPhone;
        var a = lookup(senderToken, local);
        var b = lookup(senderToken, international);
        var c = lookup(senderToken, "+233 " + recipientPhone.substring(3, 5) + " " + recipientPhone.substring(5));
        for (var r : List.of(a, b, c)) {
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body().get("status").asString()).isEqualTo("verified");
            assertThat(r.body().get("verifiedName").asString()).isEqualTo("Ama Mensah");
            assertThat(r.body().get("destination").asString()).isEqualTo("+" + recipientPhone);
        }
        // Only the name needed for confirmation is returned.
        Set<String> fields = new HashSet<>();
        a.body().propertyNames().forEach(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("status", "rail", "destination", "verifiedName",
                "confirmationId", "expiresAt", "message");
        assertThat(a.body().toString()).doesNotContain(recipient.toString()).doesNotContainIgnoringCase("balance");
    }

    @Test
    void unknownNumberAndDisabledWalletDoNotProduceAConfirmation() {
        var unknown = lookup(senderToken, TestApi.uniquePhone());
        assertThat(unknown.body().get("status").asString()).isEqualTo("not_found");
        assertThat(unknown.body().get("verifiedName").isNull()).isTrue();
        assertThat(unknown.body().get("confirmationId").isNull()).isTrue();

        api.call("PUT", "/v1/ops/customers/" + recipient + "/status", ops, null,
                Map.of("status", "frozen", "reason", "test"));
        var disabled = lookup(senderToken, recipientPhone);
        assertThat(disabled.body().get("status").asString()).isEqualTo("recipient_unavailable");
        assertThat(disabled.body().get("verifiedName").isNull()).isTrue();
    }

    @Test
    void failedAndUnavailableExternalVerificationIsExplained() {
        var notFound = api.call("POST", "/v1/wallet/recipients/lookup", senderToken, null,
                Map.of("rail", "momo", "phone", "0241234560", "network", "mtn"));
        assertThat(notFound.body().get("status").asString()).isEqualTo("not_found");
        var unavailable = api.call("POST", "/v1/wallet/recipients/lookup", senderToken, null,
                Map.of("rail", "bank", "bankCode", "GCB", "accountNumber", "1234567899"));
        assertThat(unavailable.body().get("status").asString()).isEqualTo("verification_unavailable");
        assertThat(unavailable.body().get("message").asString()).contains("could not verify");
        var verified = api.call("POST", "/v1/wallet/recipients/lookup", senderToken, null,
                Map.of("rail", "bank", "bankCode", "GCB", "accountNumber", "1234567812"));
        assertThat(verified.body().get("status").asString()).isEqualTo("verified");
        assertThat(verified.body().get("verifiedName").asString()).startsWith("SIMULATED");
        assertThat(verified.body().get("destination").asString()).doesNotContain("1234567812");
    }

    @Test
    void changedDestinationAfterLookupInvalidatesTheConfirmation() {
        UUID confirmation = UUID.fromString(lookup(senderToken, recipientPhone).body().get("confirmationId").asString());
        api.call("PUT", "/v1/ops/customers/" + recipient + "/phone", ops, null,
                Map.of("phone", TestApi.uniquePhone(), "reason", "number reassigned"));
        var result = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, UUID.randomUUID().toString(),
                Map.of("confirmationId", confirmation, "amount", 1000));
        assertThat(result.status()).isEqualTo(409);
        assertThat(ledger.balance(available(recipient))).isZero();

        // A disabled wallet after lookup is rejected the same way, even if the confirmation was not revoked.
        String phone2 = TestApi.uniquePhone();
        UUID other = api.enroll(ops, "other-" + UUID.randomUUID(), phone2, "Yaw Boateng");
        UUID c2 = UUID.fromString(lookup(senderToken, phone2).body().get("confirmationId").asString());
        db.sql("UPDATE customer SET status = 'frozen' WHERE id = :id").param("id", other).update();
        var r2 = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, UUID.randomUUID().toString(),
                Map.of("confirmationId", c2, "amount", 1000));
        assertThat(r2.status()).isEqualTo(409);
        assertThat(r2.body().get("code").asString()).isEqualTo("confirmation_stale");
    }

    @Test
    void expiredConfirmationCannotAuthorizeATransfer() {
        UUID confirmation = UUID.fromString(lookup(senderToken, recipientPhone).body().get("confirmationId").asString());
        db.sql("UPDATE recipient_confirmation SET expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", confirmation).update();
        var result = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, UUID.randomUUID().toString(),
                Map.of("confirmationId", confirmation, "amount", 1000));
        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body().get("code").asString()).isEqualTo("confirmation_expired");
        assertThat(ledger.balance(available(sender))).isEqualTo(15_000);
    }

    @Test
    void unauthorizedAndExcessiveLookupsAreBlocked() {
        assertThat(lookup(null, recipientPhone).status()).isEqualTo(401);
        String outsider = api.token("not-a-customer-" + UUID.randomUUID());
        assertThat(lookup(outsider, recipientPhone).status()).isEqualTo(404);

        // Another sender cannot use someone else's confirmation.
        UUID confirmation = UUID.fromString(lookup(senderToken, recipientPhone).body().get("confirmationId").asString());
        String thief = "thief-" + UUID.randomUUID();
        api.enroll(ops, thief, TestApi.uniquePhone(), "Thief");
        var stolen = api.call("POST", "/v1/wallet/transfers/confirmed", api.token(thief), UUID.randomUUID().toString(),
                Map.of("confirmationId", confirmation, "amount", 100));
        assertThat(stolen.status()).isEqualTo(404);

        int limited = 0;
        for (int i = 0; i < 15; i++) {
            if (lookup(senderToken, TestApi.uniquePhone()).status() == 429) limited++;
        }
        assertThat(limited).isGreaterThan(0);
        long audited = db.sql("SELECT count(*) FROM audit_log WHERE action = 'recipient_lookup.rate_limited' AND subject = :s")
                .param("s", "customer:" + sender).query(Long.class).single();
        assertThat(audited).isEqualTo(limited);
        assertThat(db.sql("SELECT count(*) FROM ops_item WHERE kind = 'recipient_lookup_abuse' AND subject = :s")
                .param("s", "customer:" + sender).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void previewShowsNameDestinationAmountFeeAndTotal() {
        UUID confirmation = UUID.fromString(lookup(senderToken, recipientPhone).body().get("confirmationId").asString());
        var preview = api.call("POST", "/v1/wallet/transfers/preview", senderToken, null,
                Map.of("confirmationId", confirmation, "amount", 2500)).body();
        assertThat(preview.get("verifiedName").asString()).isEqualTo("Ama Mensah");
        assertThat(preview.get("destination").asString()).isEqualTo("+" + recipientPhone);
        assertThat(preview.get("amount").asLong()).isEqualTo(2500);
        assertThat(preview.get("fee").asLong()).isZero();
        assertThat(preview.get("total").asLong()).isEqualTo(2500);
    }

    @Test
    void confirmedTransferIsIdempotentSingleUseAndReconstructableFromTheJournal() throws Exception {
        UUID confirmation = UUID.fromString(lookup(senderToken, recipientPhone).body().get("confirmationId").asString());
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("confirmationId", confirmation, "amount", 4000);

        // Dropped connection: the client never saw the first response and retries with the same key.
        var first = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, key, body);
        var retry = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, key, body);
        assertThat(first.status()).isEqualTo(200);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(first.body().get("verifiedName").asString()).isEqualTo("Ama Mensah");
        // Same key, changed instructions.
        assertThat(api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, key,
                Map.of("confirmationId", confirmation, "amount", 4001)).status()).isEqualTo(409);
        // The confirmation is single use.
        var reuse = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, UUID.randomUUID().toString(), body);
        assertThat(reuse.body().get("code").asString()).isEqualTo("confirmation_used");

        // Concurrent spending: 10 confirmations of 3,000 against the remaining 11,000.
        List<UUID> confirmations = new ArrayList<>();
        db.sql("DELETE FROM recipient_lookup WHERE sender_customer_id = :s").param("s", sender).update();
        for (int i = 0; i < 10; i++) {
            confirmations.add(UUID.fromString(lookup(senderToken, recipientPhone).body().get("confirmationId").asString()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (UUID c : confirmations) {
            results.add(pool.submit(() -> {
                start.await();
                return api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, UUID.randomUUID().toString(),
                        Map.of("confirmationId", c, "amount", 3000)).status();
            }));
        }
        start.countDown();
        int ok = 0;
        for (var f : results) if (f.get(60, TimeUnit.SECONDS) == 200) ok++;
        pool.shutdown();
        assertThat(ok).isEqualTo(3);
        assertThat(ledger.balance(available(sender))).isEqualTo(2_000);
        assertThat(ledger.balance(available(recipient))).isEqualTo(13_000);

        // Both balances reconstruct from the journal alone.
        for (UUID customer : List.of(sender, recipient)) {
            long journal = db.sql("""
                    SELECT coalesce(sum(e.amount), 0) FROM journal_entry e
                    JOIN ledger_account a ON a.id = e.account_id WHERE a.code = :c""")
                    .param("c", available(customer)).query(Long.class).single();
            assertThat(journal).isEqualTo(ledger.balance(available(customer)));
        }
        assertThat(ledger.integrity().ok()).isTrue();
    }
}
