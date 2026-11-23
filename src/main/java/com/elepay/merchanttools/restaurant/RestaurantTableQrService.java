package com.elepay.merchanttools.restaurant;

import com.elepay.common.ApiException;
import com.elepay.merchanttools.pos.PosOrderService;
import com.elepay.identity.KillSwitchService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signed-by-entropy rotating table QR invitations. Customers request menu items,
 * the manager explicitly confirms the request before a real POS/KDS order is created.
 * No public endpoint can debit a wallet, accept a card, or fabricate payment.
 */
@Service
public class RestaurantTableQrService {
    private final JdbcClient db;
    private final PosOrderService orders;
    private final KillSwitchService killSwitches;
    private final com.elepay.merchant.MerchantAccess merchants;
    private final SecureRandom random = new SecureRandom();

    public RestaurantTableQrService(JdbcClient db, PosOrderService orders, KillSwitchService killSwitches,
                                    com.elepay.merchant.MerchantAccess merchants) {
        this.db = db; this.orders = orders; this.killSwitches = killSwitches;
        this.merchants = merchants;
    }
    public record TableOffer(UUID merchantId, UUID tableId, String tableNumber,
                             String merchantName, List<MenuItem> menu) {}
    public record MenuItem(UUID variantId, String name, String sku, long pricePesewas) {}
    public record Selection(UUID variantId, int quantity, String notes) {}
    public record CustomerRequest(UUID id, String status, UUID serverOrderId) {}
    public record PendingRequest(UUID id, UUID tableId, String tableNumber, OffsetDateTime createdAt,
                                 OffsetDateTime expiresAt, long totalPesewas, List<Line> lines) {}
    public record Line(UUID variantId, String name, int quantity, long unitPricePesewas, String notes) {}
    public record RotatedQr(UUID tableId, String tableNumber, String relativeUrl) {}
    private record Table(UUID merchantId, UUID tableId, String tableNumber, String merchantName) {}
    private record Request(UUID id, UUID merchantId, UUID tableId, String status, UUID serverOrderId,
                           OffsetDateTime expiresAt) {}

