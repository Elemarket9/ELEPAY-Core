package com.elepay.settlement;

import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Domain 11: Dispute & Chargeback Financial Lifecycle Engine.
 *
 * <p>Automates end-to-end chargeback state transitions, evidence verification,
 * and double-entry escrow ledger accounting:
 * <ul>
 *   <li>Places immediate defensive holds on merchant available balances or reserves upon chargeback notification.</li>
 *   <li>Assesses statutory dispute administrative fees.</li>
 *   <li>Coordinates cryptographic evidence submissions.</li>
 *   <li>Resolves disputes with zero-drift ledger postings upon scheme determination (Merchant Won vs Merchant Lost).</li>
 * </ul>
 */
@Service
public class DisputeChargebackSettlementService {

    public record DisputeRecord(
            UUID id,
            String disputeReference,
            String transactionReference,
            UUID merchantId,
            String customerId,
            String currency,
            long disputedAmount,
            long feeAmount,
            String status,
            String reasonCode,
            String evidenceHash,
            OffsetDateTime initiatedAt,
            OffsetDateTime resolvedAt,
            UUID holdJournalTxId,
            UUID resolutionJournalTxId
    ) {}

    private final JdbcClient jdbc;
    private final Ledger ledger;

    public DisputeChargebackSettlementService(JdbcClient jdbc, Ledger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    /**
     * Initiates a dispute, places an escrow hold on merchant funds, and posts double-entry ledger entries.
     */
    @Transactional
    public DisputeRecord initiateDispute(
            String disputeReference,
            String transactionReference,
            UUID merchantId,
            String customerId,
            String currency,
            long disputedAmount,
            long feeAmount,
            String reasonCode,
            String evidenceHash
    ) {
        if (disputeReference == null || disputeReference.isBlank()) {
            throw ApiException.badRequest("invalid_reference", "Dispute reference is required");
        }
        if (transactionReference == null || transactionReference.isBlank()) {
            throw ApiException.badRequest("invalid_tx_ref", "Transaction reference is required");
        }
        if (merchantId == null) {
            throw ApiException.badRequest("invalid_merchant", "Merchant ID is required");
        }
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw ApiException.badRequest("invalid_currency", "Valid 3-letter currency is required");
        }
        if (disputedAmount <= 0) {
            throw ApiException.badRequest("invalid_amount", "Disputed amount must be strictly positive");
        }
        if (feeAmount < 0) {
            throw ApiException.badRequest("invalid_fee", "Fee amount cannot be negative");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw ApiException.badRequest("invalid_reason", "Reason code is required");
        }

        UUID disputeId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        // Ledger postings:
        // Debit merchant available balance by (disputedAmount + feeAmount)
        // Credit chargeback suspense account by disputedAmount
        // Credit platform dispute fee account by feeAmount (if fee > 0)
        String merchantAvailableCode = Ledger.code("merchant", merchantId, "available", currency);
        String suspenseCode = "system:clearing:chargeback_suspense:" + currency;
        String disputeFeeCode = "system:fee:dispute_admin:" + currency;

        ledger.open("merchant", merchantId, "available", currency);
        ledger.openSystem(suspenseCode, "suspense", currency, true);
        if (feeAmount > 0) {
            ledger.openSystem(disputeFeeCode, "revenue", currency, true);
        }

        long totalDeduction = Math.addExact(disputedAmount, feeAmount);
        List<Ledger.Leg> legs = new ArrayList<>();
        legs.add(Ledger.Leg.debit(merchantAvailableCode, totalDeduction));
        legs.add(Ledger.Leg.credit(suspenseCode, disputedAmount));
        if (feeAmount > 0) {
            legs.add(Ledger.Leg.credit(disputeFeeCode, feeAmount));
        }

        var posting = Ledger.Posting.of(
                "dispute_hold",
                "HOLD-" + disputeReference,
                "DISPUTE_ESCROW_HOLD",
                legs
        ).describedAs("Dispute hold for " + disputeReference + " on " + transactionReference);

        UUID holdJournalTxId = ledger.post(posting).transactionId();

        jdbc.sql("""
            INSERT INTO settlement_dispute_chargeback (
                id, dispute_reference, transaction_reference, merchant_id, customer_id,
                currency, disputed_amount, fee_amount, status, reason_code,
                evidence_hash, initiated_at, hold_journal_tx_id
            ) VALUES (
                :id, :disputeReference, :txReference, :merchantId, :customerId,
                :currency, :disputedAmount, :feeAmount, 'INITIATED', :reasonCode,
                :evidenceHash, :initiatedAt, :holdJournalTxId
            )
        """)
        .param("id", disputeId)
        .param("disputeReference", disputeReference)
        .param("txReference", transactionReference)
        .param("merchantId", merchantId)
        .param("customerId", customerId)
        .param("currency", currency)
        .param("disputedAmount", disputedAmount)
        .param("feeAmount", feeAmount)
        .param("reasonCode", reasonCode)
        .param("evidenceHash", evidenceHash)
        .param("initiatedAt", now)
        .param("holdJournalTxId", holdJournalTxId)
        .update();

        return new DisputeRecord(
                disputeId, disputeReference, transactionReference, merchantId, customerId,
                currency, disputedAmount, feeAmount, "INITIATED", reasonCode,
                evidenceHash, now, null, holdJournalTxId, null
        );
    }

