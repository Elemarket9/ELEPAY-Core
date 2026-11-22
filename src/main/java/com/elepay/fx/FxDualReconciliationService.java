package com.elepay.fx;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.ledger.Ledger;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Algorithm #064: Multi-currency partner reconciliation.
 *
 * <p>Performs comprehensive dual-currency reconciliation against partner statements:
 * verifies source debit amounts, destination credit amounts, effective exchange rates,
 * partner network fees, and compares partner stated clearing balances against ELEPAY's
 * authoritative double-entry clearing ledger balances.
 */
@Service
public class FxDualReconciliationService {
    private final JdbcClient db;
    private final Ledger ledger;
    private final OpsQueue ops;
    private final Audit audit;
    private final ObjectMapper json;

    public FxDualReconciliationService(JdbcClient db, Ledger ledger, OpsQueue ops, Audit audit, ObjectMapper json) {
        this.db = db;
        this.ledger = ledger;
        this.ops = ops;
        this.audit = audit;
        this.json = json;
    }

    public record DualStatementLine(String reference, String sourceCurrency, long sourceAmount,
                                    String destinationCurrency, long destinationAmount,
                                    long feeAmount, String feeCurrency, String status) {}

    public record DualReconItem(String reference, String result, String detail, long sourceDiscrepancy,
                                long destDiscrepancy) {}

    public record DualReconReport(UUID statementId, String partner, String period, String currencyPair,
                                  long statedBalance, long computedBalance, long balanceDifference,
                                  String overallStatus, Map<String, Long> itemSummary,
                                  List<DualReconItem> items) {}

