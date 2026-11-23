package com.elepay.merchanttools.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 5: Invoice and Quote generation with embedded payment links,
 * credit notes, and automatic posting to the Merchant General Ledger.
 */
@Service
public class InvoiceAndQuoteService {

    private final JdbcClient db;
    private final MerchantGeneralLedgerService glService;

    public InvoiceAndQuoteService(JdbcClient db, MerchantGeneralLedgerService glService) {
        this.db = db;
        this.glService = glService;
    }

    public record InvoiceItemCommand(
            String description,
            BigDecimal quantity,
            long unitPricePesewas,
            int taxRateBps,
            long discountPesewas
    ) {
        public InvoiceItemCommand(String description, double quantity, long unitPricePesewas, int taxRateBps, long discountPesewas) {
            this(description, BigDecimal.valueOf(quantity), unitPricePesewas, taxRateBps, discountPesewas);
        }
    }

    public record CreateInvoiceCommand(
            UUID merchantId,
            String invoiceNumber,
            UUID quoteId,
            UUID customerId,
            String customerName,
            String customerEmail,
            String customerPhone,
            String customerTin,
            LocalDate issueDate,
            LocalDate dueDate,
            String currency,
            List<InvoiceItemCommand> items,
            String notes
    ) {}

    public record InvoiceSummary(
            UUID id,
            UUID merchantId,
            String invoiceNumber,
            String customerName,
            LocalDate issueDate,
            LocalDate dueDate,
            String currency,
            long subtotalPesewas,
            long taxPesewas,
            long discountPesewas,
            long totalPesewas,
            long paidAmountPesewas,
            String status,
            String paymentLinkUrl,
            String graSdcReceiptNumber
    ) {}

    public record QuoteSummary(
            UUID id,
            UUID merchantId,
            String quoteNumber,
            String customerName,
            LocalDate issueDate,
            LocalDate expiryDate,
            long totalPesewas,
            String status,
            UUID convertedInvoiceId
    ) {}

    public record CreditNoteSummary(
            UUID id,
            UUID merchantId,
            String creditNoteNumber,
            UUID invoiceId,
            long amountPesewas,
            String reason,
            String status
    ) {}

    /**
     * Creates an estimate / quote.
     */
    @Transactional
    public QuoteSummary createQuote(UUID merchantId, String quoteNumber, UUID customerId,
                                    String customerName, String customerEmail, String customerPhone,
                                    LocalDate issueDate, LocalDate expiryDate,
                                    List<InvoiceItemCommand> items, String notes) {
        UUID quoteId = UUID.randomUUID();
        long subtotal = 0;
        long totalTax = 0;

        for (InvoiceItemCommand item : items) {
            BigDecimal qty = item.quantity() != null ? item.quantity() : BigDecimal.ONE;
            long lineSubtotal = qty.multiply(BigDecimal.valueOf(item.unitPricePesewas()))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            long lineTax = Math.multiplyExact(lineSubtotal, item.taxRateBps()) / 10000L;
            subtotal = Math.addExact(subtotal, lineSubtotal);
            totalTax = Math.addExact(totalTax, lineTax);
        }

        long grandTotal = Math.addExact(subtotal, totalTax);

        db.sql("""
            INSERT INTO merchant_quote
            (id, merchant_id, quote_number, customer_id, customer_name, customer_email, customer_phone,
             issue_date, expiry_date, currency, subtotal_pesewas, tax_pesewas, total_pesewas, status, notes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'GHS', ?, ?, ?, 'DRAFT', ?)
        """).params(quoteId, merchantId, quoteNumber, customerId, customerName, customerEmail,
                customerPhone, issueDate, expiryDate, subtotal, totalTax, grandTotal, notes)
            .update();

        for (InvoiceItemCommand item : items) {
            UUID itemId = UUID.randomUUID();
            BigDecimal qty = item.quantity() != null ? item.quantity() : BigDecimal.ONE;
            long lineSubtotal = qty.multiply(BigDecimal.valueOf(item.unitPricePesewas()))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            long lineTax = Math.multiplyExact(lineSubtotal, item.taxRateBps()) / 10000L;
            long lineTotal = Math.addExact(lineSubtotal, lineTax);
            db.sql("""
                INSERT INTO merchant_quote_item
                (id, quote_id, item_description, quantity, unit_price_pesewas, tax_rate_bps, total_pesewas)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """).params(itemId, quoteId, item.description(), qty, item.unitPricePesewas(),
                    item.taxRateBps(), lineTotal)
            .update();
        }

        return new QuoteSummary(quoteId, merchantId, quoteNumber, customerName, issueDate, expiryDate, grandTotal, "DRAFT", null);
    }