    /**
     * Submits cryptographic evidence dossier for scheme or switch evaluation.
     */
    @Transactional
    public DisputeRecord submitEvidence(String disputeReference, String evidenceHash) {
        if (evidenceHash == null || evidenceHash.isBlank()) {
            throw ApiException.badRequest("invalid_evidence", "Evidence hash cannot be blank");
        }

        int updated = jdbc.sql("""
            UPDATE settlement_dispute_chargeback
            SET evidence_hash = :evidenceHash,
                status = 'EVIDENCE_SUBMITTED'
            WHERE dispute_reference = :disputeReference AND status IN ('INITIATED', 'EVIDENCE_SUBMITTED')
        """)
        .param("evidenceHash", evidenceHash)
        .param("disputeReference", disputeReference)
        .update();

        if (updated == 0) {
            throw ApiException.notFound("dispute_not_found", "Dispute not found or cannot receive evidence: " + disputeReference);
        }

        return getDispute(disputeReference);
    }

    /**
     * Finalizes dispute determination: releases held funds back to merchant (if won)
     * or settles to clearing counterparty (if lost).
     */
    @Transactional
    public DisputeRecord resolveDispute(String disputeReference, boolean merchantWon, String resolutionNotes) {
        Map<String, Object> row = jdbc.sql("""
            SELECT id, dispute_reference, transaction_reference, merchant_id, customer_id,
                   currency, disputed_amount, fee_amount, status, reason_code,
                   evidence_hash, initiated_at, hold_journal_tx_id, resolution_journal_tx_id
            FROM settlement_dispute_chargeback
            WHERE dispute_reference = :disputeReference
            FOR UPDATE
        """)
        .param("disputeReference", disputeReference)
        .query()
        .singleRow();

        String currentStatus = (String) row.get("status");
        if ("MERCHANT_WON".equals(currentStatus) || "MERCHANT_LOST".equals(currentStatus)) {
            throw ApiException.conflict("already_resolved", "Dispute is already resolved: " + currentStatus);
        }

        UUID disputeId = (UUID) row.get("id");
        String txReference = (String) row.get("transaction_reference");
        UUID merchantId = (UUID) row.get("merchant_id");
        String customerId = (String) row.get("customer_id");
        String currency = (String) row.get("currency");
        long disputedAmount = ((Number) row.get("disputed_amount")).longValue();
        long feeAmount = ((Number) row.get("fee_amount")).longValue();
        String reasonCode = (String) row.get("reason_code");
        String evidenceHash = (String) row.get("evidence_hash");
        OffsetDateTime initiatedAt = toOffsetDateTime(row.get("initiated_at"));
        UUID holdJournalTxId = (UUID) row.get("hold_journal_tx_id");

        String suspenseCode = "system:clearing:chargeback_suspense:" + currency;
        String newStatus;
        UUID resolutionJournalTxId;

        if (merchantWon) {
            newStatus = "MERCHANT_WON";
            // Return disputed funds from suspense to merchant available balance
            String merchantAvailableCode = Ledger.code("merchant", merchantId, "available", currency);
            ledger.open("merchant", merchantId, "available", currency);
            ledger.openSystem(suspenseCode, "suspense", currency, true);

            var posting = Ledger.Posting.of(
                    "dispute_win",
                    "WIN-" + disputeReference,
                    "DISPUTE_RESOLUTION_WIN",
                    List.of(
                            Ledger.Leg.debit(suspenseCode, disputedAmount),
                            Ledger.Leg.credit(merchantAvailableCode, disputedAmount)
                    )
            ).describedAs("Dispute won by merchant: " + disputeReference + " (" + resolutionNotes + ")");

            resolutionJournalTxId = ledger.post(posting).transactionId();
        } else {
            newStatus = "MERCHANT_LOST";
            // Sweep funds from suspense to scheme/acquirer clearing settlement
            String clearingSettlementCode = "system:clearing:chargeback_settlement:" + currency;
            ledger.openSystem(suspenseCode, "suspense", currency, true);
            ledger.openSystem(clearingSettlementCode, "clearing", currency, true);

            var posting = Ledger.Posting.of(
                    "dispute_loss",
                    "LOSS-" + disputeReference,
                    "DISPUTE_RESOLUTION_LOSS",
                    List.of(
                            Ledger.Leg.debit(suspenseCode, disputedAmount),
                            Ledger.Leg.credit(clearingSettlementCode, disputedAmount)
                    )
            ).describedAs("Dispute lost by merchant: " + disputeReference + " (" + resolutionNotes + ")");

            resolutionJournalTxId = ledger.post(posting).transactionId();
        }

        OffsetDateTime resolvedAt = OffsetDateTime.now();

        jdbc.sql("""
            UPDATE settlement_dispute_chargeback
            SET status = :status,
                resolved_at = :resolvedAt,
                resolution_journal_tx_id = :resolutionJournalTxId
            WHERE dispute_reference = :disputeReference
        """)
        .param("status", newStatus)
        .param("resolvedAt", resolvedAt)
        .param("resolutionJournalTxId", resolutionJournalTxId)
        .param("disputeReference", disputeReference)
        .update();

        return new DisputeRecord(
                disputeId, disputeReference, txReference, merchantId, customerId,
                currency, disputedAmount, feeAmount, newStatus, reasonCode,
                evidenceHash, initiatedAt, resolvedAt, holdJournalTxId, resolutionJournalTxId
        );
    }

