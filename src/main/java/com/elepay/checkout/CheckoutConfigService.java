package com.elepay.checkout;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 5 & 14: Versioned, auditable configuration for checkout limits, customer fees,
 * and settlement schedules. Eliminates inappropriate hardcoded constants.
 */
@Service
public class CheckoutConfigService {

    public static final String KEY_MAX_ALLOCATIONS = "checkout.max_allocations";
    public static final String KEY_MAX_TOTAL = "checkout.max_total";
    public static final String KEY_DELIVERY_CONFIRM_DAYS = "checkout.default_delivery_confirm_days";
    public static final String KEY_SETTLEMENT_DELAY_HOURS = "checkout.default_settlement_delay_hours";
    public static final String KEY_FEE_WALLET_BPS = "checkout.fee.customer.wallet_bps";
    public static final String KEY_FEE_WALLET_FIXED = "checkout.fee.customer.wallet_fixed";
    public static final String KEY_FEE_MOMO_BPS = "checkout.fee.customer.momo_bps";
    public static final String KEY_FEE_MOMO_FIXED = "checkout.fee.customer.momo_fixed";
    public static final String KEY_FEE_CARD_BPS = "checkout.fee.customer.card_bps";
    public static final String KEY_FEE_CARD_FIXED = "checkout.fee.customer.card_fixed";

    private final JdbcClient db;
    private final Audit audit;

    public CheckoutConfigService(
            JdbcClient db,
            Audit audit,
            @Value("${elepay.fees.customer.wallet-bps:0}") int defaultWalletBps,
            @Value("${elepay.fees.customer.wallet-fixed:0}") int defaultWalletFixed,
            @Value("${elepay.fees.customer.momo-bps:0}") int defaultMomoBps,
            @Value("${elepay.fees.customer.momo-fixed:0}") int defaultMomoFixed,
            @Value("${elepay.fees.customer.card-bps:0}") int defaultCardBps,
            @Value("${elepay.fees.customer.card-fixed:0}") int defaultCardFixed
    ) {
        this.db = db;
        this.audit = audit;
    }

    public record ConfigEntry(
            String key,
            String value,
            int version,
            String description,
            String updatedBy
    ) {}

    public int getIntConfig(String key, int fallback) {
        try {
            return db.sql("SELECT config_value FROM checkout_config WHERE config_key = ?")
                    .param(key).query(String.class).optional()
                    .map(Integer::parseInt)
                    .orElse(fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    public long getLongConfig(String key, long fallback) {
        try {
            return db.sql("SELECT config_value FROM checkout_config WHERE config_key = ?")
                    .param(key).query(String.class).optional()
                    .map(Long::parseLong)
                    .orElse(fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    public int maxAllocations() {
        return getIntConfig(KEY_MAX_ALLOCATIONS, 50);
    }

    public long maxTotal() {
        return getLongConfig(KEY_MAX_TOTAL, 5_000_000_00L);
    }

    public int defaultDeliveryConfirmDays() {
        return getIntConfig(KEY_DELIVERY_CONFIRM_DAYS, 14);
    }

    public int defaultSettlementDelayHours() {
        return getIntConfig(KEY_SETTLEMENT_DELAY_HOURS, 24);
    }

    public long calculateCustomerFee(String method, long subtotal) {
        if (method == null) throw ApiException.badRequest("invalid_method", "Payment method is required");
        int bps;
        long fixed;

        switch (method.toLowerCase(Locale.ROOT)) {
            case "wallet" -> {
                bps = getIntConfig(KEY_FEE_WALLET_BPS, 0);
                fixed = getLongConfig(KEY_FEE_WALLET_FIXED, 0L);
            }
            case "momo" -> {
                bps = getIntConfig(KEY_FEE_MOMO_BPS, 0);
                fixed = getLongConfig(KEY_FEE_MOMO_FIXED, 0L);
            }
            case "card" -> {
                bps = getIntConfig(KEY_FEE_CARD_BPS, 0);
                fixed = getLongConfig(KEY_FEE_CARD_FIXED, 0L);
            }
            case "ghqr" -> {
                bps = getIntConfig("checkout.fee.customer.ghqr_bps", 0);
                fixed = getLongConfig("checkout.fee.customer.ghqr_fixed", 0L);
            }
            default -> throw ApiException.badRequest("invalid_method", "Choose wallet, momo, card or ghqr");
        }

        return Math.addExact(Math.multiplyExact(subtotal, bps) / 10_000L, fixed);
    }

    @Transactional
    public ConfigEntry updateConfig(String key, String value, String reason, String actor) {
        if (key == null || key.isBlank() || value == null) {
            throw ApiException.badRequest("invalid_config", "Key and value are required");
        }

        var current = db.sql("SELECT config_value, version FROM checkout_config WHERE config_key = ?")
                .param(key)
                .query((rs, i) -> Map.of("value", rs.getString(1), "version", rs.getInt(2)))
                .optional();

        int nextVersion = current.map(m -> ((Integer) m.get("version")) + 1).orElse(1);
        String oldValue = current.map(m -> (String) m.get("value")).orElse(null);

        db.sql("""
                INSERT INTO checkout_config (config_key, config_value, version, updated_by, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (config_key) DO UPDATE SET
                    config_value = EXCLUDED.config_value,
                    version = EXCLUDED.version,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = now()
                """).params(key, value, nextVersion, actor).update();

        UUID historyId = UUID.randomUUID();
        db.sql("""
                INSERT INTO checkout_config_history (id, config_key, config_value, version, updated_by, change_reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """).params(historyId, key, value, nextVersion, actor, reason).update();

        audit.record(actor, "checkout.config.updated", "config:" + key,
                Map.of("oldValue", String.valueOf(oldValue), "newValue", value, "version", nextVersion, "reason", String.valueOf(reason)));

        return new ConfigEntry(key, value, nextVersion, null, actor);
    }

    public List<ConfigEntry> listConfigs() {
        return db.sql("SELECT config_key, config_value, version, description, updated_by FROM checkout_config ORDER BY config_key")
                .query((rs, i) -> new ConfigEntry(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5)))
                .list();
    }
}
