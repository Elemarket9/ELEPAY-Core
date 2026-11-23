package com.elepay.merchanttools;

import com.elepay.common.ApiException;
import com.elepay.merchant.MerchantAccess;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Merchant-wide administrative access for merchant tools. Related identifiers are never trusted. */
@Service
public class MerchantToolAccess {
    private final MerchantAccess merchants;
    private final JdbcClient db;

    public MerchantToolAccess(MerchantAccess merchants, JdbcClient db) {
        this.merchants = merchants;
        this.db = db;
    }

    public enum Resource {
        WAREHOUSE("SELECT merchant_id FROM inventory_warehouse WHERE id = :id"),
        CATEGORY("SELECT merchant_id FROM inventory_category WHERE id = :id"),
        PRODUCT("SELECT merchant_id FROM inventory_product WHERE id = :id"),
        VARIANT("SELECT merchant_id FROM inventory_variant WHERE id = :id"),
        SUPPLIER("SELECT merchant_id FROM inventory_supplier WHERE id = :id"),
        TRANSFER("SELECT merchant_id FROM inventory_transfer_order WHERE id = :id"),
        PURCHASE_ORDER("SELECT merchant_id FROM inventory_purchase_order WHERE id = :id"),
        TERMINAL("SELECT merchant_id FROM pos_terminal WHERE id = :id"),
        CASHIER("SELECT merchant_id FROM pos_cashier WHERE id = :id"),
        SHIFT("SELECT merchant_id FROM pos_shift WHERE id = :id"),
        CART("SELECT merchant_id FROM pos_cart_held WHERE id = :id"),
        ORDER("SELECT merchant_id FROM pos_order WHERE id = :id"),
        ORDER_ITEM("SELECT o.merchant_id FROM pos_order_item i JOIN pos_order o ON o.id = i.order_id WHERE i.id = :id"),
        FLOOR_PLAN("SELECT merchant_id FROM restaurant_floor_plan WHERE id = :id"),
        TABLE("SELECT merchant_id FROM restaurant_table WHERE id = :id"),
        MODIFIER_GROUP("SELECT merchant_id FROM restaurant_modifier_group WHERE id = :id"),
        RECIPE("SELECT merchant_id FROM restaurant_recipe WHERE id = :id"),
        KDS_TICKET("SELECT merchant_id FROM restaurant_kds_ticket WHERE id = :id"),
        KDS_ITEM("SELECT t.merchant_id FROM restaurant_kds_item i JOIN restaurant_kds_ticket t ON t.id = i.ticket_id WHERE i.id = :id"),
        STATION("SELECT merchant_id FROM restaurant_kitchen_station WHERE id = :id"),
        PRINT_JOB("SELECT merchant_id FROM restaurant_kds_print_job WHERE id = :id");

        private final String sql;
        Resource(String sql) { this.sql = sql; }
    }

    public UUID manage(UUID merchantId) {
        if (merchantId == null) throw ApiException.badRequest("merchant_required", "Merchant is required");
        MerchantAccess.Access access = merchants.require(merchantId, MerchantAccess.Permission.MANAGE);
        if (access.branchScoped()) throw ApiException.forbidden("Merchant-wide administrator required");
        return merchantId;
    }

    /** Read access for any merchant member, including branch-scoped staff. */
    public UUID view(UUID merchantId) {
        if (merchantId == null) throw ApiException.badRequest("merchant_required", "Merchant is required");
        merchants.require(merchantId, MerchantAccess.Permission.VIEW);
        return merchantId;
    }

    public UUID view(Resource type, UUID id) { return view(owner(type, id)); }

    /** Day-to-day operation (cashiers, waiters, kitchen staff): placing and progressing orders. */
    public UUID operate(UUID merchantId) {
        if (merchantId == null) throw ApiException.badRequest("merchant_required", "Merchant is required");
        merchants.require(merchantId, MerchantAccess.Permission.COLLECT);
        return merchantId;
    }

    public UUID operate(Resource type, UUID id) { return operate(owner(type, id)); }

    public UUID owner(Resource type, UUID id) {
        if (id == null) throw ApiException.badRequest("resource_required", "Resource is required");
        return db.sql(type.sql).param("id", id).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Resource not found"));
    }

    public UUID manage(Resource type, UUID id) { return manage(owner(type, id)); }

    public void belongs(UUID merchantId, Resource type, UUID id) {
        if (!merchantId.equals(owner(type, id))) throw ApiException.notFound("Resource not found");
    }

    public void branch(UUID merchantId, UUID branchId) {
        if (branchId != null && !merchants.branchBelongs(merchantId, branchId))
            throw ApiException.notFound("Branch not found");
    }
}
