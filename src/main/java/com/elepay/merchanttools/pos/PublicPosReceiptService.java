package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Historical paid receipt view: no account details, PIN, bank credentials or payment references. */
@Service
public class PublicPosReceiptService {
    private final JdbcClient db;
    public PublicPosReceiptService(JdbcClient db) { this.db = db; }

    public record Item(String name, BigDecimal quantity, long amountPesewas) {}
    public record ReceiptView(String merchantName, String receiptNumber, String orderNumber,
                              OffsetDateTime createdAt, long subtotalPesewas, long discountPesewas,
                              long taxPesewas, long tipPesewas, long totalPesewas,
                              List<Item> items) {}
    private record Header(String merchantName, String receiptNumber, String orderNumber,
                          OffsetDateTime createdAt, long subtotalPesewas, long discountPesewas,
                          long taxPesewas, long tipPesewas, long totalPesewas,
                          java.util.UUID orderId) {}

    public ReceiptView find(String token) {
        if (token == null || !token.matches("[0-9a-fA-F]{64}"))
            throw ApiException.notFound("Receipt link not found");
        var header = db.sql("""
                SELECT m.trading_name AS merchant_name, r.receipt_number, p.order_number,
                       r.created_at, p.subtotal_pesewas, p.discount_pesewas,
                       p.tax_pesewas, p.tip_pesewas, p.total_pesewas, p.id AS order_id
                FROM pos_receipt_public_token t
                JOIN pos_receipt r ON r.id = t.receipt_id
                JOIN pos_order p ON p.id = r.order_id
                JOIN merchant m ON m.id = p.merchant_id
                WHERE t.token_hash = :hash AND t.revoked = false
                    AND t.expires_at > now()
                """).param("hash", PosReceiptService.receiptTokenHash(token))
                .query(Header.class).optional()
                .orElseThrow(() -> ApiException.notFound("Receipt link expired or invalid"));
        var items = db.sql("""
                SELECT item_name AS name, quantity,
                       total_pesewas AS amount_pesewas
                FROM pos_order_item WHERE order_id = :order ORDER BY id
                """).param("order",header.orderId())
                .query(Item.class).list();
        return new ReceiptView(header.merchantName(),header.receiptNumber(),header.orderNumber(),
                header.createdAt(),header.subtotalPesewas(),header.discountPesewas(),
                header.taxPesewas(),header.tipPesewas(),header.totalPesewas(),items);
    }
}
