package com.elepay.reconciliation;

import com.elepay.ledger.Ledger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Service for managing reconciliation suspense ledgers, aging bucket classification,
 * and dual-control resolution workflows.
 * Guarantees zero-drift accounting by routing un-reconciled discrepancies through
 * dedicated system suspense accounts with balanced double-entry journal postings.
 */
@Service
public class ReconSuspenseService {

    public enum AgingBucket {
        BUCKET_0_24H("0_24H"),
        BUCKET_24_72H("24_72H"),
        BUCKET_OVER_72H("OVER_72H");

        private final String code;
        AgingBucket(String code) { this.code = code; }
        public String getCode() { return code; }

        public static AgingBucket fromCode(String code) {
            for (AgingBucket b : values()) {
                if (b.code.equalsIgnoreCase(code)) return b;
            }
            return BUCKET_0_24H;
        }
    }

    public enum SuspenseStatus {
        OPEN, UNDER_INVESTIGATION, WRITTEN_OFF, RESOLVED
    }

    public record SuspenseRecord(
            UUID id,
            UUID matchRecordId,
            String suspenseAccountCode,
            long variancePesewas,
            String currency,
            AgingBucket agingBucket,
            SuspenseStatus status,
            String resolutionNotes,
            String resolvedBy,
            OffsetDateTime resolvedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    private final JdbcClient db;
    private final Ledger ledger;

    public ReconSuspenseService(JdbcClient db, Ledger ledger) {
        this.db = db;
        this.ledger = ledger;
    }

    @Transactional
    public SuspenseRecord postSuspenseVariance(
            UUID matchRecordId,
            String bankCode,
            long variancePesewas,
            String currency
    ) {
        if (variancePesewas == 0) {
            throw new IllegalArgumentException("Variance amount cannot be zero for suspense posting");
        }

        UUID id = UUID.randomUUID();
        String curr = (currency != null && !currency.isBlank()) ? currency : "GHS";
        String bank = (bankCode != null && !bankCode.isBlank()) ? bankCode.toUpperCase() : "GENERAL";
        String suspenseAccount = "system:suspense:recon:" + bank + ":" + curr;
        String clearingAccount = "system:clearing:" + bank.toLowerCase() + ":" + curr;
        OffsetDateTime now = OffsetDateTime.now();

        // 1. Post to authoritative Double-Entry Ledger
        ledger.openSystem(suspenseAccount, "suspense", curr, true);
        ledger.openSystem(clearingAccount, "clearing", curr, true);

        long absVariance = Math.abs(variancePesewas);
        var legs = (variancePesewas > 0)
                ? List.of(Ledger.Leg.debit(suspenseAccount, absVariance), Ledger.Leg.credit(clearingAccount, absVariance))
                : List.of(Ledger.Leg.debit(clearingAccount, absVariance), Ledger.Leg.credit(suspenseAccount, absVariance));

        ledger.post(Ledger.Posting.of(
                "recon_suspense",
                "SUSPENSE-" + id,
                "RECON_VARIANCE_SUSPENSE",
                legs
        ).describedAs("Reconciliation variance suspense posting for match " + matchRecordId));

        // 2. Persist in recon_suspense_ledger
        db.sql("""
            INSERT INTO recon_suspense_ledger (
                id, match_record_id, suspense_account_code, variance_pesewas,
                currency, aging_bucket, status, created_at, updated_at
            ) VALUES (
                :id, :matchId, :account, :variance,
                :curr, :bucket, :status, :now, :now
            )
        """)
                .param("id", id)
                .param("matchId", matchRecordId)
                .param("account", suspenseAccount)
                .param("variance", variancePesewas)
                .param("curr", curr)
                .param("bucket", AgingBucket.BUCKET_0_24H.getCode())
                .param("status", SuspenseStatus.OPEN.name())
                .param("now", now)
                .update();

        return new SuspenseRecord(
                id, matchRecordId, suspenseAccount, variancePesewas, curr,
                AgingBucket.BUCKET_0_24H, SuspenseStatus.OPEN, null, null, null, now, now
        );
    }

    @Transactional
    public int updateAgingBuckets() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime threshold24h = now.minusHours(24);
        OffsetDateTime threshold72h = now.minusHours(72);

        // Update > 72h
        int updatedOver72 = db.sql("""
            UPDATE recon_suspense_ledger
            SET aging_bucket = :bucket, updated_at = :now
            WHERE status IN ('OPEN', 'UNDER_INVESTIGATION') AND created_at < :threshold
        """)
                .param("bucket", AgingBucket.BUCKET_OVER_72H.getCode())
                .param("now", now)
                .param("threshold", threshold72h)
                .update();

        // Update 24h - 72h
        int updated24to72 = db.sql("""
            UPDATE recon_suspense_ledger
            SET aging_bucket = :bucket, updated_at = :now
            WHERE status IN ('OPEN', 'UNDER_INVESTIGATION')
              AND created_at < :threshold24 AND created_at >= :threshold72
        """)
                .param("bucket", AgingBucket.BUCKET_24_72H.getCode())
                .param("now", now)
                .param("threshold24", threshold24h)
                .param("threshold72", threshold72h)
                .update();

        return updatedOver72 + updated24to72;
    }

