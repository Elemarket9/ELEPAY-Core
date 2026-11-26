package com.elepay.identity;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.common.RedisBreaker;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 1 Kill-Switch Matrix & <1s Global/Merchant/Module Kill Switches.
 *
 * <p>Provides:
 * <ul>
 *   <li>Route-level non-Stage-1 module kill switches (default-off for lending, virtual-cards,
 *       bill-payments, agent-banking, fx-trading; runtime-togglable for pos, fx, consumer-wallet,
 *       consumer-w2w, ussd, mandates).</li>
 *   <li>Global Payout Kill Switch ({@code elepay:killswitch:global} in Redis + atomic in-memory
 *       {@link AtomicBoolean} + {@code payout_safety_control.emergency_frozen}), stopping all
 *       outbound payouts/withdrawals/transfers in < 1 second.</li>
 *   <li>Merchant Payout Kill Switch ({@code elepay:killswitch:merchants} in Redis + in-memory set
 *       + {@code merchant.payouts_frozen}), stopping payouts for 1 merchant only in < 1 second.</li>
 * </ul>
 */
@Service
@DependsOn("flywayInitializer")
public class KillSwitchService {
    public static final String REDIS_GLOBAL_KEY = "elepay:killswitch:global";
    public static final String REDIS_MERCHANTS_KEY = "elepay:killswitch:merchants";
    public static final String REDIS_MODULE_PREFIX = "elepay:killswitch:module:";

    public static final String CARD_PAYMENTS = "card-payments";
    public static final String MTN_COLLECTIONS = "mtn-collections";
    public static final String MTN_PAYOUTS = "mtn-payouts";
    public static final String CHECKOUT = "checkout";
    public static final String RESTAURANT = "restaurant";

    /**
     * The switch that governs NEW money movement on a rail, or null when none applies. Refunds are never
     * blocked: returning a customer's money must keep working while a rail is switched off.
     */
    public static String railSwitch(String direction, String rail) {
        if (rail == null || direction == null || "refund".equals(direction)) return null;
        return switch (rail.toLowerCase(Locale.ROOT)) {
            case "card" -> CARD_PAYMENTS;
            case "mtn" -> "charge".equals(direction) ? MTN_COLLECTIONS : "payout".equals(direction) ? MTN_PAYOUTS : null;
            default -> null;
        };
    }

    /** True unless the rail switch for this direction is off. */
    public boolean isRailAllowed(String direction, String rail) {
        String key = railSwitch(direction, rail);
        return key == null || isModuleEnabled(key);
    }

    private final JdbcClient db;
    private final Audit audit;
    private final OpsQueue ops;
    private final StringRedisTemplate redis;
    private final RedisBreaker redisBreaker;

    private final AtomicBoolean globalFrozen = new AtomicBoolean(false);
    private volatile String globalFreezeReason = null;
    private volatile String globalFrozenBy = null;
    private final Set<UUID> frozenMerchants = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> merchantFrozenBy = new ConcurrentHashMap<>();
    private final Map<String, Boolean> moduleStates = new ConcurrentHashMap<>();
    private final Map<String, String> moduleChangedBy = new ConcurrentHashMap<>();
    private volatile boolean controlsUnreadable = false;
    private volatile boolean redisObservedHealthy = false;

    @Autowired
    public KillSwitchService(
            JdbcClient db,
            Audit audit,
            OpsQueue ops,
            @Autowired(required = false) StringRedisTemplate redis,
            @Value("${elepay.stage1.strict-kill-switch-enabled:true}") boolean strictStage1,
            @Value("${elepay.stage1.module.pos.enabled:true}") boolean posEnabled,
            @Value("${elepay.stage1.module.fx.enabled:true}") boolean fxEnabled,
            @Value("${elepay.stage1.module.consumer-wallet.enabled:true}") boolean consumerWalletEnabled,
            @Value("${elepay.stage1.module.ussd.enabled:true}") boolean ussdEnabled,
            @Value("${elepay.stage1.module.mandates.enabled:true}") boolean mandatesEnabled
    ) {
        this.db = db;
        this.audit = audit;
        this.ops = ops;
        this.redis = redis;
        this.redisBreaker = new RedisBreaker(5_000L);

        // Default-off non-Stage-1 modules
        moduleStates.put("lending", !strictStage1);
        moduleStates.put("virtual-cards", !strictStage1);
        moduleStates.put("bill-payments", !strictStage1);
        moduleStates.put("agent-banking", !strictStage1);
        moduleStates.put("fx-trading", !strictStage1);

        // Runtime-togglable modules
        moduleStates.put("pos", posEnabled);
        moduleStates.put("fx", fxEnabled);
        moduleStates.put("consumer-wallet", consumerWalletEnabled);
        moduleStates.put("consumer-w2w", consumerWalletEnabled);
        moduleStates.put("ussd", ussdEnabled);
        moduleStates.put("mandates", mandatesEnabled);

        // Launch payment switches (on by default; the admin dashboard turns them off and on at runtime).
        // Rail switches stop NEW payments only: status checks, recovery of in-flight payments and refunds go on.
        moduleStates.put(CARD_PAYMENTS, true);
        moduleStates.put(MTN_COLLECTIONS, true);
        moduleStates.put(MTN_PAYOUTS, true);
        moduleStates.put(CHECKOUT, true);
        moduleStates.put(RESTAURANT, true);
        loadControls();
    }

