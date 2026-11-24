package com.elepay.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.ProportionalAllocation;
import com.elepay.common.TokenBucketRateLimiter;
import com.elepay.fees.FeeSchedule;
import com.elepay.ledger.Ledger;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public class Challenger2AdversarialTest extends PostgresTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private Ledger ledger;

    @Autowired
    private TransferLimits limits;

    @Autowired
    private Audit audit;

    @Autowired
    private JdbcClient db;

    @Autowired
    private TransactionTemplate tx;

    private TestApi api;
    private String opsToken;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        opsToken = api.token("ops-challenger2", "ops");
    }

    private static String available(UUID id) {
        return Ledger.code("customer", id, "available", Ledger.GHS);
    }

    private UUID createCustomer(String phone, int tier, String name) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO customer (id, subject, phone, display_name, kyc_tier, status)
                VALUES (:id, :subject, :phone, :name, :tier, 'active')
                """)
                .param("id", id)
                .param("subject", "sub-" + id)
                .param("phone", phone)
                .param("name", name)
                .param("tier", tier)
                .update();

        for (String bucket : List.of("available", "pending", "reserved")) {
            ledger.open("customer", id, bucket, Ledger.GHS);
        }
        return id;
    }

    private void fundWallet(UUID customerId, long amount) {
        ledger.post(Ledger.Posting.of("fund-challenger2-" + UUID.randomUUID(), "tx-" + UUID.randomUUID(), "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), amount),
                Ledger.Leg.credit(available(customerId), amount)
        )));
    }

    // =========================================================================
    // 1. BOUNDARY AMOUNTS & ARITHMETIC OVERFLOW
    // =========================================================================
    @Nested
    @DisplayName("1. Boundary Amounts, Extreme Integers & Arithmetic Overflow")
    class BoundaryAmountTests {

        @Test
        @DisplayName("FeeSchedule: 0 pesewas, 1 pesewa, Long.MAX_VALUE, and negative amounts")
        void feeScheduleBoundaries() {
            FeeSchedule.Plan plan = new FeeSchedule.Plan("standard", 15, 5_000, List.of(
                    new FeeSchedule.Tier(10_000, 150), // 1.5% up to GH₵100
                    new FeeSchedule.Tier(100_000, 100), // 1.0% up to GH₵1,000
                    new FeeSchedule.Tier(Long.MAX_VALUE, 50) // 0.5% above
            ));

            // 0 pesewas: should return fixed fee (15)
            assertEquals(15, FeeSchedule.calculate(plan, 0));

            // 1 pesewa: portion = 1, weighted = 150, (150 + 5000) / 10000 = 0. Total = 0 + 15 = 15
            assertEquals(15, FeeSchedule.calculate(plan, 1));

            // Negative amount must be rejected
            assertThrows(IllegalArgumentException.class, () -> FeeSchedule.calculate(plan, -1));
            assertThrows(IllegalArgumentException.class, () -> FeeSchedule.calculate(plan, -100));
            assertThrows(IllegalArgumentException.class, () -> FeeSchedule.calculate(plan, Long.MIN_VALUE));

            // Long.MAX_VALUE must not cause integer overflow or unhandled exceptions, should hit cap
            assertEquals(5_000, FeeSchedule.calculate(plan, Long.MAX_VALUE));

            // Plan with no cap (capMinor = Long.MAX_VALUE): handles Long.MAX_VALUE safely with BigInteger
            FeeSchedule.Plan uncapped = new FeeSchedule.Plan("uncapped", 0, Long.MAX_VALUE, List.of(
                    new FeeSchedule.Tier(Long.MAX_VALUE, 100) // 1%
            ));
            long uncappedFee = FeeSchedule.calculate(uncapped, Long.MAX_VALUE);
            assertTrue(uncappedFee > 0);
            assertEquals(92_233_720_368_547_758L, uncappedFee); // BigInteger math: Long.MAX_VALUE / 100
        }

        @Test
        @DisplayName("ProportionalAllocation: 0 units, 1 pesewa, Long.MAX_VALUE, negative weights")
        void proportionalAllocationBoundaries() {
            // 0 units across weights returns all zeros
            List<Long> allocZero = ProportionalAllocation.allocate(0L, List.of(10L, 20L, 30L));
            assertEquals(List.of(0L, 0L, 0L), allocZero);

            // Negative total must throw IllegalArgumentException
            assertThrows(IllegalArgumentException.class, () ->
                    ProportionalAllocation.allocate(-1L, List.of(10L, 20L)));
            assertThrows(IllegalArgumentException.class, () ->
                    ProportionalAllocation.allocate(Long.MIN_VALUE, List.of(10L, 20L)));

            // Negative weight must throw IllegalArgumentException
            assertThrows(IllegalArgumentException.class, () ->
                    ProportionalAllocation.allocate(100L, List.of(10L, -5L)));

            // 1 pesewa distributed across equal weights: exactly 1 pesewa allocated, no loss or duplication
            List<Long> allocOne = ProportionalAllocation.allocate(1L, List.of(10L, 10L, 10L));
            assertEquals(List.of(1L, 0L, 0L), allocOne);
            assertEquals(1L, allocOne.stream().mapToLong(Long::longValue).sum());

            // Long.MAX_VALUE distributed across weights: no long overflow in sumFloor or remainder
            List<Long> allocMax = ProportionalAllocation.allocate(Long.MAX_VALUE, List.of(1L, 1L));
            assertEquals(2, allocMax.size());
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE),
                    BigInteger.valueOf(allocMax.get(0)).add(BigInteger.valueOf(allocMax.get(1))));
            assertEquals(4611686018427387904L, allocMax.get(0));
            assertEquals(4611686018427387903L, allocMax.get(1));

            // Extreme weights: Long.MAX_VALUE weights
            List<Long> allocLargeWeights = ProportionalAllocation.allocate(1000L, List.of(Long.MAX_VALUE, Long.MAX_VALUE));
            assertEquals(List.of(500L, 500L), allocLargeWeights);
            assertEquals(1000L, allocLargeWeights.stream().mapToLong(Long::longValue).sum());
        }

        @Test
        @DisplayName("Ledger & Limits: Non-positive amounts rejected")
        void nonPositiveAmountsRejected() {
            UUID sender = createCustomer(TestApi.uniquePhone(), 1, "Boundary Sender");
            UUID recipient = createCustomer(TestApi.uniquePhone(), 1, "Boundary Recipient");

            // Zero amount rejected by TransferLimits
            ApiException exZero = assertThrows(ApiException.class, () ->
                    tx.executeWithoutResult(status -> limits.check(sender, recipient, 0L)));
            assertEquals("invalid_amount", exZero.code());

            // Negative amount rejected by TransferLimits
            ApiException exNeg = assertThrows(ApiException.class, () ->
                    tx.executeWithoutResult(status -> limits.check(sender, recipient, -100L)));
            assertEquals("invalid_amount", exNeg.code());

            // Ledger posting with zero leg amount rejected
            Ledger.Posting zeroLeg = Ledger.Posting.of("scope-zero", "key-zero", "transfer", List.of(
                    new Ledger.Leg(available(sender), 0L),
                    new Ledger.Leg(available(recipient), 0L)
            ));
            ApiException exLedgerZero = assertThrows(ApiException.class, () -> ledger.post(zeroLeg));
            assertEquals("zero_amount", exLedgerZero.code());
        }
    }

    // =========================================================================
    // 2. KYC TIER LIMIT BOUNDARIES
    // =========================================================================
    @Nested
    @DisplayName("2. KYC Tier Limit Boundaries & Ceilings")
    class KycTierBoundaryTests {

        @Test
        @DisplayName("Tier 1: Exactly at daily limit (100,000) passes; 1 pesewa over (100,001) fails")
        void tier1DailyBoundary() {
            UUID sender = createCustomer(TestApi.uniquePhone(), 1, "Tier1 Sender");
            UUID recipient = createCustomer(TestApi.uniquePhone(), 3, "Tier3 Recipient");

            // Exactly at daily limit (100,000 pesewas)
            assertDoesNotThrow(() ->
                    tx.executeWithoutResult(status -> limits.check(sender, recipient, 100_000L)));

            // 1 pesewa over daily limit (100,001 pesewas)
            ApiException ex = assertThrows(ApiException.class, () ->
                    tx.executeWithoutResult(status -> limits.check(sender, recipient, 100_001L)));
            assertEquals("daily_transfer_limit", ex.code());
        }

        @Test
        @DisplayName("Tier 2: Exactly at daily limit (500,000) passes; 500,001 fails")
        void tier2DailyBoundary() {
            UUID sender = createCustomer(TestApi.uniquePhone(), 2, "Tier2 Sender");
            UUID recipient = createCustomer(TestApi.uniquePhone(), 3, "Tier3 Recipient");

            // Exactly 500,000 pesewas passes
            assertDoesNotThrow(() ->
                    tx.executeWithoutResult(status -> limits.check(sender, recipient, 500_000L)));

            // 500,001 pesewas fails
            ApiException ex = assertThrows(ApiException.class, () ->
                    tx.executeWithoutResult(status -> limits.check(sender, recipient, 500_001L)));
            assertEquals("daily_transfer_limit", ex.code());
        }

        @Test
        @DisplayName("Tier 3: Exactly at daily limit (2,000,000) passes; 2,000,001 fails")
        void tier3DailyBoundary() {
            UUID sender = createCustomer(TestApi.uniquePhone(), 3, "Tier3 Sender");
            UUID recipient = createCustomer(TestApi.uniquePhone(), 3, "Tier3 Recipient");

            // Exactly 2,000,000 pesewas passes
            assertDoesNotThrow(() ->
                    tx.executeWithoutResult(status -> limits.check(sender, recipient, 2_000_000L)));

            // 2,000,001 pesewas fails
            ApiException ex = assertThrows(ApiException.class, () ->
                    tx.executeWithoutResult(status -> limits.check(sender, recipient, 2_000_001L)));
            assertEquals("daily_transfer_limit", ex.code());
        }

        @Test
        @DisplayName("Monthly limit boundary: cumulative usage up to monthly limit succeeds, 1 pesewa over fails")
        void monthlyLimitBoundary() {
            UUID sender = createCustomer(TestApi.uniquePhone(), 1, "Monthly Sender"); // Tier 1 monthly limit: 1,000,000
            UUID recipient = createCustomer(TestApi.uniquePhone(), 3, "Tier3 Recipient");

            // Insert past transfer operations earlier this month totaling 950,000 pesewas (split over earlier days)
            db.sql("""
                    INSERT INTO wallet_operation (id, reference, customer_id, kind, amount, status, created_at)
                    VALUES (:id, :ref, :c, 'transfer_out', 950000, 'completed', now() - interval '2 days')
                    """)
                    .param("id", UUID.randomUUID())
                    .param("ref", "WLT-PAST-" + UUID.randomUUID())
                    .param("c", sender)
                    .update();

            // Sender has 50,000 pesewas remaining this month (and 100,000 daily).
            // A transfer of exactly 50,000 pesewas reaches monthly limit of 1,000,000 -> succeeds!
            assertDoesNotThrow(() ->
                    tx.executeWithoutResult(status -> limits.check(sender, recipient, 50_000L)));

            // A transfer of 50,001 pesewas brings monthly total to 1,000,001 -> fails with monthly_transfer_limit!
            ApiException ex = assertThrows(ApiException.class, () ->
                    tx.executeWithoutResult(status -> limits.check(sender, recipient, 50_001L)));
            assertEquals("monthly_transfer_limit", ex.code());
        }

        @Test
        @DisplayName("Recipient balance ceiling: exactly reaches ceiling succeeds, 1 pesewa over fails")
        void recipientBalanceCeiling() {
            UUID sender = createCustomer(TestApi.uniquePhone(), 3, "Rich Sender");
            UUID recipientTier1 = createCustomer(TestApi.uniquePhone(), 1, "Tier1 Recipient"); // Max balance 100,000 pesewas

            // Fund recipient to 95,000 pesewas
            fundWallet(recipientTier1, 95_000L);

            // Transfer of 5,000 pesewas brings recipient balance to exactly 100,000 pesewas -> succeeds
            assertDoesNotThrow(() ->
                    tx.executeWithoutResult(status -> limits.check(sender, recipientTier1, 5_000L)));

            // Transfer of 5,001 pesewas brings recipient balance to 100,001 pesewas -> fails
            ApiException ex = assertThrows(ApiException.class, () ->
                    tx.executeWithoutResult(status -> limits.check(sender, recipientTier1, 5_001L)));
            assertEquals("recipient_balance_limit", ex.code());
            assertThat(ex.getMessage()).contains("recipient max balance limit of 100000 pesewas");

            // When recipient already has 100,000 pesewas, even 1 pesewa is rejected
            fundWallet(recipientTier1, 5_000L); // Recipient now has 100,000
            ApiException exFull = assertThrows(ApiException.class, () ->
                    tx.executeWithoutResult(status -> limits.check(sender, recipientTier1, 1L)));
            assertEquals("recipient_balance_limit", exFull.code());
        }
    }

    // =========================================================================
    // 3. CONFIRMATION TOKEN ATTACKS
    // =========================================================================
    @Nested
    @DisplayName("3. Confirmation Token Attacks & Tampering")
    class ConfirmationTokenTests {

        @Test
        @DisplayName("Token Reuse: Reusing an already consumed confirmation token fails with 409 confirmation_used")
        void confirmationTokenReuseRejected() {
            String s = UUID.randomUUID().toString();
            String senderToken = api.token("sender-" + s);
            String recPhone = TestApi.uniquePhone();
            UUID senderId = api.enroll(opsToken, "sender-" + s, TestApi.uniquePhone(), "Kofi One");
            UUID recId = api.enroll(opsToken, "recipient-" + s, recPhone, "Ama Two");

            fundWallet(senderId, 50_000L);

            // 1. Lookup recipient to get confirmation token
            var lookupRes = api.call("POST", "/v1/wallet/recipients/lookup", senderToken, null,
                    Map.of("rail", "wallet", "phone", recPhone));
            assertThat(lookupRes.status()).isEqualTo(200);
            UUID confId = UUID.fromString(lookupRes.body().get("confirmationId").asString());

            // 2. First transfer: consumes confirmation token
            String key1 = UUID.randomUUID().toString();
            var transfer1 = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, key1,
                    Map.of("confirmationId", confId, "amount", 5_000L));
            assertThat(transfer1.status()).isEqualTo(200);
            assertThat(transfer1.body().get("status").asString()).isEqualTo("completed");

            // 3. Attack: Attempt to reuse the SAME confirmation token with a NEW idempotency key
            String key2 = UUID.randomUUID().toString();
            var transferReuse = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, key2,
                    Map.of("confirmationId", confId, "amount", 5_000L));
            assertThat(transferReuse.status()).isEqualTo(409);
            assertThat(transferReuse.body().get("code").asString()).isEqualTo("confirmation_used");

            // Verify balances: recipient received exactly 5,000, sender debited exactly 5,000
            assertThat(ledger.balance(available(senderId))).isEqualTo(45_000L);
            assertThat(ledger.balance(available(recId))).isEqualTo(5_000L);
        }

        @Test
        @DisplayName("Expired Token: Using a token expired past its TTL fails with 409 confirmation_expired")
        void expiredConfirmationTokenRejected() {
            String s = UUID.randomUUID().toString();
            String senderToken = api.token("sender-" + s);
            String recPhone = TestApi.uniquePhone();
            UUID senderId = api.enroll(opsToken, "sender-" + s, TestApi.uniquePhone(), "Kofi Expire");
            UUID recId = api.enroll(opsToken, "recipient-" + s, recPhone, "Ama Expire");

            fundWallet(senderId, 50_000L);

            var lookupRes = api.call("POST", "/v1/wallet/recipients/lookup", senderToken, null,
                    Map.of("rail", "wallet", "phone", recPhone));
            UUID confId = UUID.fromString(lookupRes.body().get("confirmationId").asString());

            // Manually expire the token in database (>5 minutes ago)
            db.sql("UPDATE recipient_confirmation SET expires_at = now() - interval '6 minutes' WHERE id = :id")
                    .param("id", confId).update();

            // Attempt transfer with expired token
            var transferRes = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, UUID.randomUUID().toString(),
                    Map.of("confirmationId", confId, "amount", 5_000L));
            assertThat(transferRes.status()).isEqualTo(409);
            assertThat(transferRes.body().get("code").asString()).isEqualTo("confirmation_expired");

            // Balances unchanged
            assertThat(ledger.balance(available(senderId))).isEqualTo(50_000L);
            assertThat(ledger.balance(available(recId))).isZero();
        }

        @Test
        @DisplayName("Altered Recipient Binding: Token for recipient A cannot be used to pay recipient B")
        void alteredRecipientBindingRejected() {
            String s = UUID.randomUUID().toString();
            String senderToken = api.token("sender-" + s);
            String phoneA = TestApi.uniquePhone();
            String phoneB = TestApi.uniquePhone();
            UUID senderId = api.enroll(opsToken, "sender-" + s, TestApi.uniquePhone(), "Kofi Sender");
            UUID recA = api.enroll(opsToken, "recA-" + s, phoneA, "Recipient A");
            UUID recB = api.enroll(opsToken, "recB-" + s, phoneB, "Recipient B");

            fundWallet(senderId, 50_000L);

            // Lookup Recipient A to get confirmation token for Recipient A
            var lookupRes = api.call("POST", "/v1/wallet/recipients/lookup", senderToken, null,
                    Map.of("rail", "wallet", "phone", phoneA));
            UUID confIdForA = UUID.fromString(lookupRes.body().get("confirmationId").asString());

            // Attack: Attempt to call /v1/wallet/transfers specifying Recipient B with confIdForA
            var spoofTransfer = api.call("POST", "/v1/wallet/transfers", senderToken, UUID.randomUUID().toString(),
                    Map.of("recipient", recB, "amount", 1000L, "confirmationId", confIdForA));
            assertThat(spoofTransfer.status()).isEqualTo(409);
            assertThat(spoofTransfer.body().get("code").asString()).isEqualTo("confirmation_mismatch");

            // Balances untouched
            assertThat(ledger.balance(available(senderId))).isEqualTo(50_000L);
            assertThat(ledger.balance(available(recA))).isZero();
            assertThat(ledger.balance(available(recB))).isZero();
        }

        @Test
        @DisplayName("Idempotency Replay with Altered Amount: Same key with modified amount returns 409")
        void idempotencyReplayWithAlteredAmountFails() {
            String s = UUID.randomUUID().toString();
            String senderToken = api.token("sender-" + s);
            String recPhone = TestApi.uniquePhone();
            UUID senderId = api.enroll(opsToken, "sender-" + s, TestApi.uniquePhone(), "Kofi Replay");
            UUID recId = api.enroll(opsToken, "recipient-" + s, recPhone, "Ama Replay");

            fundWallet(senderId, 50_000L);

            var lookupRes = api.call("POST", "/v1/wallet/recipients/lookup", senderToken, null,
                    Map.of("rail", "wallet", "phone", recPhone));
            UUID confId = UUID.fromString(lookupRes.body().get("confirmationId").asString());

            String key = "idem-key-" + UUID.randomUUID();

            // 1. Initial request with amount 2,000
            var res1 = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, key,
                    Map.of("confirmationId", confId, "amount", 2_000L));
            assertThat(res1.status()).isEqualTo(200);

            // 2. Replay with identical payload and same key -> 200 OK (idempotent replay)
            var resReplay = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, key,
                    Map.of("confirmationId", confId, "amount", 2_000L));
            assertThat(resReplay.status()).isEqualTo(200);
            assertThat(resReplay.body().get("transactionId").asString())
                    .isEqualTo(res1.body().get("transactionId").asString());

            // 3. Attack: Replay with SAME key but altered amount 3,000 -> MUST return 409 Conflict
            var resTampered = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, key,
                    Map.of("confirmationId", confId, "amount", 3_000L));
            assertThat(resTampered.status()).isEqualTo(409);
            assertThat(resTampered.body().get("code").asString()).isEqualTo("idempotency_conflict");

            // Recipient received exactly 2,000, not 3,000 or 5,000
            assertThat(ledger.balance(available(recId))).isEqualTo(2_000L);
            assertThat(ledger.balance(available(senderId))).isEqualTo(48_000L);
        }
    }

    // =========================================================================
    // 4. TOKEN BUCKET RATE LIMITER
    // =========================================================================
    @Nested
    @DisplayName("4. Token Bucket Rate Limiter Edge Cases & Bursting")
    class TokenBucketLimiterTests {

        @Test
        @DisplayName("Invalid parameters: zero capacity, negative refill, zero tokens rejected")
        void invalidParametersRejected() {
            // Zero capacity rejected
            assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0.0, 1.0));
            assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-5.0, 1.0));

            // Zero or negative refill rate rejected
            assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10.0, 0.0));
            assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10.0, -2.0));

            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 1.0);

            // Null key rejected
            assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(null, 1));

            // Zero or negative requested tokens rejected
            assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("key", 0));
            assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("key", -1));
        }

        @Test
        @DisplayName("Rapid bursting: concurrent burst strictly bounded by capacity")
        void rapidBurstingConcurrency() throws InterruptedException {
            int capacity = 25;
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, 0.0001); // Negligible refill

            int threads = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger granted = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        if (limiter.tryAcquire("burst-client", 1)) {
                            granted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            done.await();
            pool.shutdown();

            // Exactly capacity tokens must have been granted, zero over-granting
            assertEquals(capacity, granted.get(), "Tokens granted under burst must exactly match capacity");
            assertFalse(limiter.tryAcquire("burst-client", 1), "Next request must be rejected");
        }

        @Test
        @DisplayName("Extreme refill rates and large time jumps handled safely")
        void extremeRefillRates() {
            AtomicLong clock = new AtomicLong(0);

            // Extremely high refill rate (1,000,000,000 tokens/sec)
            TokenBucketRateLimiter hyperLimiter = new TokenBucketRateLimiter(100.0, 1_000_000_000.0, clock::get);
            assertTrue(hyperLimiter.tryAcquire("hyper", 100));
            assertFalse(hyperLimiter.tryAcquire("hyper", 1));
            // 1 millisecond later: should be completely refilled and capped at max capacity (100)
            clock.addAndGet(1_000_000L);
            assertEquals(100.0, hyperLimiter.getAvailableTokens("hyper"), 0.001);
            assertTrue(hyperLimiter.tryAcquire("hyper", 100));

            // Extremely low refill rate: 1 token per 1,000 seconds
            clock.set(0);
            TokenBucketRateLimiter slowLimiter = new TokenBucketRateLimiter(2.0, 0.001, clock::get);
            assertTrue(slowLimiter.tryAcquire("slow", 2));
            assertFalse(slowLimiter.tryAcquire("slow", 1));
            // 1 second later: only 0.001 tokens refilled, still cannot acquire 1
            clock.addAndGet(1_000_000_000L);
            assertFalse(slowLimiter.tryAcquire("slow", 1));

            // Massive elapsed time (100 years = ~3.15e18 nanos) caps cleanly at capacity without double overflow
            clock.addAndGet(3_153_600_000_000_000_000L);
            assertEquals(2.0, slowLimiter.getAvailableTokens("slow"), 0.001);

            // Backward clock step (NTP jump backward) does not corrupt tokens
            long before = clock.get();
            clock.set(before - 1_000_000_000L); // 1 second backward
            assertEquals(2.0, slowLimiter.getAvailableTokens("slow"), 0.001);
            assertTrue(slowLimiter.tryAcquire("slow", 1));
        }
    }

    // =========================================================================
    // 5. AUDIT LOG TAMPERING DETECTION
    // =========================================================================
    @Nested
    @DisplayName("5. Audit Log Tamper Evident Verification")
    class AuditTamperTests {

        private void cleanAuditTable() {
            tx.executeWithoutResult(s -> {
                db.sql("SET LOCAL session_replication_role = 'replica'").update();
                db.sql("TRUNCATE audit_log, audit_checkpoint").update();
                db.sql("ALTER SEQUENCE audit_log_id_seq RESTART WITH 1").update();
            });
        }

        @BeforeEach
        void setUpAuditTable() {
            cleanAuditTable();
        }

        @Test
        @DisplayName("Audit verifyChain detects payload tampering directly in database")
        void detectsPayloadTampering() {
            cleanAuditTable();
            var e1 = audit.recordEntry("sec-admin", "user.create", "customer:1", Map.of("email", "test@elepay.com"));
            var e2 = audit.recordEntry("system", "balance.topup", "customer:1", Map.of("pesewas", 10_000));
            var e3 = audit.recordEntry("sec-admin", "user.freeze", "customer:1", Map.of("reason", "investigation"));

            assertTrue(audit.verifyChain().valid());

            String originalDetails = e2.payload();
            try {
                tx.executeWithoutResult(s -> {
                    db.sql("SET LOCAL session_replication_role = 'replica'").update();
                    db.sql("UPDATE audit_log SET details = CAST('{\"pesewas\": 99999999}' AS jsonb) WHERE id = :id")
                            .param("id", e2.id()).update();
                });

                Audit.AuditTamperedException ex = assertThrows(Audit.AuditTamperedException.class,
                        () -> audit.verifyChain());
                assertThat(ex.getMessage()).contains("Audit entry tampered at id " + e2.id());
            } finally {
                tx.executeWithoutResult(s -> {
                    db.sql("SET LOCAL session_replication_role = 'replica'").update();
                    db.sql("UPDATE audit_log SET details = CAST(:d AS jsonb) WHERE id = :id")
                            .param("d", originalDetails).param("id", e2.id()).update();
                });
            }
        }

        @Test
        @DisplayName("Audit verifyChain detects actor and action tampering")
        void detectsActorAndActionTampering() {
            cleanAuditTable();
            var e1 = audit.recordEntry("compliance-bot", "aml.flag", "tx:99", Map.of("score", 95));
            var e2 = audit.recordEntry("risk-officer", "aml.dismiss", "tx:99", Map.of("note", "whitelisted"));

            assertTrue(audit.verifyChain().valid());

            try {
                // Tamper actor
                tx.executeWithoutResult(s -> {
                    db.sql("SET LOCAL session_replication_role = 'replica'").update();
                    db.sql("UPDATE audit_log SET actor = 'rogue-attacker' WHERE id = :id")
                            .param("id", e2.id()).update();
                });

                Audit.AuditTamperedException exActor = assertThrows(Audit.AuditTamperedException.class,
                        () -> audit.verifyChain());
                assertThat(exActor.getMessage()).contains("Audit entry tampered at id " + e2.id());

                // Restore actor, tamper action
                tx.executeWithoutResult(s -> {
                    db.sql("SET LOCAL session_replication_role = 'replica'").update();
                    db.sql("UPDATE audit_log SET actor = 'risk-officer', action = 'aml.approve_all' WHERE id = :id")
                            .param("id", e2.id()).update();
                });

                Audit.AuditTamperedException exAction = assertThrows(Audit.AuditTamperedException.class,
                        () -> audit.verifyChain());
                assertThat(exAction.getMessage()).contains("Audit entry tampered at id " + e2.id());
            } finally {
                tx.executeWithoutResult(s -> {
                    db.sql("SET LOCAL session_replication_role = 'replica'").update();
                    db.sql("UPDATE audit_log SET action = 'aml.dismiss' WHERE id = :id")
                            .param("id", e2.id()).update();
                });
            }
        }

        @Test
        @DisplayName("Audit verifyChain detects record deletion breaking the hash chain")
        void detectsRecordDeletion() {
            cleanAuditTable();
            var e1 = audit.recordEntry("admin", "step1", "sub:1", Map.of());
            var e2 = audit.recordEntry("admin", "step2", "sub:2", Map.of());
            var e3 = audit.recordEntry("admin", "step3", "sub:3", Map.of());

            assertTrue(audit.verifyChain().valid());

            try {
                // Delete middle entry e2
                tx.executeWithoutResult(s -> {
                    db.sql("SET LOCAL session_replication_role = 'replica'").update();
                    db.sql("DELETE FROM audit_log WHERE id = :id").param("id", e2.id()).update();
                });

                // e3.prevHash points to e2, but preceding entry is now e1 -> chain broken!
                Audit.AuditTamperedException ex = assertThrows(Audit.AuditTamperedException.class,
                        () -> audit.verifyChain());
                assertThat(ex.getMessage()).contains("Audit chain broken at entry id " + e3.id());
            } finally {
                cleanAuditTable();
            }
        }

        @Test
        @DisplayName("Audit verifyChain detects entry_hash tampering")
        void detectsEntryHashTampering() {
            cleanAuditTable();
            var e1 = audit.recordEntry("admin", "init", "sub:1", Map.of());

            String originalHash = e1.entryHash();
            String fakeHash = "bad0" + "0".repeat(60); // Exactly 64 chars
            try {
                tx.executeWithoutResult(s -> {
                    db.sql("SET LOCAL session_replication_role = 'replica'").update();
                    db.sql("UPDATE audit_log SET entry_hash = :hash WHERE id = :id")
                            .param("hash", fakeHash).param("id", e1.id()).update();
                });

                Audit.AuditTamperedException ex = assertThrows(Audit.AuditTamperedException.class,
                        () -> audit.verifyChain());
                assertThat(ex.getMessage()).contains("Audit entry tampered at id " + e1.id());
            } finally {
                tx.executeWithoutResult(s -> {
                    db.sql("SET LOCAL session_replication_role = 'replica'").update();
                    db.sql("UPDATE audit_log SET entry_hash = :hash WHERE id = :id")
                            .param("hash", originalHash).param("id", e1.id()).update();
                });
            }
        }
    }
}
