package com.elepay.fx;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Algorithm #063: Delivery-time estimation.
 *
 * <p>Estimates realistic cross-border payment arrival times and SLAs based on corridor,
 * delivery method (wallet, momo, bank, cash pickup), network, daily clearing cutoff hours,
 * and weekend/public holiday processing schedules.
 */
@Service
public class FxDeliveryEstimator {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm 'GMT'");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm 'GMT'");

    private final JdbcClient db;

    public FxDeliveryEstimator(JdbcClient db) {
        this.db = db;
    }

    public record DeliveryRule(UUID id, String corridor, String deliveryMethod, String destinationNetwork,
                                int minMinutes, int p50Minutes, int p95Minutes, int maxMinutes,
                                Integer dailyCutoffHour, int weekendDelayHours) {}

    public record DeliveryEstimate(String corridor, String deliveryMethod, String network,
                                   int minMinutes, int estimatedMinutes, int maxMinutes,
                                   OffsetDateTime estimatedArrival, OffsetDateTime slaDeadline,
                                   String displayText, boolean cutoffApplied, boolean weekendDelayApplied) {}

    /**
     * Resolves the most specific delivery estimation rule for the corridor and delivery method.
     */
    public DeliveryRule resolveRule(String corridorCode, String deliveryMethod, String network) {
        return db.sql("""
                SELECT id, corridor, delivery_method, destination_network, min_minutes, p50_minutes,
                       p95_minutes, max_minutes, daily_cutoff_hour, weekend_delay_hours
                FROM fx_delivery_estimate_rule
                WHERE (corridor = :c OR corridor IS NULL)
                  AND delivery_method = :m
                  AND (destination_network = :net OR destination_network IS NULL)
                ORDER BY corridor NULLS LAST, destination_network NULLS LAST
                LIMIT 1""")
                .param("c", corridorCode).param("m", deliveryMethod).param("net", network)
                .query((rs, i) -> new DeliveryRule(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                        (Integer) rs.getObject(9), rs.getInt(10)))
                .optional()
                .orElse(new DeliveryRule(UUID.randomUUID(), corridorCode, deliveryMethod, network, 5, 30, 120, 240, null, 0));
    }

    /**
     * Computes the delivery arrival time estimate and SLA window taking cutoffs and weekends into account.
     */
    public DeliveryEstimate estimate(String corridorCode, String deliveryMethod, String network, OffsetDateTime requestTime) {
        OffsetDateTime now = (requestTime != null) ? requestTime : OffsetDateTime.now();
        DeliveryRule rule = resolveRule(corridorCode, deliveryMethod, network);

        OffsetDateTime start = now;
        boolean cutoffApplied = false;
        boolean weekendApplied = false;

        // 1. Check daily banking/clearing cutoff hour
        if (rule.dailyCutoffHour() != null && start.getHour() >= rule.dailyCutoffHour()) {
            // Pushed to next day at 09:00 GMT
            start = start.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
            cutoffApplied = true;
        }

        // 2. Check weekend processing delay
        DayOfWeek dow = start.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY && rule.weekendDelayHours() > 0) {
            // Push to Monday morning 09:00 GMT
            start = start.plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);
            weekendApplied = true;
        } else if (dow == DayOfWeek.SUNDAY && rule.weekendDelayHours() > 0) {
            // Push to Monday morning 09:00 GMT
            start = start.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
            weekendApplied = true;
        }

        OffsetDateTime estimatedArrival = start.plusMinutes(rule.p50Minutes());
        OffsetDateTime slaDeadline = start.plusMinutes(rule.p95Minutes());

        String display;
        if (rule.p50Minutes() <= 15) {
            display = "Instant delivery (within " + rule.p50Minutes() + " mins)";
        } else if (rule.p50Minutes() <= 120) {
            display = "Delivery within " + rule.p50Minutes() + " mins (by " + estimatedArrival.format(TIME_FMT) + ")";
        } else if (cutoffApplied || weekendApplied) {
            display = "Expected arrival by " + estimatedArrival.format(DATE_TIME_FMT);
        } else {
            display = "Expected delivery in " + (rule.p50Minutes() / 60) + " hours (by " + estimatedArrival.format(DATE_TIME_FMT) + ")";
        }

        return new DeliveryEstimate(corridorCode, deliveryMethod, network, rule.minMinutes(),
                rule.p50Minutes(), rule.p95Minutes(), estimatedArrival, slaDeadline, display,
                cutoffApplied, weekendApplied);
    }
}
