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
 * Domain 11: Rolling Reserve Retention and Maturity Scheduling Service.
 *
 * <p>Automates merchant rolling reserve risk management:
 * <ul>
 *   <li>Retains configurable haircuts (e.g., 500 to 1500 bps = 5% to 15%) from gross settlement batches.</li>
 *   <li>Establishes immutable maturity tranches with 90 to 180 day release horizons.</li>
 *   <li>Executes automated ledger rebalancing from restricted reserve buckets to available merchant balances upon maturity.</li>
 *   <li>Supports risk impairment and reserve clawbacks to absorb unpaid dispute/chargeback obligations.</li>
 * </ul>
 */
@Service
public class RollingReserveScheduleService {

    public record ReserveTranche(
            UUID id,
            UUID merchantId,
            String currency,
            long grossAmount,
            int reserveRateBps,
            long reserveAmount,
            long impairedAmount,
            long releasedAmount,
            String status,
            OffsetDateTime heldAt,
            OffsetDateTime maturesAt,
            OffsetDateTime releasedAt,
            UUID holdJournalTxId,
            UUID releaseJournalTxId
    ) {
        public long netRemainingReserve() {
            return Math.max(0L, reserveAmount - impairedAmount - releasedAmount);
        }
    }

    private final JdbcClient jdbc;
    private final Ledger ledger;

