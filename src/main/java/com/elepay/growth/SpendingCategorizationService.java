package com.elepay.growth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 096: Spending categorization.
 * Assigns explainable categories to customer transactions using MCC codes and description heuristics.
 * Non-destructive: categories are stored independently without altering immutable ledger postings.
 * Supports customer corrections.
 */
@Service
public class SpendingCategorizationService {

    private final JdbcClient db;

    public SpendingCategorizationService(JdbcClient db) {
        this.db = db;
    }

    public enum Category {
        GROCERIES,
        UTILITIES,
        TRANSPORT,
        FOOD_AND_DINING,
        HEALTHCARE,
        EDUCATION,
        ENTERTAINMENT,
        SAVINGS_INVESTMENTS,
        TRANSFERS_P2P,
        OTHER
    }

    public record CategorizedTransaction(
            UUID id,
            UUID transactionId,
            UUID customerId,
            Category category,
            String subcategory,
            double confidence,
            String source,
            boolean correctedByUser
    ) {}

    private static final Map<String, Category> MCC_MAP = Map.ofEntries(
            Map.entry("5411", Category.GROCERIES),
            Map.entry("5499", Category.GROCERIES),
            Map.entry("4900", Category.UTILITIES),
            Map.entry("4121", Category.TRANSPORT),
            Map.entry("4111", Category.TRANSPORT),
            Map.entry("5812", Category.FOOD_AND_DINING),
            Map.entry("5814", Category.FOOD_AND_DINING),
            Map.entry("8011", Category.HEALTHCARE),
            Map.entry("8099", Category.HEALTHCARE),
            Map.entry("8220", Category.EDUCATION),
            Map.entry("7832", Category.ENTERTAINMENT),
            Map.entry("6012", Category.SAVINGS_INVESTMENTS)
    );

    @Transactional
    public CategorizedTransaction categorizeTransaction(UUID transactionId, UUID customerId, String description, String mcc) {
        Category category = Category.OTHER;
        String subcategory = "General";
        double confidence = 0.50;
        String source = "HEURISTIC";

        // Check MCC first if present
        if (mcc != null && MCC_MAP.containsKey(mcc)) {
            category = MCC_MAP.get(mcc);
            subcategory = "MCC_" + mcc;
            confidence = 0.95;
            source = "MCC_LOOKUP";
        } else if (description != null) {
            String desc = description.toLowerCase(Locale.ROOT);
            if (desc.contains("market") || desc.contains("grocery") || desc.contains("shoprite") || desc.contains("supermarket")) {
                category = Category.GROCERIES;
                confidence = 0.85;
            } else if (desc.contains("ecg") || desc.contains("gwcl") || desc.contains("water") || desc.contains("electric") || desc.contains("power")) {
                category = Category.UTILITIES;
                confidence = 0.90;
            } else if (desc.contains("bolt") || desc.contains("uber") || desc.contains("fuel") || desc.contains("shell") || desc.contains("total")) {
                category = Category.TRANSPORT;
                confidence = 0.85;
            } else if (desc.contains("restaurant") || desc.contains("cafe") || desc.contains("kfc") || desc.contains("chop") || desc.contains("pizza")) {
                category = Category.FOOD_AND_DINING;
                confidence = 0.85;
            } else if (desc.contains("pharmacy") || desc.contains("hospital") || desc.contains("clinic")) {
                category = Category.HEALTHCARE;
                confidence = 0.88;
            } else if (desc.contains("school") || desc.contains("college") || desc.contains("tuition")) {
                category = Category.EDUCATION;
                confidence = 0.88;
            } else if (desc.contains("transfer") || desc.contains("send money") || desc.contains("p2p")) {
                category = Category.TRANSFERS_P2P;
                confidence = 0.80;
            }
        }

        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO transaction_category
            (id, transaction_id, customer_id, category, subcategory, confidence, source, corrected_by_user)
            VALUES (?, ?, ?, ?, ?, ?, ?, false)
            ON CONFLICT (transaction_id) DO UPDATE SET
                category = EXCLUDED.category,
                subcategory = EXCLUDED.subcategory,
                confidence = EXCLUDED.confidence
        """).params(id, transactionId, customerId, category.name(), subcategory,
                BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP), source)
        .update();

        return new CategorizedTransaction(id, transactionId, customerId, category, subcategory, confidence, source, false);
    }

    @Transactional
    public void correctCategory(UUID transactionId, Category newCategory, String userSubcategory) {
        db.sql("""
            UPDATE transaction_category
            SET category = ?,
                subcategory = ?,
                confidence = 1.000,
                source = 'USER_OVERRIDE',
                corrected_by_user = true
            WHERE transaction_id = ?
        """).params(newCategory.name(), userSubcategory != null ? userSubcategory : "UserSelected", transactionId)
        .update();
    }

    public Optional<CategorizedTransaction> getCategory(UUID transactionId) {
        return db.sql("SELECT id, transaction_id, customer_id, category, subcategory, confidence, source, corrected_by_user FROM transaction_category WHERE transaction_id = ?")
                .param(transactionId)
                .query((rs, rowNum) -> new CategorizedTransaction(
                        rs.getObject("id", UUID.class),
                        rs.getObject("transaction_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        Category.valueOf(rs.getString("category")),
                        rs.getString("subcategory"),
                        rs.getDouble("confidence"),
                        rs.getString("source"),
                        rs.getBoolean("corrected_by_user")
                )).optional();
    }
}
