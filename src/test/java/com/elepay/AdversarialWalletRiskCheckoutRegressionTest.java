package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import com.elepay.wallet.TransferLimits;
import com.elepay.wallet.Wallets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression tests for Track 4 adversarial remediation:
 * 1. Recipient lookup verifiedName masking
 * 2. Recipient confirmation (amount, currency) binding
 * 3. RiskEngineService enforcement on Wallets.send() and merchant Payouts.create()
 * 4. TransferLimits inclusion of withdrawal and mandate_charge in daily debit cap
 * 5. Checkout payerPhone (boundPhone) enforcement on payWithWallet, card, and ghqr
 */
class AdversarialWalletRiskCheckoutRegressionTest extends PostgresTestBase {

    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired TransferLimits transferLimits;
    @Autowired TransactionTemplate tx;

    TestApi api;
    String ops;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        ops = api.token("ops-" + UUID.randomUUID(), "ops");
    }

    private void fundCustomer(UUID customerId, long amount) {
        ledger.post(Ledger.Posting.of("reg-fund-" + UUID.randomUUID(), UUID.randomUUID().toString(), "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), amount),
                Ledger.Leg.credit(Wallets.available(customerId), amount))));
    }

    private void fundMerchant(UUID merchantId, long amount) {
        ledger.post(Ledger.Posting.of("reg-mfund-" + UUID.randomUUID(), UUID.randomUUID().toString(), "settlement", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), amount),
                Ledger.Leg.credit(Ledger.code("merchant", merchantId, "available", Ledger.GHS), amount))));
    }

    private UUID createApprovedMerchant(String ownerToken) {
        var created = api.call("POST", "/v1/merchants", ownerToken, null, Map.of(
                "legalName", "Reg Merchant Ltd",
                "tradingName", "Reg Shop",
                "kind", "merchant",
                "contactPhone", "0241234567"));
        assertThat(created.status()).isEqualTo(200);
        UUID merchantId = UUID.fromString(created.body().get("id").asString());
        var approved = api.call("POST", "/v1/ops/merchants/" + merchantId + "/review", ops, null,
                Map.of("decision", "approve"));
        assertThat(approved.status()).isEqualTo(200);
        return merchantId;
    }

    @Test
    void recipientLookupMasksVerifiedName() {
        String s = UUID.randomUUID().toString();
        String senderToken = api.token("mask-sender-" + s);
        api.enroll(ops, "mask-sender-" + s, TestApi.uniquePhone(), "Kofi Sender");

        String recPhone1 = TestApi.uniquePhone();
        api.enroll(ops, "mask-rec1-" + s, recPhone1, "Ama Boateng SecretFullName");

        String recPhone2 = TestApi.uniquePhone();
        api.enroll(ops, "mask-rec2-" + s, recPhone2, "Abena");

        var multi = api.call("POST", "/v1/wallet/recipients/lookup", senderToken, null,
                Map.of("rail", "wallet", "phone", recPhone1));
        assertThat(multi.status()).isEqualTo(200);
        String maskedMulti = multi.body().get("verifiedName").asString();
        assertThat(maskedMulti).isEqualTo("Ama B*** S***");
        assertThat(maskedMulti).doesNotContain("Boateng").doesNotContain("SecretFullName");

        var single = api.call("POST", "/v1/wallet/recipients/lookup", senderToken, null,
                Map.of("rail", "wallet", "phone", recPhone2));
        assertThat(single.status()).isEqualTo(200);
        assertThat(single.body().get("verifiedName").asString()).isEqualTo("Ab***");
    }

    @Test
    void recipientConfirmationBindsAmountAndCurrency() {
        String s = UUID.randomUUID().toString();
        String senderToken = api.token("bind-sender-" + s);
        UUID senderId = api.enroll(ops, "bind-sender-" + s, TestApi.uniquePhone(), "Kofi Binder");
        String recPhone = TestApi.uniquePhone();
        UUID recId = api.enroll(ops, "bind-rec-" + s, recPhone, "Ama Receiver");
        fundCustomer(senderId, 50_000L);

        // 1. Amountless lookup cannot be used directly on POST /v1/wallet/transfers
        var unboundLookup = api.call("POST", "/v1/wallet/recipients/lookup", senderToken, null,
                Map.of("rail", "wallet", "phone", recPhone));
        assertThat(unboundLookup.status()).isEqualTo(200);
        UUID unboundConfId = UUID.fromString(unboundLookup.body().get("confirmationId").asString());

        var unboundAttempt = api.call("POST", "/v1/wallet/transfers", senderToken, UUID.randomUUID().toString(),
                Map.of("recipient", recId, "amount", 25_000L, "confirmationId", unboundConfId));
        assertThat(unboundAttempt.status()).isEqualTo(409);
        assertThat(unboundAttempt.body().get("code").asString()).isEqualTo("confirmation_unbound");

        // 2. Bound lookup rejects mismatched amount and mismatched currency
        var boundLookup = api.call("POST", "/v1/wallet/recipients/lookup", senderToken, null,
                Map.of("rail", "wallet", "phone", recPhone, "amount", 5_000L, "currency", "GHS"));
        assertThat(boundLookup.status()).isEqualTo(200);
        UUID boundConfId = UUID.fromString(boundLookup.body().get("confirmationId").asString());

        var wrongAmount = api.call("POST", "/v1/wallet/transfers", senderToken, UUID.randomUUID().toString(),
                Map.of("recipient", recId, "amount", 25_000L, "currency", "GHS", "confirmationId", boundConfId));
        assertThat(wrongAmount.status()).isEqualTo(409);
        assertThat(wrongAmount.body().get("code").asString()).isEqualTo("confirmation_mismatch");

        var wrongCurrency = api.call("POST", "/v1/wallet/transfers", senderToken, UUID.randomUUID().toString(),
                Map.of("recipient", recId, "amount", 5_000L, "currency", "USD", "confirmationId", boundConfId));
        assertThat(wrongCurrency.status()).isEqualTo(409);
        assertThat(wrongCurrency.body().get("code").asString()).isEqualTo("confirmation_mismatch");

        var wrongAmountConfirmed = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, UUID.randomUUID().toString(),
                Map.of("confirmationId", boundConfId, "amount", 6_000L));
        assertThat(wrongAmountConfirmed.status()).isEqualTo(409);
        assertThat(wrongAmountConfirmed.body().get("code").asString()).isEqualTo("confirmation_mismatch");

        // 3. Exact matching (amount, currency) succeeds
        var exact = api.call("POST", "/v1/wallet/transfers", senderToken, UUID.randomUUID().toString(),
                Map.of("recipient", recId, "amount", 5_000L, "currency", "GHS", "confirmationId", boundConfId));
        assertThat(exact.status()).isEqualTo(200);
        assertThat(ledger.balance(Wallets.available(recId))).isEqualTo(5_000L);
        assertThat(ledger.balance(Wallets.available(senderId))).isEqualTo(45_000L);
    }

    @Test
    void riskEngineEvaluatesWalletTransfersAndMerchantPayouts() {
        String s = UUID.randomUUID().toString();
        String senderToken = api.token("risk-sender-" + s);
        UUID senderId = api.enroll(ops, "risk-sender-" + s, TestApi.uniquePhone(), "Kofi Risk");
        UUID recId = api.enroll(ops, "risk-rec-" + s, TestApi.uniquePhone(), "Ama Risk");
        fundCustomer(senderId, 50_000L);

        // 10 small transfers succeed within the 1h velocity cap of 10
        for (int i = 0; i < 10; i++) {
            var res = api.call("POST", "/v1/wallet/transfers", senderToken, UUID.randomUUID().toString(),
                    Map.of("recipient", recId, "amount", 100L));
            assertThat(res.status()).isEqualTo(200);
        }
        // 11th transfer in the same hour is blocked by RiskEngineService (RULE_VEL_002 -> REJECT)
        var blockedTransfer = api.call("POST", "/v1/wallet/transfers", senderToken, UUID.randomUUID().toString(),
                Map.of("recipient", recId, "amount", 100L));
        assertThat(blockedTransfer.status()).isEqualTo(403);
        assertThat(blockedTransfer.body().get("code").asString()).isEqualTo("risk_blocked");

        // Merchant payout exceeding 1h velocity amount cap (500,000 pesewas) is blocked by RiskEngineService
        String ownerToken = api.token("risk-owner-" + s);
        UUID merchantId = createApprovedMerchant(ownerToken);
        fundMerchant(merchantId, 700_000L);
        db.sql("""
                INSERT INTO settlement_destination
                    (id, owner_type, owner_id, type, network, account_number, account_name, verification, verified_name, status, created_by, cooling_off_until)
                VALUES (:id, 'merchant', :m, 'momo', 'mtn', '233245550005', 'Merchant Owner', 'verified', 'Merchant Owner', 'active', 'test', now() - interval '1 hour')""")
                .param("id", UUID.randomUUID()).param("m", merchantId).update();

        var blockedPayout = api.call("POST", "/v1/merchants/" + merchantId + "/payout-batches",
                ownerToken, UUID.randomUUID().toString(),
                Map.of("description", "Excessive payout", "items", List.of(Map.of(
                        "reference", "po-risk-high",
                        "amount", 600_000L,
                        "type", "momo",
                        "network", "mtn",
                        "accountNumber", "0245550005",
                        "accountName", "Merchant Owner"))));
        assertThat(blockedPayout.status()).isEqualTo(409);
        assertThat(blockedPayout.body().get("code").asString()).isEqualTo("risk_blocked");

        // Normal low-risk merchant payout still passes cleanly
        var okPayout = api.call("POST", "/v1/merchants/" + merchantId + "/payout-batches",
                ownerToken, UUID.randomUUID().toString(),
                Map.of("description", "Normal payout", "items", List.of(Map.of(
                        "reference", "po-risk-ok",
                        "amount", 10_000L,
                        "type", "momo",
                        "network", "mtn",
                        "accountNumber", "0245550005",
                        "accountName", "Merchant Owner"))));
        assertThat(okPayout.status()).isEqualTo(200);
    }

    @Test
    void transferLimitsIncludesWithdrawalAndMandateChargeInDailyDebit() {
        String s = UUID.randomUUID().toString();
        UUID sender = api.enroll(ops, "lim-sender-" + s, TestApi.uniquePhone(), "Tier1 Sender");
        UUID recipient = api.enroll(ops, "lim-rec-" + s, TestApi.uniquePhone(), "Tier1 Recipient");

        // Record today's withdrawal (40,000) and mandate_charge (40,000) => 80,000 used of 100,000 Tier-1 cap
        db.sql("""
                INSERT INTO wallet_operation (id, reference, customer_id, kind, amount, status)
                VALUES (:id, :ref, :c, 'withdrawal', 40000, 'completed')""")
                .param("id", UUID.randomUUID()).param("ref", "WLT-WD-" + UUID.randomUUID()).param("c", sender).update();
        db.sql("""
                INSERT INTO wallet_operation (id, reference, customer_id, kind, amount, status)
                VALUES (:id, :ref, :c, 'mandate_charge', 40000, 'completed')""")
                .param("id", UUID.randomUUID()).param("ref", "WLT-MC-" + UUID.randomUUID()).param("c", sender).update();

        // Remaining 20,000 fits exactly
        tx.executeWithoutResult(status -> transferLimits.check(sender, recipient, 20_000L));

        // 20,001 exceeds remaining Tier-1 daily cap
        ApiException ex = assertThrows(ApiException.class, () ->
                tx.executeWithoutResult(status -> transferLimits.check(sender, recipient, 20_001L)));
        assertThat(ex.code()).isEqualTo("daily_transfer_limit");
    }

    @Test
    void checkoutPayerPhoneEnforcedOnWalletCardAndGhqr() {
        String s = UUID.randomUUID().toString();
        String ownerToken = api.token("chk-owner-" + s);
        UUID merchantId = createApprovedMerchant(ownerToken);

        String boundPhone = TestApi.uniquePhone();
        String otherPhone = TestApi.uniquePhone();
        String boundToken = api.token("chk-bound-" + s);
        String otherToken = api.token("chk-other-" + s);
        UUID boundCustomer = api.enroll(ops, "chk-bound-" + s, boundPhone, "Bound Payer");
        UUID otherCustomer = api.enroll(ops, "chk-other-" + s, otherPhone, "Other Payer");
        fundCustomer(boundCustomer, 50_000L);
        fundCustomer(otherCustomer, 50_000L);

        var created1 = api.call("POST", "/v1/merchants/" + merchantId + "/checkouts",
                ownerToken, UUID.randomUUID().toString(),
                Map.of("amount", 5_000L, "payerPhone", "+" + boundPhone));
        assertThat(created1.status()).isEqualTo(200);
        String ref1 = created1.body().get("reference").asString();

        // Wrong wallet customer rejected with 403 payer_phone_mismatch
        var wrongWallet = api.call("POST", "/v1/checkouts/" + ref1 + "/pay/wallet",
                otherToken, UUID.randomUUID().toString(), Map.of());
        assertThat(wrongWallet.status()).isEqualTo(403);
        assertThat(wrongWallet.body().get("code").asString()).isEqualTo("payer_phone_mismatch");

        // Card without phone or with wrong phone rejected with 403 payer_phone_mismatch
        var cardNoPhone = api.call("POST", "/v1/public/checkouts/" + ref1 + "/pay",
                null, UUID.randomUUID().toString(), Map.of("method", "card", "email", "a@example.com"));
        assertThat(cardNoPhone.status()).isEqualTo(403);
        assertThat(cardNoPhone.body().get("code").asString()).isEqualTo("payer_phone_mismatch");

        var cardWrongPhone = api.call("POST", "/v1/public/checkouts/" + ref1 + "/pay",
                null, UUID.randomUUID().toString(),
                Map.of("method", "card", "phone", "+" + otherPhone, "email", "a@example.com"));
        assertThat(cardWrongPhone.status()).isEqualTo(403);
        assertThat(cardWrongPhone.body().get("code").asString()).isEqualTo("payer_phone_mismatch");

        // GhQR without phone or with wrong phone rejected with 403 payer_phone_mismatch
        var ghqrNoPhone = api.call("POST", "/v1/public/checkouts/" + ref1 + "/pay",
                null, UUID.randomUUID().toString(), Map.of("method", "ghqr"));
        assertThat(ghqrNoPhone.status()).isEqualTo(403);
        assertThat(ghqrNoPhone.body().get("code").asString()).isEqualTo("payer_phone_mismatch");

        var ghqrWrongPhone = api.call("POST", "/v1/public/checkouts/" + ref1 + "/pay",
                null, UUID.randomUUID().toString(), Map.of("method", "ghqr", "phone", "+" + otherPhone));
        assertThat(ghqrWrongPhone.status()).isEqualTo(403);
        assertThat(ghqrWrongPhone.body().get("code").asString()).isEqualTo("payer_phone_mismatch");

        // Matching wallet customer succeeds
        var okWallet = api.call("POST", "/v1/checkouts/" + ref1 + "/pay/wallet",
                boundToken, UUID.randomUUID().toString(), Map.of());
        assertThat(okWallet.status()).isEqualTo(200);
        assertThat(okWallet.body().get("status").asString()).isEqualTo("paid");

        // Matching local-format phone on GhQR normalizes and succeeds
        var created2 = api.call("POST", "/v1/merchants/" + merchantId + "/checkouts",
                ownerToken, UUID.randomUUID().toString(),
                Map.of("amount", 4_000L, "payerPhone", "+" + boundPhone));
        assertThat(created2.status()).isEqualTo(200);
        String ref2 = created2.body().get("reference").asString();

        String localBoundPhone = "0" + boundPhone.substring(3);
        var okGhqr = api.call("POST", "/v1/public/checkouts/" + ref2 + "/pay",
                null, UUID.randomUUID().toString(), Map.of("method", "ghqr", "phone", localBoundPhone));
        assertThat(okGhqr.status()).isEqualTo(200);
        assertThat(okGhqr.body().get("status").asString()).isEqualTo("processing");
    }
}
