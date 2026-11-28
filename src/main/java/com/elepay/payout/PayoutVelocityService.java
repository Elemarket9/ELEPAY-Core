package com.elepay.payout;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.identity.KillSwitchService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Automatic Hourly/Daily Global Cedi Velocity Ceiling & Emergency Auto-Freeze.
 *
 * <p>Enforces rolling hourly and daily global GHS cedi caps on outbound payouts in
 * {@link Payouts#create}, {@link Payouts#retry}, and {@link Payouts#submit}.
 * On breach, automatically trips {@code emergency_frozen = true} in a {@code REQUIRES_NEW}
 * transaction and raises a critical {@link OpsQueue} alert while collections, checkouts,
 * and callbacks keep running.
 */
@Service
public class PayoutVelocityService {
    private final JdbcClient db;
    private final KillSwitchService killSwitchService;
    private final OpsQueue ops;
    private final Audit audit;
    private final TransactionTemplate requiresNewTx;

    @Autowired
    public PayoutVelocityService(
            JdbcClient db,
            KillSwitchService killSwitchService,
            OpsQueue ops,
            Audit audit,
            PlatformTransactionManager tm
    ) {
        this.db = db;
        this.killSwitchService = killSwitchService;
        this.ops = ops;
        this.audit = audit;
        this.requiresNewTx = new TransactionTemplate(tm);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record SafetyConfig(
            boolean emergencyFrozen,
            String freezeReason,
            long hourlyCeilingPesewas,
            long dailyCeilingPesewas,
            long maxSinglePayoutPesewas,
            boolean autoFreezeOnCeilingBreach
    ) {}

    public SafetyConfig getConfig() {
        return db.sql("""
                SELECT emergency_frozen, freeze_reason, hourly_ceiling_pesewas, daily_ceiling_pesewas,
                       max_single_payout_pesewas, auto_freeze_on_ceiling_breach
                FROM payout_safety_control WHERE id = 1""")
                .query((rs, i) -> new SafetyConfig(
                        rs.getBoolean(1),
                        rs.getString(2),
                        rs.getLong(3),
                        rs.getLong(4),
                        rs.getLong(5),
                        rs.getBoolean(6)
                ))
                .optional()
                .orElse(new SafetyConfig(false, null, 50_000_000_000L, 200_000_000_000L, 10_000_000_000L, true));
    }

    @Transactional
    public void updateCeilings(long hourlyCeilingPesewas, long dailyCeilingPesewas, long maxSinglePayoutPesewas,
                               boolean autoFreezeOnBreach, String actor) {
        db.sql("""
                UPDATE payout_safety_control
                SET hourly_ceiling_pesewas = :h,
                    daily_ceiling_pesewas = :d,
                    max_single_payout_pesewas = :s,
                    auto_freeze_on_ceiling_breach = :a,
                    updated_by = :by,
                    updated_at = now()
                WHERE id = 1""")
                .param("h", hourlyCeilingPesewas)
                .param("d", dailyCeilingPesewas)
                .param("s", maxSinglePayoutPesewas)
                .param("a", autoFreezeOnBreach)
                .param("by", actor != null ? actor : "system")
                .update();
    }

    @Transactional
    public void resetWindowsForTesting() {
        db.sql("DELETE FROM payout_global_velocity_window").update();
        db.sql("""
                UPDATE payout_safety_control
                SET emergency_frozen = false,
                    freeze_reason = NULL,
                    hourly_ceiling_pesewas = 50000000000,
                    daily_ceiling_pesewas = 200000000000,
                    max_single_payout_pesewas = 10000000000,
                    auto_freeze_on_ceiling_breach = true
                WHERE id = 1""").update();
        if (killSwitchService != null) {
            killSwitchService.setGlobalFrozen(false, null, "test-reset");
        }
    }

    /**
     * Enforces global/merchant kill switches and rolling hourly/daily cedi velocity ceilings
     * before reserving funds for a payout batch.
     */
    public void checkAndRecordOutflow(String ownerType, UUID ownerId, long totalBatchPesewas, int itemCount, String actor) {
        UUID merchantId = "merchant".equalsIgnoreCase(ownerType) ? ownerId : null;
        if (killSwitchService != null) {
            killSwitchService.requirePayoutsAllowed(merchantId);
        }

        SafetyConfig cfg = getConfig();
        if (cfg.emergencyFrozen()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GLOBAL_KILL_SWITCH_ACTIVE",
                    "Outbound payouts are frozen: " + (cfg.freezeReason() != null ? cfg.freezeReason() : "Emergency freeze"));
        }

        if (totalBatchPesewas > cfg.maxSinglePayoutPesewas()) {
            if (cfg.autoFreezeOnCeilingBreach()) {
                autoFreeze("Single payout batch " + totalBatchPesewas + " exceeded max single ceiling " + cfg.maxSinglePayoutPesewas(),
                        totalBatchPesewas, actor);
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "payout_velocity_ceiling_exceeded",
                    "Payout batch amount exceeds maximum single payout ceiling");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime hourStart = now.truncatedTo(ChronoUnit.HOURS);
        OffsetDateTime dayStart = now.truncatedTo(ChronoUnit.DAYS);

        ensureWindowExists("HOURLY", hourStart);
        ensureWindowExists("DAILY", dayStart);

        long currentHourly = db.sql("""
                SELECT total_outflow_pesewas FROM payout_global_velocity_window
                WHERE window_type = 'HOURLY' AND window_start = :ws FOR UPDATE""")
                .param("ws", hourStart).query(Long.class).single();

        long currentDaily = db.sql("""
                SELECT total_outflow_pesewas FROM payout_global_velocity_window
                WHERE window_type = 'DAILY' AND window_start = :ws FOR UPDATE""")
                .param("ws", dayStart).query(Long.class).single();

        long projectedHourly = Math.addExact(currentHourly, totalBatchPesewas);
        long projectedDaily = Math.addExact(currentDaily, totalBatchPesewas);
        if (projectedHourly > cfg.hourlyCeilingPesewas()
                || projectedDaily > cfg.dailyCeilingPesewas()) {
            String breachReason = (projectedHourly > cfg.hourlyCeilingPesewas())
                    ? "Hourly global payout cedi ceiling breached (" + projectedHourly + " > " + cfg.hourlyCeilingPesewas() + ")"
                    : "Daily global payout cedi ceiling breached (" + projectedDaily + " > " + cfg.dailyCeilingPesewas() + ")";
            if (cfg.autoFreezeOnCeilingBreach()) {
                autoFreeze(breachReason, totalBatchPesewas, actor);
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "payout_velocity_ceiling_exceeded",
                    "Global outbound payout velocity ceiling exceeded; outbound payouts have been automatically frozen");
        }

        db.sql("""
                UPDATE payout_global_velocity_window
                SET total_outflow_pesewas = total_outflow_pesewas + :amt,
                    payout_count = payout_count + :cnt,
                    updated_at = now()
                WHERE window_type = 'HOURLY' AND window_start = :ws""")
                .param("amt", totalBatchPesewas).param("cnt", itemCount).param("ws", hourStart).update();

        db.sql("""
                UPDATE payout_global_velocity_window
                SET total_outflow_pesewas = total_outflow_pesewas + :amt,
                    payout_count = payout_count + :cnt,
                    updated_at = now()
                WHERE window_type = 'DAILY' AND window_start = :ws""")
                .param("amt", totalBatchPesewas).param("cnt", itemCount).param("ws", dayStart).update();
    }

    /**
     * Releases previously recorded outflow from the hourly and daily velocity windows when a
     * payout fails before disbursement or is returned by the receiving rail.
     */
    public void releaseOutflow(long amountPesewas, OffsetDateTime createdAt) {
        if (amountPesewas <= 0) return;
        OffsetDateTime ts = (createdAt != null ? createdAt : OffsetDateTime.now(ZoneOffset.UTC))
                .withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime hourStart = ts.truncatedTo(ChronoUnit.HOURS);
        OffsetDateTime dayStart = ts.truncatedTo(ChronoUnit.DAYS);

        db.sql("""
                UPDATE payout_global_velocity_window
                SET total_outflow_pesewas = GREATEST(0, total_outflow_pesewas - :amt),
                    payout_count = GREATEST(0, payout_count - 1),
                    updated_at = now()
                WHERE window_type = 'HOURLY' AND window_start = :ws""")
                .param("amt", amountPesewas).param("ws", hourStart).update();

        db.sql("""
                UPDATE payout_global_velocity_window
                SET total_outflow_pesewas = GREATEST(0, total_outflow_pesewas - :amt),
                    payout_count = GREATEST(0, payout_count - 1),
                    updated_at = now()
                WHERE window_type = 'DAILY' AND window_start = :ws""")
                .param("amt", amountPesewas).param("ws", dayStart).update();
    }

    private void ensureWindowExists(String windowType, OffsetDateTime windowStart) {
        db.sql("""
                INSERT INTO payout_global_velocity_window (window_type, window_start, total_outflow_pesewas, payout_count, updated_at)
                VALUES (:wt, :ws, 0, 0, now())
                ON CONFLICT (window_type, window_start) DO NOTHING""")
                .param("wt", windowType).param("ws", windowStart).update();
    }

    private void autoFreeze(String breachReason, long attemptedAmountPesewas, String actor) {
        requiresNewTx.executeWithoutResult(status -> {
            db.sql("""
                    UPDATE payout_safety_control
                    SET emergency_frozen = true,
                        freeze_reason = :r,
                        frozen_by = 'system:velocity-ceiling',
                        frozen_at = now(),
                        updated_by = 'system:velocity-ceiling',
                        updated_at = now()
                    WHERE id = 1""")
                    .param("r", breachReason).update();
            if (killSwitchService != null) {
                killSwitchService.setGlobalFrozen(true, breachReason, "system:velocity-ceiling");
            }
            if (ops != null) {
                ops.raise("payout_velocity_ceiling_tripped", "critical", "payout-velocity:global",
                        Map.of("reason", breachReason, "attemptedAmountPesewas", attemptedAmountPesewas,
                                "actor", String.valueOf(actor)));
            }
            if (audit != null) {
                audit.record("system:velocity-ceiling", "payout.velocity_ceiling_auto_frozen", "payout-velocity:global",
                        Map.of("reason", breachReason, "attemptedAmountPesewas", attemptedAmountPesewas));
            }
        });
    }
}
