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
        TERMINAL("SELECT merchant_id FROM pos_terminal WHERE id = :id"),
        CASHIER("SELECT merchant_id FROM pos_cashier WHERE id = :id"),
        SHIFT("SELECT merchant_id FROM pos_shift WHERE id = :id"),
        ORDER("SELECT merchant_id FROM pos_order WHERE id = :id");

        private final String sql;
        Resource(String sql) { this.sql = sql; }
    }

    public UUID manage(UUID merchantId) {
        if (merchantId == null) throw ApiException.badRequest("merchant_required", "Merchant is required");
        MerchantAccess.Access access = merchants.require(merchantId, MerchantAccess.Permission.MANAGE);
        if (access.branchScoped()) throw ApiException.forbidden("Merchant-wide administrator required");
        return merchantId;
    }

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
