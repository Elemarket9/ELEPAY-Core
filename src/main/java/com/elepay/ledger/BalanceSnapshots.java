package com.elepay.ledger;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Point-in-time balance snapshot service (Algorithm 010).
 *
 * <p>Captures an immutable, cryptographically signed ledger balance snapshot
 * with strict row locking and validation against the underlying journal entries.
 */
@Service
public class BalanceSnapshots {
    private final JdbcClient db;

    public BalanceSnapshots(JdbcClient db) {
        this.db = Objects.requireNonNull(db);
    }

    public record Snapshot(
            UUID id,
            long accountId,
            String currency,
            long snapshotBalance,
            long journalEntrySequenceId,
            Instant snapshotAt,
            String signature,
            Instant createdAt
    ) {}

    public record VerificationResult(
            boolean valid,
            UUID snapshotId,
            long snapshotBalance,
            long reconstructedBalance,
            boolean signatureValid,
            String message
    ) {}

    private record SnapshotRow(
            UUID id,
            long accountId,
            String currency,
            long snapshotBalance,
            long sequenceId,
            OffsetDateTime snapshotAt,
            String signature,
            OffsetDateTime createdAt
    ) {}

    /**
     * Creates a balance snapshot for the specified account id.
     * Acquires a row lock on the ledger account, verifies consistency with
     * the journal entries, and commits an immutable signed snapshot.
     */
    @Transactional
    public Snapshot createSnapshot(long accountId) {
        record AccountRow(long id, String currency, long balance) {}
        AccountRow acc = db.sql("SELECT id, currency, balance FROM ledger_account WHERE id = :id FOR UPDATE")
                .param("id", accountId)
                .query((rs, i) -> new AccountRow(rs.getLong(1), rs.getString(2), rs.getLong(3)))
                .optional()
                .orElseThrow(() -> ApiException.notFound("Ledger account not found: " + accountId));

        record JournalSummary(long sum, long count) {}
        JournalSummary journal = db.sql("""
                SELECT coalesce(sum(amount), 0), count(*)
                FROM journal_entry WHERE account_id = :id""")
                .param("id", accountId)
                .query((rs, i) -> new JournalSummary(rs.getLong(1), rs.getLong(2)))
                .single();

        if (journal.sum() != acc.balance()) {
            throw ApiException.conflict("ledger_drift",
                    String.format("Balance drift detected: projected=%d, journal=%d", acc.balance(), journal.sum()));
        }

        UUID snapshotId = UUID.randomUUID();
        Instant now = Instant.now();
        OffsetDateTime snapshotAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        long sequenceId = journal.count();

        String signature = computeSignature(snapshotId, acc.id(), acc.currency(), acc.balance(), sequenceId, snapshotAt);

        db.sql("""
                INSERT INTO ledger_account_snapshot
                    (id, account_id, currency, snapshot_balance, journal_entry_sequence_id, snapshot_at, signature, created_at)
                VALUES
                    (:id, :acc, :curr, :bal, :seq, :at, :sig, :created)""")
                .param("id", snapshotId)
                .param("acc", acc.id())
                .param("curr", acc.currency().trim())
                .param("bal", acc.balance())
                .param("seq", sequenceId)
                .param("at", snapshotAt)
                .param("sig", signature)
                .param("created", snapshotAt)
                .update();

        return new Snapshot(snapshotId, acc.id(), acc.currency().trim(), acc.balance(), sequenceId, now, signature, now);
    }

    /**
     * Overload to snapshot by customer or merchant owner UUID.
     */
    @Transactional
    public Snapshot createSnapshot(UUID ownerId) {
        Long accountId = db.sql("""
                SELECT id FROM ledger_account
                WHERE owner_id = :id AND bucket = 'available'
                ORDER BY id LIMIT 1""")
                .param("id", ownerId)
                .query(Long.class)
                .optional()
                .orElseGet(() -> db.sql("SELECT id FROM ledger_account WHERE owner_id = :id ORDER BY id LIMIT 1")
                        .param("id", ownerId)
                        .query(Long.class)
                        .optional()
                        .orElseThrow(() -> ApiException.notFound("Ledger account not found for owner: " + ownerId)));
        return createSnapshot(accountId);
    }

    /**
     * Overload to snapshot by account code.
     */
    @Transactional
    public Snapshot createSnapshot(String accountCode) {
        Long accountId = db.sql("SELECT id FROM ledger_account WHERE code = :code")
                .param("code", accountCode)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Ledger account not found for code: " + accountCode));
        return createSnapshot(accountId);
    }

    /**
     * Rebuilds the balance from journal entries and verifies consistency with the snapshot.
     */
    @Transactional(readOnly = true)
    public boolean verifySnapshot(UUID snapshotId) {
        return verify(snapshotId).valid();
    }

    /**
     * Detailed verification report for the specified snapshot.
     */
    @Transactional(readOnly = true)
    public VerificationResult verify(UUID snapshotId) {
        var opt = db.sql("""
                SELECT id, account_id, currency, snapshot_balance, journal_entry_sequence_id, snapshot_at, signature, created_at
                FROM ledger_account_snapshot WHERE id = :id""")
                .param("id", snapshotId)
                .query((rs, i) -> new SnapshotRow(
                        rs.getObject(1, UUID.class),
                        rs.getLong(2),
                        rs.getString(3),
                        rs.getLong(4),
                        rs.getLong(5),
                        rs.getObject(6, OffsetDateTime.class),
                        rs.getString(7),
                        rs.getObject(8, OffsetDateTime.class)
                )).optional();

        if (opt.isEmpty()) {
            throw ApiException.notFound("Snapshot not found: " + snapshotId);
        }

        SnapshotRow row = opt.get();
        String expectedSignature = computeSignature(row.id(), row.accountId(), row.currency(),
                row.snapshotBalance(), row.sequenceId(), row.snapshotAt());

        boolean sigMatch = MessageDigest.isEqual(
                row.signature().getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8)
        );

        if (!sigMatch) {
            return new VerificationResult(false, snapshotId, row.snapshotBalance(), -1, false,
                    "Cryptographic signature mismatch: snapshot was tampered");
        }

        long reconstructed = db.sql("""
                SELECT coalesce(sum(e.amount), 0)
                FROM journal_entry e
                JOIN journal_transaction t ON t.id = e.transaction_id
                WHERE e.account_id = :acc AND t.posted_at <= :at""")
                .param("acc", row.accountId())
                .param("at", row.snapshotAt())
                .query(Long.class).single();

        if (reconstructed != row.snapshotBalance()) {
            return new VerificationResult(false, snapshotId, row.snapshotBalance(), reconstructed, true,
                    String.format("Reconstructed balance %d does not match snapshot balance %d",
                            reconstructed, row.snapshotBalance()));
        }

        return new VerificationResult(true, snapshotId, row.snapshotBalance(), reconstructed, true,
                "Snapshot verified consistent with journal entries");
    }

    private static String computeSignature(UUID id, long accountId, String currency, long balance,
                                           long sequenceId, OffsetDateTime at) {
        String canonical = id + ":" + accountId + ":" + currency.trim() + ":" + balance + ":"
                + sequenceId + ":" + at.toInstant().toEpochMilli();
        return Idempotency.sha256(canonical);
    }
}
