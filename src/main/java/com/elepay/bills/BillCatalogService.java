package com.elepay.bills;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class BillCatalogService {

    private final JdbcClient db;

    public BillCatalogService(JdbcClient db) {
        this.db = db;
    }

    public record BillProvider(
            UUID id,
            String providerCode,
            String name,
            String category,
            boolean isActive
    ) {}

    public record BillProduct(
            UUID id,
            String providerCode,
            String productCode,
            String name,
            String category,
            long amountPesewas,
            Integer dataMb,
            Integer validityDays,
            boolean isActive
    ) {}

    public record Institution(
            UUID id,
            String institutionCode,
            String name,
            String category,
            boolean isActive
    ) {}

    public List<BillProvider> listActiveProviders() {
        return db.sql("""
            SELECT id, provider_code, name, category, is_active
            FROM bill_provider
            WHERE is_active = true
            ORDER BY provider_code ASC
        """).query((rs, rowNum) -> new BillProvider(
                rs.getObject("id", UUID.class),
                rs.getString("provider_code"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getBoolean("is_active")
        )).list();
    }

    public List<BillProduct> listProductsForProvider(String providerCode) {
        return db.sql("""
            SELECT id, provider_code, product_code, name, category, amount_pesewas, data_mb, validity_days, is_active
            FROM bill_product
            WHERE provider_code = ? AND is_active = true
            ORDER BY amount_pesewas ASC
        """).param(providerCode)
        .query((rs, rowNum) -> new BillProduct(
                rs.getObject("id", UUID.class),
                rs.getString("provider_code"),
                rs.getString("product_code"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getLong("amount_pesewas"),
                (Integer) rs.getObject("data_mb"),
                (Integer) rs.getObject("validity_days"),
                rs.getBoolean("is_active")
        )).list();
    }

    public Optional<BillProduct> getProductByCode(String productCode) {
        return db.sql("""
            SELECT id, provider_code, product_code, name, category, amount_pesewas, data_mb, validity_days, is_active
            FROM bill_product
            WHERE product_code = ?
        """).param(productCode)
        .query((rs, rowNum) -> new BillProduct(
                rs.getObject("id", UUID.class),
                rs.getString("provider_code"),
                rs.getString("product_code"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getLong("amount_pesewas"),
                (Integer) rs.getObject("data_mb"),
                (Integer) rs.getObject("validity_days"),
                rs.getBoolean("is_active")
        )).optional();
    }

    public List<Institution> listActiveInstitutions() {
        return db.sql("""
            SELECT id, institution_code, name, category, is_active
            FROM institution_registry
            WHERE is_active = true
            ORDER BY name ASC
        """).query((rs, rowNum) -> new Institution(
                rs.getObject("id", UUID.class),
                rs.getString("institution_code"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getBoolean("is_active")
        )).list();
    }
}
