package com.elepay.compliance;

import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Current transfers carry no E-Levy under Act 1127. Historical refunds remain available. */
@Service
public class ElevyCalculationEngine {

    public static final String ELEVY_SYSTEM_ACCOUNT = "system:clearing:elevy:GHS";

    private final JdbcClient db;
    private final Ledger ledger;

    public ElevyCalculationEngine(JdbcClient db, Ledger ledger) {
        this.db = db;
        this.ledger = ledger;
    }

    public record ElevyAssessment(
            String transactionReference,
            UUID customerId,
            String recipientIdentifier,
            long transferAmountPesewas,
            long exemptPortionPesewas,
            long taxablePortionPesewas,
            long levyAmountPesewas,
            boolean isExempt,
            String exemptionReason,
            String graClearanceCode,
            UUID ledgerTxId
    ) {}

    public record ElevyReversalResult(
            String originalReference,
            long refundedLevyPesewas,
            String status,
            UUID reversalLedgerTxId
    ) {}

    public record TransferContext(
            String transactionReference,
            UUID customerId,
            String recipientIdentifier,
            String recipientTinOrGhCard,
            long amountPesewas,
            boolean isCommercialMerchantPayment
    ) {}

    public ElevyAssessment assessAndChargeElevy(TransferContext ctx) {
        if (ctx.amountPesewas() <= 0) {
            throw ApiException.badRequest("invalid_amount", "Transfer amount must be positive");
        }

        return new ElevyAssessment(ctx.transactionReference(), ctx.customerId(), ctx.recipientIdentifier(),
                ctx.amountPesewas(), ctx.amountPesewas(), 0L, 0L, true, "LEVY_REPEALED", null, null);
    }

    @Transactional
    public ElevyReversalResult refundElevy(String originalTxRef, String reversalReason) {
        var txOpt = db.sql("""
            SELECT id, customer_id, transfer_amount_pesewas, exempt_portion_pesewas,
                   taxable_portion_pesewas, levy_amount_pesewas, status, ledger_tx_id, created_at
            FROM elevy_transaction_log
            WHERE transaction_reference = :ref FOR UPDATE
        """).param("ref", originalTxRef)
        .query((rs, rowNum) -> new Object[] {
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getLong("transfer_amount_pesewas"),
                rs.getLong("exempt_portion_pesewas"),
                rs.getLong("taxable_portion_pesewas"),
                rs.getLong("levy_amount_pesewas"),
                rs.getString("status"),
                rs.getObject("ledger_tx_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toLocalDate()
        }).optional();

        if (txOpt.isEmpty()) {
            throw ApiException.notFound("tx_not_found", "e-Levy transaction log not found: " + originalTxRef);
        }

        Object[] row = txOpt.get();
        UUID txId = (UUID) row[0];
        UUID customerId = (UUID) row[1];
        long transferAmt = (Long) row[2];
        long exemptAmt = (Long) row[3];
        long taxableAmt = (Long) row[4];
        long levyAmt = (Long) row[5];
        String status = (String) row[6];
        UUID originalPosting = (UUID) row[7];
        LocalDate txDate = (LocalDate) row[8];

        if ("REVERSED".equalsIgnoreCase(status)) {
            return new ElevyReversalResult(originalTxRef, 0L, "ALREADY_REVERSED", null);
        }

        // 1. Mark transaction as REVERSED
        db.sql("UPDATE elevy_transaction_log SET status = 'REVERSED', exemption_reason = :reason WHERE id = :id")
                .param("reason", "REVERSED: " + reversalReason)
                .param("id", txId)
                .update();

        // 2. Adjust daily accumulator if on the same tax day
        if (txDate.equals(LocalDate.now())) {
            db.sql("""
                UPDATE elevy_daily_accumulator
                SET cumulative_amount_pesewas = GREATEST(0, cumulative_amount_pesewas - :amt),
                    exempt_amount_pesewas = GREATEST(0, exempt_amount_pesewas - :exempt),
                    taxable_amount_pesewas = GREATEST(0, taxable_amount_pesewas - :taxable),
                    total_levy_pesewas = GREATEST(0, total_levy_pesewas - :levy),
                    updated_at = now()
                WHERE customer_id = :cid AND tax_date = :taxDate
            """)
                    .param("amt", transferAmt)
                    .param("exempt", exemptAmt)
                    .param("taxable", taxableAmt)
                    .param("levy", levyAmt)
                    .param("cid", customerId)
                    .param("taxDate", txDate)
                    .update();
        }

        // 3. Post reversal in ledger if levy was charged
        UUID refundLedgerTxId = null;
        if (levyAmt > 0) {
            if (originalPosting == null) {
                throw ApiException.conflict("missing_levy_posting", "Reconcile the historical charge before refunding it");
            }
            refundLedgerTxId = ledger.reverse(originalPosting, "elevy_reversal",
                    "REV-ELEV-" + originalTxRef, reversalReason).transactionId();
        }

        return new ElevyReversalResult(originalTxRef, levyAmt, "REVERSED", refundLedgerTxId);
    }
}