    /** Database is the source of truth so a restart or another instance sees the same switches. */
    private void loadControls() {
        if (db == null) {
            controlsUnreadable = true;
            return;
        }
        try {
            db.sql("SELECT module_key, enabled, updated_by FROM module_kill_switch")
                    .query((rs, i) -> {
                        moduleStates.put(rs.getString(1), rs.getBoolean(2));
                        moduleChangedBy.put(rs.getString(1), rs.getString(3));
                        return 1;
                    }).list();
            var global = db.sql("""
                    SELECT emergency_frozen, freeze_reason, frozen_by FROM payout_safety_control WHERE id = 1""")
                    .query((rs, i) -> new Object[]{rs.getBoolean(1), rs.getString(2), rs.getString(3)}).optional();
            if (global.isPresent() && Boolean.TRUE.equals(global.get()[0])) {
                globalFrozen.set(true);
                globalFreezeReason = (String) global.get()[1];
                globalFrozenBy = (String) global.get()[2];
            }
            db.sql("SELECT merchant_id, payouts_frozen, updated_by FROM merchant_kill_switch")
                    .query((rs, i) -> {
                        if (rs.getBoolean(2)) {
                            UUID id = rs.getObject(1, UUID.class);
                            frozenMerchants.add(id);
                            merchantFrozenBy.put(id, rs.getString(3));
                        }
                        return 1;
                    }).list();
            controlsUnreadable = false;
        } catch (RuntimeException e) {
            controlsUnreadable = true;
        }
    }

    public String globalFrozenBy() { return globalFrozenBy; }

    public String merchantFrozenBy(UUID merchantId) { return merchantFrozenBy.get(merchantId); }

    public record KillSwitchStatus(
            boolean globalFrozen,
            String globalFreezeReason,
            Set<UUID> frozenMerchants,
            Map<String, Boolean> modules
    ) {}

    // ---- Module Route Kill Switch (<1ms zero-DB check) --------------------------------------

    public boolean isModuleEnabled(String moduleKey) {
        if (controlsUnreadable) return false;
        if (moduleKey == null) return true;
        String key = moduleKey.toLowerCase(Locale.ROOT);
        if (redis != null) {
            if (!redisBreaker.allow()) {
                return moduleStates.getOrDefault(key, true);
            }
            try {
                String redisVal = redis.opsForValue().get(REDIS_MODULE_PREFIX + key);
                redisObservedHealthy = true;
                if (redisVal != null) {
                    boolean enabled = Boolean.parseBoolean(redisVal);
                    moduleStates.put(key, enabled);
                    return enabled;
                }
            } catch (Exception e) {
                redisBreaker.failure();
                return moduleStates.getOrDefault(key, true);
            }
        }
        return moduleStates.getOrDefault(key, true);
    }

    public boolean isConsumerWalletEnabled() {
        return isModuleEnabled("consumer-wallet") && isModuleEnabled("consumer-w2w");
    }

    @Transactional
    public void setModuleEnabled(String moduleKey, boolean enabled, String reason, String actor) {
        String key = moduleKey.toLowerCase(Locale.ROOT);
        moduleStates.put(key, enabled);
        writeRedis(REDIS_MODULE_PREFIX + key, Boolean.toString(enabled));
        if ("consumer-wallet".equals(key) || "consumer-w2w".equals(key)) {
            moduleStates.put("consumer-wallet", enabled);
            moduleStates.put("consumer-w2w", enabled);
            writeRedis(REDIS_MODULE_PREFIX + "consumer-wallet", Boolean.toString(enabled));
            writeRedis(REDIS_MODULE_PREFIX + "consumer-w2w", Boolean.toString(enabled));
        }
        moduleChangedBy.put(key, actor);
        if (db != null) {
            db.sql("""
                    INSERT INTO module_kill_switch (module_key, enabled, stage, reason, updated_by, updated_at)
                    VALUES (:k, :e, :s, :r, :by, now())
                    ON CONFLICT (module_key) DO UPDATE SET
                        enabled = :e, reason = :r, updated_by = :by, updated_at = now()""")
                    .param("k", key).param("e", enabled).param("s", enabled ? 1 : 2)
                    .param("r", reason).param("by", actor != null ? actor : "system").update();
        }
        if (audit != null && actor != null) {
            audit.record(actor, enabled ? "kill_switch.module_enabled" : "kill_switch.module_disabled",
                    "module:" + key, Map.of("enabled", enabled, "reason", String.valueOf(reason)));
        }
    }

