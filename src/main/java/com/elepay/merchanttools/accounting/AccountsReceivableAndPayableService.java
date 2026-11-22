package com.elepay.merchanttools.accounting;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Domain 5: Accounts Receivable (AR) & Accounts Payable (AP) Aging and Tracking Service.
 */
@Service
public class AccountsReceivableAndPayableService {

    private final JdbcClient db;

    public AccountsReceivableAndPayableService(JdbcClient db) {
        this.db = db;
    }

    public record AgingBucket(
            long currentPesewas,
            long days1To30Pesewas,
            long days31To60Pesewas,
            long days61To90Pesewas,
            long daysOver90Pesewas,
            long totalOutstandingPesewas
    ) {}

    public record ArAgingReport(
            UUID merchantId,
            LocalDate asOfDate,
            AgingBucket aging,
            int totalUnpaidInvoicesCount
    ) {}

    public record ApAgingReport(
            UUID merchantId,
            LocalDate asOfDate,
            AgingBucket aging,
            int totalUnpaidBillsCount
    ) {}

    public record OutstandingInvoice(
            UUID invoiceId,
            String invoiceNumber,
            String customerName,
            LocalDate dueDate,
            long outstandingPesewas,
            long daysOverdue
    ) {}

    public record OutstandingBill(
            UUID billId,
            String billNumber,
            String supplierName,
            LocalDate dueDate,
            long outstandingPesewas,
            long daysOverdue
    ) {}

    /**
     * Computes Accounts Receivable (AR) aging report as of a specified date.
     */
    public ArAgingReport getArAgingReport(UUID merchantId, LocalDate asOfDate) {
        var invoices = db.sql("""
            SELECT id, invoice_number, customer_name, due_date, total_pesewas, paid_amount_pesewas
            FROM merchant_invoice
            WHERE merchant_id = ? AND status IN ('SENT', 'PARTIALLY_PAID', 'OVERDUE')
        """).params(merchantId)
        .query((rs, rowNum) -> new OutstandingInvoice(
                rs.getObject("id", UUID.class),
                rs.getString("invoice_number"),
                rs.getString("customer_name"),
                rs.getDate("due_date").toLocalDate(),
                rs.getLong("total_pesewas") - rs.getLong("paid_amount_pesewas"),
                ChronoUnit.DAYS.between(rs.getDate("due_date").toLocalDate(), asOfDate)
        )).list();

        long current = 0;
        long d1_30 = 0;
        long d31_60 = 0;
        long d61_90 = 0;
        long dOver90 = 0;

        for (var inv : invoices) {
            long remaining = Math.max(0, inv.outstandingPesewas());
            long days = inv.daysOverdue();

            if (days <= 0) {
                current += remaining;
            } else if (days <= 30) {
                d1_30 += remaining;
            } else if (days <= 60) {
                d31_60 += remaining;
            } else if (days <= 90) {
                d61_90 += remaining;
            } else {
                dOver90 += remaining;
            }
        }

        long total = current + d1_30 + d31_60 + d61_90 + dOver90;
        AgingBucket bucket = new AgingBucket(current, d1_30, d31_60, d61_90, dOver90, total);

        return new ArAgingReport(merchantId, asOfDate, bucket, invoices.size());
    }

    /**
     * Computes Accounts Payable (AP) aging report as of a specified date.
     */
    public ApAgingReport getApAgingReport(UUID merchantId, LocalDate asOfDate) {
        var bills = db.sql("""
            SELECT b.id, b.bill_number, s.name as supplier_name, b.due_date, b.total_amount_pesewas, b.paid_amount_pesewas
            FROM supplier_bill b
            JOIN supplier_master s ON b.supplier_id = s.id
            WHERE b.merchant_id = ? AND b.status IN ('PENDING_APPROVAL', 'APPROVED', 'PARTIALLY_PAID')
        """).params(merchantId)
        .query((rs, rowNum) -> new OutstandingBill(
                rs.getObject("id", UUID.class),
                rs.getString("bill_number"),
                rs.getString("supplier_name"),
                rs.getDate("due_date").toLocalDate(),
                rs.getLong("total_amount_pesewas") - rs.getLong("paid_amount_pesewas"),
                ChronoUnit.DAYS.between(rs.getDate("due_date").toLocalDate(), asOfDate)
        )).list();

        long current = 0;
        long d1_30 = 0;
        long d31_60 = 0;
        long d61_90 = 0;
        long dOver90 = 0;

        for (var bill : bills) {
            long remaining = Math.max(0, bill.outstandingPesewas());
            long days = bill.daysOverdue();

            if (days <= 0) {
                current += remaining;
            } else if (days <= 30) {
                d1_30 += remaining;
            } else if (days <= 60) {
                d31_60 += remaining;
            } else if (days <= 90) {
                d61_90 += remaining;
            } else {
                dOver90 += remaining;
            }
        }

        long total = current + d1_30 + d31_60 + d61_90 + dOver90;
        AgingBucket bucket = new AgingBucket(current, d1_30, d31_60, d61_90, dOver90, total);

        return new ApAgingReport(merchantId, asOfDate, bucket, bills.size());
    }
}
