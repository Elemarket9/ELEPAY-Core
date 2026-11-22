package com.elepay.credit;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 056: Float forecasting for mobile money and bank accounts.
 * Projects liquidity requirements across calendar windows with uncertainty bounds.
 */
@Service
public class FloatForecastingService {

    private final JdbcClient db;

    public FloatForecastingService(JdbcClient db) {
        this.db = db;
    }

    public record DailyFlow(LocalDate date, long inflowPesewas, long outflowPesewas) {}

    public record ForecastResult(
            UUID id,
            String rail,
            LocalDate forecastDate,
            long expectedInflowPesewas,
            long expectedOutflowPesewas,
            long projectedEodPesewas,
            long bufferPesewas,
            long confidenceLowerPesewas,
            long confidenceUpperPesewas,
            String modelVersion,
            boolean hasDataLimitation,
            String limitationReason
    ) {}

    /**
     * Projects float requirements for a given rail over historical flows.
     */
    @Transactional
    public ForecastResult generateForecast(String rail, LocalDate targetDate, List<DailyFlow> historicalFlows, long currentBalancePesewas) {
        boolean hasLimitation = historicalFlows == null || historicalFlows.size() < 7;
        String reason = hasLimitation ? "INSUFFICIENT_HISTORY_MINIMUM_7_DAYS" : "NORMAL";

        double avgInflow = 0;
        double avgOutflow = 0;
        double varianceNetFlow = 0;

        if (historicalFlows != null && !historicalFlows.isEmpty()) {
            double sumInflow = 0;
            double sumOutflow = 0;
            for (DailyFlow flow : historicalFlows) {
                sumInflow += flow.inflowPesewas();
                sumOutflow += flow.outflowPesewas();
            }
            int n = historicalFlows.size();
            avgInflow = sumInflow / n;
            avgOutflow = sumOutflow / n;

            // Apply weekend / weekday seasonality multipliers
            DayOfWeek dow = targetDate.getDayOfWeek();
            double dowFactor = (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) ? 1.30 : 0.95;
            avgInflow *= dowFactor;
            avgOutflow *= dowFactor;

            // Variance in net daily flows
            double sumSqDiff = 0;
            for (DailyFlow flow : historicalFlows) {
                double net = flow.outflowPesewas() - flow.inflowPesewas();
                double meanNet = (sumOutflow - sumInflow) / n;
                sumSqDiff += Math.pow(net - meanNet, 2);
            }
            varianceNetFlow = sumSqDiff / Math.max(1, n - 1);
        }

        double stdDev = Math.sqrt(varianceNetFlow);
        // Safety buffer = Z (1.96 for 95% confidence) * stdDev
        long buffer = Math.round(1.96 * (stdDev > 0 ? stdDev : (avgOutflow * 0.20)));

        long expectedInflow = Math.round(avgInflow);
        long expectedOutflow = Math.round(avgOutflow);
        long projectedEod = currentBalancePesewas + expectedInflow - expectedOutflow;

        long confidenceLower = Math.max(0, projectedEod - buffer);
        long confidenceUpper = projectedEod + buffer;

        UUID id = UUID.randomUUID();
        String modelVersion = "EXP_SEASONAL_V1";

        db.sql("""
            INSERT INTO float_forecast
            (id, rail, forecast_date, expected_inflow_pesewas, expected_outflow_pesewas, projected_eod_pesewas, buffer_pesewas, confidence_lower_pesewas, confidence_upper_pesewas, model_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(id, rail, targetDate, expectedInflow, expectedOutflow, projectedEod, buffer, confidenceLower, confidenceUpper, modelVersion)
        .update();

        return new ForecastResult(id, rail, targetDate, expectedInflow, expectedOutflow, projectedEod,
                buffer, confidenceLower, confidenceUpper, modelVersion, hasLimitation, reason);
    }
}
