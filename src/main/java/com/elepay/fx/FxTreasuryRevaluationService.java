package com.elepay.fx;

import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Domain 17: Foreign Exchange Treasury Position Revaluation Service.
 *
 * <p>Executes daily Mark-to-Market (MTM) revaluations of foreign currency treasury
 * positions against official Bank of Ghana (BoG) mid-market benchmark rates:
 * <ul>
 *   <li>Evaluates open currency positions across multi-currency treasury accounts.</li>
 *   <li>Calculates unrealized FX gains or losses with integer precision (e4 rate scaling).</li>
 *   <li>Produces authoritative revaluation snapshots for regulatory reporting and balance sheet reconciliation.</li>
 *   <li>Posts balanced double-entry revaluation entries to the general ledger.</li>
 * </ul>
 */
@Service
public class FxTreasuryRevaluationService {

    public record TreasuryFxSnapshot(
            UUID id,
            LocalDate snapshotDate,
            String currency,
            long positionBalance,
            long bogMidRateE4,
            long revaluedGhsAmount,
            long unrealizedGainLoss,
            OffsetDateTime createdAt
    ) {}

    private final JdbcClient jdbc;
    private final Ledger ledger;

    public FxTreasuryRevaluationService(JdbcClient jdbc, Ledger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    /**
     * Records a daily mark-to-market position snapshot and calculates unrealized gain/loss against historical cost.
     *
     * @param snapshotDate       the valuation date
     * @param currency           3-letter foreign currency code (e.g. USD, EUR, GBP)
     * @param positionBalance    foreign currency balance in minor units (e.g., cents)
     * @param bogMidRateE4       official BoG rate scaled by 10,000 (e.g. 15.4200 GHS/USD -> 154200)
     * @param historicalCostGhs  historical carrying value in GHS minor units (pesewas)
     * @return authoritative snapshot record
     */
    @Transactional
    public TreasuryFxSnapshot recordDailyPositionSnapshot(
            LocalDate snapshotDate,
            String currency,
            long positionBalance,
            long bogMidRateE4,
            long historicalCostGhs
    ) {
        if (snapshotDate == null) {
            throw ApiException.badRequest("invalid_date", "Snapshot date is required");
        }
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw ApiException.badRequest("invalid_currency", "Valid 3-letter currency code is required");
        }
        if ("GHS".equalsIgnoreCase(currency)) {
            throw ApiException.badRequest("base_currency", "Base currency GHS does not require FX revaluation");
        }
        if (bogMidRateE4 <= 0) {
            throw ApiException.badRequest("invalid_rate", "BoG mid rate must be strictly positive");
        }

        long revaluedGhsAmount = Math.multiplyExact(positionBalance, bogMidRateE4) / 10_000L;
        long unrealizedGainLoss = revaluedGhsAmount - historicalCostGhs;

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        jdbc.sql("""
            INSERT INTO treasury_fx_position_snapshot (
                id, snapshot_date, currency, position_balance,
                bog_mid_rate_e4, revalued_ghs_amount, unrealized_gain_loss, created_at
            ) VALUES (
                :id, :snapshotDate, :currency, :positionBalance,
                :bogMidRateE4, :revaluedGhsAmount, :unrealizedGainLoss, :createdAt
            )
            ON CONFLICT (snapshot_date, currency) DO UPDATE SET
                position_balance = EXCLUDED.position_balance,
                bog_mid_rate_e4 = EXCLUDED.bog_mid_rate_e4,
                revalued_ghs_amount = EXCLUDED.revalued_ghs_amount,
                unrealized_gain_loss = EXCLUDED.unrealized_gain_loss,
                created_at = EXCLUDED.created_at
        """)
        .param("id", id)
        .param("snapshotDate", snapshotDate)
        .param("currency", currency)
        .param("positionBalance", positionBalance)
        .param("bogMidRateE4", bogMidRateE4)
        .param("revaluedGhsAmount", revaluedGhsAmount)
        .param("unrealizedGainLoss", unrealizedGainLoss)
        .param("createdAt", now)
        .update();

        // If ledger is present and there is an unrealized gain/loss to recognize, post balanced entry in GHS
        if (ledger != null && unrealizedGainLoss != 0) {
            String positionRevalAccount = "system:fx:position_reval_" + currency + ":GHS";
            String unrealizedGainLossAccount = "system:fx:unrealized_gain_loss:GHS";

            ledger.openSystem(positionRevalAccount, "holding", Ledger.GHS, true);
            ledger.openSystem(unrealizedGainLossAccount, "operating", Ledger.GHS, true);

            if (unrealizedGainLoss > 0) {
                // Gain: credit position revaluation asset (+), debit unrealized gain equity (-)
                var posting = Ledger.Posting.of(
                        "fx_reval_gain",
                        "GAIN-" + currency + "-" + snapshotDate + "-" + UUID.randomUUID().toString().substring(0, 8),
                        "FX_UNREALIZED_GAIN",
                        List.of(
                                Ledger.Leg.credit(positionRevalAccount, unrealizedGainLoss),
                                Ledger.Leg.debit(unrealizedGainLossAccount, unrealizedGainLoss)
                        )
                ).describedAs("Unrealized FX Gain for " + currency + " on " + snapshotDate);

                ledger.post(posting);
            } else {
                // Loss: debit position revaluation asset (-), credit unrealized loss equity (+)
                long absLoss = Math.abs(unrealizedGainLoss);
                var posting = Ledger.Posting.of(
                        "fx_reval_loss",
                        "LOSS-" + currency + "-" + snapshotDate + "-" + UUID.randomUUID().toString().substring(0, 8),
                        "FX_UNREALIZED_LOSS",
                        List.of(
                                Ledger.Leg.debit(positionRevalAccount, absLoss),
                                Ledger.Leg.credit(unrealizedGainLossAccount, absLoss)
                        )
                ).describedAs("Unrealized FX Loss for " + currency + " on " + snapshotDate);

                ledger.post(posting);
            }
        }

        return getSnapshot(snapshotDate, currency);
    }