    /**
     * Resolves whether a request path belongs to a disabled Stage 1 OFF module.
     * Returns the disabled module name if blocked, or {@code null} if allowed.
     */
    public String resolveDisabledModuleForPath(String path) {
        if (path == null) return null;

        // 1. Lending & Credit
        if (path.startsWith("/v1/lending") || path.startsWith("/v1/loans") || path.startsWith("/v1/credit")
                || path.startsWith("/v1/ops/lending") || path.startsWith("/v1/ops/credit")) {
            return isModuleEnabled("lending") ? null : "lending";
        }
        // 2. Virtual Cards & Card Issuing (Note: /v1/rails/card/** acquiring tokenization remains ON)
        if (path.startsWith("/v1/cards") || path.startsWith("/v1/virtual-cards") || path.startsWith("/v1/ops/cards")) {
            return isModuleEnabled("virtual-cards") ? null : "virtual-cards";
        }
        // 3. Bill Payments, Airtime & Utilities
        if (path.startsWith("/v1/bills") || path.startsWith("/v1/bill-payments")
                || path.startsWith("/v1/airtime") || path.startsWith("/v1/utilities")) {
            return isModuleEnabled("bill-payments") ? null : "bill-payments";
        }
        // 4. Agent Banking
        if (path.startsWith("/v1/agents") || path.startsWith("/v1/agent-banking") || path.startsWith("/v1/ops/agents")) {
            return isModuleEnabled("agent-banking") ? null : "agent-banking";
        }
        // 5. FX Trading (explicitly disabled route prefix /v1/fx-trading/**, plus /v1/fx/** when fx or fx-trading off)
        if (path.startsWith("/v1/fx-trading")) {
            return isModuleEnabled("fx-trading") ? null : "fx-trading";
        }
        if (path.startsWith("/v1/fx") || path.startsWith("/v1/ops/fx")) {
            return isModuleEnabled("fx") ? null : "fx-trading";
        }
        // 6. Physical POS Terminals
        if (path.startsWith("/v1/pos")) {
            return isModuleEnabled("pos") ? null : "pos";
        }
        // 6a. Card payments: new card tokenisation (charges are also stopped at the rail by isRailAllowed)
        if (path.startsWith("/v1/rails/card/tokens")) {
            return isModuleEnabled(CARD_PAYMENTS) ? null : CARD_PAYMENTS;
        }
        // 6b. Checkout: creating checkouts (merchant API, public links, merchant codes) and paying them.
        // Order lookups and status pages stay up so customers who already paid can still see their order.
        if (path.matches("/v1/merchants/[^/]+/checkouts/?")
                || path.matches("/v1/public/checkouts/[^/]+/pay(/.*)?")
                || path.matches("/v1/public/links/[^/]+/checkouts/?")
                || path.matches("/v1/public/merchants/[^/]+/checkouts/?")
                || (path.startsWith("/v1/checkouts/") && path.endsWith("/pay/wallet"))) {
            if (!isModuleEnabled(CHECKOUT)) {
                return CHECKOUT;
            }
        }
        // 6c. Restaurant ordering and kitchen screens (waiter orders also respect the POS kill switch)
        if (path.startsWith("/v1/restaurant/waiter/") && !isModuleEnabled("pos")) {
            return "pos";
        }
        if (path.startsWith("/v1/restaurant") || path.startsWith("/v1/public/kitchen")) {
            return isModuleEnabled(RESTAURANT) ? null : RESTAURANT;
        }
        // 7. Consumer Wallet / W2W / Growth
        if (path.startsWith("/v1/wallet") || path.startsWith("/v1/ops/customers")
                || path.startsWith("/v1/growth") || path.startsWith("/v1/savings")
                || (path.startsWith("/v1/checkouts/") && path.endsWith("/pay/wallet"))) {
            return isConsumerWalletEnabled() ? null : "consumer-w2w";
        }
        // 8. Consumer USSD
        if (path.startsWith("/v1/ussd")) {
            return (isModuleEnabled("ussd") && isConsumerWalletEnabled()) ? null : "ussd";
        }
        // 9. Consumer Recurring Mandates
        if (path.startsWith("/v1/customers/mandates")
                || (path.startsWith("/v1/merchants/") && path.contains("/mandates"))) {
            return (isModuleEnabled("mandates") && isConsumerWalletEnabled()) ? null : "mandates";
        }

        return null;
    }