    /**
     * Creates a customer invoice, generates an embedded payment link, and posts to GL. No GRA API is connected.
     */
    @Transactional
    public InvoiceSummary createInvoice(CreateInvoiceCommand cmd) {
        UUID invoiceId = UUID.randomUUID();
        String paymentLinkToken = UUID.randomUUID().toString().replace("-", "");
        String currency = cmd.currency() != null ? cmd.currency() : "GHS";

        long subtotal = 0;
        long totalTax = 0;
        long totalDiscount = 0;

        for (InvoiceItemCommand item : cmd.items()) {
            BigDecimal qty = item.quantity() != null ? item.quantity() : BigDecimal.ONE;
            long lineSubtotal = qty.multiply(BigDecimal.valueOf(item.unitPricePesewas()))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            long lineDiscount = item.discountPesewas();
            long lineTaxable = Math.max(0, lineSubtotal - lineDiscount);
            long lineTax = Math.multiplyExact(lineTaxable, item.taxRateBps()) / 10000L;

            subtotal = Math.addExact(subtotal, lineSubtotal);
            totalDiscount = Math.addExact(totalDiscount, lineDiscount);
            totalTax = Math.addExact(totalTax, lineTax);
        }

        long grandTotal = Math.addExact(Math.max(0, subtotal - totalDiscount), totalTax);

        db.sql("""
            INSERT INTO merchant_invoice
            (id, merchant_id, invoice_number, quote_id, customer_id, customer_name, customer_email,
             customer_phone, customer_tin, issue_date, due_date, currency, subtotal_pesewas, tax_pesewas,
             discount_pesewas, total_pesewas, paid_amount_pesewas, status, payment_link_token, notes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'ISSUED', ?, ?)
        """).params(invoiceId, cmd.merchantId(), cmd.invoiceNumber(), cmd.quoteId(), cmd.customerId(),
                cmd.customerName(), cmd.customerEmail(), cmd.customerPhone(), cmd.customerTin(),
                cmd.issueDate(), cmd.dueDate(), currency, subtotal, totalTax, totalDiscount,
                grandTotal, paymentLinkToken, cmd.notes())
        .update();

        for (InvoiceItemCommand item : cmd.items()) {
            UUID itemId = UUID.randomUUID();
            BigDecimal qty = item.quantity() != null ? item.quantity() : BigDecimal.ONE;
            long lineSubtotal = qty.multiply(BigDecimal.valueOf(item.unitPricePesewas()))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            long lineTaxable = Math.max(0, lineSubtotal - item.discountPesewas());
            long lineTax = Math.multiplyExact(lineTaxable, item.taxRateBps()) / 10000L;
            long lineTotal = Math.addExact(lineTaxable, lineTax);

            db.sql("""
                INSERT INTO merchant_invoice_item
                (id, invoice_id, item_description, quantity, unit_price_pesewas, tax_rate_bps, discount_pesewas, total_pesewas)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """).params(itemId, invoiceId, item.description(), qty, item.unitPricePesewas(),
                    item.taxRateBps(), item.discountPesewas(), lineTotal)
            .update();
        }

        // GRA integration NOT IMPLEMENTED - manual submission required.
        // Do not generate fiscal certification, a GRA acknowledgment, or a false submission status.

        // Post to General Ledger:
        // Debit: Accounts Receivable (1040) -> totalPesewas
        // Credit: Operating Sales Revenue (4010) -> (subtotal - discount)
        // Credit: GRA e-VAT & Levies Payable (2020) -> totalTax
        var arAcc = glService.getAccountByCode(cmd.merchantId(), "1040");
        var revAcc = glService.getAccountByCode(cmd.merchantId(), "4010");
        var vatAcc = glService.getAccountByCode(cmd.merchantId(), "2020");

        if (arAcc.isPresent() && revAcc.isPresent() && grandTotal > 0) {
            List<MerchantGeneralLedgerService.JournalLineCommand> glLines = new ArrayList<>();
            glLines.add(new MerchantGeneralLedgerService.JournalLineCommand(arAcc.get().id(), grandTotal, 0L, "Invoice AR: " + cmd.invoiceNumber()));

            long netRevenue = Math.max(0, subtotal - totalDiscount);
            if (totalTax > 0 && vatAcc.isPresent()) {
                glLines.add(new MerchantGeneralLedgerService.JournalLineCommand(revAcc.get().id(), 0L, netRevenue, "Sales Revenue: " + cmd.invoiceNumber()));
                glLines.add(new MerchantGeneralLedgerService.JournalLineCommand(vatAcc.get().id(), 0L, totalTax, "GRA e-VAT: " + cmd.invoiceNumber()));
            } else {
                glLines.add(new MerchantGeneralLedgerService.JournalLineCommand(revAcc.get().id(), 0L, grandTotal, "Sales Revenue: " + cmd.invoiceNumber()));
            }

            glService.postJournalEntry(new MerchantGeneralLedgerService.PostJournalEntryCommand(
                    cmd.merchantId(),
                    "INV-GL-" + cmd.invoiceNumber(),
                    cmd.issueDate(),
                    "Sales Invoice: " + cmd.invoiceNumber(),
                    "INVOICE",
                    invoiceId,
                    currency,
                    glLines
            ));
        }

        // No payment page exists for invoices yet; never show a link that does not work.
        String paymentUrl = null;
        return new InvoiceSummary(invoiceId, cmd.merchantId(), cmd.invoiceNumber(), cmd.customerName(),
                cmd.issueDate(), cmd.dueDate(), currency, subtotal, totalTax, totalDiscount,
                grandTotal, 0L, "ISSUED", paymentUrl, null);
    }

