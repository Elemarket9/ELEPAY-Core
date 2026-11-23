package com.elepay.compliance;

import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghana Revenue Authority (GRA) Electronic Transfer Levy (e-Levy) Engine.
 * Implements the Electronic Transfer Levy Act, 2022 (Act 1075 as amended):
 * 1. Statutory Rate: 1.0% (100 basis points) on taxable electronic transfers.
 * 2. Statutory Daily Exemption: First 100 GHS (10,000 pesewas) cumulative daily transfers
 *    per customer identity (across all linked accounts/wallets) is strictly tax-exempt.
 * 3. Self-Transfer Exemption: Transfers between accounts owned by the same verified tax identity.
 * 4. Commercial Merchant Exemption: Approved tax-compliant merchant payments.
 * 5. Deterministic Concurrency Protection: Pessimistic row locking on daily accumulators.
 * 6. Authoritative Double-Entry Ledger Posting: Credits system tax liability account.
 * 7. Statutory Reversal & Refund recalculation upon failed or reversed transfers.
 */
@Service
public class ElevyCalculationEngine {

    public static final int ELEVY_RATE_BPS = 100; // 1.0%
    public static final long DAILY_EXEMPTION_THRESHOLD_PESEWAS = 10_000L; // 100.00 GHS
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

    @Transactional
    public ElevyAssessment assessAndChargeElevy(TransferContext ctx) {
        if (ctx.amountPesewas() <= 0) {
            throw ApiException.badRequest("invalid_amount", "Transfer amount must be positive");
        }

        LocalDate today = LocalDate.now();

        // 1. Check for specific customer exemption profile
        Optional<ExemptionProfile> profileOpt = db.sql("""
            SELECT id, tin, ghana_card_number, is_tax_exempt, exemption_category
            FROM elevy_exemption_profile
            WHERE customer_id = :cid
        """).param("cid", ctx.customerId())
        .query((rs, rowNum) -> new ExemptionProfile(
                rs.getObject("id", UUID.class),
                rs.getString("tin"),
                rs.getString("ghana_card_number"),
                rs.getBoolean("is_tax_exempt"),
                rs.getString("exemption_category")
        )).optional();

        if (profileOpt.isPresent() && profileOpt.get().isTaxExempt()) {
            return recordAssessment(ctx, ctx.amountPesewas(), 0L, 0L, true,
                    "STATUTORY_EXEMPT_CUSTOMER: " + profileOpt.get().exemptionCategory(), null);
        }

        // 2. Self-transfer check (same verified TIN or Ghana Card)
        if (profileOpt.isPresent() && ctx.recipientTinOrGhCard() != null && !ctx.recipientTinOrGhCard().isBlank()) {
            String recipientId = ctx.recipientTinOrGhCard().trim();
            ExemptionProfile prof = profileOpt.get();
            if ((prof.tin() != null && prof.tin().equalsIgnoreCase(recipientId))
                    || (prof.ghanaCardNumber() != null && prof.ghanaCardNumber().equalsIgnoreCase(recipientId))) {
                return recordAssessment(ctx, ctx.amountPesewas(), 0L, 0L, true,
                        "SELF_TRANSFER_EXEMPT", null);
            }
        }

        // 3. Commercial merchant payment exemption
        if (ctx.isCommercialMerchantPayment()) {
            return recordAssessment(ctx, ctx.amountPesewas(), 0L, 0L, true,
                    "COMMERCIAL_MERCHANT_EXEMPT", null);
        }

        // 4. Lock or create daily accumulator (Pessimistic Row Lock)
        ensureAccumulatorExists(ctx.customerId(), today);

        var accum = db.sql("""
            SELECT id, cumulative_amount_pesewas, exempt_amount_pesewas, taxable_amount_pesewas, total_levy_pesewas, transaction_count
            FROM elevy_daily_accumulator
            WHERE customer_id = :cid AND tax_date = :taxDate
            FOR UPDATE
        """)
                .param("cid", ctx.customerId())
                .param("taxDate", today)
                .query((rs, rowNum) -> new AccumulatorRow(
                        rs.getObject("id", UUID.class),
                        rs.getLong("cumulative_amount_pesewas"),
                        rs.getLong("exempt_amount_pesewas"),
                        rs.getLong("taxable_amount_pesewas"),
                        rs.getLong("total_levy_pesewas"),
                        rs.getInt("transaction_count")
                )).single();

        long cumulative = accum.cumulativeAmount;
        long amount = ctx.amountPesewas();
        long exemptPortion = 0L;
        long taxablePortion = 0L;

        if (cumulative < DAILY_EXEMPTION_THRESHOLD_PESEWAS) {
            long remainingExemption = DAILY_EXEMPTION_THRESHOLD_PESEWAS - cumulative;
            if (amount <= remainingExemption) {
                exemptPortion = amount;
                taxablePortion = 0L;
            } else {
                exemptPortion = remainingExemption;
                taxablePortion = amount - remainingExemption;
            }
        } else {
            exemptPortion = 0L;
            taxablePortion = amount;
        }

        // Calculate 1.0% levy on taxable portion
        long levyPesewas = Math.round((taxablePortion * ELEVY_RATE_BPS) / 10000.0);

        // Update accumulator
        db.sql("""
            UPDATE elevy_daily_accumulator
            SET cumulative_amount_pesewas = cumulative_amount_pesewas + :amt,
                exempt_amount_pesewas = exempt_amount_pesewas + :exempt,
                taxable_amount_pesewas = taxable_amount_pesewas + :taxable,
                total_levy_pesewas = total_levy_pesewas + :levy,
                transaction_count = transaction_count + 1,
                updated_at = now()
            WHERE id = :id
        """)
                .param("amt", amount)
                .param("exempt", exemptPortion)
                .param("taxable", taxablePortion)
                .param("levy", levyPesewas)
                .param("id", accum.id)
                .update();

        // 5. Post e-Levy tax collection to Authoritative Double-Entry Ledger if levy > 0
        UUID ledgerTxId = null;
        if (levyPesewas > 0) {
            ledger.openSystem(ELEVY_SYSTEM_ACCOUNT, "clearing", Ledger.GHS, true);
            String customerAccount = Ledger.code("customer", ctx.customerId(), "available", Ledger.GHS);
            ledger.open("customer", ctx.customerId(), "available", Ledger.GHS);

            var posting = Ledger.Posting.of(
                    "elevy_collection",
                    "ELEV-" + ctx.transactionReference(),
                    "GRA_E_LEVY_TAX_DEDUCTION",
                    List.of(
                            Ledger.Leg.debit(customerAccount, levyPesewas),
                            Ledger.Leg.credit(ELEVY_SYSTEM_ACCOUNT, levyPesewas)
                    )
            ).describedAs("GRA Act 1075 e-Levy 1.0% deduction on tx " + ctx.transactionReference());

            ledgerTxId = ledger.post(posting).transactionId();
        }

        return recordAssessment(ctx, exemptPortion, taxablePortion, levyPesewas,
                taxablePortion == 0, taxablePortion == 0 ? "STATUTORY_DAILY_EXEMPTION" : null, ledgerTxId);
    }