    private static String digest(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    @Transactional
    public RotatedQr rotate(UUID tableId) {
        var table = db.sql("SELECT table_number FROM restaurant_table WHERE id = :id")
                .param("id", tableId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Restaurant table not found"));
        UUID merchantId = db.sql("SELECT merchant_id FROM restaurant_table WHERE id = :id")
                .param("id", tableId).query(UUID.class).single();
        byte[] raw = new byte[32]; random.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        db.sql("""
                INSERT INTO restaurant_table_qr (table_id, merchant_id, token_hash, enabled, rotated_at)
                VALUES (:table, :merchant, :hash, true, now())
                ON CONFLICT (table_id) DO UPDATE SET token_hash = excluded.token_hash,
                    enabled = true, rotated_at = now()
                """).param("table", tableId).param("merchant", merchantId)
                .param("hash", digest(token)).update();
        return new RotatedQr(tableId, table, "/restaurant/table/" + token);
    }

    @Transactional
    public void disable(UUID tableId) {
        db.sql("UPDATE restaurant_table_qr SET enabled = false WHERE table_id = :id")
                .param("id", tableId).update();
    }

    private Table table(String token) {
        if (token == null || !token.matches("[0-9a-fA-F]{64}"))
            throw ApiException.notFound("Restaurant table QR not found");
        return db.sql("""
                SELECT q.merchant_id AS merchant_id, q.table_id AS table_id,
                       t.table_number AS table_number
                FROM restaurant_table_qr q
                JOIN restaurant_table t ON t.id = q.table_id
                WHERE q.token_hash = :hash AND q.enabled = true
                """).param("hash", digest(token))
                .query((rs, n) -> new Table(rs.getObject("merchant_id", UUID.class),
                        rs.getObject("table_id", UUID.class), rs.getString("table_number"), null))
                .optional()
                .map(found -> {
                    var merchant = merchants.find(found.merchantId())
                            .filter(m -> "active".equals(m.status()))
                            .orElseThrow(() -> ApiException.notFound("Restaurant QR unavailable"));
                    return new Table(found.merchantId(), found.tableId(), found.tableNumber(), merchant.tradingName());
                })
                .orElseThrow(() -> ApiException.notFound("Restaurant QR is expired or disabled"));
    }

    public TableOffer offer(String token) {
        Table table = table(token);
        var menu = db.sql("""
                SELECT v.id AS variant_id, p.name AS name, v.sku AS sku,
                       v.retail_price_pesewas AS price_pesewas
                FROM inventory_variant v
                JOIN inventory_product p ON p.id = v.product_id
                WHERE v.merchant_id = :merchant AND p.merchant_id = :merchant
                  AND p.is_active = true AND v.retail_price_pesewas >= 0
                ORDER BY p.name, v.variant_name LIMIT 300
                """).param("merchant", table.merchantId()).query(MenuItem.class).list();
        return new TableOffer(table.merchantId(), table.tableId(), table.tableNumber(),
                table.merchantName(), menu);
    }

    @Transactional
    public CustomerRequest submit(String token, String clientKey, List<Selection> selections) {
        Table table = table(token);
        if (clientKey == null || !clientKey.matches("[A-Za-z0-9._:-]{8,64}")
                || selections == null || selections.isEmpty() || selections.size() > 30)
            throw ApiException.badRequest("table_qr_invalid", "Choose 1-30 dishes and a valid request key");
        var previous = db.sql("""
                SELECT id, status, server_order_id FROM restaurant_table_qr_request
                WHERE table_id = :table AND client_key = :key
                """).param("table", table.tableId()).param("key", clientKey)
                .query((rs,n) -> new CustomerRequest(rs.getObject("id",UUID.class),
                        rs.getString("status"),rs.getObject("server_order_id",UUID.class))).optional();
        if (previous.isPresent()) return previous.get();
        long recent = db.sql("""
                SELECT count(*) FROM restaurant_table_qr_request
                WHERE table_id = :table AND created_at > now() - INTERVAL '5 minutes'
                """).param("table", table.tableId()).query(Long.class).single();
        if (recent >= 10)
            throw ApiException.unprocessable("table_qr_rate_limited", "Too many table requests; ask restaurant staff");
        Set<UUID> variants = new HashSet<>();
        for (Selection selection : selections) {
            if (selection == null || selection.variantId() == null || !variants.add(selection.variantId())
                    || selection.quantity() <= 0 || selection.quantity() > 10
                    || selection.notes() != null && selection.notes().length() > 200)
                throw ApiException.badRequest("table_qr_item_invalid", "Invalid item, quantity or kitchen note");
        }
        var offer = offer(token);
        var menu = offer.menu().stream().collect(java.util.stream.Collectors.toMap(MenuItem::variantId, x -> x));
        for (UUID variant : variants) if (!menu.containsKey(variant))
            throw ApiException.badRequest("table_qr_menu_invalid", "Selected item is no longer on the menu");

        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO restaurant_table_qr_request
                    (id, merchant_id, table_id, client_key, status, expires_at)
                VALUES (:id,:merchant,:table,:key,'PENDING',now()+INTERVAL '20 minutes')
                """).param("id", id).param("merchant", table.merchantId())
                .param("table", table.tableId()).param("key", clientKey).update();
        for (Selection selection : selections) {
            MenuItem menuItem = menu.get(selection.variantId());
            db.sql("""
                    INSERT INTO restaurant_table_qr_request_item
                        (id, request_id, variant_id, quantity, quoted_price_pesewas, notes)
                    VALUES (:id,:request,:variant,:quantity,:price,:notes)
                    """).param("id", UUID.randomUUID()).param("request", id)
                    .param("variant", selection.variantId())
                    .param("quantity", selection.quantity())
                    .param("price", menuItem.pricePesewas())
                    .param("notes", selection.notes()).update();
        }
        return new CustomerRequest(id, "PENDING", null);
    }

    public CustomerRequest status(UUID id) {
        return db.sql("""
                SELECT id,
                    CASE WHEN expires_at < now() AND status = 'PENDING' THEN 'EXPIRED'
                         ELSE status END AS status,
                    server_order_id
                FROM restaurant_table_qr_request WHERE id = :id
                """).param("id", id)
                .query(CustomerRequest.class).optional()
                .orElseThrow(() -> ApiException.notFound("QR request not found"));
    }

    public List<PendingRequest> pending(UUID merchantId) {
        var list = db.sql("""
                SELECT r.id, r.table_id, t.table_number, r.created_at, r.expires_at
                FROM restaurant_table_qr_request r
                JOIN restaurant_table t ON t.id = r.table_id
                WHERE r.merchant_id = :merchant AND r.status = 'PENDING'
                  AND r.expires_at > now() ORDER BY r.created_at LIMIT 100
                """).param("merchant", merchantId)
                .query((rs,n) -> new Object[]{rs.getObject("id", UUID.class),
                        rs.getObject("table_id",UUID.class),rs.getString("table_number"),
                        rs.getObject("created_at",OffsetDateTime.class),
                        rs.getObject("expires_at",OffsetDateTime.class)}).list();
        return list.stream().map(a -> {
            UUID requestId=(UUID)a[0];
            List<Line> lines = lines(requestId);
            long total = 0;
            for (Line line : lines) total = Math.addExact(total,
                    Math.multiplyExact(line.unitPricePesewas(),line.quantity()));
            return new PendingRequest(requestId,(UUID)a[1],(String)a[2],
                    (OffsetDateTime)a[3],(OffsetDateTime)a[4],total,lines);
        }).toList();
    }

    private List<Line> lines(UUID requestId) {
        return db.sql("""
                SELECT i.variant_id, p.name AS name, i.quantity,
                       i.quoted_price_pesewas AS unit_price_pesewas, i.notes
                FROM restaurant_table_qr_request_item i
                JOIN inventory_variant v ON v.id = i.variant_id
                JOIN inventory_product p ON p.id = v.product_id
                WHERE i.request_id = :request ORDER BY i.id
                """).param("request", requestId).query(Line.class).list();
    }

    @Transactional
    public CustomerRequest accept(UUID id, String actor) {
        if (!killSwitches.isModuleEnabled("pos"))
            throw ApiException.conflict("pos_disabled", "POS acceptance is currently disabled");
        Request req = db.sql("""
                SELECT id, merchant_id, table_id, status, server_order_id, expires_at
                FROM restaurant_table_qr_request WHERE id = :id FOR UPDATE
                """).param("id", id).query(Request.class).optional()
                .orElseThrow(() -> ApiException.notFound("QR request not found"));
        if ("ACCEPTED".equals(req.status())) return new CustomerRequest(req.id(),"ACCEPTED",req.serverOrderId());
        if (!"PENDING".equals(req.status()) || req.expiresAt().isBefore(OffsetDateTime.now()))
            throw ApiException.conflict("table_qr_request_expired", "Request is no longer pending");
        List<Line> lines = lines(id);
        if (lines.isEmpty()) throw ApiException.badRequest("table_qr_items_required", "No dishes selected");
        var items = lines.stream().map(line -> {
            Long latest = db.sql("""
                    SELECT v.retail_price_pesewas FROM inventory_variant v
                    JOIN inventory_product p ON p.id = v.product_id
                    WHERE v.id = :variant AND v.merchant_id = :merchant
                      AND p.is_active = true
                    """).param("variant", line.variantId()).param("merchant", req.merchantId())
                    .query(Long.class).optional()
                    .orElseThrow(() -> ApiException.conflict("menu_item_unavailable", "A menu item has been disabled"));
            if (latest.longValue() != line.unitPricePesewas())
                throw ApiException.conflict("menu_price_changed", "Menu price changed; customer must resubmit");
            return new PosOrderService.OrderItemInput(line.variantId(), line.name(),
                    BigDecimal.valueOf(line.quantity()), latest, 0L, 0L, "[]", null, "MAIN", line.notes());
        }).toList();
        var order = orders.createOrder(req.merchantId(), null, null, null, null,
                items, 0L, 0L, 0L, "QR", req.tableId(), actor);
        db.sql("""
                UPDATE restaurant_table_qr_request
                SET status = 'ACCEPTED', server_order_id = :order, decided_at = now()
                WHERE id = :request
                """).param("order", order.id()).param("request", id).update();
        return new CustomerRequest(id, "ACCEPTED", order.id());
    }

    @Transactional
    public CustomerRequest reject(UUID id) {
        Request req = db.sql("""
                SELECT id, merchant_id, table_id, status, server_order_id, expires_at
                FROM restaurant_table_qr_request WHERE id = :id FOR UPDATE
                """).param("id",id).query(Request.class).optional()
                .orElseThrow(() -> ApiException.notFound("QR request not found"));
        if ("PENDING".equals(req.status()))
            db.sql("""
                    UPDATE restaurant_table_qr_request SET status='REJECTED', decided_at=now()
                    WHERE id = :id
                    """).param("id",id).update();
        return status(id);
    }

    public UUID requestMerchant(UUID id) {
        return db.sql("SELECT merchant_id FROM restaurant_table_qr_request WHERE id = :id")
                .param("id", id).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("QR request not found"));
    }
}