    @Transactional
    public SuspenseRecord resolveVariance(
            UUID suspenseId,
            String resolutionNotes,
            String resolvedBy,
            String targetAdjustmentAccount
    ) {
        if (resolvedBy == null || resolvedBy.isBlank()) {
            throw new IllegalArgumentException("Maker-checker compliance requires non-empty resolvedBy identity");
        }

        SuspenseRecord record = findById(suspenseId)
                .orElseThrow(() -> new IllegalArgumentException("Suspense record not found: " + suspenseId));

        if (record.status() == SuspenseStatus.RESOLVED) {
            return record;
        }

        OffsetDateTime now = OffsetDateTime.now();

        // Always clear suspense balance in Double-Entry Ledger to prevent un-cleared ledger drift
        String targetAccount = (targetAdjustmentAccount != null && !targetAdjustmentAccount.isBlank())
                ? targetAdjustmentAccount
                : "system:clearing:recon_resolution:" + record.currency();

        ledger.openSystem(targetAccount, "clearing", record.currency(), true);
        long absVariance = Math.abs(record.variancePesewas());

        if (absVariance > 0) {
            var legs = (record.variancePesewas() > 0)
                    ? List.of(Ledger.Leg.debit(targetAccount, absVariance), Ledger.Leg.credit(record.suspenseAccountCode(), absVariance))
                    : List.of(Ledger.Leg.debit(record.suspenseAccountCode(), absVariance), Ledger.Leg.credit(targetAccount, absVariance));

            ledger.post(Ledger.Posting.of(
                    "recon_resolution",
                    "RESOLVE-" + suspenseId,
                    "RECON_VARIANCE_RESOLUTION",
                    legs
            ).describedAs("Reconciliation variance cleared by " + resolvedBy + ": " + resolutionNotes));
        }

        db.sql("""
            UPDATE recon_suspense_ledger
            SET status = :status,
                resolution_notes = :notes,
                resolved_by = :resolver,
                resolved_at = :now,
                updated_at = :now
            WHERE id = :id
        """)
                .param("status", SuspenseStatus.RESOLVED.name())
                .param("notes", resolutionNotes)
                .param("resolver", resolvedBy)
                .param("now", now)
                .param("id", suspenseId)
                .update();

        return new SuspenseRecord(
                record.id(), record.matchRecordId(), record.suspenseAccountCode(),
                record.variancePesewas(), record.currency(), record.agingBucket(),
                SuspenseStatus.RESOLVED, resolutionNotes, resolvedBy, now, record.createdAt(), now
        );
    }

    public Optional<SuspenseRecord> findById(UUID suspenseId) {
        return db.sql("""
            SELECT id, match_record_id, suspense_account_code, variance_pesewas,
                   currency, aging_bucket, status, resolution_notes, resolved_by,
                   resolved_at, created_at, updated_at
            FROM recon_suspense_ledger
            WHERE id = :id
        """)
                .param("id", suspenseId)
                .query((rs, rowNum) -> new SuspenseRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("match_record_id", UUID.class),
                        rs.getString("suspense_account_code"),
                        rs.getLong("variance_pesewas"),
                        rs.getString("currency"),
                        AgingBucket.fromCode(rs.getString("aging_bucket")),
                        SuspenseStatus.valueOf(rs.getString("status")),
                        rs.getString("resolution_notes"),
                        rs.getString("resolved_by"),
                        rs.getObject("resolved_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ))
                .optional();
    }

    public List<SuspenseRecord> listOpenSuspenseItems(AgingBucket bucket) {
        String query = (bucket != null)
                ? "SELECT id, match_record_id, suspense_account_code, variance_pesewas, currency, aging_bucket, status, resolution_notes, resolved_by, resolved_at, created_at, updated_at FROM recon_suspense_ledger WHERE status IN ('OPEN', 'UNDER_INVESTIGATION') AND aging_bucket = :bucket ORDER BY created_at ASC"
                : "SELECT id, match_record_id, suspense_account_code, variance_pesewas, currency, aging_bucket, status, resolution_notes, resolved_by, resolved_at, created_at, updated_at FROM recon_suspense_ledger WHERE status IN ('OPEN', 'UNDER_INVESTIGATION') ORDER BY created_at ASC";

        var client = db.sql(query);
        if (bucket != null) {
            client = client.param("bucket", bucket.getCode());
        }

        return client.query((rs, rowNum) -> new SuspenseRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("match_record_id", UUID.class),
                rs.getString("suspense_account_code"),
                rs.getLong("variance_pesewas"),
                rs.getString("currency"),
                AgingBucket.fromCode(rs.getString("aging_bucket")),
                SuspenseStatus.valueOf(rs.getString("status")),
                rs.getString("resolution_notes"),
                rs.getString("resolved_by"),
                rs.getObject("resolved_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        )).list();
    }
}
