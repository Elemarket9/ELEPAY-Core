package com.elepay.credit;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 008: Interest and yield accrual engine.
 * Computes deterministic interest using configurable day-count conventions (ACT/365, ACT/360, 30/360).
 * Keep disabled for live use until authorized by regulatory policy.
 */
@Service
public class InterestAccrualService {

    private final JdbcClient db;

    public InterestAccrualService(JdbcClient db) {
        this.db = db;
    }

    public record AccrualCalculation(
            UUID accountId,
            LocalDate calculationDate,
            long principalPesewas,
            int rateBps,
            DayCountConvention convention,
            long accruedPesewas,
            long cumulativePesewas,
            boolean liveEnabled
    ) {}

    public record AccrualPolicy(
            UUID id,
            String productName,
            int rateBps,
            DayCountConvention convention,
            String postingSchedule,
            boolean liveEnabled
    ) {}

    /**
     * Calculates the deterministic accrued interest in pesewas for a given principal and period.
     */
    public long calculateAccrual(long principalPesewas, int rateBps, LocalDate startDate, LocalDate endDate, DayCountConvention convention) {
        if (principalPesewas <= 0 || rateBps <= 0 || !endDate.isAfter(startDate)) {
            return 0L;
        }
        double yearFraction = convention.yearFraction(startDate, endDate);
        // Annual rate in bps: 10000 bps = 100.0%
        // Accrual = principal * (rateBps / 10000.0) * yearFraction
        double accrued = (principalPesewas * (double) rateBps * yearFraction) / 10000.0;
        return Math.round(accrued);
    }

    /**
     * Accrues interest and records it. In live mode, throws if credit product is not authorized.
     */
    @Transactional
    public AccrualCalculation accrue(UUID accountId, String productName, long principalPesewas,
                                     LocalDate startDate, LocalDate endDate, boolean liveRequested) {
        AccrualPolicy policy = getPolicy(productName)
                .orElseGet(() -> createPolicy(productName, 1200, DayCountConvention.ACT_365, "DAILY", false));

        if (liveRequested && !policy.liveEnabled()) {
            throw new IllegalStateException("Interest accrual is disabled for live use until regulatory authorization");
        }

        long accrued = calculateAccrual(principalPesewas, policy.rateBps(), startDate, endDate, policy.convention());

        Long priorCumulative = db.sql("SELECT COALESCE(SUM(accrued_pesewas), 0) FROM interest_accrual_record WHERE account_id = ?")
                .param(accountId)
                .query(Long.class)
                .single();

        long cumulative = (priorCumulative != null ? priorCumulative : 0L) + accrued;

        UUID recordId = UUID.randomUUID();
        db.sql("""
            INSERT INTO interest_accrual_record
            (id, account_id, calculation_date, principal_pesewas, rate_bps, day_count_convention, accrued_pesewas, cumulative_pesewas, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(recordId, accountId, endDate, principalPesewas, policy.rateBps(),
                    policy.convention().name(), accrued, cumulative, liveRequested ? "POSTED" : "PREVIEW")
        .update();

        return new AccrualCalculation(accountId, endDate, principalPesewas, policy.rateBps(),
                policy.convention(), accrued, cumulative, policy.liveEnabled());
    }

    public Optional<AccrualPolicy> getPolicy(String productName) {
        return db.sql("SELECT id, product_name, rate_bps, day_count_convention, posting_schedule, live_enabled FROM interest_accrual_policy WHERE product_name = ?")
                .param(productName)
                .query((rs, rowNum) -> new AccrualPolicy(
                        rs.getObject("id", UUID.class),
                        rs.getString("product_name"),
                        rs.getInt("rate_bps"),
                        DayCountConvention.valueOf(rs.getString("day_count_convention")),
                        rs.getString("posting_schedule"),
                        rs.getBoolean("live_enabled")
                )).optional();
    }

    @Transactional
    public AccrualPolicy createPolicy(String productName, int rateBps, DayCountConvention convention, String postingSchedule, boolean liveEnabled) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO interest_accrual_policy (id, product_name, rate_bps, day_count_convention, posting_schedule, live_enabled)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (product_name) DO UPDATE SET rate_bps = EXCLUDED.rate_bps, day_count_convention = EXCLUDED.day_count_convention, live_enabled = EXCLUDED.live_enabled
        """).params(id, productName, rateBps, convention.name(), postingSchedule, liveEnabled)
        .update();
        return new AccrualPolicy(id, productName, rateBps, convention, postingSchedule, liveEnabled);
    }
}
