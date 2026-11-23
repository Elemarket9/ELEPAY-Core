package com.elepay.risk;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Algorithm 028: Multi-Window Rolling Velocity Engine.
 * Evaluates real-time velocity bursts and accumulated monetary exposure across
 * 1-minute, 5-minute, 1-hour, 24-hour, and 7-day windows across multiple identity dimensions.
 */
@Service
public class MultiWindowVelocityEngine {

    public enum DimensionType {
        CUSTOMER, PHONE, CARD_FINGERPRINT, DEVICE, IP_SUBNET
    }

    public enum WindowInterval {
        WINDOW_1M("1M", Duration.ofMinutes(1), 5, 200_000L),       // max 5 tx or 2,000 GHS
        WINDOW_5M("5M", Duration.ofMinutes(5), 15, 500_000L),      // max 15 tx or 5,000 GHS
        WINDOW_1H("1H", Duration.ofHours(1), 40, 2_000_000L),      // max 40 tx or 20,000 GHS
        WINDOW_24H("24H", Duration.ofHours(24), 100, 10_000_000L), // max 100 tx or 100,000 GHS
        WINDOW_7D("7D", Duration.ofDays(7), 500, 50_000_000L);     // max 500 tx or 500,000 GHS

        private final String code;
        private final Duration duration;
        private final int countThreshold;
        private final long amountThresholdPesewas;

        WindowInterval(String code, Duration duration, int countThreshold, long amountThresholdPesewas) {
            this.code = code;
            this.duration = duration;
            this.countThreshold = countThreshold;
            this.amountThresholdPesewas = amountThresholdPesewas;
        }

        public String getCode() { return code; }
        public Duration getDuration() { return duration; }
        public int getCountThreshold() { return countThreshold; }
        public long getAmountThresholdPesewas() { return amountThresholdPesewas; }
    }

    public record WindowSummary(
            String windowCode,
            int transactionCount,
            long accumulatedAmountPesewas,
            double windowScore
    ) {}

    public record VelocityEvaluation(
            double compositeScore,
            RiskDecision decision,
            List<String> triggeredRules,
            List<WindowSummary> windowSummaries
    ) {}

    private final JdbcClient db;

    public MultiWindowVelocityEngine(JdbcClient db) {
        this.db = db;
    }

    @Transactional
    public VelocityEvaluation recordAndEvaluate(
            DimensionType dimensionType,
            String dimensionKey,
            long amountPesewas,
            OffsetDateTime transactionTime
    ) {
        if (dimensionKey == null || dimensionKey.isBlank()) {
            return new VelocityEvaluation(0.0, RiskDecision.ALLOW, List.of(), List.of());
        }

        OffsetDateTime now = transactionTime != null ? transactionTime : OffsetDateTime.now(ZoneOffset.UTC);
        List<WindowSummary> summaries = new ArrayList<>();
        List<String> triggeredRules = new ArrayList<>();
        double maxScore = 0.0;

        for (WindowInterval interval : WindowInterval.values()) {
            OffsetDateTime windowStart = alignWindowStart(now, interval.getDuration());
            OffsetDateTime windowEnd = windowStart.plus(interval.getDuration());

            // Atomic upsert
            UUID id = UUID.randomUUID();
            db.sql("""
                INSERT INTO fraud_velocity_window (
                    id, dimension_type, dimension_key, window_interval,
                    window_start, window_end, transaction_count,
                    accumulated_amount_pesewas, velocity_score, created_at, updated_at
                ) VALUES (
                    :id, :dimType, :dimKey, :interval,
                    :start, :end, 1, :amt, 0.0, :now, :now
                )
                ON CONFLICT (dimension_type, dimension_key, window_interval, window_start)
                DO UPDATE SET
                    transaction_count = fraud_velocity_window.transaction_count + 1,
                    accumulated_amount_pesewas = fraud_velocity_window.accumulated_amount_pesewas + EXCLUDED.accumulated_amount_pesewas,
                    updated_at = EXCLUDED.updated_at
            """)
                    .param("id", id)
                    .param("dimType", dimensionType.name())
                    .param("dimKey", dimensionKey)
                    .param("interval", interval.getCode())
                    .param("start", windowStart)
                    .param("end", windowEnd)
                    .param("amt", amountPesewas)
                    .param("now", now)
                    .update();

            // Query current aggregated values for this window
            var row = db.sql("""
                SELECT transaction_count, accumulated_amount_pesewas
                FROM fraud_velocity_window
                WHERE dimension_type = :dimType AND dimension_key = :dimKey
                  AND window_interval = :interval AND window_start = :start
            """)
                    .param("dimType", dimensionType.name())
                    .param("dimKey", dimensionKey)
                    .param("interval", interval.getCode())
                    .param("start", windowStart)
                    .query((rs, rowNum) -> new Object() {
                        int count = rs.getInt("transaction_count");
                        long total = rs.getLong("accumulated_amount_pesewas");
                    })
                    .single();

            int count = row.count;
            long total = row.total;

            // Score calculation for this interval
            double countRatio = (double) count / interval.getCountThreshold();
            double amountRatio = (double) total / interval.getAmountThresholdPesewas();
            double windowScore = Math.min(100.0, Math.max(countRatio, amountRatio) * 100.0);

            if (count > interval.getCountThreshold()) {
                triggeredRules.add("VELOCITY_COUNT_BURST_" + interval.getCode() + " (count: " + count + " > threshold: " + interval.getCountThreshold() + ")");
            }
            if (total > interval.getAmountThresholdPesewas()) {
                triggeredRules.add("VELOCITY_AMOUNT_SURGE_" + interval.getCode() + " (accumulated: " + total + " > threshold: " + interval.getAmountThresholdPesewas() + ")");
            }

            maxScore = Math.max(maxScore, windowScore);
            summaries.add(new WindowSummary(interval.getCode(), count, total, windowScore));

            // Persist the calculated velocity score back to the record
            db.sql("""
                UPDATE fraud_velocity_window
                SET velocity_score = :score
                WHERE dimension_type = :dimType AND dimension_key = :dimKey
                  AND window_interval = :interval AND window_start = :start
            """)
                    .param("score", windowScore)
                    .param("dimType", dimensionType.name())
                    .param("dimKey", dimensionKey)
                    .param("interval", interval.getCode())
                    .param("start", windowStart)
                    .update();
        }

        RiskDecision decision;
        if (maxScore >= 100.0) {
            decision = RiskDecision.REJECT;
        } else if (maxScore >= 60.0) {
            decision = RiskDecision.REVIEW;
        } else {
            decision = RiskDecision.ALLOW;
        }

        return new VelocityEvaluation(maxScore, decision, triggeredRules, summaries);
    }

    private OffsetDateTime alignWindowStart(OffsetDateTime time, Duration duration) {
        long seconds = duration.toSeconds();
        long epochSecond = time.toEpochSecond();
        long alignedSecond = (epochSecond / seconds) * seconds;
        return OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(alignedSecond), ZoneOffset.UTC);
    }
}