    // ---- Global Payout Kill Switch (<1s Redis + Atomic Memory + DB) -------------------------

    public boolean isGlobalFrozen() {
        if (controlsUnreadable) return true;
        if (redis != null) {
            if (!redisBreaker.allow()) {
                return redisObservedHealthy || globalFrozen.get();
            }
            try {
                String redisVal = redis.opsForValue().get(REDIS_GLOBAL_KEY);
                redisObservedHealthy = true;
                if (redisVal != null) {
                    boolean frozen = "true".equalsIgnoreCase(redisVal);
                    globalFrozen.set(frozen);
                    return frozen;
                }
            } catch (Exception e) {
                redisBreaker.failure();
                if (redisObservedHealthy) {
                    return true;
                }
            }
        }
        return globalFrozen.get();
    }

    public void requirePayoutsAllowed(UUID merchantId) {
        if (isGlobalFrozen()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GLOBAL_KILL_SWITCH_ACTIVE",
                    "All outbound payouts, withdrawals, and transfers are halted by the global kill switch");
        }
        if (merchantId != null && isMerchantFrozen(merchantId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MERCHANT_KILL_SWITCH_ACTIVE",
                    "Outbound payouts and withdrawals are frozen for merchant " + merchantId);
        }
    }

    @Transactional
    public void setGlobalFrozen(boolean frozen, String reason, String actor) {
        globalFrozen.set(frozen);
        globalFreezeReason = frozen ? (reason != null ? reason : "Emergency global kill switch activated") : null;
        globalFrozenBy = frozen ? actor : null;
        writeRedis(REDIS_GLOBAL_KEY, Boolean.toString(frozen));
        if (db != null) {
            db.sql("""
                    UPDATE payout_safety_control
                    SET emergency_frozen = :f,
                        freeze_reason = :r,
                        frozen_by = :by,
                        frozen_at = CASE WHEN :f THEN now() ELSE NULL END,
                        updated_by = :by,
                        updated_at = now()
                    WHERE id = 1""")
                    .param("f", frozen)
                    .param("r", globalFreezeReason)
                    .param("by", actor != null ? actor : "system")
                    .update();
        }
        if (frozen && ops != null) {
            ops.raise("global_kill_switch_activated", "critical", "kill-switch:global",
                    Map.of("actor", String.valueOf(actor), "reason", String.valueOf(globalFreezeReason)));
        }
        if (audit != null && actor != null) {
            audit.record(actor, frozen ? "kill_switch.global_frozen" : "kill_switch.global_unfrozen",
                    "kill-switch:global", Map.of("frozen", frozen, "reason", String.valueOf(reason)));
        }
    }

    // ---- Merchant Payout Kill Switch (<1s Redis + Atomic Memory + DB) -----------------------

    public boolean isMerchantFrozen(UUID merchantId) {
        if (merchantId == null) return false;
        if (controlsUnreadable) return true;
        if (redis != null) {
            if (!redisBreaker.allow()) {
                return redisObservedHealthy || frozenMerchants.contains(merchantId);
            }
            try {
                Boolean res = redis.opsForSet().isMember(REDIS_MERCHANTS_KEY, merchantId.toString());
                redisObservedHealthy = true;
                if (Boolean.TRUE.equals(res)) {
                    frozenMerchants.add(merchantId);
                    return true;
                } else {
                    frozenMerchants.remove(merchantId);
                    return false;
                }
            } catch (Exception e) {
                redisBreaker.failure();
                if (redisObservedHealthy) {
                    return true;
                }
            }
        }
        return frozenMerchants.contains(merchantId);
    }

    @Transactional
    public void setMerchantFrozen(UUID merchantId, boolean frozen, String reason, String actor) {
        Objects.requireNonNull(merchantId, "merchantId is required");
        if (frozen) {
            frozenMerchants.add(merchantId);
            merchantFrozenBy.put(merchantId, actor);
            addSetRedis(REDIS_MERCHANTS_KEY, merchantId.toString());
        } else {
            frozenMerchants.remove(merchantId);
            merchantFrozenBy.remove(merchantId);
            removeSetRedis(REDIS_MERCHANTS_KEY, merchantId.toString());
        }
        if (db != null) {
            db.sql("""
                    INSERT INTO merchant_kill_switch (merchant_id, payouts_frozen, reason, updated_by, updated_at)
                    VALUES (:id, :f, :r, :by, now())
                    ON CONFLICT (merchant_id) DO UPDATE SET
                        payouts_frozen = :f, reason = :r, updated_by = :by, updated_at = now()""")
                    .param("id", merchantId).param("f", frozen).param("r", reason)
                    .param("by", actor != null ? actor : "system").update();
        }
        if (audit != null && actor != null) {
            audit.record(actor, frozen ? "kill_switch.merchant_frozen" : "kill_switch.merchant_unfrozen",
                    "merchant:" + merchantId, Map.of("frozen", frozen, "reason", String.valueOf(reason)));
        }
    }

    public KillSwitchStatus status() {
        return new KillSwitchStatus(
                isGlobalFrozen(),
                globalFreezeReason,
                Set.copyOf(frozenMerchants),
                new TreeMap<>(moduleStates)
        );
    }

    /** Reset helper for test isolation so global/merchant kill switches don't leak across tests. */
    @Transactional
    public void resetForTesting() {
        globalFrozen.set(false);
        globalFreezeReason = null;
        globalFrozenBy = null;
        frozenMerchants.clear();
        merchantFrozenBy.clear();
        controlsUnreadable = false;
        writeRedis(REDIS_GLOBAL_KEY, "false");
        deleteRedis(REDIS_MERCHANTS_KEY);
        moduleStates.put("lending", false);
        moduleStates.put("virtual-cards", false);
        moduleStates.put("bill-payments", false);
        moduleStates.put("agent-banking", false);
        moduleStates.put("fx-trading", false);
        moduleStates.put("pos", true);
        moduleStates.put("fx", true);
        moduleStates.put("consumer-wallet", true);
        moduleStates.put("consumer-w2w", true);
        moduleStates.put("ussd", true);
        moduleStates.put("mandates", true);
        for (var entry : moduleStates.entrySet()) {
            writeRedis(REDIS_MODULE_PREFIX + entry.getKey(), Boolean.toString(entry.getValue()));
        }
        if (db != null) {
            db.sql("UPDATE payout_safety_control SET emergency_frozen = false, freeze_reason = NULL, frozen_by = NULL WHERE id = 1").update();
            db.sql("DELETE FROM merchant_kill_switch").update();
            for (var entry : moduleStates.entrySet()) {
                db.sql("""
                        INSERT INTO module_kill_switch (module_key, enabled, stage, reason, updated_by, updated_at)
                        VALUES (:k, :e, 1, 'test reset', 'test-reset', now())
                        ON CONFLICT (module_key) DO UPDATE SET enabled = :e, updated_by = 'test-reset', updated_at = now()""")
                        .param("k", entry.getKey()).param("e", entry.getValue()).update();
            }
        }
    }

    // ---- Redis helpers ----------------------------------------------------------------------

    private void writeRedis(String key, String value) {
        if (redis == null || !redisBreaker.allow()) return;
        try {
            redis.opsForValue().set(key, value);
        } catch (Exception e) {
            redisBreaker.failure();
        }
    }

    private String readRedis(String key) {
        if (redis == null || !redisBreaker.allow()) return null;
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            redisBreaker.failure();
            return null;
        }
    }

    private void addSetRedis(String key, String member) {
        if (redis == null || !redisBreaker.allow()) return;
        try {
            redis.opsForSet().add(key, member);
        } catch (Exception e) {
            redisBreaker.failure();
        }
    }

    private void removeSetRedis(String key, String member) {
        if (redis == null || !redisBreaker.allow()) return;
        try {
            redis.opsForSet().remove(key, member);
        } catch (Exception e) {
            redisBreaker.failure();
        }
    }

    private boolean isMemberRedis(String key, String member) {
        if (redis == null || !redisBreaker.allow()) return false;
        try {
            Boolean res = redis.opsForSet().isMember(key, member);
            return Boolean.TRUE.equals(res);
        } catch (Exception e) {
            redisBreaker.failure();
            return false;
        }
    }

    private void deleteRedis(String key) {
        if (redis == null || !redisBreaker.allow()) return;
        try {
            redis.delete(key);
        } catch (Exception e) {
            redisBreaker.failure();
        }
    }
}
