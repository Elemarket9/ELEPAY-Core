package com.elepay.merchanttools.pos;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosReceiptService {
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    @Value("${elepay.pos.receipt-link-ttl-days:30}")
    private int receiptTtlDays = 30;
    private final JdbcClient db;
    private final PosOrderService orderService;
    private final com.elepay.merchant.MerchantAccess merchants;

    public PosReceiptService(JdbcClient db, PosOrderService orderService, com.elepay.merchant.MerchantAccess merchants) {
        this.merchants = merchants;
        this.db = db;
        this.orderService = orderService;
    }

    public record PosReceipt(
            UUID id,
            String receiptNumber,
            UUID orderId,
            String digitalReceiptUrl,
            String qrPayload,
            String escposFormattedText,
            OffsetDateTime createdAt
    ) {}

    public static String generateReceiptNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        int rand = ThreadLocalRandom.current().nextInt(100000, 999999);
        return "REC-" + date + "-" + rand;
    }

    @Transactional
    public PosReceipt generateReceipt(UUID orderId) {
        db.sql("SELECT id FROM pos_order WHERE id = :id FOR UPDATE").param("id", orderId)
                .query(UUID.class).optional()
                .orElseThrow(() -> com.elepay.common.ApiException.notFound("Order not found"));
        PosOrderService.PosOrder order = orderService.getOrder(orderId).orElseThrow();
        if (!"PAID".equals(order.status())) throw com.elepay.common.ApiException.conflict("order_unpaid", "Payment is not complete");
        // A printed receipt can be requested repeatedly or from two tills; only one record is issued.
        Optional<PosReceipt> existing = findByOrderId(orderId);
        if (existing.isPresent()) return existing.get();
        List<PosOrderService.PosOrderItem> items = orderService.listOrderItems(orderId);
        List<PosOrderService.TenderPayment> payments = orderService.listTenderPayments(orderId);

        String receiptNumber = generateReceiptNumber();
        UUID receiptId = UUID.randomUUID();
        if (receiptTtlDays < 1 || receiptTtlDays > 365)
            throw new IllegalStateException("Receipt link expiry must be 1 to 365 days");
        byte[] random = new byte[32];
        TOKEN_RANDOM.nextBytes(random);
        String token = HexFormat.of().formatHex(random);
        String digitalReceiptUrl = "/receipt/" + token;
        String qrPayload = digitalReceiptUrl;
        String shopName = merchants.find(order.merchantId()).map(com.elepay.merchant.MerchantAccess.Merchant::tradingName)
                .orElse("Receipt").toUpperCase();
        if (shopName.length() > 40) shopName = shopName.substring(0, 40);

        // Build ESC/POS 80mm Plain Text format
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append(" ".repeat((40 - shopName.length()) / 2)).append(shopName).append("\n");
        sb.append("========================================\n");
        sb.append("Receipt No: ").append(receiptNumber).append("\n");
        sb.append("Order No  : ").append(order.orderNumber()).append("\n");
        sb.append("Date      : ").append(OffsetDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("\n");
        sb.append("----------------------------------------\n");
        sb.append(String.format("%-20s %4s %12s\n", "ITEM", "QTY", "TOTAL (GHS)"));
        sb.append("----------------------------------------\n");

        for (PosOrderService.PosOrderItem item : items) {
            sb.append(String.format("%-20.20s %4s %12s\n", item.itemName(), item.quantity().stripTrailingZeros().toPlainString(), formatMinorUnits(item.totalPesewas())));
        }

        sb.append("----------------------------------------\n");
        sb.append(String.format("%-25s %12s\n", "Subtotal:", formatMinorUnits(order.subtotalPesewas())));
        if (order.discountPesewas() > 0) {
            sb.append(String.format("%-25s -%11s\n", "Discount:", formatMinorUnits(order.discountPesewas())));
        }
        if (order.taxPesewas() > 0) {
            sb.append(String.format("%-25s %12s\n", "Tax/VAT:", formatMinorUnits(order.taxPesewas())));
        }
        if (order.tipPesewas() > 0) {
            sb.append(String.format("%-25s %12s\n", "Tip:", formatMinorUnits(order.tipPesewas())));
        }
        sb.append("========================================\n");
        sb.append(String.format("%-25s %12s GHS\n", "TOTAL DUE:", formatMinorUnits(order.totalPesewas())));
        sb.append("========================================\n");

        sb.append("PAYMENTS:\n");
        for (PosOrderService.TenderPayment p : payments) {
            sb.append(String.format(" - %-10s %10s GHS (%s)\n", p.tenderType(), formatMinorUnits(p.amountPesewas()), p.reference() == null ? "OK" : p.reference()));
        }
        if (order.changePesewas() > 0) {
            sb.append(String.format("%-25s %12s GHS\n", "CHANGE GIVEN:", formatMinorUnits(order.changePesewas())));
        }

        sb.append("----------------------------------------\n");
        if (digitalReceiptUrl != null) {
            sb.append("Scan for Digital Receipt & Verification:\n");
            sb.append(digitalReceiptUrl).append("\n");
        }
        sb.append("========================================\n");
        sb.append("       THANK YOU FOR YOUR PATRONAGE     \n");
        sb.append("========================================\n");

        String escposText = sb.toString();

        db.sql("""
            INSERT INTO pos_receipt (
                id, receipt_number, order_id, digital_receipt_url,
                qr_payload, escpos_formatted_text, created_at
            ) VALUES (
                :id, :receiptNumber, :orderId, :digitalReceiptUrl,
                :qrPayload, :escposText, now()
            )
            """)
            .param("id", receiptId)
            .param("receiptNumber", receiptNumber)
            .param("orderId", orderId)
            .param("digitalReceiptUrl", digitalReceiptUrl)
            .param("qrPayload", qrPayload)
            .param("escposText", escposText)
            .update();

        db.sql("""
                INSERT INTO pos_receipt_public_token
                    (receipt_id, token_hash, expires_at, revoked, created_at)
                VALUES (:receipt, :hash, now() + (:ttl * interval '1 day'), false, now())
                """).param("receipt", receiptId).param("hash", receiptTokenHash(token))
                .param("ttl", receiptTtlDays).update();
        return getReceipt(receiptId).orElseThrow();
    }

    public Optional<PosReceipt> getReceipt(UUID id) {
        return db.sql("""
            SELECT id, receipt_number, order_id, digital_receipt_url,
                   qr_payload, escpos_formatted_text, created_at
            FROM pos_receipt WHERE id = :id
            """)
            .param("id", id)
            .query(PosReceipt.class)
            .optional();
    }

    public Optional<PosReceipt> findByOrderId(UUID orderId) {
        return db.sql("""
            SELECT id, receipt_number, order_id, digital_receipt_url,
                   qr_payload, escpos_formatted_text, created_at
            FROM pos_receipt WHERE order_id = :orderId
            ORDER BY created_at ASC, id ASC LIMIT 1
            """)
            .param("orderId", orderId)
            .query(PosReceipt.class)
            .optional();
    }

    static String receiptTokenHash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String formatMinorUnits(long pesewas) {
        long whole = pesewas / 100L;
        long frac = Math.abs(pesewas % 100L);
        return String.format("%d.%02d", whole, frac);
    }
}