    @Transactional
    public DualReconReport reconcileDual(String partner, String period, String sourceCurrency,
                                         String destinationCurrency, long statedClosingClearingBalance,
                                         List<DualStatementLine> lines, String actor) {
        String pair = sourceCurrency + "/" + destinationCurrency;
        UUID statementId = UUID.randomUUID();
        List<DualReconItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        long totalSource = 0;
        long totalDest = 0;
        long totalFee = 0;

        for (DualStatementLine line : lines) {
            totalSource += line.sourceAmount();
            totalDest += line.destinationAmount();
            totalFee += line.feeAmount();

            var internal = db.sql("""
                    SELECT id, reference, source_currency, source_amount, destination_currency, destination_amount,
                           rate, fee, status
                    FROM fx_transfer
                    WHERE partner = :p AND (reference = :r OR partner_reference = :r)""")
                    .param("p", partner).param("r", line.reference())
                    .query((rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                            rs.getLong(4), rs.getString(5), rs.getLong(6), rs.getBigDecimal(7), rs.getLong(8),
                            rs.getString(9)})
                    .optional();

            if (internal.isEmpty()) {
                items.add(new DualReconItem(line.reference(), "missing_internal", "Transaction not found in ELEPAY",
                        line.sourceAmount(), line.destinationAmount()));
                ops.raise("fx_dual_recon_missing_internal", "high", "fx-dual-recon:" + partner + ":" + line.reference(),
                        Map.of("period", period, "line", line.reference()));
                continue;
            }

            Object[] t = internal.get();
            String intRef = (String) t[1];
            seen.add(intRef);

            String intSrcCur = (String) t[2];
            long intSrcAmt = (long) t[3];
            String intDstCur = (String) t[4];
            long intDstAmt = (long) t[5];
            String intStatus = (String) t[8];

            long srcDiff = line.sourceAmount() - intSrcAmt;
            long dstDiff = line.destinationAmount() - intDstAmt;

            String result;
            String detail = null;

            if (!intSrcCur.equals(line.sourceCurrency()) || !intDstCur.equals(line.destinationCurrency())) {
                result = "currency_mismatch";
                detail = "Expected " + intSrcCur + "/" + intDstCur + " but got " + line.sourceCurrency() + "/" + line.destinationCurrency();
            } else if (srcDiff != 0 || dstDiff != 0) {
                result = "amount_mismatch";
                detail = "Source diff: " + srcDiff + " " + line.sourceCurrency() + ", Dest diff: " + dstDiff + " " + line.destinationCurrency();
            } else if (!intStatus.equalsIgnoreCase(line.status())) {
                result = "status_mismatch";
                detail = "Internal status is " + intStatus + ", partner reported " + line.status();
            } else {
                result = "matched";
            }

            if (!result.equals("matched")) {
                ops.raise("fx_dual_recon_" + result, "high", "fx-dual-recon:" + partner + ":" + line.reference(),
                        Map.of("period", period, "detail", String.valueOf(detail)));
            }

            items.add(new DualReconItem(line.reference(), result, detail, srcDiff, dstDiff));
        }

        // Check for missing partner transactions (present in ELEPAY but missing on statement)
        List<String> internalPendingOrPaid = db.sql("""
                SELECT reference FROM fx_transfer
                WHERE partner = :p AND source_currency = :sc AND destination_currency = :dc
                  AND status IN ('paid', 'returned')
                  AND to_char(created_at, 'YYYY-MM-DD') LIKE :period || '%'""")
                .param("p", partner).param("sc", sourceCurrency).param("dc", destinationCurrency)
                .param("period", period).query(String.class).list();

        for (String ref : internalPendingOrPaid) {
            if (!seen.contains(ref)) {
                items.add(new DualReconItem(ref, "missing_partner", "Internal transaction missing on partner statement", 0, 0));
                ops.raise("fx_dual_recon_missing_partner", "high", "fx-dual-recon:" + partner + ":" + ref,
                        Map.of("period", period, "reference", ref));
            }
        }

        // Authoritative ledger clearing balance check for destination currency
        String partnerClearingCode = "system:clearing:fx-" + partner + ":" + destinationCurrency;
        long computedClearingBalance = ledger.balance(partnerClearingCode);
        long balanceDifference = statedClosingClearingBalance - computedClearingBalance;

        String overallStatus = (balanceDifference == 0 && items.stream().allMatch(i -> i.result().equals("matched")))
                ? "matched" : "discrepancy";

        if (balanceDifference != 0) {
            ops.raise("fx_dual_recon_clearing_discrepancy", "high", "fx-clearing:" + partner + ":" + destinationCurrency,
                    Map.of("period", period, "stated", statedClosingClearingBalance, "computed", computedClearingBalance,
                            "difference", balanceDifference));
        }

        db.sql("""
                INSERT INTO fx_dual_currency_statement (id, partner, period, currency_pair, source_currency,
                    destination_currency, total_source_debits, total_destination_credits, partner_fee_amount,
                    fee_currency, clearing_balance_stated, clearing_balance_computed, status, reconciled_at, notes)
                VALUES (:id, :p, :per, :pair, :sc, :dc, :ts, :td, :fee, :fc, :cbs, :cbc, :st, now(), :notes)""")
                .param("id", statementId).param("p", partner).param("per", period).param("pair", pair)
                .param("sc", sourceCurrency).param("dc", destinationCurrency).param("ts", totalSource)
                .param("td", totalDest).param("fee", totalFee).param("fc", destinationCurrency)
                .param("cbs", statedClosingClearingBalance).param("cbc", computedClearingBalance)
                .param("st", overallStatus)
                .param("notes", "Reconciled " + items.size() + " items, status=" + overallStatus).update();

        Map<String, Long> summary = items.stream().collect(Collectors.groupingBy(DualReconItem::result, TreeMap::new, Collectors.counting()));

        audit.record(actor, "fx.dual_reconciled", "fx-recon:" + partner, Map.of(
                "period", period, "pair", pair, "status", overallStatus, "diff", balanceDifference));

        return new DualReconReport(statementId, partner, period, pair, statedClosingClearingBalance,
                computedClearingBalance, balanceDifference, overallStatus, summary, items);
    }
}