    @Transactional
    public ElevyReversalResult refundElevy(String originalTxRef, String reversalReason) {
        var txOpt = db.sql("""
            SELECT id, customer_id, transfer_amount_pesewas, exempt_portion_pesewas,
                   taxable_portion_pesewas, levy_amount_pesewas, status, created_at
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
        LocalDate txDate = (LocalDate) row[7];

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
            String customerAccount = Ledger.code("customer", customerId, "available", Ledger.GHS);
            var posting = Ledger.Posting.of(
                    "elevy_reversal",
                    "REV-ELEV-" + originalTxRef,
                    "GRA_E_LEVY_REVERSAL_REFUND",
                    List.of(
                            Ledger.Leg.debit(ELEVY_SYSTEM_ACCOUNT, levyAmt),
                            Ledger.Leg.credit(customerAccount, levyAmt)
                    )
            ).describedAs("GRA Act 1075 e-Levy refund on reversed tx " + originalTxRef);

            refundLedgerTxId = ledger.post(posting).transactionId();
        }

        return new ElevyReversalResult(originalTxRef, levyAmt, "REVERSED", refundLedgerTxId);
    }

    private void ensureAccumulatorExists(UUID customerId, LocalDate date) {
        db.sql("""
            INSERT INTO elevy_daily_accumulator (
                id, customer_id, tax_date, cumulative_amount_pesewas,
                exempt_amount_pesewas, taxable_amount_pesewas, total_levy_pesewas,
                transaction_count, created_at, updated_at
            ) VALUES (
                :id, :cid, :taxDate, 0, 0, 0, 0, 0, now(), now()
            ) ON CONFLICT (customer_id, tax_date) DO NOTHING
        """)
                .param("id", UUID.randomUUID())
                .param("cid", customerId)
                .param("taxDate", date)
                .update();
    }

    private ElevyAssessment recordAssessment(
            TransferContext ctx,
            long exemptPortion,
            long taxablePortion,
            long levyPesewas,
            boolean isExempt,
            String reason,
            UUID ledgerTxId
    ) {
        String clearanceCode = "GRA-ELEV-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        db.sql("""
            INSERT INTO elevy_transaction_log (
                id, transaction_reference, customer_id, recipient_identifier,
                transfer_amount_pesewas, exempt_portion_pesewas, taxable_portion_pesewas,
                levy_rate_bps, levy_amount_pesewas, is_exempt, exemption_reason,
                status, gra_clearance_code, ledger_tx_id, created_at
            ) VALUES (
                :id, :ref, :cid, :recipient,
                :amt, :exempt, :taxable,
                :bps, :levy, :isExempt, :reason,
                'CHARGED', :code, :ledgerTx, now()
            )
        """)
                .param("id", UUID.randomUUID())
                .param("ref", ctx.transactionReference())
                .param("cid", ctx.customerId())
                .param("recipient", ctx.recipientIdentifier())
                .param("amt", ctx.amountPesewas())
                .param("exempt", exemptPortion)
                .param("taxable", taxablePortion)
                .param("bps", ELEVY_RATE_BPS)
                .param("levy", levyPesewas)
                .param("isExempt", isExempt)
                .param("reason", reason)
                .param("code", clearanceCode)
                .param("ledgerTx", ledgerTxId)
                .update();

        return new ElevyAssessment(
                ctx.transactionReference(), ctx.customerId(), ctx.recipientIdentifier(),
                ctx.amountPesewas(), exemptPortion, taxablePortion, levyPesewas,
                isExempt, reason, clearanceCode, ledgerTxId
        );
    }

    private record ExemptionProfile(
            UUID id,
            String tin,
            String ghanaCardNumber,
            boolean isTaxExempt,
            String exemptionCategory
    ) {}

    private record AccumulatorRow(
            UUID id,
            long cumulativeAmount,
            long exemptAmount,
            long taxableAmount,
            long totalLevy,
            int txCount
    ) {}
}
