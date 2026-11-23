package com.elepay.merchanttools.payroll;

import com.elepay.merchanttools.accounting.MerchantGeneralLedgerService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 6: Supplier Master Records, Supplier Bills (Accounts Payable),
 * Approval Workflows, and Automated Payment Runs.
 */
@Service
public class SupplierManagementService {

    private final JdbcClient db;
    private final MerchantGeneralLedgerService glService;

    public SupplierManagementService(JdbcClient db, MerchantGeneralLedgerService glService) {
        this.db = db;
        this.glService = glService;
    }

    public record SupplierRecord(
            UUID id,
            UUID merchantId,
            String supplierCode,
            String name,
            String contactPerson,
            String email,
            String phone,
            String tin,
            int paymentTermsDays,
            String defaultPaymentMethod,
            String bankCode,
            String bankAccountNumber,
            String momoNetwork,
            String momoNumber,
            boolean isActive
    ) {}

    public record CreateSupplierCommand(
            UUID merchantId,
            String supplierCode,
            String name,
            String contactPerson,
            String email,
            String phone,
            String tin,
            int paymentTermsDays,
            String defaultPaymentMethod,
            String bankCode,
            String bankAccountNumber,
            String momoNetwork,
            String momoNumber
    ) {}

    public record BillItemCommand(
            String description,
            java.math.BigDecimal quantity,
            long unitPricePesewas
    ) {
        public BillItemCommand(String description, double quantity, long unitPricePesewas) {
            this(description, java.math.BigDecimal.valueOf(quantity), unitPricePesewas);
        }
    }

    public record CreateBillCommand(
            UUID merchantId,
            UUID supplierId,
            String billNumber,
            LocalDate billDate,
            LocalDate dueDate,
            String currency,
            List<BillItemCommand> items
    ) {}

    public record SupplierBillSummary(
            UUID id,
            UUID merchantId,
            UUID supplierId,
            String supplierName,
            String billNumber,
            LocalDate billDate,
            LocalDate dueDate,
            String currency,
            long subtotalPesewas,
            long taxPesewas,
            long totalAmountPesewas,
            long paidAmountPesewas,
            String status,
            String approvedBy
    ) {}

    public record PaymentRunSummary(
            UUID id,
            UUID merchantId,
            String runNumber,
            long totalAmountPesewas,
            int billCount,
            String status,
            OffsetDateTime executedAt
    ) {}