    public DisputeRecord getDispute(String disputeReference) {
        return jdbc.sql("""
            SELECT id, dispute_reference, transaction_reference, merchant_id, customer_id,
                   currency, disputed_amount, fee_amount, status, reason_code,
                   evidence_hash, initiated_at, resolved_at, hold_journal_tx_id, resolution_journal_tx_id
            FROM settlement_dispute_chargeback
            WHERE dispute_reference = :disputeReference
        """)
        .param("disputeReference", disputeReference)
        .query((rs, rowNum) -> new DisputeRecord(
                rs.getObject("id", UUID.class),
                rs.getString("dispute_reference"),
                rs.getString("transaction_reference"),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("customer_id"),
                rs.getString("currency"),
                rs.getLong("disputed_amount"),
                rs.getLong("fee_amount"),
                rs.getString("status"),
                rs.getString("reason_code"),
                rs.getString("evidence_hash"),
                rs.getObject("initiated_at", OffsetDateTime.class),
                rs.getObject("resolved_at", OffsetDateTime.class),
                rs.getObject("hold_journal_tx_id", UUID.class),
                rs.getObject("resolution_journal_tx_id", UUID.class)
        ))
        .single();
    }

    private static OffsetDateTime toOffsetDateTime(Object val) {
        if (val == null) return null;
        if (val instanceof OffsetDateTime odt) return odt;
        if (val instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return OffsetDateTime.parse(val.toString());
    }
}
