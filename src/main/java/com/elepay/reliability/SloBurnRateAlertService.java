package com.elepay.reliability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 090: SLO burn-rate alerting.
 * Implements Google SRE multi-window multi-burn-rate alerting for 99.95% availability target.
 * Prevents false positives by requiring correlated short-window and long-window consumption.
 */
@Service
public class SloBurnRateAlertService {

    private final JdbcClient db;

    public SloBurnRateAlertService(JdbcClient db) {
        this.db = db;
    }

    public record WindowMetrics(long totalRequests, long failedRequests, double windowHours) {
        public double errorRate() {
            return totalRequests > 0 ? (double) failedRequests / totalRequests : 0.0;
        }
    }

    public record BurnAlert(
            UUID id,
            String serviceName,
            String sloName,
            String windowName,
            double burnRate,
            double threshold,
            String severity,
            String status
    ) {}

    /**
     * Evaluates SLO error budget consumption against target (e.g. 99.95% -> error budget = 0.0005).
     */
    @Transactional
    public List<BurnAlert> evaluateBurnRate(String serviceName, String sloName, double targetAvailability,
                                           WindowMetrics shortWindow, WindowMetrics longWindow) {
        double errorBudget = 1.0 - targetAvailability; // e.g. 0.0005
        if (errorBudget <= 0) errorBudget = 0.0005;

        double shortBurnRate = shortWindow.errorRate() / errorBudget;
        double longBurnRate = longWindow.errorRate() / errorBudget;

        List<BurnAlert> triggered = new ArrayList<>();

        // Tier 1: 1h window (14.4x burn rate = 2% consumed in 1h) & 5m window short
        if (longBurnRate >= 14.4 && shortBurnRate >= 14.4) {
            triggered.add(saveAlert(serviceName, sloName, "1h_5m", longBurnRate, 14.4, "PAGE_CRITICAL"));
        }
        // Tier 2: 6h window (6.0x burn rate = 5% consumed in 6h) & 30m window short
        else if (longBurnRate >= 6.0 && shortBurnRate >= 6.0) {
            triggered.add(saveAlert(serviceName, sloName, "6h_30m", longBurnRate, 6.0, "PAGE_HIGH"));
        }
        // Tier 3: 24h window (3.0x burn rate = 10% consumed in 24h)
        else if (longBurnRate >= 3.0 && shortBurnRate >= 3.0) {
            triggered.add(saveAlert(serviceName, sloName, "24h_2h", longBurnRate, 3.0, "TICKET_MEDIUM"));
        }
        // Tier 4: 3d window (1.0x burn rate = 10% consumed in 3d)
        else if (longBurnRate >= 1.0 && shortBurnRate >= 1.0) {
            triggered.add(saveAlert(serviceName, sloName, "3d_6h", longBurnRate, 1.0, "TICKET_LOW"));
        }

        return triggered;
    }

    private BurnAlert saveAlert(String service, String slo, String window, double burn, double threshold, String severity) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO slo_burn_alert
            (id, service_name, slo_name, window_name, burn_rate, threshold, severity, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'FIRING')
        """).params(id, service, slo, window,
                BigDecimal.valueOf(burn).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(threshold).setScale(4, RoundingMode.HALF_UP),
                severity)
        .update();

        return new BurnAlert(id, service, slo, window, burn, threshold, severity, "FIRING");
    }
}
