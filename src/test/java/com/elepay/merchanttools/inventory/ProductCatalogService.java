package com.elepay.merchanttools.inventory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductCatalogService {
    private final JdbcClient db;

    public ProductCatalogService(JdbcClient db) {
        this.db = db;
    }

    public record Category(
            UUID id,
            UUID merchantId,
            String name,
            OffsetDateTime createdAt
    ) {}

    public record Product(
            UUID id,
            UUID merchantId,
            UUID categoryId,
            String name,
            String description,
            String brand,
            boolean isActive,
            OffsetDateTime createdAt
    ) {}

    public record Variant(
            UUID id,
            UUID productId,
            UUID merchantId,
            String sku,
            String barcode,
            String variantName,
            String attributesJson,
            long costPricePesewas,
            long retailPricePesewas,
            boolean trackInventory,
            OffsetDateTime createdAt
    ) {}

    public record VariantDetail(
            UUID id,
            UUID productId,
            UUID merchantId,
            String productName,
            String sku,
            String barcode,
            String variantName,
            String attributesJson,
            long costPricePesewas,
            long retailPricePesewas,
            boolean trackInventory
    ) {}

    @Transactional
    public Category createCategory(UUID merchantId, String name) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO inventory_category (id, merchant_id, name, created_at)
            VALUES (:id, :merchantId, :name, now())
            """)
            .param("id", id)
            .param("merchantId", merchantId)
            .param("name", name)
            .update();

        return new Category(id, merchantId, name, OffsetDateTime.now());
    }

    public List<Category> listCategories(UUID merchantId) {
        return db.sql("""
            SELECT id, merchant_id, name, created_at
            FROM inventory_category WHERE merchant_id = :merchantId
            ORDER BY name ASC
            """)
            .param("merchantId", merchantId)
            .query(Category.class)
            .list();
    }

    @Transactional
    public Product createProduct(UUID merchantId, UUID categoryId, String name, String description, String brand) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO inventory_product (id, merchant_id, category_id, name, description, brand, is_active, created_at)
            VALUES (:id, :merchantId, :categoryId, :name, :description, :brand, true, now())
            """)
            .param("id", id)
            .param("merchantId", merchantId)
            .param("categoryId", categoryId)
            .param("name", name)
            .param("description", description)
            .param("brand", brand)
            .update();

        return getProduct(id).orElseThrow();
    }

    public Optional<Product> getProduct(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, category_id, name, description, brand, is_active, created_at
            FROM inventory_product WHERE id = :id
            """)
            .param("id", id)
            .query(Product.class)
            .optional();
    }

    public List<Product> listProducts(UUID merchantId) {
        return db.sql("""
            SELECT id, merchant_id, category_id, name, description, brand, is_active, created_at
            FROM inventory_product WHERE merchant_id = :merchantId
            ORDER BY name ASC
            """)
            .param("merchantId", merchantId)
            .query(Product.class)
            .list();
    }

    @Transactional
    public Variant createVariant(
            UUID merchantId,
            UUID productId,
            String sku,
            String barcode,
            String variantName,
            String attributesJson,
            long costPricePesewas,
            long retailPricePesewas,
            boolean trackInventory
    ) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO inventory_variant (
                id, product_id, merchant_id, sku, barcode, variant_name,
                attributes_json, cost_price_pesewas, retail_price_pesewas, track_inventory, created_at
            ) VALUES (
                :id, :productId, :merchantId, :sku, :barcode, :variantName,
                :attributesJson, :costPricePesewas, :retailPricePesewas, :trackInventory, now()
            )
            """)
            .param("id", id)
            .param("productId", productId)
            .param("merchantId", merchantId)
            .param("sku", sku)
            .param("barcode", barcode)
            .param("variantName", variantName)
            .param("attributesJson", attributesJson == null ? "{}" : attributesJson)
            .param("costPricePesewas", costPricePesewas)
            .param("retailPricePesewas", retailPricePesewas)
            .param("trackInventory", trackInventory)
            .update();

        return getVariant(id).orElseThrow();
    }

    public Optional<Variant> getVariant(UUID id) {
        return db.sql("""
            SELECT id, product_id, merchant_id, sku, barcode, variant_name,
                   attributes_json, cost_price_pesewas, retail_price_pesewas, track_inventory, created_at
            FROM inventory_variant WHERE id = :id
            """)
            .param("id", id)
            .query(Variant.class)
            .optional();
    }

    public List<Variant> listVariantsByProduct(UUID productId) {
        return db.sql("""
            SELECT id, product_id, merchant_id, sku, barcode, variant_name,
                   attributes_json, cost_price_pesewas, retail_price_pesewas, track_inventory, created_at
            FROM inventory_variant WHERE product_id = :productId
            ORDER BY variant_name ASC
            """)
            .param("productId", productId)
            .query(Variant.class)
            .list();
    }

    // Fast scanner lookup by barcode (EAN-13, UPC, Code128)
    public Optional<VariantDetail> lookupByBarcode(UUID merchantId, String barcode) {
        return db.sql("""
            SELECT v.id, v.product_id, v.merchant_id, p.name AS product_name, v.sku, v.barcode,
                   v.variant_name, v.attributes_json, v.cost_price_pesewas, v.retail_price_pesewas, v.track_inventory
            FROM inventory_variant v
            JOIN inventory_product p ON p.id = v.product_id
            WHERE v.merchant_id = :merchantId AND v.barcode = :barcode
            LIMIT 1
            """)
            .param("merchantId", merchantId)
            .param("barcode", barcode)
            .query(VariantDetail.class)
            .optional();
    }

    // Fast scanner lookup by SKU
    public Optional<VariantDetail> lookupBySku(UUID merchantId, String sku) {
        return db.sql("""
            SELECT v.id, v.product_id, v.merchant_id, p.name AS product_name, v.sku, v.barcode,
                   v.variant_name, v.attributes_json, v.cost_price_pesewas, v.retail_price_pesewas, v.track_inventory
            FROM inventory_variant v
            JOIN inventory_product p ON p.id = v.product_id
            WHERE v.merchant_id = :merchantId AND v.sku = :sku
            LIMIT 1
            """)
            .param("merchantId", merchantId)
            .param("sku", sku)
            .query(VariantDetail.class)
            .optional();
    }
}
