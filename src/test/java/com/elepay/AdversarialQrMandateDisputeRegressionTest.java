package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.checkout.MandateService;
import com.elepay.common.qr.EmvcoQr;
import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayName("Track 3 Adversarial Regression: EMVCo QR, Mandates/Dunning/KYC, and Dispute Rolling Reserve")
class AdversarialQrMandateDisputeRegressionTest extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired MandateService mandateService;
    @Autowired(required = false) com.elepay.identity.KillSwitchService killSwitchService;

    TestApi api;
    String opsToken;
    String checkerOpsToken;
    String merchantOwnerToken;
    String sellerOwnerToken;
    String customerToken;
    UUID marketplaceId;
    UUID sellerId;
    UUID customerId;

    @BeforeEach
    void setUp() {
        if (killSwitchService != null) {
            killSwitchService.setModuleEnabled("consumer-wallet", true, "test setup", "test");
            killSwitchService.setModuleEnabled("mandates", true, "test setup", "test");
        }
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        opsToken = api.token("ops-" + s, "ops");
        checkerOpsToken = api.token("checker-" + s, "ops_admin");
        merchantOwnerToken = api.token("mp-" + s);
        sellerOwnerToken = api.token("seller-" + s);

        marketplaceId = createMerchant(merchantOwnerToken, "MP " + s, "marketplace", 0);
        sellerId = createMerchant(sellerOwnerToken, "Seller " + s, "merchant", 100);

        String sellerCode = api.call("GET", "/v1/merchants/" + sellerId, sellerOwnerToken, null, null)
                .body().get("merchantCode").asString();
        assertThat(api.call("POST", "/v1/merchants/" + marketplaceId + "/sellers", merchantOwnerToken, null,
                Map.of("merchantCode", sellerCode)).status()).isEqualTo(200);
        assertThat(api.call("POST", "/v1/merchants/" + sellerId + "/marketplaces/" + marketplaceId + "/accept",
                sellerOwnerToken, null, null).status()).isEqualTo(200);

        String custSub = "cust-" + s;
        customerToken = api.token(custSub);
        customerId = api.enroll(opsToken, custSub, TestApi.uniquePhone(), "Kwame Tester");
    }

    private UUID createMerchant(String owner, String name, String kind, int feeBps) {
        var created = api.call("POST", "/v1/merchants", owner, null, Map.of(
                "legalName", name + " Ltd", "tradingName", name, "kind", kind, "contactPhone", "0241234567"));
        assertThat(created.status()).isEqualTo(200);
        UUID id = UUID.fromString(created.body().get("id").asString());
        assertThat(api.call("POST", "/v1/ops/merchants/" + id + "/review", opsToken, null,
                Map.of("decision", "approve", "feeBps", feeBps, "feeFixed", 0, "settlementDelayHours", 0))
                .body().get("status").asString()).isEqualTo("active");
        return id;
    }

    private void topUpCustomer(long amount) {
        var topUp = api.call("POST", "/v1/wallet/topups", customerToken, "topup-" + UUID.randomUUID(),
                Map.of("amount", amount, "method", "momo", "phone", "0245550005", "network", "mtn"));
        assertThat(topUp.status()).as("topup: %s", topUp.body()).isEqualTo(200);
        api.call("POST", "/v1/ops/rails/recover", opsToken, null, null);
    }

    // ---- 1. EMVCo QR Nested TLV, UTF-8 Byte Length, and TLV >= 100 ----

    @Test
    @DisplayName("CRIT-02: Duplicate sub-tag 01 inside Tag 26 with valid CRC16 is rejected")
    void emvcoQrRejectsDuplicateSubTagInsideTag26() {
        String smuggledTag26 = EmvcoQr.tlv("00", EmvcoQr.REVERSE_DOMAIN)
                + EmvcoQr.tlv("01", "LEGIT_MERCH")
                + EmvcoQr.tlv("01", "EVIL_MERCH");
        String body = EmvcoQr.tlv("00", "01")
                + EmvcoQr.tlv("01", EmvcoQr.POINT_DYNAMIC)
                + EmvcoQr.tlv("26", smuggledTag26)
                + EmvcoQr.tlv("52", EmvcoQr.DEFAULT_MCC)
                + EmvcoQr.tlv("53", EmvcoQr.DEFAULT_CURRENCY_GHS)
                + EmvcoQr.tlv("54", "10.00")
                + EmvcoQr.tlv("58", EmvcoQr.DEFAULT_COUNTRY)
                + EmvcoQr.tlv("59", "Legit Shop")
                + EmvcoQr.tlv("60", EmvcoQr.DEFAULT_CITY)
                + "6304";
        String payload = body + EmvcoQr.calculateCrc(body);

        EmvcoQr.Decoded decoded = EmvcoQr.decode(payload);
        assertThat(decoded.valid()).isFalse();
    }

    @Test
    @DisplayName("CRIT-02: Duplicate sub-tag inside Tag 62 and trailing data inside Tag 26 are rejected")
    void emvcoQrRejectsDuplicateSubTagInsideTag62AndMalformedNestedTlv() {
        String smuggledTag62 = EmvcoQr.tlv("05", "ORD-1") + EmvcoQr.tlv("05", "ORD-EVIL");
        String body62 = EmvcoQr.tlv("00", "01")
                + EmvcoQr.tlv("01", EmvcoQr.POINT_DYNAMIC)
                + EmvcoQr.tlv("26", EmvcoQr.tlv("00", EmvcoQr.REVERSE_DOMAIN) + EmvcoQr.tlv("01", "MCH001"))
                + EmvcoQr.tlv("52", EmvcoQr.DEFAULT_MCC)
                + EmvcoQr.tlv("53", EmvcoQr.DEFAULT_CURRENCY_GHS)
                + EmvcoQr.tlv("58", EmvcoQr.DEFAULT_COUNTRY)
                + EmvcoQr.tlv("59", "Legit Shop")
                + EmvcoQr.tlv("60", EmvcoQr.DEFAULT_CITY)
                + EmvcoQr.tlv("62", smuggledTag62)
                + "6304";
        assertThat(EmvcoQr.decode(body62 + EmvcoQr.calculateCrc(body62)).valid()).isFalse();

        // Trailing bytes inside Tag 26
        String trailingTag26 = EmvcoQr.tlv("00", EmvcoQr.REVERSE_DOMAIN) + EmvcoQr.tlv("01", "MCH001") + "99";
        String body26 = EmvcoQr.tlv("00", "01")
                + EmvcoQr.tlv("01", EmvcoQr.POINT_DYNAMIC)
                + EmvcoQr.tlv("26", trailingTag26)
                + EmvcoQr.tlv("52", EmvcoQr.DEFAULT_MCC)
                + EmvcoQr.tlv("53", EmvcoQr.DEFAULT_CURRENCY_GHS)
                + EmvcoQr.tlv("58", EmvcoQr.DEFAULT_COUNTRY)
                + EmvcoQr.tlv("59", "Legit Shop")
                + EmvcoQr.tlv("60", EmvcoQr.DEFAULT_CITY)
                + "6304";
        assertThat(EmvcoQr.decode(body26 + EmvcoQr.calculateCrc(body26)).valid()).isFalse();
    }

    @Test
    @DisplayName("EMVCo QR round-trips multibyte UTF-8 merchant name without length corruption")
    void emvcoQrRoundTripsMultibyteUtf8MerchantName() {
        String payload = EmvcoQr.builder()
                .pointOfInitiation(EmvcoQr.POINT_STATIC)
                .merchantCode("MCH001")
                .merchantName("Café Élépay GH₵")
                .merchantCity("Accra")
                .build();

        EmvcoQr.Decoded decoded = EmvcoQr.decode(payload);
        assertThat(decoded.valid()).isTrue();
        assertThat(decoded.merchantName()).isEqualTo("Café Élépay GH₵");
        assertThat(decoded.merchantCity()).isEqualTo("Accra");
        assertThat(decoded.merchantCode()).isEqualTo("MCH001");
    }

    @Test
    @DisplayName("EMVCo QR encoder rejects TLV values exceeding 99 UTF-8 bytes")
    void emvcoQrRejectsTlvExceeding99Bytes() {
        assertThatThrownBy(() -> EmvcoQr.tlv("59", "A".repeat(100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmvcoQr.tlv("59", "₵".repeat(34))) // 34 * 3 = 102 UTF-8 bytes
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 2. Mandate Billing Frequency & BoG Tier-1 KYC Cap ----

    @Test
    @DisplayName("HIGH: Monthly mandate rejects second charge within active billing interval")
    void monthlyMandateRejectsSecondChargeWithinBillingInterval() {
        topUpCustomer(50_000L);

        var created = api.call("POST", "/v1/customers/mandates", customerToken, "mnd-" + UUID.randomUUID(),
                Map.of("merchantId", sellerId.toString(), "maxAmountPesewas", 20_000,
                        "frequency", "monthly", "consentReference", "SUB-FREQ-01"));
        assertThat(created.status()).isEqualTo(200);
        UUID mandateId = UUID.fromString(created.body().get("id").asString());

        // First charge succeeds
        var charge1 = api.call("POST", "/v1/merchants/" + sellerId + "/mandates/" + mandateId + "/charge",
                sellerOwnerToken, "chg1-" + UUID.randomUUID(), Map.of("amount", 10_000, "description", "Month 1"));
        assertThat(charge1.status()).isEqualTo(200);
        assertThat(charge1.body().get("status").asString()).isEqualTo("succeeded");

        // Second charge within same month is rejected with 409 mandate_frequency_exceeded
        var charge2 = api.call("POST", "/v1/merchants/" + sellerId + "/mandates/" + mandateId + "/charge",
                sellerOwnerToken, "chg2-" + UUID.randomUUID(), Map.of("amount", 10_000, "description", "Premature Month 2"));
        assertThat(charge2.status()).isEqualTo(409);
        assertThat(charge2.body().get("code").asString()).isEqualTo("mandate_frequency_exceeded");
    }

    @Test
    @DisplayName("HIGH: Mandate charge enforces BoG Tier-1 KYC daily limit (100,000 pesewas)")
    void mandateChargeEnforcesTier1DailyLimit() {
        // Tier-1 customer max balance is 100,000 and daily limit is 100,000
        topUpCustomer(100_000L);

        var created = api.call("POST", "/v1/customers/mandates", customerToken, "mnd-kyc-" + UUID.randomUUID(),
                Map.of("merchantId", sellerId.toString(), "maxAmountPesewas", 200_000,
                        "frequency", "ad_hoc", "consentReference", "ADHOC-KYC-01"));
        assertThat(created.status()).isEqualTo(200);
        UUID mandateId = UUID.fromString(created.body().get("id").asString());

        // First charge of 60,000 succeeds
        var charge1 = api.call("POST", "/v1/merchants/" + sellerId + "/mandates/" + mandateId + "/charge",
                sellerOwnerToken, "kyc1-" + UUID.randomUUID(), Map.of("amount", 60_000, "description", "Charge 1"));
        assertThat(charge1.status()).isEqualTo(200);

        // Top up another 60,000 (balance returns to 100,000)
        topUpCustomer(60_000L);

        // Second charge of 60,000 (cumulative 120,000 > 100,000 Tier-1 daily limit) is rejected with 409 daily_transfer_limit
        var charge2 = api.call("POST", "/v1/merchants/" + sellerId + "/mandates/" + mandateId + "/charge",
                sellerOwnerToken, "kyc2-" + UUID.randomUUID(), Map.of("amount", 60_000, "description", "Charge 2 over cap"));
        assertThat(charge2.status()).isEqualTo(409);
        assertThat(charge2.body().get("code").asString()).isEqualTo("daily_transfer_limit");
    }

    // ---- 3. MandateService.progressDunning() Customer Active Check ----

    @Test
    @DisplayName("PARTIAL_GAP: progressDunning abandons schedule and suspends mandate for frozen customer")
    void progressDunningAbandonsAndSuspendsForFrozenCustomer() {
        var created = api.call("POST", "/v1/customers/mandates", customerToken, "mnd-dun-" + UUID.randomUUID(),
                Map.of("merchantId", sellerId.toString(), "maxAmountPesewas", 10_000,
                        "frequency", "monthly", "consentReference", "DUN-FROZEN-01"));
        assertThat(created.status()).isEqualTo(200);
        UUID mandateId = UUID.fromString(created.body().get("id").asString());

        // Charge with 0 balance triggers dunning schedule
        var failed = api.call("POST", "/v1/merchants/" + sellerId + "/mandates/" + mandateId + "/charge",
                sellerOwnerToken, "dun1-" + UUID.randomUUID(), Map.of("amount", 5_000, "description", "Unfunded"));
        assertThat(failed.status()).isEqualTo(409);

        UUID scheduleId = db.sql("""
                SELECT ds.id FROM dunning_schedule ds
                JOIN mandate_charge mc ON mc.id = ds.charge_id
                WHERE mc.mandate_id = :m AND ds.status = 'active'
                LIMIT 1""")
                .param("m", mandateId).query(UUID.class).single();

        // Freeze customer
        var freezeRes = api.call("PUT", "/v1/ops/customers/" + customerId + "/status", opsToken, null,
                Map.of("status", "frozen", "reason", "Adversarial dunning regression test freeze"));
        assertThat(freezeRes.status()).isEqualTo(200);

        // Progress dunning: must abandon schedule and suspend mandate without advancing step
        mandateService.progressDunning(scheduleId);

        String scheduleStatus = db.sql("SELECT status FROM dunning_schedule WHERE id = :id")
                .param("id", scheduleId).query(String.class).single();
        String mandateStatus = db.sql("SELECT status FROM mandate WHERE id = :id")
                .param("id", mandateId).query(String.class).single();
        assertThat(scheduleStatus).isEqualTo("abandoned");
        assertThat(mandateStatus).isEqualTo("suspended");
    }

    // ---- 4. Rolling Reserve Restoration on merchant_won Dispute Resolution ----

    @Test
    @DisplayName("HIGH: merchant_won dispute resolution restores rolling reserve to reserved bucket, not pending")
    void merchantWonDisputeRestoresRollingReserveToReservedBucket() {
        topUpCustomer(20_000L);

        // Create checkout for 10,000 with 20% rolling reserve (2,000 reserved, 100 fee, 7,900 pending)
        var created = api.call("POST", "/v1/merchants/" + marketplaceId + "/checkouts", merchantOwnerToken,
                UUID.randomUUID().toString(),
                Map.of("description", "Reserve Dispute Order", "allocations", List.of(
                        Map.of("merchantId", sellerId.toString(), "kind", "sale", "amount", 10_000,
                                "description", "Item", "reserveBps", 2000))));
        assertThat(created.status()).isEqualTo(200);
        String ref = created.body().get("reference").asString();

        var paid = api.call("POST", "/v1/checkouts/" + ref + "/pay/wallet", customerToken,
                UUID.randomUUID().toString(), null);
        assertThat(paid.status()).isEqualTo(200);

        String sellerPending = Ledger.code("merchant", sellerId, "pending", Ledger.GHS);
        String sellerReserved = Ledger.code("merchant", sellerId, "reserved", Ledger.GHS);
        assertThat(ledger.balance(sellerPending)).isEqualTo(7_900L);
        assertThat(ledger.balance(sellerReserved)).isEqualTo(2_000L);

        // Open full dispute (10,000): pulls 7,900 from pending, 2,000 from reserved, 100 from deferred revenue
        var opened = api.call("POST", "/v1/ops/disputes", opsToken, null, Map.of(
                "checkout", ref, "allocationLine", 0, "amount", 10_000, "reason", "Customer claim"));
        assertThat(opened.status()).isEqualTo(200);
        UUID disputeId = UUID.fromString(opened.body().get("id").asString());
        assertThat(ledger.balance(sellerPending)).isEqualTo(0L);
        assertThat(ledger.balance(sellerReserved)).isEqualTo(10_000L);

        // Resolve dispute merchant_won: 2,000 must stay in reserved, only 7,900 returns to pending
        var resolved = api.call("POST", "/v1/ops/disputes/" + disputeId + "/resolve", checkerOpsToken, null,
                Map.of("outcome", "merchant_won", "note", "Merchant provided proof of delivery"));
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.body().get("status").asString()).isEqualTo("merchant_won");

        assertThat(ledger.balance(sellerReserved)).isEqualTo(2_000L);
        assertThat(ledger.balance(sellerPending)).isEqualTo(7_900L);

        long reserveReleased = db.sql("""
                SELECT a.reserve_released FROM checkout_allocation a
                JOIN checkout c ON c.id = a.checkout_id
                WHERE c.reference = :ref AND a.line = 0""")
                .param("ref", ref).query(Long.class).single();
        assertThat(reserveReleased).isEqualTo(0L);
    }
}