    public RollingReserveScheduleService(JdbcClient jdbc, Ledger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    /**
     * Retains a rolling reserve tranche from a settlement payout and places funds in the merchant's restricted reserve bucket.
     */
    @Transactional
    public ReserveTranche createReserveTranche(
            UUID merchantId,
            String currency,
            long grossAmount,
            int reserveRateBps,
            int maturityDays
    ) {
        if (merchantId == null) {
            throw ApiException.badRequest("invalid_merchant", "Merchant ID is required");
        }
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw ApiException.badRequest("invalid_currency", "Valid 3-letter currency is required");
        }
        if (grossAmount <= 0) {
            throw ApiException.badRequest("invalid_amount", "Gross amount must be strictly positive");
        }
        if (reserveRateBps < 0 || reserveRateBps > 10_000) {
            throw ApiException.badRequest("invalid_rate", "Reserve rate BPS must be between 0 and 10000");
        }
        if (maturityDays < 1) {
            throw ApiException.badRequest("invalid_maturity", "Maturity horizon must be at least 1 day");
        }

        long reserveAmount = Math.multiplyExact(grossAmount, (long) reserveRateBps) / 10_000L;
        if (reserveAmount <= 0) {
            throw ApiException.badRequest("zero_reserve", "Calculated reserve amount is zero");
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime maturesAt = now.plusDays(maturityDays);
        UUID trancheId = UUID.randomUUID();

        // Balanced double-entry hold: move from merchant available to merchant reserve
        String availableCode = Ledger.code("merchant", merchantId, "available", currency);
        String reserveCode = Ledger.code("merchant", merchantId, "reserved", currency);
        ledger.open("merchant", merchantId, "available", currency);
        ledger.open("merchant", merchantId, "reserved", currency);

        var posting = Ledger.Posting.of(
                "reserve_hold",
                "HOLD-" + trancheId,
                "ROLLING_RESERVE_HOLD",
                List.of(
                        Ledger.Leg.debit(availableCode, reserveAmount),
                        Ledger.Leg.credit(reserveCode, reserveAmount)
                )
        ).describedAs("Rolling reserve retention tranche " + trancheId);

        UUID holdJournalTxId = ledger.post(posting).transactionId();

        jdbc.sql("""
            INSERT INTO settlement_rolling_reserve_tranche (
                id, merchant_id, currency, gross_amount, reserve_rate_bps,
                reserve_amount, impaired_amount, released_amount, status,
                held_at, matures_at, hold_journal_tx_id
            ) VALUES (
                :id, :merchantId, :currency, :grossAmount, :reserveRateBps,
                :reserveAmount, 0, 0, 'HELD',
                :heldAt, :maturesAt, :holdJournalTxId
            )
        """)
        .param("id", trancheId)
        .param("merchantId", merchantId)
        .param("currency", currency)
        .param("grossAmount", grossAmount)
        .param("reserveRateBps", reserveRateBps)
        .param("reserveAmount", reserveAmount)
        .param("heldAt", now)
        .param("maturesAt", maturesAt)
        .param("holdJournalTxId", holdJournalTxId)
        .update();

        return new ReserveTranche(
                trancheId, merchantId, currency, grossAmount, reserveRateBps,
                reserveAmount, 0L, 0L, "HELD", now, maturesAt, null, holdJournalTxId, null
        );
    }

    /**
     * Finds and releases all matured reserve tranches whose maturity date is on or before asOf.
     */
    @Transactional
    public List<ReserveTranche> releaseMaturedTranches(OffsetDateTime asOf) {
        OffsetDateTime cutoff = (asOf != null) ? asOf : OffsetDateTime.now();

        List<Map<String, Object>> rows = jdbc.sql("""
            SELECT id, merchant_id, currency, gross_amount, reserve_rate_bps,
                   reserve_amount, impaired_amount, released_amount, status,
                   held_at, matures_at, hold_journal_tx_id
            FROM settlement_rolling_reserve_tranche
            WHERE status = 'HELD' AND matures_at <= :cutoff
            ORDER BY id ASC
            FOR UPDATE SKIP LOCKED
        """)
        .param("cutoff", cutoff)
        .query()
        .listOfRows();

        List<ReserveTranche> releasedTranches = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            UUID trancheId = (UUID) row.get("id");
            UUID merchantId = (UUID) row.get("merchant_id");
            String currency = (String) row.get("currency");
            long grossAmount = ((Number) row.get("gross_amount")).longValue();
            int reserveRateBps = ((Number) row.get("reserve_rate_bps")).intValue();
            long reserveAmount = ((Number) row.get("reserve_amount")).longValue();
            long impairedAmount = ((Number) row.get("impaired_amount")).longValue();
            long previouslyReleased = ((Number) row.get("released_amount")).longValue();
            OffsetDateTime heldAt = toOffsetDateTime(row.get("held_at"));
            OffsetDateTime maturesAt = toOffsetDateTime(row.get("matures_at"));
            UUID holdJournalTxId = (UUID) row.get("hold_journal_tx_id");

            long payableRelease = Math.subtractExact(Math.subtractExact(reserveAmount, impairedAmount), previouslyReleased);
            UUID releaseJournalTxId = null;

            if (payableRelease > 0) {
                String reserveCode = Ledger.code("merchant", merchantId, "reserved", currency);
                String availableCode = Ledger.code("merchant", merchantId, "available", currency);
                ledger.open("merchant", merchantId, "available", currency);
                ledger.open("merchant", merchantId, "reserved", currency);

                var posting = Ledger.Posting.of(
                        "reserve_release",
                        "REL-" + trancheId,
                        "ROLLING_RESERVE_RELEASE",
                        List.of(
                                Ledger.Leg.debit(reserveCode, payableRelease),
                                Ledger.Leg.credit(availableCode, payableRelease)
                        )
                ).describedAs("Rolling reserve maturity release tranche " + trancheId);

                releaseJournalTxId = ledger.post(posting).transactionId();
            }

            OffsetDateTime releasedAt = OffsetDateTime.now();
            long totalReleased = Math.addExact(previouslyReleased, Math.max(0L, payableRelease));
            String newStatus = (impairedAmount > 0 && totalReleased < reserveAmount) ? "IMPAIRED" : "RELEASED";

            jdbc.sql("""
                UPDATE settlement_rolling_reserve_tranche
                SET status = :status,
                    released_amount = :totalReleased,
                    released_at = :releasedAt,
                    release_journal_tx_id = :releaseJournalTxId
                WHERE id = :id
            """)
            .param("status", newStatus)
            .param("totalReleased", totalReleased)
            .param("releasedAt", releasedAt)
            .param("releaseJournalTxId", releaseJournalTxId)
            .param("id", trancheId)
            .update();

            releasedTranches.add(new ReserveTranche(
                    trancheId, merchantId, currency, grossAmount, reserveRateBps,
                    reserveAmount, impairedAmount, totalReleased, newStatus,
                    heldAt, maturesAt, releasedAt, holdJournalTxId, releaseJournalTxId
            ));
        }

        return releasedTranches;
    }

