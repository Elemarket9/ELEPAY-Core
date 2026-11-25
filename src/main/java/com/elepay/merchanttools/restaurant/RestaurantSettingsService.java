package com.elepay.merchanttools.restaurant;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Per-restaurant settings. Today: the tax rate applied server-side to waiter (dine-in) orders. */
@Service
public class RestaurantSettingsService {
    private final JdbcClient db;

    public RestaurantSettingsService(JdbcClient db) {
        this.db = db;
    }

    public int taxBasisPoints(UUID merchantId) {
        return db.sql("SELECT tax_basis_points FROM restaurant_settings WHERE merchant_id = :m")
                .param("m", merchantId).query(Integer.class).optional().orElse(0);
    }

    public void setTaxBasisPoints(UUID merchantId, int basisPoints, String updatedBy) {
        db.sql("""
                INSERT INTO restaurant_settings (merchant_id, tax_basis_points, updated_by)
                VALUES (:m, :bps, :by)
                ON CONFLICT (merchant_id) DO UPDATE
                SET tax_basis_points = EXCLUDED.tax_basis_points, updated_by = EXCLUDED.updated_by, updated_at = now()
                """).param("m", merchantId).param("bps", basisPoints).param("by", updatedBy).update();
    }

    /** Tax on a subtotal, rounded half-up to the pesewa. */
    public long taxOn(UUID merchantId, long subtotalPesewas) {
        int bps = taxBasisPoints(merchantId);
        if (bps == 0 || subtotalPesewas <= 0) return 0;
        return java.math.BigDecimal.valueOf(subtotalPesewas).multiply(java.math.BigDecimal.valueOf(bps))
                .divide(java.math.BigDecimal.valueOf(10_000), 0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
