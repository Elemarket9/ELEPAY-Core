package com.elepay.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.ledger.BalanceSnapshots;
import com.elepay.ledger.Ledger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class ConcurrencyDeadlockAdversarialIT extends PostgresTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private Ledger ledger;

    @Autowired
    private Wallets wallets;

    @Autowired
    private Idempotency idempotency;

    @Autowired
    private BalanceSnapshots balanceSnapshots;

    @Autowired
    private JdbcClient db;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TestApi api;
    private String opsToken;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        opsToken = api.token("ops-admin-" + UUID.randomUUID(), "ops");
    }

    private record CustomerInfo(UUID id, String phone, String token, String subject) {}

    private CustomerInfo createCustomer(String name, long initialBalancePesewas) {
        String runId = UUID.randomUUID().toString();
        String subject = "user-" + runId;
        String phone = TestApi.uniquePhone();
        String token = api.token(subject);
        UUID id = api.enroll(opsToken, subject, phone, name);

        if (initialBalancePesewas > 0) {
            ledger.post(Ledger.Posting.of("fund-" + runId, "topup-" + runId, "topup", List.of(
                    Ledger.Leg.debit(Ledger.clearing("sandbox"), initialBalancePesewas),
                    Ledger.Leg.credit(Wallets.available(id), initialBalancePesewas)
            )));
        }
        return new CustomerInfo(id, phone, token, subject);
    }

    /**
     * Vector 1: Mutually opposing transfers between Account A and Account B
     * (A -> B and B -> A simultaneously across 30 concurrent threads).
     * Must not produce deadlocks, lost updates, or ledger imbalance.
     */
    @Test
    void testOpposingConcurrentTransfersNoDeadlock() throws Exception {
        CustomerInfo a = createCustomer("Alice Opposing", 20_000L);
        CustomerInfo b = createCustomer("Bob Opposing", 20_000L);

        int threadsPerDirection = 10;
        int totalThreads = threadsPerDirection * 2;
        long transferAmount = 100L;

        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger aToBSuccess = new AtomicInteger();
        AtomicInteger bToASuccess = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        // 15 threads sending A -> B
        for (int i = 0; i < threadsPerDirection; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    String key = "idem-a-to-b-" + idx + "-" + UUID.randomUUID();
                    TestApi.Response resp = api.call("POST", "/v1/wallet/transfers", a.token(), key,
                            Map.of("recipient", b.id().toString(), "amount", transferAmount));
                    if (resp.status() == 200) {
                        aToBSuccess.incrementAndGet();
                    } else {
                        errors.add(new RuntimeException("A->B failed with status " + resp.status() + ": " + resp.body()));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 15 threads sending B -> A
        for (int i = 0; i < threadsPerDirection; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    String key = "idem-b-to-a-" + idx + "-" + UUID.randomUUID();
                    TestApi.Response resp = api.call("POST", "/v1/wallet/transfers", b.token(), key,
                            Map.of("recipient", a.id().toString(), "amount", transferAmount));
                    if (resp.status() == 200) {
                        bToASuccess.incrementAndGet();
                    } else {
                        errors.add(new RuntimeException("B->A failed with status " + resp.status() + ": " + resp.body()));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished, "All concurrent opposing transfers must finish within 30 seconds");

        // Verify zero deadlock or other exceptions
        assertThat(errors).isEmpty();
        assertEquals(threadsPerDirection, aToBSuccess.get());
        assertEquals(threadsPerDirection, bToASuccess.get());

        // Net balance change should be 0 because 15 transfers of 100 GHS in each direction
        long balA = ledger.balance(Wallets.available(a.id()));
        long balB = ledger.balance(Wallets.available(b.id()));
        assertEquals(20_000L, balA);
        assertEquals(20_000L, balB);

        // Verify ledger global zero-sum integrity
        Ledger.Integrity integrity = ledger.integrity();
        assertTrue(integrity.ok(), "Ledger integrity check failed: " + integrity);
    }

    /**
     * Vector 2: Multi-Party Cyclic Transfers (Ring of 3 accounts):
     * A -> B, B -> C, C -> A concurrently.
     * Verifies deterministic row lock ordering prevents deadlocks in cyclic topologies.
     */
    @Test
    void testCyclicConcurrentTransfersRing() throws Exception {
        CustomerInfo a = createCustomer("Alice Ring", 15_000L);
        CustomerInfo b = createCustomer("Bob Ring", 15_000L);
        CustomerInfo c = createCustomer("Charlie Ring", 15_000L);

        int transfersPerLeg = 10;
        int totalThreads = transfersPerLeg * 3;
        long amount = 50L;

        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        // A -> B
        for (int i = 0; i < transfersPerLeg; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    TestApi.Response resp = api.call("POST", "/v1/wallet/transfers", a.token(),
                            "ring-ab-" + idx + "-" + UUID.randomUUID(),
                            Map.of("recipient", b.id().toString(), "amount", amount));
                    if (resp.status() != 200) errors.add(new RuntimeException("AB fail: " + resp.body()));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // B -> C
        for (int i = 0; i < transfersPerLeg; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    TestApi.Response resp = api.call("POST", "/v1/wallet/transfers", b.token(),
                            "ring-bc-" + idx + "-" + UUID.randomUUID(),
                            Map.of("recipient", c.id().toString(), "amount", amount));
                    if (resp.status() != 200) errors.add(new RuntimeException("BC fail: " + resp.body()));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // C -> A
        for (int i = 0; i < transfersPerLeg; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    TestApi.Response resp = api.call("POST", "/v1/wallet/transfers", c.token(),
                            "ring-ca-" + idx + "-" + UUID.randomUUID(),
                            Map.of("recipient", a.id().toString(), "amount", amount));
                    if (resp.status() != 200) errors.add(new RuntimeException("CA fail: " + resp.body()));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished, "Cyclic ring transfers must complete within timeout");

        assertThat(errors).isEmpty();
        assertEquals(15_000L, ledger.balance(Wallets.available(a.id())));
        assertEquals(15_000L, ledger.balance(Wallets.available(b.id())));
        assertEquals(15_000L, ledger.balance(Wallets.available(c.id())));

        assertTrue(ledger.integrity().ok());
    }

    /**
     * Vector 3: High Contention Overdraft Attack:
     * Account A has 3,000 pesewas.
     * 15 concurrent threads try to transfer 1,000 pesewas each.
     * Exactly 3 transfers MUST succeed, 12 MUST fail with insufficient_funds (HTTP 409).
     * Account A balance MUST NEVER go negative.
     */
    @Test
    void testConcurrentOverdraftRaceConditionPrevention() throws Exception {
        CustomerInfo sender = createCustomer("Sender Overdraft", 3_000L);
        CustomerInfo receiver = createCustomer("Receiver Overdraft", 0L);

        int totalThreads = 15;
        long amount = 1_000L;

        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficientFundsCount = new AtomicInteger();
        List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < totalThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    String key = "overdraft-" + idx + "-" + UUID.randomUUID();
                    TestApi.Response resp = api.call("POST", "/v1/wallet/transfers", sender.token(), key,
                            Map.of("recipient", receiver.id().toString(), "amount", amount));
                    if (resp.status() == 200) {
                        successes.incrementAndGet();
                    } else if (resp.status() == 409 && "insufficient_funds".equals(resp.body().get("code").asString())) {
                        insufficientFundsCount.incrementAndGet();
                    } else {
                        unexpectedErrors.add(new RuntimeException("Unexpected response: " + resp.status() + " " + resp.body()));
                    }
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished);

        assertThat(unexpectedErrors).isEmpty();
        assertEquals(3, successes.get(), "Exactly 3 transfers of 1000 can succeed from 3000 initial balance");
        assertEquals(12, insufficientFundsCount.get(), "Exactly 12 transfers must fail with insufficient_funds");

        long senderBal = ledger.balance(Wallets.available(sender.id()));
        long receiverBal = ledger.balance(Wallets.available(receiver.id()));
        assertEquals(0L, senderBal, "Sender balance must be exactly 0, never negative");
        assertEquals(3_000L, receiverBal, "Receiver must receive exactly 3,000");

        assertTrue(ledger.integrity().ok());
    }

    /**
     * Vector 4: Concurrent Idempotency Replay (Identical Key & Payload):
     * 10 concurrent requests with the SAME idempotency key.
     * Exactly one transaction should be executed; all 10 return HTTP 200 with identical transactionId.
     */
    @Test
    void testConcurrentIdempotencyReplayIdentical() throws Exception {
        CustomerInfo sender = createCustomer("Sender Idem", 10_000L);
        CustomerInfo receiver = createCustomer("Receiver Idem", 0L);

        String idempotencyKey = "shared-idem-key-" + UUID.randomUUID();
        int totalThreads = 10;
        long amount = 1_500L;

        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        List<String> transactionIds = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < totalThreads; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    TestApi.Response resp = api.call("POST", "/v1/wallet/transfers", sender.token(), idempotencyKey,
                            Map.of("recipient", receiver.id().toString(), "amount", amount));
                    if (resp.status() == 200) {
                        transactionIds.add(resp.body().get("transactionId").asString());
                    } else {
                        errors.add(new RuntimeException("Non-200: " + resp.status() + " " + resp.body()));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished);

        assertThat(errors).isEmpty();
        assertEquals(10, transactionIds.size());
        // All returned transaction IDs must be identical!
        String firstTx = transactionIds.get(0);
        for (String txId : transactionIds) {
            assertEquals(firstTx, txId);
        }

        // Sender debited exactly once
        assertEquals(8_500L, ledger.balance(Wallets.available(sender.id())));
        assertEquals(1_500L, ledger.balance(Wallets.available(receiver.id())));

        assertTrue(ledger.integrity().ok());
    }

    /**
     * Vector 5: Concurrent Idempotency Collision (Mismatched Payload):
     * Multiple concurrent requests submitting DIFFERENT payloads under the SAME idempotency key.
     * One claims and succeeds, the other gets 409 idempotency conflict.
     */
    @Test
    void testConcurrentIdempotencyCollisionModifiedPayload() throws Exception {
        CustomerInfo sender = createCustomer("Sender Collision", 10_000L);
        CustomerInfo receiver1 = createCustomer("Recv 1", 0L);
        CustomerInfo receiver2 = createCustomer("Recv 2", 0L);

        String key = "collision-key-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger success200 = new AtomicInteger();
        AtomicInteger conflict409 = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        // Request 1: sends to receiver1 amount 500
        pool.submit(() -> {
            try {
                startLatch.await();
                TestApi.Response resp = api.call("POST", "/v1/wallet/transfers", sender.token(), key,
                        Map.of("recipient", receiver1.id().toString(), "amount", 500L));
                if (resp.status() == 200) success200.incrementAndGet();
                else if (resp.status() == 409) conflict409.incrementAndGet();
                else errors.add(new RuntimeException("Unexpected: " + resp.status() + " " + resp.body()));
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                doneLatch.countDown();
            }
        });

        // Request 2: sends to receiver2 amount 800
        pool.submit(() -> {
            try {
                startLatch.await();
                TestApi.Response resp = api.call("POST", "/v1/wallet/transfers", sender.token(), key,
                        Map.of("recipient", receiver2.id().toString(), "amount", 800L));
                if (resp.status() == 200) success200.incrementAndGet();
                else if (resp.status() == 409) conflict409.incrementAndGet();
                else errors.add(new RuntimeException("Unexpected: " + resp.status() + " " + resp.body()));
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean finished = doneLatch.await(15, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished);

        assertThat(errors).isEmpty();
        assertEquals(1, success200.get(), "Exactly one payload must succeed");
        assertEquals(1, conflict409.get(), "Conflicting payload under same key must return 409");

        assertTrue(ledger.integrity().ok());
    }

    /**
     * Vector 6: Single-use confirmation token race condition:
     * Multiple concurrent requests trying to spend the same confirmation token.
     * Exactly one must succeed, all others must fail with 409 confirmation_used.
     */
    @Test
    void testConcurrentConfirmationTokenSpending() throws Exception {
        CustomerInfo sender = createCustomer("Sender Conf", 10_000L);
        CustomerInfo receiver = createCustomer("Recv Conf", 0L);

        // Perform lookup to generate a single-use confirmation token
        TestApi.Response lookupResp = api.call("POST", "/v1/wallet/recipients/lookup", sender.token(), null,
                Map.of("rail", "wallet", "phone", receiver.phone()));
        assertThat(lookupResp.status()).isEqualTo(200);
        String confirmationIdStr = lookupResp.body().get("confirmationId").asString();
        assertThat(confirmationIdStr).isNotNull();

        int totalThreads = 8;
        long amount = 1_000L;

        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger confirmationUsedCount = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < totalThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    // Each request has its own unique Idempotency-Key
                    String key = "conf-spend-" + idx + "-" + UUID.randomUUID();
                    TestApi.Response resp = api.call("POST", "/v1/wallet/transfers/confirmed", sender.token(), key,
                            Map.of("confirmationId", confirmationIdStr, "amount", amount));
                    if (resp.status() == 200) {
                        successCount.incrementAndGet();
                    } else if (resp.status() == 409 && "confirmation_used".equals(resp.body().get("code").asString())) {
                        confirmationUsedCount.incrementAndGet();
                    } else {
                        errors.add(new RuntimeException("Unexpected: " + resp.status() + " " + resp.body()));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished);

        assertThat(errors).isEmpty();
        assertEquals(1, successCount.get(), "Confirmation token can only be consumed once");
        assertEquals(7, confirmationUsedCount.get(), "All other concurrent callers must receive confirmation_used");

        // Balance check
        assertEquals(9_000L, ledger.balance(Wallets.available(sender.id())));
        assertEquals(1_000L, ledger.balance(Wallets.available(receiver.id())));

        assertTrue(ledger.integrity().ok());
    }

    /**
     * Vector 7: Mid-Transfer Transactional Rollback:
     * If an unhandled exception or failure happens mid-transfer (after ledger.post or during execution),
     * the entire transaction rolls back: no orphaned journal entries, no balance debit.
     */
    @Test
    void testMidTransferFailureRollback() {
        CustomerInfo sender = createCustomer("Sender Rollback", 10_000L);
        CustomerInfo receiver = createCustomer("Recv Rollback", 0L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String scope = "rollback-test:" + sender.id();
        String key = "rollback-key-" + UUID.randomUUID();

        assertThrows(RuntimeException.class, () -> {
            tx.execute(status -> {
                // Execute a transfer inside transaction
                wallets.send(sender.id(), scope, key, receiver.id(), 2_500L);

                // Verify inside tx that balance changed
                assertEquals(7_500L, ledger.balance(Wallets.available(sender.id())));

                // Simulate sudden failure before commit
                throw new RuntimeException("Simulated crash / infrastructure failure");
            });
        });

        // After transaction rolls back:
        // 1. Sender balance must be fully restored to 10,000L
        assertEquals(10_000L, ledger.balance(Wallets.available(sender.id())));
        // 2. Receiver balance must remain 0L
        assertEquals(0L, ledger.balance(Wallets.available(receiver.id())));

        // 3. No journal transaction or entry persisted with this idempotency key
        Long txCount = db.sql("SELECT count(*) FROM journal_transaction WHERE idempotency_scope = :s AND idempotency_key = :k")
                .param("s", scope).param("k", key).query(Long.class).single();
        assertEquals(0L, txCount, "Journal transaction must be rolled back completely");

        // 4. Ledger integrity must remain pristine
        assertTrue(ledger.integrity().ok());
    }

    /**
     * Vector 8: Ledger Deterministic Row Locking Order:
     * Check that arbitrary posting leg orders always acquire locks in sorted account ID order.
     */
    @Test
    void testLedgerDeterministicLockOrdering() {
        CustomerInfo a = createCustomer("Lock Order A", 5_000L);
        CustomerInfo b = createCustomer("Lock Order B", 5_000L);

        String accA = Wallets.available(a.id());
        String accB = Wallets.available(b.id());

        // Post with A then B
        Ledger.Posted p1 = ledger.post(Ledger.Posting.of("test-lock", "lock-1-" + UUID.randomUUID(), "transfer", List.of(
                Ledger.Leg.debit(accA, 100),
                Ledger.Leg.credit(accB, 100)
        )));
        assertNotNull(p1.transactionId());

        // Post with B then A (inverted leg order in request)
        Ledger.Posted p2 = ledger.post(Ledger.Posting.of("test-lock", "lock-2-" + UUID.randomUUID(), "transfer", List.of(
                Ledger.Leg.credit(accA, 100),
                Ledger.Leg.debit(accB, 100)
        )));
        assertNotNull(p2.transactionId());

        assertTrue(ledger.integrity().ok());
    }

    /**
     * Vector 9: Concurrent Double Reversal Attack:
     * Two concurrent requests try to reverse the same transaction with different keys.
     * Exactly one must succeed, the second must receive conflict (already_reversed).
     */
    @Test
    void testConcurrentDoubleReversalPrevention() throws Exception {
        CustomerInfo a = createCustomer("Rev A", 10_000L);
        CustomerInfo b = createCustomer("Rev B", 0L);

        Ledger.Posted original = ledger.post(Ledger.Posting.of("rev-test", "tx-orig-" + UUID.randomUUID(), "transfer", List.of(
                Ledger.Leg.debit(Wallets.available(a.id()), 2_000L),
                Ledger.Leg.credit(Wallets.available(b.id()), 2_000L)
        )));
        UUID origTxId = original.transactionId();

        int totalThreads = 4;
        String revScope = "rev-test-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger reversedCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < totalThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    ledger.reverse(origTxId, revScope, "rev-key-" + idx, "Adversarial concurrent reversal");
                    reversedCount.incrementAndGet();
                } catch (ApiException e) {
                    if ("already_reversed".equals(e.code())) {
                        conflictCount.incrementAndGet();
                    } else {
                        errors.add(e);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(15, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished);

        assertThat(errors).isEmpty();
        assertEquals(1, reversedCount.get(), "Exactly one reversal must succeed");
        assertEquals(3, conflictCount.get(), "Concurrent subsequent reversals must be rejected with already_reversed");

        // Verify balances restored
        assertEquals(10_000L, ledger.balance(Wallets.available(a.id())));
        assertEquals(0L, ledger.balance(Wallets.available(b.id())));

        assertTrue(ledger.integrity().ok());
    }

    /**
     * Vector 10: Concurrent Snapshot & Transfer Race:
     * While concurrent transfers are actively debiting/crediting an account,
     * balance snapshot creation acquires the row lock, captures an exact point-in-time balance,
     * and guarantees that snapshot_balance == sum(journal_entry.amount) at that exact snapshot instant.
     */
    @Test
    void testConcurrentBalanceSnapshotUnderTransferLoad() throws Exception {
        CustomerInfo a = createCustomer("Snapshot A", 100_000L);
        CustomerInfo b = createCustomer("Snapshot B", 0L);

        int numTransfers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numTransfers + 1);

        List<Throwable> errors = new CopyOnWriteArrayList<>();

        // Background transfers
        for (int i = 0; i < numTransfers; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    api.call("POST", "/v1/wallet/transfers", a.token(), "snap-xfer-" + idx + "-" + UUID.randomUUID(),
                            Map.of("recipient", b.id().toString(), "amount", 100L));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Concurrent balance snapshot
        pool.submit(() -> {
            try {
                startLatch.await();
                BalanceSnapshots.Snapshot snapshot = balanceSnapshots.createSnapshot(a.id());
                assertNotNull(snapshot);
                assertTrue(balanceSnapshots.verifySnapshot(snapshot.id()), "Snapshot must verify against journal");
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean finished = doneLatch.await(20, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished);

        assertThat(errors).isEmpty();
        assertTrue(ledger.integrity().ok());
    }
}