    /**
     * Impairs a portion of a held reserve to cover catastrophic merchant chargeback losses.
     */
    @Transactional
    public ReserveTranche impairReserve(UUID trancheId, long amountToImpair, String reason) {
        if (trancheId == null) {
            throw ApiException.badRequest("invalid_tranche", "Tranche ID is required");
        }
        if (amountToImpair <= 0) {
            throw ApiException.badRequest("invalid_amount", "Impairment amount must be positive");
        }

        List<Map<String, Object>> rows = jdbc.sql("""
            SELECT id, merchant_id, currency, gross_amount, reserve_rate_bps,
                   reserve_amount, impaired_amount, released_amount, status,
                   held_at, matures_at, hold_journal_tx_id, release_journal_tx_id
            FROM settlement_rolling_reserve_tranche
            WHERE id = :id
            FOR UPDATE
        """)
        .param("id", trancheId)
        .query()
        .listOfRows();
        if (rows.isEmpty()) {
            throw ApiException.notFound("reserve_tranche_not_found", "Rolling reserve tranche not found: " + trancheId);
        }
        Map<String, Object> row = rows.getFirst();

        UUID merchantId = (UUID) row.get("merchant_id");
        String currency = (String) row.get("currency");
        long grossAmount = ((Number) row.get("gross_amount")).longValue();
        int reserveRateBps = ((Number) row.get("reserve_rate_bps")).intValue();
        long reserveAmount = ((Number) row.get("reserve_amount")).longValue();
        long existingImpaired = ((Number) row.get("impaired_amount")).longValue();
        long released = ((Number) row.get("released_amount")).longValue();
        OffsetDateTime heldAt = toOffsetDateTime(row.get("held_at"));
        OffsetDateTime maturesAt = toOffsetDateTime(row.get("matures_at"));
        UUID holdJournalTxId = (UUID) row.get("hold_journal_tx_id");
        UUID releaseJournalTxId = (UUID) row.get("release_journal_tx_id");

        long availableInReserve = Math.subtractExact(Math.subtractExact(reserveAmount, existingImpaired), released);
        if (amountToImpair > availableInReserve) {
            throw ApiException.conflict("insufficient_reserve",
                    "Cannot impair " + amountToImpair + "; available in tranche is " + availableInReserve);
        }

        // Debit reserve, credit clearing loss recovery
        String reserveCode = Ledger.code("merchant", merchantId, "reserved", currency);
        String lossRecoveryCode = "system:clearing:loss_recovery:" + currency;
        ledger.open("merchant", merchantId, "reserved", currency);
        ledger.openSystem(lossRecoveryCode, "clearing", currency, true);

        var posting = Ledger.Posting.of(
                "reserve_impairment",
                "IMP-" + trancheId + "-" + existingImpaired + "-" + amountToImpair,
                "RESERVE_IMPAIRMENT",
                List.of(
                        Ledger.Leg.debit(reserveCode, amountToImpair),
                        Ledger.Leg.credit(lossRecoveryCode, amountToImpair)
                )
        ).describedAs("Reserve impairment: " + reason);

        ledger.post(posting);

        long newImpaired = Math.addExact(existingImpaired, amountToImpair);
        String status = (Math.addExact(newImpaired, released) >= reserveAmount) ? "IMPAIRED" : "HELD";

        jdbc.sql("""
            UPDATE settlement_rolling_reserve_tranche
            SET impaired_amount = :newImpaired,
                status = :status
            WHERE id = :id
        """)
        .param("newImpaired", newImpaired)
        .param("status", status)
        .param("id", trancheId)
        .update();

        return new ReserveTranche(
                trancheId, merchantId, currency, grossAmount, reserveRateBps,
                reserveAmount, newImpaired, released, status, heldAt, maturesAt, null,
                holdJournalTxId, releaseJournalTxId
        );
    }

    private static OffsetDateTime toOffsetDateTime(Object val) {
        if (val == null) return null;
        if (val instanceof OffsetDateTime odt) return odt;
        if (val instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return OffsetDateTime.parse(val.toString());
    }
}