    public TreasuryFxSnapshot getSnapshot(LocalDate snapshotDate, String currency) {
        return jdbc.sql("""
            SELECT id, snapshot_date, currency, position_balance,
                   bog_mid_rate_e4, revalued_ghs_amount, unrealized_gain_loss, created_at
            FROM treasury_fx_position_snapshot
            WHERE snapshot_date = :snapshotDate AND currency = :currency
        """)
        .param("snapshotDate", snapshotDate)
        .param("currency", currency)
        .query((rs, rowNum) -> new TreasuryFxSnapshot(
                rs.getObject("id", UUID.class),
                rs.getObject("snapshot_date", LocalDate.class),
                rs.getString("currency"),
                rs.getLong("position_balance"),
                rs.getLong("bog_mid_rate_e4"),
                rs.getLong("revalued_ghs_amount"),
                rs.getLong("unrealized_gain_loss"),
                rs.getObject("created_at", OffsetDateTime.class)
        ))
        .single();
    }

    public List<TreasuryFxSnapshot> listSnapshotsForDate(LocalDate snapshotDate) {
        return jdbc.sql("""
            SELECT id, snapshot_date, currency, position_balance,
                   bog_mid_rate_e4, revalued_ghs_amount, unrealized_gain_loss, created_at
            FROM treasury_fx_position_snapshot
            WHERE snapshot_date = :snapshotDate
            ORDER BY currency ASC
        """)
        .param("snapshotDate", snapshotDate)
        .query((rs, rowNum) -> new TreasuryFxSnapshot(
                rs.getObject("id", UUID.class),
                rs.getObject("snapshot_date", LocalDate.class),
                rs.getString("currency"),
                rs.getLong("position_balance"),
                rs.getLong("bog_mid_rate_e4"),
                rs.getLong("revalued_ghs_amount"),
                rs.getLong("unrealized_gain_loss"),
                rs.getObject("created_at", OffsetDateTime.class)
        ))
        .list();
    }
}
