package com.elepay.crm;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 7: CRM Recency, Frequency, Monetary (RFM) Segmentation Engine.
 * Segments customers into actionable behavioral cohorts:
 * - CHAMPIONS: Bought recently, buy often, and spend the most
 * - LOYAL_CUSTOMERS: Buy regularly, responsive to promotions
 * - POTENTIAL_LOYALISTS: Recent customers with average frequency
 * - NEW_CUSTOMERS: Bought recently for the first time
 * - AT_RISK: Spent good money and bought often in the past, but haven't returned recently
 * - CANNOT_LOSE_THEM: Made huge purchases often, but haven't returned in a long time
 * - HIBERNATING: Low recency, low frequency, low spend
 */
@Service
public class RfmSegmentationService {

    private final JdbcClient db;

    public RfmSegmentationService(JdbcClient db) {
        this.db = db;
    }

    public record RfmScores(
            int recencyScore,
            int frequencyScore,
            int monetaryScore,
            String segment
    ) {}

    /**
     * Calculates RFM scores (1 to 5) and determines the customer behavioral segment.
     */
    public RfmScores calculateRfm(OffsetDateTime lastPurchaseAt, int transactionCount, long totalSpendPesewas) {
        int rScore;
        if (lastPurchaseAt == null) {
            rScore = 1;
        } else {
            long daysAgo = Duration.between(lastPurchaseAt, OffsetDateTime.now()).toDays();
            if (daysAgo <= 7) rScore = 5;
            else if (daysAgo <= 30) rScore = 4;
            else if (daysAgo <= 60) rScore = 3;
            else if (daysAgo <= 120) rScore = 2;
            else rScore = 1;
        }

        int fScore;
        if (transactionCount >= 20) fScore = 5;
        else if (transactionCount >= 10) fScore = 4;
        else if (transactionCount >= 5) fScore = 3;
        else if (transactionCount >= 2) fScore = 2;
        else fScore = 1;

        int mScore;
        // Total spend in pesewas: 100,000 pesewas = GHS 1,000
        if (totalSpendPesewas >= 500_000) mScore = 5;       // GHS 5,000+
        else if (totalSpendPesewas >= 200_000) mScore = 4;  // GHS 2,000+
        else if (totalSpendPesewas >= 100_000) mScore = 3;  // GHS 1,000+
        else if (totalSpendPesewas >= 30_000) mScore = 2;   // GHS 300+
        else mScore = 1;

        String segment = determineSegment(rScore, fScore, mScore);
        return new RfmScores(rScore, fScore, mScore, segment);
    }

    public static String determineSegment(int r, int f, int m) {
        if (r >= 4 && f >= 4 && m >= 4) {
            return "CHAMPIONS";
        }
        if (r >= 3 && f >= 3) {
            return "LOYAL_CUSTOMERS";
        }
        if (r == 1 && f >= 4 && m >= 4) {
            return "CANNOT_LOSE_THEM";
        }
        if (r <= 2 && f >= 3) {
            return "AT_RISK";
        }
        if (r >= 4 && f <= 2 && m <= 2) {
            return f == 1 ? "NEW_CUSTOMERS" : "POTENTIAL_LOYALISTS";
        }
        if (r >= 3) {
            return "POTENTIAL_LOYALISTS";
        }
        return "HIBERNATING";
    }

    /**
     * Updates customer purchase metrics, recalculates RFM, and updates the database record.
     */
    @Transactional
    public RfmScores recordCustomerPurchase(UUID customerId, long purchaseAmountPesewas) {
        var customer = db.sql("""
            SELECT id, total_spend_pesewas, transaction_count
            FROM crm_customer WHERE id = ? FOR UPDATE
        """).param(customerId)
        .query((rs, rowNum) -> new Object[] {
                rs.getLong("total_spend_pesewas"),
                rs.getInt("transaction_count")
        }).single();

        long newSpend = ((Long) customer[0]) + purchaseAmountPesewas;
        int newCount = ((Integer) customer[1]) + 1;
        OffsetDateTime now = OffsetDateTime.now();

        RfmScores rfm = calculateRfm(now, newCount, newSpend);

        db.sql("""
            UPDATE crm_customer
            SET total_spend_pesewas = ?,
                transaction_count = ?,
                last_purchase_at = ?,
                rfm_recency_score = ?,
                rfm_frequency_score = ?,
                rfm_monetary_score = ?,
                rfm_segment = ?,
                updated_at = now()
            WHERE id = ?
        """).params(newSpend, newCount, now, rfm.recencyScore(), rfm.frequencyScore(),
                rfm.monetaryScore(), rfm.segment(), customerId)
        .update();

        return rfm;
    }
}