    /**
     * Registers a new supplier in the merchant's supplier directory.
     */
    @Transactional
    public SupplierRecord registerSupplier(CreateSupplierCommand cmd) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO supplier_master
            (id, merchant_id, supplier_code, name, contact_person, email, phone, tin,
             payment_terms_days, default_payment_method, bank_code, bank_account_number,
             momo_network, momo_number, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true)
        """).params(id, cmd.merchantId(), cmd.supplierCode(), cmd.name(), cmd.contactPerson(),
                cmd.email(), cmd.phone(), cmd.tin(), cmd.paymentTermsDays(),
                cmd.defaultPaymentMethod() != null ? cmd.defaultPaymentMethod() : "BANK",
                cmd.bankCode(), cmd.bankAccountNumber(), cmd.momoNetwork(), cmd.momoNumber())
        .update();

        return new SupplierRecord(id, cmd.merchantId(), cmd.supplierCode(), cmd.name(), cmd.contactPerson(),
                cmd.email(), cmd.phone(), cmd.tin(), cmd.paymentTermsDays(),
                cmd.defaultPaymentMethod() != null ? cmd.defaultPaymentMethod() : "BANK",
                cmd.bankCode(), cmd.bankAccountNumber(), cmd.momoNetwork(), cmd.momoNumber(), true);
    }

    /**
     * Creates a supplier bill for inventory/supplies received.
     */
    @Transactional
    public SupplierBillSummary createBill(CreateBillCommand cmd) {
        UUID billId = UUID.randomUUID();
        long total = 0;

        for (BillItemCommand item : cmd.items()) {
            java.math.BigDecimal qty = item.quantity() != null ? item.quantity() : java.math.BigDecimal.ONE;
            long lineTotal = qty.multiply(java.math.BigDecimal.valueOf(item.unitPricePesewas()))
                    .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
            total = Math.addExact(total, lineTotal);
        }

        var supplier = db.sql("SELECT name FROM supplier_master WHERE id = ?").param(cmd.supplierId())
                .query(String.class).single();

        String currency = cmd.currency() != null ? cmd.currency() : "GHS";

        db.sql("""
            INSERT INTO supplier_bill
            (id, merchant_id, supplier_id, bill_number, bill_date, due_date, currency,
             subtotal_pesewas, tax_pesewas, total_amount_pesewas, paid_amount_pesewas, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, 'PENDING_APPROVAL')
        """).params(billId, cmd.merchantId(), cmd.supplierId(), cmd.billNumber(),
                cmd.billDate(), cmd.dueDate(), currency, total, total)
        .update();

        for (BillItemCommand item : cmd.items()) {
            UUID itemId = UUID.randomUUID();
            java.math.BigDecimal qty = item.quantity() != null ? item.quantity() : java.math.BigDecimal.ONE;
            long lineTotal = qty.multiply(java.math.BigDecimal.valueOf(item.unitPricePesewas()))
                    .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
            db.sql("""
                INSERT INTO supplier_bill_item
                (id, bill_id, description, quantity, unit_price_pesewas, total_pesewas)
                VALUES (?, ?, ?, ?, ?, ?)
            """).params(itemId, billId, item.description(), qty, item.unitPricePesewas(), lineTotal)
            .update();
        }

        return new SupplierBillSummary(billId, cmd.merchantId(), cmd.supplierId(), supplier,
                cmd.billNumber(), cmd.billDate(), cmd.dueDate(), currency, total, 0L, total, 0L,
                "PENDING_APPROVAL", null);
    }

    /**
     * Approves a supplier bill and posts to General Ledger (Debit COGS/Expense, Credit Accounts Payable).
     */
    @Transactional
    public SupplierBillSummary approveBill(UUID merchantId, UUID billId, String approvedBy) {
        var bill = db.sql("""
            SELECT b.id, b.merchant_id, b.supplier_id, s.name as supplier_name, b.bill_number,
                   b.bill_date, b.due_date, b.currency, b.subtotal_pesewas, b.tax_pesewas,
                   b.total_amount_pesewas, b.paid_amount_pesewas, b.status
            FROM supplier_bill b
            JOIN supplier_master s ON b.supplier_id = s.id
            WHERE b.id = ? AND b.merchant_id = ? FOR UPDATE
        """).params(billId, merchantId)
        .query((rs, rowNum) -> new SupplierBillSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getString("bill_number"),
                rs.getDate("bill_date").toLocalDate(),
                rs.getDate("due_date").toLocalDate(),
                rs.getString("currency"),
                rs.getLong("subtotal_pesewas"),
                rs.getLong("tax_pesewas"),
                rs.getLong("total_amount_pesewas"),
                rs.getLong("paid_amount_pesewas"),
                rs.getString("status"),
                null
        )).single();

        OffsetDateTime now = OffsetDateTime.now();
        db.sql("UPDATE supplier_bill SET status = 'APPROVED', approved_by = ?, approved_at = ? WHERE id = ?")
                .params(approvedBy, now, billId).update();

        // GL Posting:
        // Debit: Cost of Goods Sold (5010)
        // Credit: Accounts Payable (2010)
        var cogsAcc = glService.getAccountByCode(merchantId, "5010");
        var apAcc = glService.getAccountByCode(merchantId, "2010");

        if (cogsAcc.isPresent() && apAcc.isPresent() && bill.totalAmountPesewas() > 0) {
            glService.postJournalEntry(new MerchantGeneralLedgerService.PostJournalEntryCommand(
                    merchantId,
                    "BILL-GL-" + bill.billNumber(),
                    bill.billDate(),
                    "Supplier Bill: " + bill.billNumber() + " (" + bill.supplierName() + ")",
                    "BILL",
                    billId,
                    bill.currency(),
                    List.of(
                            new MerchantGeneralLedgerService.JournalLineCommand(cogsAcc.get().id(), bill.totalAmountPesewas(), 0L, "COGS: " + bill.billNumber()),
                            new MerchantGeneralLedgerService.JournalLineCommand(apAcc.get().id(), 0L, bill.totalAmountPesewas(), "AP Trade: " + bill.billNumber())
                    )
            ));
        }

        return new SupplierBillSummary(bill.id(), merchantId, bill.supplierId(), bill.supplierName(),
                bill.billNumber(), bill.billDate(), bill.dueDate(), bill.currency(), bill.subtotalPesewas(),
                bill.taxPesewas(), bill.totalAmountPesewas(), bill.paidAmountPesewas(), "APPROVED", approvedBy);
    }

    /**
     * Executes an automated payment run for all approved supplier bills due on or before target date.
     * Generates payout allocations, updates bill paid status, and records GL settlement postings.
     */
    @Transactional
    /**
     * Collects approved bills due by the given date into a payment run awaiting payout. No money moves and no
     * bill is marked paid here: allocations stay PENDING without a payout reference until a real payout exists.
     */
    public PaymentRunSummary prepareSupplierPaymentRun(UUID merchantId, LocalDate dueOnOrBefore) {
        var eligibleBills = db.sql("""
            SELECT id, total_amount_pesewas, paid_amount_pesewas
            FROM supplier_bill
            WHERE merchant_id = ? AND status = 'APPROVED' AND due_date <= ?
              AND NOT EXISTS (SELECT 1 FROM supplier_payment_allocation a
                              WHERE a.bill_id = supplier_bill.id AND a.status = 'PENDING')
            FOR UPDATE
        """).params(merchantId, dueOnOrBefore)
        .query((rs, rowNum) -> Map.of(
                "id", (UUID) rs.getObject("id"),
                "payable", rs.getLong("total_amount_pesewas") - rs.getLong("paid_amount_pesewas")
        )).list();

        if (eligibleBills.isEmpty()) {
            return new PaymentRunSummary(null, merchantId, "NONE", 0L, 0, "NO_BILLS_DUE", null);
        }

        UUID runId = UUID.randomUUID();
        String runNumber = "SPAY-" + System.currentTimeMillis();
        long totalAmount = eligibleBills.stream().mapToLong(b -> (Long) b.get("payable")).sum();

        db.sql("""
            INSERT INTO supplier_payment_run
            (id, merchant_id, run_number, total_amount_pesewas, bill_count, status)
            VALUES (?, ?, ?, ?, ?, 'PENDING_PAYOUT')
        """).params(runId, merchantId, runNumber, totalAmount, eligibleBills.size())
        .update();

        for (var bill : eligibleBills) {
            db.sql("""
                INSERT INTO supplier_payment_allocation
                (id, payment_run_id, bill_id, amount_pesewas, payout_reference, status)
                VALUES (?, ?, ?, ?, NULL, 'PENDING')
            """).params(UUID.randomUUID(), runId, (UUID) bill.get("id"), (Long) bill.get("payable")).update();
        }

        return new PaymentRunSummary(runId, merchantId, runNumber, totalAmount, eligibleBills.size(), "PENDING_PAYOUT", null);
    }
}
