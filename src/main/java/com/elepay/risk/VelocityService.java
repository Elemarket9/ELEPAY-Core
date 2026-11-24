package com.elepay.risk;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Algorithm 023: Velocity rules.
 * Enforces configurable rolling transaction-count and cumulative amount limits
 * across sliding windows: 1 hour, 24 hours, and 7 days.
 */
@Service
public class VelocityService {

    private final JdbcClient db;

    public VelocityService(JdbcClient db) {
        this.db = db;
    }

    public record VelocityLimit(
            int maxCount1h,
            long maxAmountMinorUnits1h,
            int maxCount24h,
            long maxAmountMinorUnits24h,
            int maxCount7d,
            long maxAmountMinorUnits7d
    ) {
        public static VelocityLimit standardCustomerLimits() {
            return new VelocityLimit(
                    10,        // 10 txns per hour
                    500_000L,  // 5,000.00 GHS in 1h
                    30,        // 30 txns per 24h
                    2_000_000L,// 20,000.00 GHS in 24h
                    100,       // 100 txns per 7d
                    10_000_000L// 100,000.00 GHS in 7d
            );
        }

        public static VelocityLimit publicCheckoutLimits() {
            return new VelocityLimit(
                    25,         // 25 txns per hour per payer per merchant
                    2_000_000L, // 20,000.00 GHS in 1h
                    100,        // 100 txns per 24h
                    10_000_000L,// 100,000.00 GHS in 24h
                    500,        // 500 txns per 7d
                    50_000_000L // 500,000.00 GHS in 7d
            );
        }
    }

    public record VelocityStats(
            long count1h,
            long amount1h,
            long count24h,
            long amount24h,
            long count7d,
            long amount7d
    ) {}

    public record VelocityCheckResult(
            boolean allowed,
            String violationReason,
            VelocityStats stats,
            VelocityLimit appliedLimits
    ) {}

    public record VelocityEvent(
            String entityType,
            String entityId,
            long amountMinorUnits,
            String currency,
            String eventType
    ) {}

    @Transactional(readOnly = true)
    public VelocityStats getStats(String entityType, String entityId, OffsetDateTime now) {
        return getStats(entityType, entityId, "GHS", now);
    }

    @Transactional(readOnly = true)
    public VelocityStats getStats(String entityType, String entityId, String currency, OffsetDateTime now) {
        OffsetDateTime t1h = now.minusHours(1);
        OffsetDateTime t24h = now.minusHours(24);
        OffsetDateTime t7d = now.minusDays(7);
        String cur = (currency == null || currency.isBlank()) ? "GHS" : currency;

        String sql = """
            SELECT
                COALESCE(SUM(CASE WHEN created_at >= :t1h THEN 1 ELSE 0 END), 0) AS cnt_1h,
                COALESCE(SUM(CASE WHEN created_at >= :t1h THEN amount_minor_units ELSE 0 END), 0) AS amt_1h,
                COALESCE(SUM(CASE WHEN created_at >= :t24h THEN 1 ELSE 0 END), 0) AS cnt_24h,
                COALESCE(SUM(CASE WHEN created_at >= :t24h THEN amount_minor_units ELSE 0 END), 0) AS amt_24h,
                COALESCE(COUNT(*), 0) AS cnt_7d,
                COALESCE(SUM(amount_minor_units), 0) AS amt_7d
            FROM risk_velocity_events
            WHERE entity_type = :entityType
              AND entity_id = :entityId
              AND currency = :currency
              AND created_at >= :t7d
        """;

        return db.sql(sql)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("currency", cur)
                .param("t1h", t1h)
                .param("t24h", t24h)
                .param("t7d", t7d)
                .query((rs, rowNum) -> new VelocityStats(
                        rs.getLong("cnt_1h"),
                        rs.getLong("amt_1h"),
                        rs.getLong("cnt_24h"),
                        rs.getLong("amt_24h"),
                        rs.getLong("cnt_7d"),
                        rs.getLong("amt_7d")
                ))
                .optional()
                .orElse(new VelocityStats(0, 0, 0, 0, 0, 0));
    }

    @Transactional
    public VelocityCheckResult checkAndRecord(VelocityEvent event, VelocityLimit limits) {
        String cur = (event.currency() == null || event.currency().isBlank()) ? "GHS" : event.currency();
        String lockKey = event.entityType() + ":" + event.entityId() + ":" + cur;
        db.sql("SELECT pg_advisory_xact_lock(hashtext(:lockKey))")
                .param("lockKey", lockKey)
                .query((rs, rowNum) -> 1)
                .optional();

        OffsetDateTime now = OffsetDateTime.now();
        VelocityStats stats = getStats(event.entityType(), event.entityId(), cur, now);

        // Check 1h window
        if (stats.count1h() + 1 > limits.maxCount1h()) {
            return new VelocityCheckResult(false, "Exceeded 1-hour transaction count limit (" + limits.maxCount1h() + ")", stats, limits);
        }
        if (stats.amount1h() + event.amountMinorUnits() > limits.maxAmountMinorUnits1h()) {
            return new VelocityCheckResult(false, "Exceeded 1-hour cumulative amount limit (" + limits.maxAmountMinorUnits1h() + ")", stats, limits);
        }

        // Check 24h window
        if (stats.count24h() + 1 > limits.maxCount24h()) {
            return new VelocityCheckResult(false, "Exceeded 24-hour transaction count limit (" + limits.maxCount24h() + ")", stats, limits);
        }
        if (stats.amount24h() + event.amountMinorUnits() > limits.maxAmountMinorUnits24h()) {
            return new VelocityCheckResult(false, "Exceeded 24-hour cumulative amount limit (" + limits.maxAmountMinorUnits24h() + ")", stats, limits);
        }

        // Check 7d window
        if (stats.count7d() + 1 > limits.maxCount7d()) {
            return new VelocityCheckResult(false, "Exceeded 7-day transaction count limit (" + limits.maxCount7d() + ")", stats, limits);
        }
        if (stats.amount7d() + event.amountMinorUnits() > limits.maxAmountMinorUnits7d()) {
            return new VelocityCheckResult(false, "Exceeded 7-day cumulative amount limit (" + limits.maxAmountMinorUnits7d() + ")", stats, limits);
        }

        // Record event
        recordEvent(event, now);

        VelocityStats updatedStats = new VelocityStats(
                stats.count1h() + 1,
                stats.amount1h() + event.amountMinorUnits(),
                stats.count24h() + 1,
                stats.amount24h() + event.amountMinorUnits(),
                stats.count7d() + 1,
                stats.amount7d() + event.amountMinorUnits()
        );

        return new VelocityCheckResult(true, null, updatedStats, limits);
    }

    @Transactional
    public void recordEvent(VelocityEvent event, OffsetDateTime timestamp) {
        db.sql("""
            INSERT INTO risk_velocity_events (id, entity_type, entity_id, amount_minor_units, currency, event_type, created_at)
            VALUES (:id, :entityType, :entityId, :amount, :currency, :eventType, :createdAt)
        """)
                .param("id", UUID.randomUUID())
                .param("entityType", event.entityType())
                .param("entityId", event.entityId())
                .param("amount", event.amountMinorUnits())
                .param("currency", event.currency())
                .param("eventType", event.eventType())
                .param("createdAt", timestamp)
                .update();
    }
}
