package com.elepay.merchanttools.pos;

import java.time.LocalDate;
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
    private final JdbcClient db;
    private final PosOrderService orderService;

    public PosReceiptService(JdbcClient db, PosOrderService orderService) {
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
        PosOrderService.PosOrder order = orderService.getOrder(orderId).orElseThrow();
        if (!"PAID".equals(order.status())) throw com.elepay.common.ApiException.conflict("order_unpaid", "Payment is not complete");
        List<PosOrderService.PosOrderItem> items = orderService.listOrderItems(orderId);
        List<PosOrderService.TenderPayment> payments = orderService.listTenderPayments(orderId);

        String receiptNumber = generateReceiptNumber();
        UUID receiptId = UUID.randomUUID();
        String digitalReceiptUrl = "https://receipts.elepay.com/r/" + receiptId;
        String qrPayload = "ELEPAY:RECEIPT:" + receiptNumber + ":AMT:" + order.totalPesewas() + ":URL:" + digitalReceiptUrl;

        // Build ESC/POS 80mm Plain Text format
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append("             ELEPAY COMMERCE            \n");
        sb.append("========================================\n");
        sb.append("Receipt No: ").append(receiptNumber).append("\n");
        sb.append("Order No  : ").append(order.orderNumber()).append("\n");
        sb.append("Date      : ").append(OffsetDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("\n");
        sb.append("----------------------------------------\n");
        sb.append(String.format("%-20s %4s %12s\n", "ITEM", "QTY", "TOTAL (GHS)"));
        sb.append("----------------------------------------\n");

        for (PosOrderService.PosOrderItem item : items) {
            sb.append(String.format("%-20.20s %4s %12s\n", item.itemName(), item.quantity().toPlainString(), formatMinorUnits(item.totalPesewas())));
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
        sb.append("Scan for Digital Receipt & Verification:\n");
        sb.append(digitalReceiptUrl).append("\n");
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
            LIMIT 1
            """)
            .param("orderId", orderId)
            .query(PosReceipt.class)
            .optional();
    }

    private static String formatMinorUnits(long pesewas) {
        long whole = pesewas / 100L;
        long frac = Math.abs(pesewas % 100L);
        return String.format("%d.%02d", whole, frac);
    }
}
