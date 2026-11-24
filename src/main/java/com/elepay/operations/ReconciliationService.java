package com.elepay.operations;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.Idempotency;
import com.elepay.common.OpsQueue;
import com.elepay.identity.Actor;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Reconciliation, Treasury & Suspense Management.
 * Implements:
 * - Algorithm 049: Exact 4-way matching (statement line -> rail tx -> journal tx -> commercial checkout/payout/wallet)
 * - Algorithm 052: Fuzzy statement matching for truncated references and timestamp/amount tolerance
 * - Algorithm 053: Partner fee verification against contracted schedules (MTN, Telecel, GHIPSS)
 * - Algorithm 054: Suspense aging buckets (0-30, 31-60, 61-90, 90+ days) and maker-checker clearing
 * - Algorithm 079: Dual-control maker-checker enforcement
 */
@Service
@RestController
@RequestMapping("/v1/ops")
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final JdbcClient db;
    private final Ledger ledger;
    private final OpsQueue ops;
    private final Audit audit;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final Idempotency idempotency;

    public ReconciliationService(JdbcClient db, Ledger ledger, OpsQueue ops, Audit audit) {
        this(db, ledger, ops, audit, null, null);
    }

    public ReconciliationService(
            JdbcClient db,
            Ledger ledger,
            OpsQueue ops,
            Audit audit,
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate
    ) {
        this(db, ledger, ops, audit, jdbcTemplate, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReconciliationService(
            JdbcClient db,
            Ledger ledger,
            OpsQueue ops,
            Audit audit,
            @org.springframework.beans.factory.annotation.Autowired(required = false) org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Autowired(required = false) Idempotency idempotency
    ) {
        this.db = db;
        this.ledger = ledger;
        this.ops = ops;
        this.audit = audit;
        this.jdbcTemplate = jdbcTemplate;
        this.idempotency = idempotency;
    }

    public record StatementUploadRequest(
            @NotBlank String rail,
            @NotBlank String filename,
            @NotNull List<StatementLineInput> lines
    ) {}

    public record StatementLineInput(
            String externalReference,
            @JsonAlias({"amount", "amountPesewas"}) long amountPesewas,
            String currency,
            @JsonAlias({"fee", "feePesewas"}) long feePesewas,
            OffsetDateTime transactionTime,
            String rawDescription
    ) {}

    public record UploadResult(
            UUID uploadId,
            String rail,
            String filename,
            int totalLines,
            int matchedExact,
            int matchedFuzzy,
            int unmatched,
            int feeDiscrepancies
    ) {}

    public record SuspenseItem(
            UUID id,
            String source,
            long amountPesewas,
            String currency,
            String reason,
            String status,
            UUID proposedBy,
            String proposedAction,
            String proposedTargetAccount,
            UUID approvedBy,
            OffsetDateTime createdAt,
            OffsetDateTime resolvedAt,
            String agingBucket
    ) {}

    public record SuspenseProposalRequest(
            @NotNull UUID id,
            @NotBlank String action,
            @NotBlank String targetAccount
    ) {}

    public record AgingSummary(
            String bucket,
            long count,
            long totalAmountPesewas
    ) {}

    // ---- Statement Ingestion & 4-Way Matching --------------------------------------------

    @PostMapping("/reconciliation/statements")
    @Transactional
    public UploadResult uploadAndReconcile(@Valid @RequestBody StatementUploadRequest request) {
        Actor actor = Actor.current();
        actor.requireFinance();
        UUID actorUuid = resolveActorUuid(actor.subject());

        // Duplicate statement ingestion protection (content SHA-256)
        String contentHash = computeUploadSha256(request);
        if (idempotency != null) {
            if (idempotency.storedHash("reconciliation:statement_upload", contentHash).isPresent()) {
                throw ApiException.conflict("duplicate_statement",
                        "Duplicate statement content has already been uploaded and reconciled");
            }
            return idempotency.execute("reconciliation:statement_upload", contentHash, contentHash, UploadResult.class,
                    () -> doUploadAndReconcile(request, actor, actorUuid));
        }
        return doUploadAndReconcile(request, actor, actorUuid);
    }

    private UploadResult doUploadAndReconcile(StatementUploadRequest request, Actor actor, UUID actorUuid) {

        UUID uploadId = UUID.randomUUID();
        db.sql("""
                INSERT INTO statement_upload (id, rail, filename, uploaded_at, uploaded_by)
                VALUES (:id, :rail, :filename, now(), :by)""")
                .param("id", uploadId).param("rail", request.rail()).param("filename", request.filename())
                .param("by", actorUuid).update();

        if (jdbcTemplate != null && request.lines().size() > 100) {
            return uploadAndReconcileBulk(uploadId, request, actor.subject());
        }

        int matchedExact = 0;
        int matchedFuzzy = 0;
        int unmatched = 0;
        int feeDiscrepancies = 0;

        for (StatementLineInput line : request.lines()) {
            UUID lineId = UUID.randomUUID();
            String cur = line.currency() != null ? line.currency().toUpperCase() : Ledger.GHS;

            db.sql("""
                    INSERT INTO statement_line (id, upload_id, external_reference, amount_pesewas, currency,
                                                fee_pesewas, transaction_time, raw_description, match_status)
                    VALUES (:id, :uid, :ref, :amt, :cur, :fee, :time, :desc, 'UNMATCHED')""")
                    .param("id", lineId).param("uid", uploadId).param("ref", line.externalReference())
                    .param("amt", line.amountPesewas()).param("cur", cur).param("fee", line.feePesewas())
                    .param("time", line.transactionTime() != null ? line.transactionTime() : OffsetDateTime.now())
                    .param("desc", line.rawDescription()).update();

            // 1. Algorithm 049: Exact 4-way matching
            boolean matched = tryExact4WayMatch(lineId, request.rail(), line);
            if (matched) {
                matchedExact++;
            } else {
                // 2. Algorithm 052: Fuzzy statement matching
                boolean fuzzyMatched = tryFuzzyMatch(lineId, request.rail(), line);
                if (fuzzyMatched) {
                    matchedFuzzy++;
                    matched = true;
                } else {
                    unmatched++;
                    // Insert into suspense item and back with double-entry ledger posting
                    UUID suspenseId = UUID.randomUUID();
                    db.sql("""
                            INSERT INTO suspense_item (id, source, amount_pesewas, currency, reason, status, created_at)
                            VALUES (:id, :src, :amt, :cur, :reason, 'OPEN', now())""")
                            .param("id", suspenseId).param("src", "reconciliation:" + request.rail())
                            .param("amt", line.amountPesewas()).param("cur", cur)
                            .param("reason", "Unmatched statement line " + line.externalReference()).update();
                    postSuspenseItemBacking(suspenseId, request.rail(), cur, line.amountPesewas(),
                            "Unmatched statement line " + line.externalReference());
                }
            }

            // 3. Algorithm 053: Partner fee verification against contracted schedules for matched lines
            if (matched && verifyPartnerFee(lineId, request.rail(), line)) {
                feeDiscrepancies++;
            }
        }

        audit.record(actor.subject(), "reconciliation.statement_uploaded", "upload:" + uploadId,
                Map.of("total", request.lines().size(), "exact", matchedExact, "fuzzy", matchedFuzzy, "unmatched", unmatched));

        return new UploadResult(uploadId, request.rail(), request.filename(), request.lines().size(),
                matchedExact, matchedFuzzy, unmatched, feeDiscrepancies);
    }

    private record BulkLineEntry(UUID lineId, StatementLineInput input, String currency, OffsetDateTime txTime,
                                 String matchStatus, String matchedInternalRef) {}

    private record RailRow(UUID id, String reference, long amount, String currency, String purpose, UUID purposeId) {}

    private UploadResult uploadAndReconcileBulk(UUID uploadId, StatementUploadRequest request, String actorSubject) {
        long tStart = System.nanoTime();
        List<StatementLineInput> lines = request.lines();
        List<String> refs = lines.stream()
                .map(StatementLineInput::externalReference)
                .filter(r -> r != null && !r.isBlank())
                .distinct()
                .toList();

        Map<String, List<RailRow>> railMap = new HashMap<>(refs.size());
        List<RailRow> allFetchedRows = new ArrayList<>();
        if (!refs.isEmpty()) {
            int arrayChunkSize = 50_000;
            for (int start = 0; start < refs.size(); start += arrayChunkSize) {
                String[] chunkArr = refs.subList(start, Math.min(start + arrayChunkSize, refs.size()))
                        .toArray(String[]::new);
                jdbcTemplate.execute((java.sql.Connection conn) -> {
                    java.sql.Array sqlRefs = conn.createArrayOf("text", chunkArr);
                    try (var ps = conn.prepareStatement("""
                            WITH refs AS (
                                SELECT unnest(?::text[]) AS ref
                            )
                            SELECT rt.id, rt.reference, rt.rail_reference, rt.amount, UPPER(rt.currency), rt.purpose, rt.purpose_id
                            FROM rail_transaction rt
                            JOIN refs r ON rt.reference = r.ref
                            WHERE rt.rail = ?
                            UNION ALL
                            SELECT rt.id, rt.reference, rt.rail_reference, rt.amount, UPPER(rt.currency), rt.purpose, rt.purpose_id
                            FROM rail_transaction rt
                            JOIN refs r ON rt.rail_reference = r.ref
                            WHERE rt.rail = ?""")) {
                        ps.setArray(1, sqlRefs);
                        ps.setString(2, request.rail());
                        ps.setString(3, request.rail());
                        ps.setFetchSize(5000);
                        Set<UUID> seenRailIds = new HashSet<>(chunkArr.length * 2);
                        try (var rs = ps.executeQuery()) {
                            while (rs.next()) {
                                UUID id = rs.getObject(1, UUID.class);
                                if (!seenRailIds.add(id)) {
                                    continue;
                                }
                                RailRow rr = new RailRow(
                                        id,
                                        rs.getString(2),
                                        rs.getLong(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getObject(7, UUID.class)
                                );
                                allFetchedRows.add(rr);
                                String r1 = rs.getString(2);
                                String r2 = rs.getString(3);
                                if (r1 != null) railMap.computeIfAbsent(r1, k -> new ArrayList<>()).add(rr);
                                if (r2 != null) railMap.computeIfAbsent(r2, k -> new ArrayList<>()).add(rr);
                            }
                        }
                    }
                    return null;
                });
            }
        }

        long tRail = System.nanoTime();
        // Bulk-check commercial records (4-way verification)
        Set<UUID> verifiedRailTxIds = bulkCheckCommercialRecords(allFetchedRows);
        long tComm = System.nanoTime();

        PartnerFeeSchedule schedule = getFeeSchedule(request.rail());
        OffsetDateTime now = OffsetDateTime.now();
        List<BulkLineEntry> entries = new ArrayList<>(lines.size());
        List<BulkLineEntry> matchedEntries = new ArrayList<>(lines.size());
        List<BulkLineEntry> fuzzyMatchedEntries = new ArrayList<>();
        List<BulkLineEntry> pendingUnmatched = new ArrayList<>();
        List<OpsQueue.BatchEntry> opsRaises = new ArrayList<>();
        int matchedExact = 0;
        int matchedFuzzy = 0;
        int feeDiscrepancies = 0;
        boolean perLineFeeOps = lines.size() <= 1_000;
        long overchargeCount = 0;
        long overchargeTotal = 0;
        long underchargeCount = 0;
        long underchargeTotal = 0;

        for (StatementLineInput line : lines) {
            UUID lineId = UUID.randomUUID();
            String cur = line.currency() != null ? line.currency().toUpperCase() : Ledger.GHS;
            OffsetDateTime txTime = line.transactionTime() != null ? line.transactionTime() : now;

            RailRow matchedRow = null;
            if (line.externalReference() != null) {
                List<RailRow> candidates = railMap.get(line.externalReference());
                if (candidates != null) {
                    for (RailRow rr : candidates) {
                        if (rr.amount() == line.amountPesewas()
                                && cur.equalsIgnoreCase(rr.currency())
                                && verifiedRailTxIds.contains(rr.id())) {
                            matchedRow = rr;
                            break;
                        }
                    }
                }
            }

            if (matchedRow != null) {
                matchedExact++;
                BulkLineEntry entry = new BulkLineEntry(lineId, line, cur, txTime, "MATCHED", matchedRow.reference());
                entries.add(entry);
                matchedEntries.add(entry);
                if (schedule != null) {
                    if (perLineFeeOps) {
                        if (verifyPartnerFeeBulk(lineId, request.rail(), line, schedule, opsRaises)) {
                            feeDiscrepancies++;
                        }
                    } else {
                        long expectedFee = calculateExpectedFee(schedule, line.amountPesewas());
                        long actualFee = line.feePesewas();
                        if (actualFee > expectedFee) {
                            overchargeCount++;
                            overchargeTotal += (actualFee - expectedFee);
                            feeDiscrepancies++;
                        } else if (actualFee < expectedFee) {
                            underchargeCount++;
                            underchargeTotal += (expectedFee - actualFee);
                            feeDiscrepancies++;
                        }
                    }
                }
            } else {
                pendingUnmatched.add(new BulkLineEntry(lineId, line, cur, txTime, "UNMATCHED", null));
            }
        }

        // Bounded fuzzy matching on remaining unmatched lines
        List<BulkLineEntry> unmatchedEntries = new ArrayList<>();
        if (!pendingUnmatched.isEmpty()) {
            OffsetDateTime minTime = pendingUnmatched.stream()
                    .map(BulkLineEntry::txTime).min(OffsetDateTime::compareTo).orElse(now).minusHours(24);
            OffsetDateTime maxTime = pendingUnmatched.stream()
                    .map(BulkLineEntry::txTime).max(OffsetDateTime::compareTo).orElse(now).plusHours(24);

            var fuzzyCandidates = db.sql("""
                    SELECT id, reference, amount, UPPER(currency), created_at
                    FROM rail_transaction
                    WHERE rail = :rail
                      AND status IN ('succeeded', 'created', 'pending')
                      AND created_at BETWEEN :from AND :to
                    ORDER BY created_at DESC LIMIT 500""")
                    .param("rail", request.rail())
                    .param("from", minTime)
                    .param("to", maxTime)
                    .query((rs, i) -> new Object[]{
                            rs.getObject(1, UUID.class),
                            rs.getString(2),
                            rs.getLong(3),
                            rs.getString(4),
                            rs.getObject(5, OffsetDateTime.class)
                    }).list();

            for (BulkLineEntry e : pendingUnmatched) {
                String ref = e.input().externalReference() != null ? e.input().externalReference().trim() : "";
                String matchedCandRef = null;
                long matchedCandAmt = 0L;

                if (!ref.isBlank()) {
                    for (Object[] cand : fuzzyCandidates) {
                        String candRef = (String) cand[1];
                        long candAmount = (Long) cand[2];
                        String candCur = (String) cand[3];
                        OffsetDateTime candTime = (OffsetDateTime) cand[4];

                        if (!e.currency().equalsIgnoreCase(candCur)) continue;
                        boolean refMatch = candRef.startsWith(ref) || ref.startsWith(candRef)
                                || (ref.length() >= 6 && candRef.contains(ref))
                                || (candRef.length() >= 6 && ref.contains(candRef));
                        boolean amountMatch = Math.abs(candAmount - e.input().amountPesewas()) <= 200;
                        boolean timeMatch = e.input().transactionTime() == null
                                || Math.abs(Duration.between(e.input().transactionTime(), candTime).toHours()) <= 24;

                        if (refMatch && amountMatch && timeMatch) {
                            matchedCandRef = candRef;
                            matchedCandAmt = candAmount;
                            break;
                        }
                    }
                }

                if (matchedCandRef != null) {
                    matchedFuzzy++;
                    BulkLineEntry fuzzyEntry = new BulkLineEntry(
                            e.lineId(), e.input(), e.currency(), e.txTime(), "PROPOSED_MATCH", matchedCandRef);
                    entries.add(fuzzyEntry);
                    fuzzyMatchedEntries.add(fuzzyEntry);
                    opsRaises.add(new OpsQueue.BatchEntry(
                            "proposed_match",
                            "medium",
                            "reconciliation:" + e.lineId(),
                            "{\"lineId\":" + jsonStr(e.lineId().toString())
                                    + ",\"statementRef\":" + jsonStr(ref)
                                    + ",\"candidateRef\":" + jsonStr(matchedCandRef)
                                    + ",\"amountPesewas\":" + e.input().amountPesewas()
                                    + ",\"candidateAmount\":" + matchedCandAmt
                                    + ",\"status\":\"PROPOSED_MATCH\"}"
                    ));
                    if (schedule != null) {
                        if (perLineFeeOps) {
                            if (verifyPartnerFeeBulk(e.lineId(), request.rail(), e.input(), schedule, opsRaises)) {
                                feeDiscrepancies++;
                            }
                        } else {
                            long expectedFee = calculateExpectedFee(schedule, e.input().amountPesewas());
                            long actualFee = e.input().feePesewas();
                            if (actualFee > expectedFee) {
                                overchargeCount++;
                                overchargeTotal += (actualFee - expectedFee);
                                feeDiscrepancies++;
                            } else if (actualFee < expectedFee) {
                                underchargeCount++;
                                underchargeTotal += (expectedFee - actualFee);
                                feeDiscrepancies++;
                            }
                        }
                    }
                } else {
                    entries.add(e);
                    unmatchedEntries.add(e);
                }
            }
        }

        if (schedule != null) {
            if (overchargeCount > 0) {
                opsRaises.add(new OpsQueue.BatchEntry(
                        "partner_fee_overcharge",
                        "medium",
                        "reconciliation:upload:" + uploadId + ":overcharge",
                        "{\"rail\":" + jsonStr(request.rail())
                                + ",\"uploadId\":" + jsonStr(uploadId.toString())
                                + ",\"count\":" + overchargeCount
                                + ",\"totalDiscrepancy\":" + overchargeTotal
                                + ",\"type\":\"overcharge\",\"version\":" + schedule.version() + "}"
                ));
            }
            if (underchargeCount > 0) {
                opsRaises.add(new OpsQueue.BatchEntry(
                        "partner_fee_undercharge",
                        "low",
                        "reconciliation:upload:" + uploadId + ":undercharge",
                        "{\"rail\":" + jsonStr(request.rail())
                                + ",\"uploadId\":" + jsonStr(uploadId.toString())
                                + ",\"count\":" + underchargeCount
                                + ",\"totalDiscrepancy\":" + underchargeTotal
                                + ",\"type\":\"undercharge\",\"version\":" + schedule.version() + "}"
                ));
            }
        }

        int unmatched = unmatchedEntries.size();
        long tJavaLoop = System.nanoTime();

        int writeChunkSize = 50_000;
        for (int start = 0; start < entries.size(); start += writeChunkSize) {
            List<BulkLineEntry> chunk = entries.subList(start, Math.min(start + writeChunkSize, entries.size()));
            int n = chunk.size();
            UUID[] lineIds = new UUID[n];
            String[] extRefs = new String[n];
            Long[] amts = new Long[n];
            String[] curs = new String[n];
            Long[] fees = new Long[n];
            java.sql.Timestamp[] times = new java.sql.Timestamp[n];
            String[] descs = new String[n];
            String[] statuses = new String[n];
            for (int i = 0; i < n; i++) {
                BulkLineEntry e = chunk.get(i);
                lineIds[i] = e.lineId();
                extRefs[i] = e.input().externalReference();
                amts[i] = e.input().amountPesewas();
                curs[i] = e.currency();
                fees[i] = e.input().feePesewas();
                times[i] = java.sql.Timestamp.from(e.txTime().toInstant());
                descs[i] = e.input().rawDescription();
                statuses[i] = e.matchStatus();
            }
            jdbcTemplate.execute((java.sql.Connection conn) -> {
                try (var ps = conn.prepareStatement("""
                        INSERT INTO statement_line (id, upload_id, external_reference, amount_pesewas, currency,
                                                    fee_pesewas, transaction_time, raw_description, match_status)
                        SELECT u.id, ?, u.ext_ref, u.amt, u.cur, u.fee, u.tx_time, u.raw_desc, u.status
                        FROM unnest(?::uuid[], ?::varchar[], ?::bigint[], ?::varchar[], ?::bigint[], ?::timestamptz[], ?::text[], ?::varchar[])
                             AS u(id, ext_ref, amt, cur, fee, tx_time, raw_desc, status)""")) {
                    ps.setObject(1, uploadId);
                    ps.setArray(2, conn.createArrayOf("uuid", lineIds));
                    ps.setArray(3, conn.createArrayOf("varchar", extRefs));
                    ps.setArray(4, conn.createArrayOf("bigint", amts));
                    ps.setArray(5, conn.createArrayOf("varchar", curs));
                    ps.setArray(6, conn.createArrayOf("bigint", fees));
                    ps.setArray(7, conn.createArrayOf("timestamptz", times));
                    ps.setArray(8, conn.createArrayOf("text", descs));
                    ps.setArray(9, conn.createArrayOf("varchar", statuses));
                    ps.executeUpdate();
                }
                return null;
            });
        }
        long tStmtInsert = System.nanoTime();

        if (!matchedEntries.isEmpty()) {
            bulkInsertMatches(matchedEntries, "EXACT_4WAY", 100.00);
        }

        if (!fuzzyMatchedEntries.isEmpty()) {
            bulkInsertMatches(fuzzyMatchedEntries, "PROPOSED_MATCH", 85.00);
        }

        if (!unmatchedEntries.isEmpty()) {
            String src = "reconciliation:" + request.rail();
            for (int start = 0; start < unmatchedEntries.size(); start += writeChunkSize) {
                List<BulkLineEntry> chunk = unmatchedEntries.subList(start, Math.min(start + writeChunkSize, unmatchedEntries.size()));
                int n = chunk.size();
                UUID[] sIds = new UUID[n];
                Long[] sAmts = new Long[n];
                String[] sCurs = new String[n];
                String[] sReasons = new String[n];
                for (int i = 0; i < n; i++) {
                    BulkLineEntry e = chunk.get(i);
                    sIds[i] = UUID.randomUUID();
                    sAmts[i] = e.input().amountPesewas();
                    sCurs[i] = e.currency();
                    sReasons[i] = "Unmatched statement line " + e.input().externalReference();
                }
                jdbcTemplate.execute((java.sql.Connection conn) -> {
                    try (var ps = conn.prepareStatement("""
                            INSERT INTO suspense_item (id, source, amount_pesewas, currency, reason, status, created_at)
                            SELECT u.id, ?, u.amt, u.cur, u.reason, 'OPEN', now()
                            FROM unnest(?::uuid[], ?::bigint[], ?::varchar[], ?::text[])
                                 AS u(id, amt, cur, reason)""")) {
                        ps.setString(1, src);
                        ps.setArray(2, conn.createArrayOf("uuid", sIds));
                        ps.setArray(3, conn.createArrayOf("bigint", sAmts));
                        ps.setArray(4, conn.createArrayOf("varchar", sCurs));
                        ps.setArray(5, conn.createArrayOf("text", sReasons));
                        ps.executeUpdate();
                    }
                    return null;
                });
            }
            // Aggregate suspense backing postings per currency so ledger remains balanced and backed
            Map<String, Long> suspenseTotalsByCurrency = new HashMap<>();
            for (BulkLineEntry e : unmatchedEntries) {
                if (e.input().amountPesewas() > 0) {
                    suspenseTotalsByCurrency.merge(e.currency(), e.input().amountPesewas(), Long::sum);
                }
            }
            for (var curEntry : suspenseTotalsByCurrency.entrySet()) {
                postSuspenseItemBacking(uploadId, request.rail(), curEntry.getKey(), curEntry.getValue(),
                        "Bulk unmatched statement lines for upload " + uploadId);
            }
        }

        long tMatchInsert = System.nanoTime();
        if (!opsRaises.isEmpty()) {
            ops.raiseBatch(opsRaises);
        }

        long tMatch = System.nanoTime();
        audit.record(actorSubject, "reconciliation.statement_uploaded", "upload:" + uploadId,
                Map.of("total", lines.size(), "exact", matchedExact, "fuzzy", matchedFuzzy, "unmatched", unmatched));
        long tEnd = System.nanoTime();
        log.debug("BULK_RECON_TIMING total={}ms railFetch={}ms commCheck={}ms javaLoop={}ms stmtInsert={}ms matchInsert={}ms opsRaise={}ms audit={}ms (opsRaises={})",
                (tEnd - tStart) / 1_000_000,
                (tRail - tStart) / 1_000_000,
                (tComm - tRail) / 1_000_000,
                (tJavaLoop - tComm) / 1_000_000,
                (tStmtInsert - tJavaLoop) / 1_000_000,
                (tMatchInsert - tStmtInsert) / 1_000_000,
                (tMatch - tMatchInsert) / 1_000_000,
                (tEnd - tMatch) / 1_000_000,
                opsRaises.size());

        return new UploadResult(uploadId, request.rail(), request.filename(), lines.size(),
                matchedExact, matchedFuzzy, unmatched, feeDiscrepancies);
    }

    private void bulkInsertMatches(List<BulkLineEntry> matchEntries, String matchType, double confidence) {
        int writeChunkSize = 50_000;
        for (int start = 0; start < matchEntries.size(); start += writeChunkSize) {
            List<BulkLineEntry> chunk = matchEntries.subList(start, Math.min(start + writeChunkSize, matchEntries.size()));
            int n = chunk.size();
            UUID[] mIds = new UUID[n];
            UUID[] lineIds = new UUID[n];
            String[] internalRefs = new String[n];
            for (int i = 0; i < n; i++) {
                BulkLineEntry e = chunk.get(i);
                mIds[i] = UUID.randomUUID();
                lineIds[i] = e.lineId();
                internalRefs[i] = e.matchedInternalRef();
            }
            jdbcTemplate.execute((java.sql.Connection conn) -> {
                try (var ps = conn.prepareStatement("""
                        INSERT INTO reconciliation_match (id, statement_line_id, internal_reference, match_type, confidence, matched_at)
                        SELECT u.id, u.line_id, u.internal_ref, ?, ?, now()
                        FROM unnest(?::uuid[], ?::uuid[], ?::varchar[]) AS u(id, line_id, internal_ref)""")) {
                    ps.setString(1, matchType);
                    ps.setBigDecimal(2, BigDecimal.valueOf(confidence));
                    ps.setArray(3, conn.createArrayOf("uuid", mIds));
                    ps.setArray(4, conn.createArrayOf("uuid", lineIds));
                    ps.setArray(5, conn.createArrayOf("varchar", internalRefs));
                    ps.executeUpdate();
                }
                return null;
            });
        }
    }

    private boolean verifyPartnerFeeBulk(UUID lineId, String rail, StatementLineInput line,
                                         PartnerFeeSchedule schedule, List<OpsQueue.BatchEntry> opsRaises) {
        if (schedule == null) {
            return false;
        }
        long amount = line.amountPesewas();
        long actualFee = line.feePesewas();
        long expectedFee = calculateExpectedFee(schedule, amount);

        if (actualFee > expectedFee) {
            opsRaises.add(new OpsQueue.BatchEntry(
                    "partner_fee_overcharge",
                    "medium",
                    "reconciliation:" + lineId,
                    "{\"rail\":" + jsonStr(rail)
                            + ",\"amount\":" + amount
                            + ",\"actualFee\":" + actualFee
                            + ",\"expectedFee\":" + expectedFee
                            + ",\"discrepancy\":" + (actualFee - expectedFee)
                            + ",\"type\":\"overcharge\",\"version\":" + schedule.version() + "}"
            ));
            return true;
        } else if (actualFee < expectedFee) {
            opsRaises.add(new OpsQueue.BatchEntry(
                    "partner_fee_undercharge",
                    "low",
                    "reconciliation:" + lineId,
                    "{\"rail\":" + jsonStr(rail)
                            + ",\"amount\":" + amount
                            + ",\"actualFee\":" + actualFee
                            + ",\"expectedFee\":" + expectedFee
                            + ",\"discrepancy\":" + (expectedFee - actualFee)
                            + ",\"type\":\"undercharge\",\"version\":" + schedule.version() + "}"
            ));
            return true;
        }
        return false;
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private Set<UUID> bulkCheckCommercialRecords(List<RailRow> rows) {
        Set<UUID> verified = new HashSet<>();
        if (rows.isEmpty()) return verified;

        Set<UUID> checkoutIdSet = new HashSet<>();
        List<UUID> checkoutTxIds = new ArrayList<>();
        Set<UUID> payoutIdSet = new HashSet<>();
        List<UUID> payoutTxIds = new ArrayList<>();
        Set<UUID> walletIdSet = new HashSet<>();
        List<UUID> walletTxIds = new ArrayList<>();

        Map<UUID, RailRow> byId = new HashMap<>(rows.size() * 2);
        for (RailRow r : rows) {
            byId.put(r.id(), r);
            if ("checkout".equalsIgnoreCase(r.purpose())) {
                if (r.purposeId() != null) checkoutIdSet.add(r.purposeId());
                checkoutTxIds.add(r.id());
            } else if ("payout".equalsIgnoreCase(r.purpose())) {
                if (r.purposeId() != null) payoutIdSet.add(r.purposeId());
                payoutTxIds.add(r.id());
            } else if ("wallet".equalsIgnoreCase(r.purpose())) {
                if (r.purposeId() != null) walletIdSet.add(r.purposeId());
                walletTxIds.add(r.id());
            } else {
                verified.add(r.id());
            }
        }

        jdbcTemplate.execute((java.sql.Connection conn) -> {
            if (!checkoutTxIds.isEmpty()) {
                Set<UUID> foundIds = queryMatchingUuids(conn, "SELECT t.id FROM payment_attempt t JOIN unnest(?::uuid[]) u(id) ON t.id = u.id", checkoutIdSet);
                Set<UUID> foundTxIds = queryMatchingUuids(conn, "SELECT t.rail_tx FROM payment_attempt t JOIN unnest(?::uuid[]) u(id) ON t.rail_tx = u.id", checkoutTxIds);
                for (UUID txId : checkoutTxIds) {
                    RailRow r = byId.get(txId);
                    if (foundTxIds.contains(txId) || (r.purposeId() != null && foundIds.contains(r.purposeId()))) {
                        verified.add(txId);
                    }
                }
            }
            if (!payoutTxIds.isEmpty()) {
                Set<UUID> foundIds = queryMatchingUuids(conn, "SELECT t.id FROM payout t JOIN unnest(?::uuid[]) u(id) ON t.id = u.id", payoutIdSet);
                Set<UUID> foundTxIds = queryMatchingUuids(conn, "SELECT t.rail_tx FROM payout t JOIN unnest(?::uuid[]) u(id) ON t.rail_tx = u.id", payoutTxIds);
                for (UUID txId : payoutTxIds) {
                    RailRow r = byId.get(txId);
                    if (foundTxIds.contains(txId) || (r.purposeId() != null && foundIds.contains(r.purposeId()))) {
                        verified.add(txId);
                    }
                }
            }
            if (!walletTxIds.isEmpty()) {
                Set<UUID> foundIds = queryMatchingUuids(conn, "SELECT t.id FROM wallet_operation t JOIN unnest(?::uuid[]) u(id) ON t.id = u.id", walletIdSet);
                Set<UUID> foundTxIds = queryMatchingUuids(conn, "SELECT t.rail_tx FROM wallet_operation t JOIN unnest(?::uuid[]) u(id) ON t.rail_tx = u.id", walletTxIds);
                for (UUID txId : walletTxIds) {
                    RailRow r = byId.get(txId);
                    if (foundTxIds.contains(txId) || (r.purposeId() != null && foundIds.contains(r.purposeId()))) {
                        verified.add(txId);
                    }
                }
            }
            return null;
        });
        return verified;
    }

    private static Set<UUID> queryMatchingUuids(java.sql.Connection conn, String sql, Collection<UUID> ids) throws java.sql.SQLException {
        Set<UUID> result = new HashSet<>();
        if (ids.isEmpty()) return result;
        UUID[] arr = ids.toArray(UUID[]::new);
        try (var ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("uuid", arr));
            ps.setFetchSize(5000);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = rs.getObject(1, UUID.class);
                    if (id != null) result.add(id);
                }
            }
        }
        return result;
    }

    private void postSuspenseItemBacking(UUID keyId, String rail, String currency, long amountPesewas, String description) {
        if (amountPesewas <= 0) return;
        String cur = currency != null ? currency.toUpperCase() : Ledger.GHS;
        String suspenseAccount = "platform:suspense:" + cur.toLowerCase();
        String clearingAccount = "system:clearing:" + rail.toLowerCase() + ":" + cur;
        ledger.openSystem(suspenseAccount, "suspense", cur, true);
        ledger.openSystem(clearingAccount, "clearing", cur, true);
        ledger.post(Ledger.Posting.of("suspense-open", keyId.toString() + ":" + cur, "suspense_item", List.of(
                Leg.debit(clearingAccount, amountPesewas),
                Leg.credit(suspenseAccount, amountPesewas)
        )).describedAs(description));
    }

    private static String computeUploadSha256(StatementUploadRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.rail()).append('|');
        for (StatementLineInput l : request.lines()) {
            sb.append(l.externalReference()).append('|')
              .append(l.amountPesewas()).append('|')
              .append(l.currency() != null ? l.currency().toUpperCase() : Ledger.GHS).append('|')
              .append(l.feePesewas()).append('|')
              .append(l.transactionTime()).append('\n');
        }
        return Idempotency.sha256(sb.toString());
    }

    private boolean tryExact4WayMatch(UUID lineId, String rail, StatementLineInput line) {
        if (line.externalReference() == null || line.externalReference().isBlank()) return false;
        String cur = line.currency() != null ? line.currency().toUpperCase() : Ledger.GHS;

        // Query rail_transaction for exact reference and currency match
        var txOpt = db.sql("""
                SELECT id, reference, amount, purpose, purpose_id
                FROM rail_transaction
                WHERE rail = :rail
                  AND UPPER(currency) = :cur
                  AND (reference = :ref OR rail_reference = :ref OR CAST(purpose_id AS text) = :ref)
                ORDER BY created_at DESC LIMIT 1""")
                .param("rail", rail).param("cur", cur).param("ref", line.externalReference())
                .query((rs, i) -> new Object[]{
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getObject(5, UUID.class)
                }).optional();

        if (txOpt.isEmpty()) return false;

        Object[] tx = txOpt.get();
        UUID txId = (UUID) tx[0];
        String reference = (String) tx[1];
        long txAmount = (Long) tx[2];
        String purpose = (String) tx[3];
        UUID purposeId = (UUID) tx[4];

        // 4-way validation: amount matches, commercial record exists
        if (txAmount != line.amountPesewas()) return false;

        boolean commercialExists = checkCommercialRecordExists(purpose, purposeId, txId);
        if (!commercialExists) return false;

        // Exact match confirmed!
        UUID matchId = UUID.randomUUID();
        db.sql("""
                INSERT INTO reconciliation_match (id, statement_line_id, internal_reference, match_type, confidence, matched_at)
                VALUES (:id, :lid, :ref, 'EXACT_4WAY', 100.00, now())""")
                .param("id", matchId).param("lid", lineId).param("ref", reference).update();

        db.sql("UPDATE statement_line SET match_status = 'MATCHED' WHERE id = :id").param("id", lineId).update();
        return true;
    }

    public record PartnerFeeSchedule(
            String rail,
            int feeBps,
            long fixedFeePesewas,
            Long capPesewas,
            int version
    ) {}

    private static final Map<String, PartnerFeeSchedule> DEFAULT_FEE_SCHEDULES = Map.of(
            "mtn", new PartnerFeeSchedule("mtn", 100, 0, 1000L, 1),
            "telecel", new PartnerFeeSchedule("telecel", 75, 0, null, 1),
            "airteltigo", new PartnerFeeSchedule("airteltigo", 75, 0, null, 1),
            "ghipss", new PartnerFeeSchedule("ghipss", 0, 50, null, 1)
    );

    public PartnerFeeSchedule getFeeSchedule(String rail) {
        String r = rail.toLowerCase();
        try {
            Boolean exists = db.sql("SELECT to_regclass('public.partner_fee_schedule') IS NOT NULL")
                    .query(Boolean.class).single();
            if (Boolean.TRUE.equals(exists)) {
                var row = db.sql("""
                        SELECT rail, fee_bps, fixed_fee_pesewas, cap_pesewas, version
                        FROM partner_fee_schedule
                        WHERE lower(rail) = :r OR :r LIKE ('%' || lower(rail) || '%')
                        ORDER BY version DESC LIMIT 1""")
                        .param("r", r)
                        .query((rs, i) -> new PartnerFeeSchedule(
                                rs.getString(1),
                                rs.getInt(2),
                                rs.getLong(3),
                                rs.getObject(4) != null ? rs.getLong(4) : null,
                                rs.getInt(5)
                        )).optional();
                if (row.isPresent()) {
                    return row.get();
                }
            }
        } catch (Exception ignored) {
            // Table check failed, fall back to versioned in-code configuration
        }
        for (var entry : DEFAULT_FEE_SCHEDULES.entrySet()) {
            if (r.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static long calculateExpectedFee(PartnerFeeSchedule schedule, long amount) {
        long fee = schedule.fixedFeePesewas();
        if (schedule.feeBps() > 0) {
            BigDecimal bpsFee = BigDecimal.valueOf(amount)
                    .multiply(BigDecimal.valueOf(schedule.feeBps()))
                    .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_EVEN);
            fee += bpsFee.longValueExact();
        }
        if (schedule.capPesewas() != null && fee > schedule.capPesewas()) {
            fee = schedule.capPesewas();
        }
        return fee;
    }

    private boolean tryFuzzyMatch(UUID lineId, String rail, StatementLineInput line) {
        if (line.externalReference() == null || line.externalReference().isBlank()) return false;
        String ref = line.externalReference().trim();
        String cur = line.currency() != null ? line.currency().toUpperCase() : Ledger.GHS;
        OffsetDateTime txTime = line.transactionTime() != null ? line.transactionTime() : OffsetDateTime.now();

        // Check for prefix/suffix match within +/- 24h window and currency match
        var candidates = db.sql("""
                SELECT id, reference, amount, created_at
                FROM rail_transaction
                WHERE rail = :rail
                  AND UPPER(currency) = :cur
                  AND status IN ('succeeded', 'created', 'pending')
                  AND created_at BETWEEN :from AND :to
                ORDER BY created_at DESC LIMIT 500""")
                .param("rail", rail)
                .param("cur", cur)
                .param("from", txTime.minusHours(24))
                .param("to", txTime.plusHours(24))
                .query((rs, i) -> new Object[]{
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                        rs.getObject(4, OffsetDateTime.class)
                }).list();

        for (Object[] cand : candidates) {
            String candRef = (String) cand[1];
            long candAmount = (Long) cand[2];
            OffsetDateTime candTime = (OffsetDateTime) cand[3];

            boolean refMatch = candRef.startsWith(ref) || ref.startsWith(candRef)
                    || (ref.length() >= 6 && candRef.contains(ref))
                    || (candRef.length() >= 6 && ref.contains(candRef));

            boolean amountMatch = Math.abs(candAmount - line.amountPesewas()) <= 200; // within 2 GHS tolerance
            boolean timeMatch = line.transactionTime() == null || Math.abs(Duration.between(line.transactionTime(), candTime).toHours()) <= 24;

            // Algorithm 052: Both amount and time must match in addition to reference match
            if (refMatch && amountMatch && timeMatch) {
                UUID matchId = UUID.randomUUID();
                db.sql("""
                        INSERT INTO reconciliation_match (id, statement_line_id, internal_reference, match_type, confidence, matched_at)
                        VALUES (:id, :lid, :ref, 'PROPOSED_MATCH', 85.00, now())""")
                        .param("id", matchId).param("lid", lineId).param("ref", candRef).update();

                db.sql("UPDATE statement_line SET match_status = 'PROPOSED_MATCH' WHERE id = :id").param("id", lineId).update();
                ops.raise("proposed_match", "medium", "reconciliation:" + lineId,
                        Map.of("lineId", lineId.toString(), "matchId", matchId.toString(),
                                "statementRef", ref, "candidateRef", candRef,
                                "amountPesewas", line.amountPesewas(), "candidateAmount", candAmount,
                                "status", "PROPOSED_MATCH"));
                return true;
            }
        }
        return false;
    }

    @PostMapping("/reconciliation/matches/{matchId}/approve")
    @Transactional
    public Map<String, Object> approveFuzzyMatch(@PathVariable UUID matchId) {
        Actor actor = Actor.current();
        actor.requireFinance();

        var matchOpt = db.sql("""
                SELECT rm.id, rm.statement_line_id, rm.internal_reference, rm.match_type,
                       sl.match_status, su.uploaded_by
                FROM reconciliation_match rm
                JOIN statement_line sl ON sl.id = rm.statement_line_id
                JOIN statement_upload su ON su.id = sl.upload_id
                WHERE rm.id = :id FOR UPDATE""")
                .param("id", matchId)
                .query((rs, i) -> Map.of(
                        "id", rs.getObject(1, UUID.class),
                        "lineId", rs.getObject(2, UUID.class),
                        "ref", rs.getString(3),
                        "matchType", rs.getString(4),
                        "status", rs.getString(5),
                        "uploadedBy", rs.getObject(6, UUID.class) != null ? rs.getObject(6, UUID.class) : UUID.randomUUID()
                )).optional();

        if (matchOpt.isEmpty()) {
            throw ApiException.notFound("Reconciliation match not found");
        }
        var match = matchOpt.get();
        if (!"PROPOSED_MATCH".equalsIgnoreCase((String) match.get("status"))) {
            throw ApiException.conflict("match_not_proposed", "Match is not in PROPOSED_MATCH state");
        }

        UUID actorUuid = resolveActorUuid(actor.subject());
        if (actorUuid.equals(match.get("uploadedBy"))) {
            throw ApiException.forbidden("Maker cannot approve their own proposed fuzzy match");
        }

        UUID lineId = (UUID) match.get("lineId");
        db.sql("UPDATE statement_line SET match_status = 'MATCHED' WHERE id = :id").param("id", lineId).update();
        db.sql("UPDATE reconciliation_match SET match_type = 'FUZZY_APPROVED' WHERE id = :id").param("id", matchId).update();

        audit.record(actor.subject(), "reconciliation.fuzzy_approved", "match:" + matchId,
                Map.of("lineId", lineId.toString(), "approvedBy", actor.subject()));

        return Map.of("matchId", matchId, "status", "MATCHED");
    }

    private boolean checkCommercialRecordExists(String purpose, UUID purposeId, UUID railTxId) {
        if ("checkout".equalsIgnoreCase(purpose)) {
            Long count = db.sql("SELECT count(*) FROM payment_attempt WHERE id = :id OR rail_tx = :tx")
                    .param("id", purposeId).param("tx", railTxId).query(Long.class).single();
            return count > 0;
        } else if ("payout".equalsIgnoreCase(purpose)) {
            Long count = db.sql("SELECT count(*) FROM payout WHERE id = :id OR rail_tx = :tx")
                    .param("id", purposeId).param("tx", railTxId).query(Long.class).single();
            return count > 0;
        } else if ("wallet".equalsIgnoreCase(purpose)) {
            Long count = db.sql("SELECT count(*) FROM wallet_operation WHERE id = :id OR rail_tx = :tx")
                    .param("id", purposeId).param("tx", railTxId).query(Long.class).single();
            return count > 0;
        }
        return true;
    }

    /**
     * Algorithm 053: Partner fee verification against versioned fee matrices.
     * Computes integer minor units with banker's rounding without truncation.
     * Detects both overcharges and undercharges.
     */
    private boolean verifyPartnerFee(UUID lineId, String rail, StatementLineInput line) {
        PartnerFeeSchedule schedule = getFeeSchedule(rail);
        if (schedule == null) {
            return false;
        }
        return verifyPartnerFee(lineId, rail, line, schedule);
    }

    private boolean verifyPartnerFee(UUID lineId, String rail, StatementLineInput line, PartnerFeeSchedule schedule) {
        if (schedule == null) {
            return false;
        }

        long amount = line.amountPesewas();
        long actualFee = line.feePesewas();
        long expectedFee = calculateExpectedFee(schedule, amount);

        if (actualFee > expectedFee) {
            ops.raise("partner_fee_overcharge", "medium", "reconciliation:" + lineId,
                    Map.of("rail", rail, "amount", amount, "actualFee", actualFee, "expectedFee", expectedFee,
                            "discrepancy", actualFee - expectedFee, "type", "overcharge", "version", schedule.version()));
            return true;
        } else if (actualFee < expectedFee) {
            ops.raise("partner_fee_undercharge", "low", "reconciliation:" + lineId,
                    Map.of("rail", rail, "amount", amount, "actualFee", actualFee, "expectedFee", expectedFee,
                            "discrepancy", expectedFee - actualFee, "type", "undercharge", "version", schedule.version()));
            return true;
        }
        return false;
    }

    // ---- Suspense Aging & Maker-Checker Clearing (Algorithms 054 & 079) -------------------

    @GetMapping("/suspense")
    public List<SuspenseItem> listSuspense(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "500") int limit) {
        Actor.current().requireOps();
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        return db.sql("""
                SELECT id, source, amount_pesewas, currency, reason, status, proposed_by,
                       proposed_action, proposed_target_account, approved_by, created_at, resolved_at,
                       CASE
                           WHEN created_at >= now() - interval '30 days' THEN '0-30 days'
                           WHEN created_at >= now() - interval '60 days' THEN '31-60 days'
                           WHEN created_at >= now() - interval '90 days' THEN '61-90 days'
                           ELSE '90+ days'
                       END AS aging_bucket
                FROM suspense_item
                WHERE (:status = 'ALL' OR status = :status)
                ORDER BY created_at DESC LIMIT :limit""")
                .param("status", status.toUpperCase())
                .param("limit", boundedLimit)
                .query(ReconciliationService::mapSuspense).list();
    }

    @GetMapping("/suspense/aging")
    public List<AgingSummary> suspenseAgingSummary() {
        Actor.current().requireOps();
        return db.sql("""
                SELECT
                    CASE
                        WHEN created_at >= now() - interval '30 days' THEN '0-30 days'
                        WHEN created_at >= now() - interval '60 days' THEN '31-60 days'
                        WHEN created_at >= now() - interval '90 days' THEN '61-90 days'
                        ELSE '90+ days'
                    END AS aging_bucket,
                    count(*) AS item_count,
                    COALESCE(sum(amount_pesewas), 0) AS total_amount
                FROM suspense_item
                WHERE status IN ('OPEN', 'PROPOSED')
                GROUP BY 1
                ORDER BY 1""")
                .query((rs, i) -> new AgingSummary(rs.getString(1), rs.getLong(2), rs.getLong(3)))
                .list();
    }

    private static void validateSuspenseTargetAccount(String targetAccount) {
        if (targetAccount == null || targetAccount.isBlank()) {
            throw ApiException.badRequest("invalid_suspense_target", "Target account is required");
        }
        String trimmed = targetAccount.trim();
        boolean allowlisted = (trimmed.startsWith("system:") || trimmed.startsWith("platform:"))
                && !trimmed.contains(":available:")
                && !trimmed.startsWith("merchant:")
                && !trimmed.startsWith("customer:");
        if (!allowlisted) {
            throw ApiException.forbidden("Suspense clearing target must be an allowlisted system: or platform: account");
        }
    }

    @PostMapping("/suspense/propose")
    @Transactional
    public SuspenseItem proposeClearing(@Valid @RequestBody SuspenseProposalRequest request) {
        Actor actor = Actor.current();
        actor.requireFinance();
        validateSuspenseTargetAccount(request.targetAccount());

        var itemOpt = db.sql("SELECT id, status FROM suspense_item WHERE id = :id FOR UPDATE")
                .param("id", request.id()).query((rs, i) -> Map.of("id", rs.getObject(1, UUID.class), "status", rs.getString(2)))
                .optional();
        if (itemOpt.isEmpty()) {
            throw ApiException.notFound("Suspense item not found");
        }
        if (!"OPEN".equalsIgnoreCase((String) itemOpt.get().get("status"))) {
            throw ApiException.conflict("suspense_not_open", "Suspense item is already proposed or cleared");
        }

        UUID actorUuid = resolveActorUuid(actor.subject());
        db.sql("""
                UPDATE suspense_item
                SET status = 'PROPOSED', proposed_by = :by, proposed_action = :action,
                    proposed_target_account = :target
                WHERE id = :id""")
                .param("by", actorUuid).param("action", request.action())
                .param("target", request.targetAccount().trim()).param("id", request.id()).update();

        audit.record(actor.subject(), "suspense.proposed", "suspense:" + request.id(),
                Map.of("action", request.action(), "targetAccount", request.targetAccount().trim()));

        return getSuspenseItem(request.id());
    }

    @PostMapping("/suspense/{id}/approve")
    @Transactional
    public SuspenseItem approveClearing(@PathVariable UUID id) {
        Actor actor = Actor.current();
        actor.requireOpsAdmin();

        var itemOpt = db.sql("""
                SELECT id, source, amount_pesewas, currency, reason, status, proposed_by,
                       proposed_action, proposed_target_account, approved_by, created_at, resolved_at,
                       CASE
                           WHEN created_at >= now() - interval '30 days' THEN '0-30 days'
                           WHEN created_at >= now() - interval '60 days' THEN '31-60 days'
                           WHEN created_at >= now() - interval '90 days' THEN '61-90 days'
                           ELSE '90+ days'
                       END AS aging_bucket
                FROM suspense_item WHERE id = :id FOR UPDATE""")
                .param("id", id)
                .query(ReconciliationService::mapSuspense).optional();

        if (itemOpt.isEmpty()) {
            throw ApiException.notFound("Suspense item not found");
        }
        var item = itemOpt.get();
        if (!"PROPOSED".equalsIgnoreCase(item.status())) {
            throw ApiException.conflict("suspense_not_proposed", "Suspense item is not proposed for clearing");
        }

        // Algorithm 079: Maker-Checker Dual Control Enforcement
        UUID actorUuid = resolveActorUuid(actor.subject());
        if (item.proposedBy() != null && item.proposedBy().equals(actorUuid)) {
            throw ApiException.forbidden("Maker cannot approve their own suspense resolution proposal");
        }

        // Post clearing entry in double-entry ledger using currency-specific suspense account
        if (item.proposedTargetAccount() != null && !item.proposedTargetAccount().isBlank()) {
            validateSuspenseTargetAccount(item.proposedTargetAccount());
            long amt = item.amountPesewas();
            String cur = item.currency() != null ? item.currency().toUpperCase() : Ledger.GHS;
            String suspenseAccount = "platform:suspense:" + cur.toLowerCase();
            ledger.openSystem(suspenseAccount, "suspense", cur, true);
            ledger.post(Ledger.Posting.of("suspense-clearing", id.toString(), "suspense_clearing", List.of(
                    Leg.debit(suspenseAccount, amt),
                    Leg.credit(item.proposedTargetAccount(), amt)
            )).describedAs("Suspense clearing for item " + id));
        }

        OffsetDateTime now = OffsetDateTime.now();
        db.sql("""
                UPDATE suspense_item
                SET status = 'CLEARED', approved_by = :by, resolved_at = :now
                WHERE id = :id""")
                .param("by", actorUuid).param("now", now).param("id", id).update();

        audit.record(actor.subject(), "suspense.cleared", "suspense:" + id,
                Map.of("maker", String.valueOf(item.proposedBy()), "checker", actor.subject(),
                        "targetAccount", String.valueOf(item.proposedTargetAccount())));

        return getSuspenseItem(id);
    }

    private SuspenseItem getSuspenseItem(UUID id) {
        return db.sql("""
                SELECT id, source, amount_pesewas, currency, reason, status, proposed_by,
                       proposed_action, proposed_target_account, approved_by, created_at, resolved_at,
                       CASE
                           WHEN created_at >= now() - interval '30 days' THEN '0-30 days'
                           WHEN created_at >= now() - interval '60 days' THEN '31-60 days'
                           WHEN created_at >= now() - interval '90 days' THEN '61-90 days'
                           ELSE '90+ days'
                       END AS aging_bucket
                FROM suspense_item WHERE id = :id""")
                .param("id", id)
                .query(ReconciliationService::mapSuspense).single();
    }

    private static SuspenseItem mapSuspense(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new SuspenseItem(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getLong(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getObject(7, UUID.class),
                rs.getString(8),
                rs.getString(9),
                rs.getObject(10, UUID.class),
                rs.getObject(11, OffsetDateTime.class),
                rs.getObject(12, OffsetDateTime.class),
                rs.getString(13)
        );
    }

    private static UUID resolveActorUuid(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(subject.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