    /**
     * Converts an accepted quote into a finalized invoice.
     */
    @Transactional
    public InvoiceSummary convertQuoteToInvoice(UUID merchantId, UUID quoteId, String invoiceNumber, LocalDate dueDate) {
        var quote = db.sql("""
            SELECT id, quote_number, customer_id, customer_name, customer_email, customer_phone, currency, notes
            FROM merchant_quote WHERE id = ? AND merchant_id = ? FOR UPDATE
        """).params(quoteId, merchantId)
        .query((rs, rowNum) -> Map.of(
                "customer_id", rs.getObject("customer_id") != null ? (UUID) rs.getObject("customer_id") : UUID.randomUUID(),
                "customer_name", rs.getString("customer_name"),
                "customer_email", rs.getString("customer_email") != null ? rs.getString("customer_email") : "",
                "customer_phone", rs.getString("customer_phone") != null ? rs.getString("customer_phone") : "",
                "currency", rs.getString("currency"),
                "notes", rs.getString("notes") != null ? rs.getString("notes") : ""
        )).single();

        var quoteItems = db.sql("""
            SELECT item_description, quantity, unit_price_pesewas, tax_rate_bps
            FROM merchant_quote_item WHERE quote_id = ?
        """).param(quoteId)
        .query((rs, rowNum) -> new InvoiceItemCommand(
                rs.getString("item_description"),
                rs.getBigDecimal("quantity"),
                rs.getLong("unit_price_pesewas"),
                rs.getInt("tax_rate_bps"),
                0L
        )).list();

        InvoiceSummary inv = createInvoice(new CreateInvoiceCommand(
                merchantId,
                invoiceNumber,
                quoteId,
                (UUID) quote.get("customer_id"),
                (String) quote.get("customer_name"),
                (String) quote.get("customer_email"),
                (String) quote.get("customer_phone"),
                null,
                LocalDate.now(),
                dueDate,
                (String) quote.get("currency"),
                quoteItems,
                (String) quote.get("notes")
        ));

        db.sql("UPDATE merchant_quote SET status = 'CONVERTED', converted_invoice_id = ? WHERE id = ?")
                .params(inv.id(), quoteId).update();

        return inv;
    }

