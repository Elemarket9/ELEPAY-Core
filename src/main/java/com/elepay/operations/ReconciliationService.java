package com.elepay.operations;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.identity.Actor;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    private final JdbcClient db;
    private final Ledger ledger;
    private final OpsQueue ops;
    private final Audit audit;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public ReconciliationService(JdbcClient db, Ledger ledger, OpsQueue ops, Audit audit) {
        this(db, ledger, ops, audit, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReconciliationService(
            JdbcClient db,
            Ledger ledger,
            OpsQueue ops,
            Audit audit,
            @org.springframework.beans.factory.annotation.Autowired(required = false) org.springframework.jdbc.core.JdbcTemplate jdbcTemplate
    ) {
        this.db = db;
        this.ledger = ledger;
        this.ops = ops;
        this.audit = audit;
        this.jdbcTemplate = jdbcTemplate;
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
        actor.requireOps();
        UUID actorUuid = resolveActorUuid(actor.subject());

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
                    // Insert into suspense item
                    UUID suspenseId = UUID.randomUUID();
                    db.sql("""
                            INSERT INTO suspense_item (id, source, amount_pesewas, currency, reason, status, created_at)
                            VALUES (:id, :src, :amt, :cur, :reason, 'OPEN', now())""")
                            .param("id", suspenseId).param("src", "reconciliation:" + request.rail())
                            .param("amt", line.amountPesewas()).param("cur", cur)
                            .param("reason", "Unmatched statement line " + line.externalReference()).update();
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

    private UploadResult uploadAndReconcileBulk(UUID uploadId, StatementUploadRequest request, String actorSubject) {
        List<StatementLineInput> lines = request.lines();
        List<String> refs = lines.stream()
                .map(StatementLineInput::externalReference)
                .filter(r -> r != null && !r.isBlank())
                .distinct()
                .toList();

        record RailRow(UUID id, String reference, long amount) {}
        Map<String, RailRow> railMap = new HashMap<>(refs.size());
        int chunkSize = 5000;
        for (int start = 0; start < refs.size(); start += chunkSize) {
            List<String> chunk = refs.subList(start, Math.min(start + chunkSize, refs.size()));
            db.sql("""
                    SELECT id, reference, rail_reference, amount
                    FROM rail_transaction
                    WHERE rail = :rail AND (reference IN (:refs) OR rail_reference IN (:refs))""")
                    .param("rail", request.rail()).param("refs", chunk)
                    .query((rs, i) -> {
                        RailRow rr = new RailRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(4));
                        String r1 = rs.getString(2);
                        String r2 = rs.getString(3);
                        if (r1 != null) railMap.putIfAbsent(r1, rr);
                        if (r2 != null) railMap.putIfAbsent(r2, rr);
                        return rr;
                    }).list();
        }

        PartnerFeeSchedule schedule = getFeeSchedule(request.rail());
        OffsetDateTime now = OffsetDateTime.now();
        List<BulkLineEntry> entries = new ArrayList<>(lines.size());
        List<BulkLineEntry> matchedEntries = new ArrayList<>(lines.size());
        List<BulkLineEntry> unmatchedEntries = new ArrayList<>();
        int matchedExact = 0;
        int unmatched = 0;
        int feeDiscrepancies = 0;

        for (StatementLineInput line : lines) {
            UUID lineId = UUID.randomUUID();
            String cur = line.currency() != null ? line.currency().toUpperCase() : Ledger.GHS;
            OffsetDateTime txTime = line.transactionTime() != null ? line.transactionTime() : now;
            RailRow rr = line.externalReference() != null ? railMap.get(line.externalReference()) : null;

            if (rr != null && rr.amount() == line.amountPesewas()) {
                matchedExact++;
                BulkLineEntry entry = new BulkLineEntry(lineId, line, cur, txTime, "MATCHED", rr.reference());
                entries.add(entry);
                matchedEntries.add(entry);
                if (schedule != null && line.feePesewas() != calculateExpectedFee(schedule, line.amountPesewas())) {
                    feeDiscrepancies++;
                }
            } else {
                unmatched++;
                BulkLineEntry entry = new BulkLineEntry(lineId, line, cur, txTime, "UNMATCHED", null);
                entries.add(entry);
                unmatchedEntries.add(entry);
            }
        }

        jdbcTemplate.batchUpdate("""
                INSERT INTO statement_line (id, upload_id, external_reference, amount_pesewas, currency,
                                            fee_pesewas, transaction_time, raw_description, match_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", entries, 2000, (ps, e) -> {
            ps.setObject(1, e.lineId());
            ps.setObject(2, uploadId);
            ps.setString(3, e.input().externalReference());
            ps.setLong(4, e.input().amountPesewas());
            ps.setString(5, e.currency());
            ps.setLong(6, e.input().feePesewas());
            ps.setObject(7, e.txTime());
            ps.setString(8, e.input().rawDescription());
            ps.setString(9, e.matchStatus());
        });

        if (!matchedEntries.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO reconciliation_match (id, statement_line_id, internal_reference, match_type, confidence, matched_at)
                    VALUES (?, ?, ?, 'EXACT_4WAY', 100.00, now())""", matchedEntries, 2000, (ps, e) -> {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, e.lineId());
                ps.setString(3, e.matchedInternalRef());
            });
        }

        if (!unmatchedEntries.isEmpty()) {
            String src = "reconciliation:" + request.rail();
            jdbcTemplate.batchUpdate("""
                    INSERT INTO suspense_item (id, source, amount_pesewas, currency, reason, status, created_at)
                    VALUES (?, ?, ?, ?, ?, 'OPEN', now())""", unmatchedEntries, 2000, (ps, e) -> {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, src);
                ps.setLong(3, e.input().amountPesewas());
                ps.setString(4, e.currency());
                ps.setString(5, "Unmatched statement line " + e.input().externalReference());
            });
        }

        audit.record(actorSubject, "reconciliation.statement_uploaded", "upload:" + uploadId,
                Map.of("total", lines.size(), "exact", matchedExact, "fuzzy", 0, "unmatched", unmatched));

        return new UploadResult(uploadId, request.rail(), request.filename(), lines.size(),
                matchedExact, 0, unmatched, feeDiscrepancies);
    }

    private boolean tryExact4WayMatch(UUID lineId, String rail, StatementLineInput line) {
        if (line.externalReference() == null || line.externalReference().isBlank()) return false;

        // Query rail_transaction for exact reference match
        var txOpt = db.sql("""
                SELECT id, reference, amount, purpose, purpose_id
                FROM rail_transaction
                WHERE rail = :rail AND (reference = :ref OR rail_reference = :ref OR CAST(purpose_id AS text) = :ref)""")
                .param("rail", rail).param("ref", line.externalReference())
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

        // Check for prefix/suffix match and timestamp +/- 24h and fee tolerance
        var candidates = db.sql("""
                SELECT id, reference, amount, created_at
                FROM rail_transaction
                WHERE rail = :rail AND status IN ('succeeded', 'created', 'pending')
                ORDER BY created_at DESC LIMIT 50""")
                .param("rail", rail)
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
        actor.requireOps();

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
    public List<SuspenseItem> listSuspense(@RequestParam(defaultValue = "OPEN") String status) {
        Actor.current().requireOps();
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
                ORDER BY created_at ASC""")
                .param("status", status.toUpperCase())
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

    @PostMapping("/suspense/propose")
    @Transactional
    public SuspenseItem proposeClearing(@Valid @RequestBody SuspenseProposalRequest request) {
        Actor actor = Actor.current();
        actor.requireOps();

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
                .param("target", request.targetAccount()).param("id", request.id()).update();

        audit.record(actor.subject(), "suspense.proposed", "suspense:" + request.id(),
                Map.of("action", request.action(), "targetAccount", request.targetAccount()));

        return getSuspenseItem(request.id());
    }

    @PostMapping("/suspense/{id}/approve")
    @Transactional
    public SuspenseItem approveClearing(@PathVariable UUID id) {
        Actor actor = Actor.current();
        actor.requireOps();

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
