package com.elepay.ledger;

import static org.junit.jupiter.api.Assertions.*;

import com.elepay.PostgresTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class BalanceSnapshotsTest extends PostgresTestBase {

    @Autowired
    private BalanceSnapshots balanceSnapshots;

    @Autowired
    private Ledger ledger;

    @Autowired
    private JdbcClient db;

    @Test
    void snapshotCreationAndVerification() {
        String testId = UUID.randomUUID().toString();
        UUID ownerId = UUID.randomUUID();
        String userAccount = ledger.open("customer", ownerId, "available", Ledger.GHS);

        // Fund user account: debit clearing, credit customer available
        ledger.post(Ledger.Posting.of("test-snapshot-" + testId, "tx-1", "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 10_000),
                Ledger.Leg.credit(userAccount, 10_000)
        )));

        // Create snapshot by code
        BalanceSnapshots.Snapshot snapshot1 = balanceSnapshots.createSnapshot(userAccount);
        assertNotNull(snapshot1.id());
        assertEquals(10_000L, snapshot1.snapshotBalance());
        assertEquals("GHS", snapshot1.currency());
        assertNotNull(snapshot1.signature());

        // Verify snapshot consistency
        assertTrue(balanceSnapshots.verifySnapshot(snapshot1.id()));
        var verification = balanceSnapshots.verify(snapshot1.id());
        assertTrue(verification.valid());
        assertTrue(verification.signatureValid());
        assertEquals(10_000L, verification.snapshotBalance());
        assertEquals(10_000L, verification.reconstructedBalance());

        // Further transaction
        ledger.post(Ledger.Posting.of("test-snapshot-" + testId, "tx-2", "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 5_000),
                Ledger.Leg.credit(userAccount, 5_000)
        )));

        // Verify that snapshot1 remains historically valid
        assertTrue(balanceSnapshots.verifySnapshot(snapshot1.id()), () -> balanceSnapshots.verify(snapshot1.id()).toString());

        // Snapshot by owner UUID
        BalanceSnapshots.Snapshot snapshot2 = balanceSnapshots.createSnapshot(ownerId);
        assertEquals(15_000L, snapshot2.snapshotBalance());
        assertTrue(balanceSnapshots.verifySnapshot(snapshot2.id()), () -> balanceSnapshots.verify(snapshot2.id()).toString());
    }

    @Test
    void tamperedSignatureFailsVerification() {
        String testId = UUID.randomUUID().toString();
        UUID ownerId = UUID.randomUUID();
        String userAccount = ledger.open("customer", ownerId, "available", Ledger.GHS);

        ledger.post(Ledger.Posting.of("test-tamper-" + testId, "tx-1", "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 20_000),
                Ledger.Leg.credit(userAccount, 20_000)
        )));

        BalanceSnapshots.Snapshot snapshot = balanceSnapshots.createSnapshot(userAccount);
        assertTrue(balanceSnapshots.verifySnapshot(snapshot.id()));

        // Create an untrusted forged snapshot directly with invalid signature
        UUID forgedId = UUID.randomUUID();
        db.sql("""
                INSERT INTO ledger_account_snapshot
                    (id, account_id, currency, snapshot_balance, journal_entry_sequence_id, snapshot_at, signature, created_at)
                VALUES
                    (:id, :acc, 'GHS', 999999, 1, now(), '0000000000000000000000000000000000000000000000000000000000000bad', now())""")
                .param("id", forgedId)
                .param("acc", snapshot.accountId())
                .update();

        assertFalse(balanceSnapshots.verifySnapshot(forgedId));
        var result = balanceSnapshots.verify(forgedId);
        assertFalse(result.valid());
        assertFalse(result.signatureValid());
    }
}
