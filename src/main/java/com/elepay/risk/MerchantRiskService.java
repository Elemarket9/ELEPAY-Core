package com.elepay.risk;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Algorithm 030: Merchant risk scoring.
 * Continuously evaluates merchant processing volume, refund ratio, dispute / chargeback velocity,
 * and sudden volume surges to calculate a calibrated merchant risk score and tier.
 */
@Service
public class MerchantRiskService {

    private final JdbcClient db;

    public MerchantRiskService(JdbcClient db) {
        this.db = db;
    }

    public enum MerchantRiskTier {
        LOW,
        MEDIUM,
        HIGH,
        PROBATION,
        SUSPENDED
    }

    public record MerchantMetrics(
            String merchantId,
            long salesVolumeMinorUnits,
            long refundVolumeMinorUnits,
            int transactionCount,
            int disputeCount,
            long volumeLast24hMinorUnits
    ) {}

    public record MerchantRiskEvaluation(
            String merchantId,
            int riskScore,
            MerchantRiskTier riskTier,
            double refundRatio,
            double disputeRatio,
            double volumeSurgeFactor,
            String reviewStatus,
            List<String> riskFactors,
            OffsetDateTime assessedAt
    ) {}

    @Transactional
    public MerchantRiskEvaluation evaluateMerchantRisk(MerchantMetrics metrics) {
        OffsetDateTime now = OffsetDateTime.now();
        List<String> factors = new ArrayList<>();

        double refundRatio = metrics.salesVolumeMinorUnits() > 0
                ? (double) metrics.refundVolumeMinorUnits() / (double) metrics.salesVolumeMinorUnits()
                : 0.0;

        double disputeRatio = metrics.transactionCount() > 0
                ? (double) metrics.disputeCount() / (double) metrics.transactionCount()
                : 0.0;

        double expectedDailyVolume = (double) metrics.salesVolumeMinorUnits() / 30.0;
        double surgeFactor = expectedDailyVolume > 0
                ? (double) metrics.volumeLast24hMinorUnits() / expectedDailyVolume
                : 1.0;

        int score = 10; // Baseline healthy score

        // Refund ratio evaluation
        if (refundRatio >= 0.15) {
            score += 40;
            factors.add(String.format("CRITICAL_REFUND_RATE (%.1f%% >= 15%%)", refundRatio * 100));
        } else if (refundRatio >= 0.08) {
            score += 20;
            factors.add(String.format("ELEVATED_REFUND_RATE (%.1f%% >= 8%%)", refundRatio * 100));
        } else if (refundRatio >= 0.04) {
            score += 10;
            factors.add(String.format("MODERATE_REFUND_RATE (%.1f%%)", refundRatio * 100));
        }

        // Dispute ratio evaluation
        if (disputeRatio >= 0.02) {
            score += 45;
            factors.add(String.format("CRITICAL_DISPUTE_RATE (%.2f%% >= 2%%)", disputeRatio * 100));
        } else if (disputeRatio >= 0.01) {
            score += 25;
            factors.add(String.format("ELEVATED_DISPUTE_RATE (%.2f%% >= 1%%)", disputeRatio * 100));
        } else if (disputeRatio >= 0.005) {
            score += 10;
            factors.add(String.format("MODERATE_DISPUTE_RATE (%.2f%%)", disputeRatio * 100));
        }

        // Volume surge evaluation
        if (surgeFactor >= 5.0 && metrics.volumeLast24hMinorUnits() > 100_000L) {
            score += 25;
            factors.add(String.format("UNUSUAL_VOLUME_SURGE (%.1fx 30d daily average)", surgeFactor));
        } else if (surgeFactor >= 3.0 && metrics.volumeLast24hMinorUnits() > 50_000L) {
            score += 10;
            factors.add(String.format("MODERATE_VOLUME_SURGE (%.1fx 30d daily average)", surgeFactor));
        }

        int finalScore = Math.min(100, Math.max(0, score));

        MerchantRiskTier tier;
        String reviewStatus;

        if (finalScore >= 90) {
            tier = MerchantRiskTier.SUSPENDED;
            reviewStatus = "SUSPENDED_PENDING_INVESTIGATION";
        } else if (finalScore >= 76) {
            tier = MerchantRiskTier.PROBATION;
            reviewStatus = "PROBATION_REVIEW_REQUIRED";
        } else if (finalScore >= 51) {
            tier = MerchantRiskTier.HIGH;
            reviewStatus = "ELEVATED_MONITORING";
        } else if (finalScore >= 26) {
            tier = MerchantRiskTier.MEDIUM;
            reviewStatus = "NORMAL";
        } else {
            tier = MerchantRiskTier.LOW;
            reviewStatus = "NORMAL";
        }

        // Persist score
        db.sql("""
            INSERT INTO risk_merchant_scores (
                id, merchant_id, rolling_window_days, sales_volume_minor_units,
                refund_volume_minor_units, refund_ratio, dispute_count, dispute_ratio,
                volume_surge_factor, merchant_risk_score, risk_tier, review_status, assessed_at
            ) VALUES (
                :id, :merchantId, 30, :sales,
                :refunds, :refundRatio, :disputeCount, :disputeRatio,
                :surgeFactor, :score, :tier, :status, :now
            )
        """)
                .param("id", UUID.randomUUID())
                .param("merchantId", metrics.merchantId())
                .param("sales", metrics.salesVolumeMinorUnits())
                .param("refunds", metrics.refundVolumeMinorUnits())
                .param("refundRatio", refundRatio)
                .param("disputeCount", metrics.disputeCount())
                .param("disputeRatio", disputeRatio)
                .param("surgeFactor", surgeFactor)
                .param("score", finalScore)
                .param("tier", tier.name())
                .param("status", reviewStatus)
                .param("now", now)
                .update();

        return new MerchantRiskEvaluation(
                metrics.merchantId(),
                finalScore,
                tier,
                refundRatio,
                disputeRatio,
                surgeFactor,
                reviewStatus,
                factors,
                now
        );
    }
}
