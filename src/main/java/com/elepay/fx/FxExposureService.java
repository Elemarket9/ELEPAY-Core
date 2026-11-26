package com.elepay.fx;

import com.elepay.common.ApiException;
import com.elepay.common.OpsQueue;
import com.elepay.ledger.Ledger;
import com.elepay.outbox.Outbox;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm #062: Exposure limits and hedging triggers.
 *
 * <p>Continuously tracks the net open foreign currency position (NOFP) per currency
 * across ledger clearing accounts, in-flight settlement balances, and open partner
 * commitments. Enforces policy limits and generates automated hedging triggers and
 * operational alerts when exposure approaches or exceeds risk thresholds.
 */
@Service
public class FxExposureService {
    private final JdbcClient db;
    private final Ledger ledger;
    private final OpsQueue ops;
    private final Outbox outbox;

    public FxExposureService(JdbcClient db, Ledger ledger, OpsQueue ops, Outbox outbox) {
        this.db = db;
        this.ledger = ledger;
        this.ops = ops;
        this.outbox = outbox;
    }

    public record ExposureLimit(String currency, long maxNetOpenPosition, int warningThresholdPct,
                                long maxDailyVolume, boolean active) {}

    public record HedgingTrigger(UUID id, String currency, long netOpenPosition, long exposureLimit,
                                 String triggerType, String recommendedAction, long hedgeAmount,
                                 String status) {}

    public record ExposureStatus(String currency, long netOpenPosition, long clearingBalance,
                                 long inFlightBalance, long limit, int utilizationPct,
                                 boolean breach, boolean warning, Optional<HedgingTrigger> trigger) {}

    /**
     * Calculates current Net Open Foreign Currency Position (NOFP) for a currency.
     */
    public long calculateNofp(String currency) {
        long clearing = ledger.balance("system:fx_clearing:" + currency);
        long inFlight = ledger.balance("system:fx_in_flight:" + currency);

        // Sum across partner clearing accounts for this currency
        List<String> partnerAccounts = ledger.systemCodes("system:clearing:fx-", "clearing", currency);

        long partnerClearingTotal = 0;
        for (String acct : partnerAccounts) {
            partnerClearingTotal += ledger.balance(acct);
        }

        return clearing + inFlight + partnerClearingTotal;
    }

    /**
     * Evaluates exposure against limits for a given currency and generates hedging triggers if needed.
     */
    @Transactional
    public ExposureStatus checkExposure(String currency) {
        var limitOpt = db.sql("""
                SELECT currency, max_net_open_position, warning_threshold_pct, max_daily_volume, active
                FROM fx_exposure_limit WHERE currency = :c AND active = true""")
                .param("c", currency)
                .query((rs, i) -> new ExposureLimit(rs.getString(1), rs.getLong(2), rs.getInt(3),
                        rs.getLong(4), rs.getBoolean(5)))
                .optional();

        if (limitOpt.isEmpty()) {
            Integer knownCurrency = db.sql("SELECT COUNT(*) FROM currency WHERE code = :c")
                    .param("c", currency)
                    .query(Integer.class).single();
            if (knownCurrency == null || knownCurrency == 0) {
                throw com.elepay.common.ApiException.badRequest("unsupported_currency",
                        "Unsupported FX currency: " + currency);
            }
            long nofp = calculateNofp(currency);
            return new ExposureStatus(currency, nofp, ledger.balance("system:fx_clearing:" + currency),
                    ledger.balance("system:fx_in_flight:" + currency), 0L, 0, false, false, Optional.empty());
        }

        ExposureLimit limit = limitOpt.get();
        long nofp = calculateNofp(currency);
        long absNofp = Math.abs(nofp);
        int utilizationPct = (int) Math.min(1000, (absNofp * 100L) / Math.max(1, limit.maxNetOpenPosition()));
        boolean breach = absNofp >= limit.maxNetOpenPosition();
        boolean warning = !breach && utilizationPct >= limit.warningThresholdPct();

        Optional<HedgingTrigger> trigger = Optional.empty();

        if (breach || warning) {
            String triggerType = breach ? "breach" : "warning";
            String action = nofp > 0 ? "SELL_" + currency : "BUY_" + currency;
            long hedgeAmount = breach ? (absNofp - (limit.maxNetOpenPosition() * 80 / 100)) : (absNofp * 20 / 100);

            UUID triggerId = UUID.randomUUID();
            db.sql("""
                    INSERT INTO fx_hedging_trigger (id, currency, net_open_position, exposure_limit, trigger_type,
                        recommended_action, hedge_amount, status)
                    VALUES (:id, :c, :nofp, :limit, :ttype, :action, :hedge, 'open')""")
                    .param("id", triggerId).param("c", currency).param("nofp", nofp)
                    .param("limit", limit.maxNetOpenPosition()).param("ttype", triggerType)
                    .param("action", action).param("hedge", hedgeAmount).update();

            ops.raise("fx_exposure_" + triggerType, breach ? "critical" : "high", "fx-exposure:" + currency,
                    Map.of("currency", currency, "netOpenPosition", nofp, "limit", limit.maxNetOpenPosition(),
                            "action", action, "hedgeAmount", hedgeAmount));

            outbox.publish("fx", currency, "fx.hedging.triggered", Map.of(
                    "currency", currency, "triggerType", triggerType, "action", action, "hedgeAmount", hedgeAmount),
                    Set.of());

            trigger = Optional.of(new HedgingTrigger(triggerId, currency, nofp, limit.maxNetOpenPosition(),
                    triggerType, action, hedgeAmount, "open"));
        }

        return new ExposureStatus(currency, nofp, ledger.balance("system:fx_clearing:" + currency),
                ledger.balance("system:fx_in_flight:" + currency), limit.maxNetOpenPosition(),
                utilizationPct, breach, warning, trigger);
    }
}
