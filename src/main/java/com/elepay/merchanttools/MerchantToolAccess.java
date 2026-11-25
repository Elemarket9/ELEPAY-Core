package com.elepay.merchanttools;

import com.elepay.common.ApiException;
import com.elepay.merchant.MerchantAccess;
import java.util.Optional;
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
        PRINT_JOB("SELECT merchant_id FROM restaurant_kds_print_job WHERE id = :id"),
        INVOICE("SELECT merchant_id FROM merchant_invoice WHERE id = :id"),
        QUOTE("SELECT merchant_id FROM merchant_quote WHERE id = :id"),
        CUSTOMER("SELECT merchant_id FROM crm_customer WHERE id = :id"),
        EMPLOYEE("SELECT merchant_id FROM payroll_employee WHERE id = :id"),
        PAYROLL_RUN("SELECT merchant_id FROM payroll_run WHERE id = :id");

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
        viewAccess(merchantId);
        return merchantId;
    }

    public UUID view(Resource type, UUID id) { return view(owner(type, id)); }

    public MerchantAccess.Access viewAccess(UUID merchantId) {
        if (merchantId == null) throw ApiException.badRequest("merchant_required", "Merchant is required");
        return bindTerminal(merchants.require(merchantId, MerchantAccess.Permission.VIEW));
    }

    public MerchantAccess.Access viewAccess(Resource type, UUID id) { return viewAccess(owner(type, id)); }

    /** Day-to-day operation (cashiers, waiters, kitchen staff): placing and progressing orders. */
    public UUID operate(UUID merchantId) {
        operateAccess(merchantId);
        return merchantId;
    }

    public UUID operate(Resource type, UUID id) { return operate(owner(type, id)); }

    public MerchantAccess.Access operateAccess(UUID merchantId) {
        if (merchantId == null) throw ApiException.badRequest("merchant_required", "Merchant is required");
        return bindTerminal(merchants.require(merchantId, MerchantAccess.Permission.COLLECT));
    }

    public MerchantAccess.Access operateAccess(Resource type, UUID id) { return operateAccess(owner(type, id)); }

    private record TerminalBinding(UUID id, UUID branchId) {}

    private Optional<TerminalBinding> boundTerminal(MerchantAccess.Access access) {
        if (access == null || access.actor() == null || !access.actor().isPosTerminalKey()) {
            return Optional.empty();
        }
        UUID credId = access.actor().apiKeyCredentialId();
        if (credId == null) {
            throw ApiException.forbidden("Terminal credential is not bound to a terminal");
        }
        return Optional.of(db.sql("""
                SELECT id, branch_id FROM pos_terminal
                WHERE merchant_id = :m AND credential_id = :cred AND status <> 'REVOKED'
                LIMIT 1
                """)
                .param("m", access.merchantId())
                .param("cred", credId)
                .query((rs, i) -> new TerminalBinding(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .optional()
                .orElseThrow(() -> ApiException.forbidden("Terminal credential is not bound to an active terminal")));
    }

    private MerchantAccess.Access bindTerminal(MerchantAccess.Access access) {
        Optional<TerminalBinding> bound = boundTerminal(access);
        if (bound.isEmpty()) return access;
        return new MerchantAccess.Access(access.merchantId(), access.role(), bound.get().branchId(), access.actor());
    }

    public void requireTerminal(MerchantAccess.Access access, UUID terminalId) {
        Optional<TerminalBinding> bound = boundTerminal(access);
        if (bound.isPresent() && !java.util.Objects.equals(bound.get().id(), terminalId)) {
            throw ApiException.notFound("Terminal not found");
        }
    }

    public void requireBranch(MerchantAccess.Access access, UUID resourceBranchId) {
        if (access != null && access.branchScoped() && !java.util.Objects.equals(access.branchId(), resourceBranchId)) {
            throw ApiException.notFound("Resource not found");
        }
    }

    public void requireOrderBranch(MerchantAccess.Access access, UUID orderId) {
        if (access == null || !access.branchScoped()) return;
        UUID branchId = db.sql("SELECT branch_id FROM pos_order WHERE id = :id")
                .param("id", orderId)
                .query(UUID.class)
                .optional()
                .orElse(null);
        requireBranch(access, branchId);
    }

    public void requireTicketBranch(MerchantAccess.Access access, UUID ticketId) {
        if (access == null || !access.branchScoped()) return;
        UUID branchId = db.sql("""
                SELECT o.branch_id
                FROM restaurant_kds_ticket t
                JOIN pos_order o ON o.id = t.order_id
                WHERE t.id = :id
                """)
                .param("id", ticketId)
                .query(UUID.class)
                .optional()
                .orElse(null);
        requireBranch(access, branchId);
    }

    public void requireKdsItemBranch(MerchantAccess.Access access, UUID itemId) {
        if (access == null || !access.branchScoped()) return;
        UUID branchId = db.sql("""
                SELECT o.branch_id
                FROM restaurant_kds_item i
                JOIN restaurant_kds_ticket t ON t.id = i.ticket_id
                JOIN pos_order o ON o.id = t.order_id
                WHERE i.id = :id
                """)
                .param("id", itemId)
                .query(UUID.class)
                .optional()
                .orElse(null);
        requireBranch(access, branchId);
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