    /**
     * Records customer payment against an invoice and updates General Ledger.
     */
    @Transactional
    public InvoiceSummary recordPayment(UUID merchantId, UUID invoiceId, long paymentAmountPesewas,
                                       String paymentMethodAccountCode) {
        var inv = db.sql("""
            SELECT id, invoice_number, customer_name, issue_date, due_date, currency, subtotal_pesewas,
                   tax_pesewas, discount_pesewas, total_pesewas, paid_amount_pesewas, status, payment_link_token,
                   gra_sdc_receipt_number
            FROM merchant_invoice WHERE id = ? AND merchant_id = ? FOR UPDATE
        """).params(invoiceId, merchantId)
        .query((rs, rowNum) -> new InvoiceSummary(
                rs.getObject("id", UUID.class),
                merchantId,
                rs.getString("invoice_number"),
                rs.getString("customer_name"),
                rs.getDate("issue_date").toLocalDate(),
                rs.getDate("due_date").toLocalDate(),
                rs.getString("currency"),
                rs.getLong("subtotal_pesewas"),
                rs.getLong("tax_pesewas"),
                rs.getLong("discount_pesewas"),
                rs.getLong("total_pesewas"),
                rs.getLong("paid_amount_pesewas"),
                rs.getString("status"),
                null,
                rs.getString("gra_sdc_receipt_number")
        )).single();

        long newPaid = inv.paidAmountPesewas() + paymentAmountPesewas;
        String newStatus = newPaid >= inv.totalPesewas() ? "PAID" : "PARTIALLY_PAID";

        db.sql("UPDATE merchant_invoice SET paid_amount_pesewas = ?, status = ?, updated_at = now() WHERE id = ?")
                .params(newPaid, newStatus, invoiceId).update();

        // GL Posting for payment collection:
        // Debit: Cash (1010) or Bank (1020) or MoMo Float (1030)
        // Credit: Accounts Receivable (1040)
        String cashCode = paymentMethodAccountCode != null ? paymentMethodAccountCode : "1020"; // default operating bank
        var cashAcc = glService.getAccountByCode(merchantId, cashCode);
        var arAcc = glService.getAccountByCode(merchantId, "1040");

        if (cashAcc.isPresent() && arAcc.isPresent()) {
            glService.postJournalEntry(new MerchantGeneralLedgerService.PostJournalEntryCommand(
                    merchantId,
                    "PAY-GL-" + inv.invoiceNumber() + "-" + System.currentTimeMillis(),
                    LocalDate.now(),
                    "Invoice Payment: " + inv.invoiceNumber(),
                    "INVOICE_PAYMENT",
                    invoiceId,
                    inv.currency(),
                    List.of(
                            new MerchantGeneralLedgerService.JournalLineCommand(cashAcc.get().id(), paymentAmountPesewas, 0L, "Payment Received: " + inv.invoiceNumber()),
                            new MerchantGeneralLedgerService.JournalLineCommand(arAcc.get().id(), 0L, paymentAmountPesewas, "Clear AR: " + inv.invoiceNumber())
                    )
            ));
        }

        return new InvoiceSummary(inv.id(), merchantId, inv.invoiceNumber(), inv.customerName(),
                inv.issueDate(), inv.dueDate(), inv.currency(), inv.subtotalPesewas(), inv.taxPesewas(),
                inv.discountPesewas(), inv.totalPesewas(), newPaid, newStatus, inv.paymentLinkUrl(),
                inv.graSdcReceiptNumber());
    }

    /**
     * Issues a Credit Note against an invoice to adjust billing.
     */
    @Transactional
    public CreditNoteSummary issueCreditNote(UUID merchantId, UUID invoiceId, String creditNoteNumber,
                                            long creditAmountPesewas, String reason) {
        UUID creditNoteId = UUID.randomUUID();
        db.sql("""
            INSERT INTO merchant_credit_note
            (id, merchant_id, credit_note_number, invoice_id, reason, amount_pesewas, status)
            VALUES (?, ?, ?, ?, ?, ?, 'ISSUED')
        """).params(creditNoteId, merchantId, creditNoteNumber, invoiceId, reason, creditAmountPesewas)
        .update();

        // Reduce invoice total/paid or apply credit
        db.sql("""
            UPDATE merchant_invoice
            SET total_pesewas = GREATEST(0, total_pesewas - ?), updated_at = now()
            WHERE id = ?
        """).params(creditAmountPesewas, invoiceId).update();

        // GL Posting:
        // Debit: Sales Revenue (4010) -> creditAmountPesewas
        // Credit: Accounts Receivable (1040) -> creditAmountPesewas
        var revAcc = glService.getAccountByCode(merchantId, "4010");
        var arAcc = glService.getAccountByCode(merchantId, "1040");

        if (revAcc.isPresent() && arAcc.isPresent()) {
            glService.postJournalEntry(new MerchantGeneralLedgerService.PostJournalEntryCommand(
                    merchantId,
                    "CRN-GL-" + creditNoteNumber,
                    LocalDate.now(),
                    "Credit Note: " + reason,
                    "CREDIT_NOTE",
                    creditNoteId,
                    "GHS",
                    List.of(
                            new MerchantGeneralLedgerService.JournalLineCommand(revAcc.get().id(), creditAmountPesewas, 0L, "Sales Credit Note: " + creditNoteNumber),
                            new MerchantGeneralLedgerService.JournalLineCommand(arAcc.get().id(), 0L, creditAmountPesewas, "Reduce AR: " + creditNoteNumber)
                    )
            ));
        }

        return new CreditNoteSummary(creditNoteId, merchantId, creditNoteNumber, invoiceId,
                creditAmountPesewas, reason, "ISSUED");
    }

    public List<InvoiceSummary> listInvoices(UUID merchantId) {
        return db.sql("""
            SELECT id, merchant_id, invoice_number, customer_name, issue_date, due_date, currency, subtotal_pesewas,
                   tax_pesewas, discount_pesewas, total_pesewas, paid_amount_pesewas, status,
                   NULL AS payment_link_url, gra_sdc_receipt_number
            FROM merchant_invoice WHERE merchant_id = ? ORDER BY issue_date DESC, invoice_number DESC
            """).param(merchantId).query(InvoiceSummary.class).list();
    }

    public List<QuoteSummary> listQuotes(UUID merchantId) {
        return db.sql("""
            SELECT id, merchant_id, quote_number, customer_name, issue_date, expiry_date, total_pesewas, status,
                   converted_invoice_id
            FROM merchant_quote WHERE merchant_id = ? ORDER BY issue_date DESC, quote_number DESC
            """).param(merchantId).query(QuoteSummary.class).list();
    }

    /** Next document number per merchant and type, e.g. INV-0001 or QTE-0001. */
    public String nextNumber(UUID merchantId, String prefix) {
        boolean invoice = "INV".equals(prefix);
        String table = invoice ? "merchant_invoice" : "merchant_quote";
        String column = invoice ? "invoice_number" : "quote_number";
        long count = db.sql("SELECT count(*) FROM " + table + " WHERE merchant_id = ?").param(merchantId)
                .query(Long.class).single();
        String candidate;
        do {
            count++;
            candidate = String.format("%s-%04d", invoice ? "INV" : "QTE", count);
        } while (db.sql("SELECT count(*) FROM " + table + " WHERE merchant_id = ? AND " + column + " = ?")
                .params(merchantId, candidate).query(Long.class).single() > 0);
        return candidate;
    }
}
