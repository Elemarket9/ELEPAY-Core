package com.elepay.merchanttools.accounting;

import com.elepay.common.ApiException;
import com.elepay.merchanttools.MerchantToolAccess;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

import static com.elepay.merchanttools.MerchantToolAccess.Resource.*;

/** Invoices and quotes the merchant issues to its own customers. ELEPAY records them; it does not deliver them. */
@RestController
@RequestMapping("/v1/invoicing")
public class InvoiceController {
    private final MerchantToolAccess access;
    private final InvoiceAndQuoteService invoices;
    private final MerchantGeneralLedgerService ledger;

    public InvoiceController(MerchantToolAccess access, InvoiceAndQuoteService invoices, MerchantGeneralLedgerService ledger) {
        this.access = access;
        this.invoices = invoices;
        this.ledger = ledger;
    }

    public record Line(String description, java.math.BigDecimal quantity, long unitPricePesewas, Integer taxRateBps,
                       Long discountPesewas) {}

    public record DocumentRequest(UUID merchantId, String customerName, String customerPhone, String customerEmail,
                                  String customerTin, LocalDate issueDate, LocalDate dueDate, List<Line> items, String notes) {}

    @GetMapping("/invoices/merchant/{merchantId}")
    public List<InvoiceAndQuoteService.InvoiceSummary> listInvoices(@PathVariable UUID merchantId) {
        access.manage(merchantId);
        return invoices.listInvoices(merchantId);
    }

    @GetMapping("/quotes/merchant/{merchantId}")
    public List<InvoiceAndQuoteService.QuoteSummary> listQuotes(@PathVariable UUID merchantId) {
        access.manage(merchantId);
        return invoices.listQuotes(merchantId);
    }

    @PostMapping("/invoices")
    public InvoiceAndQuoteService.InvoiceSummary createInvoice(@RequestBody DocumentRequest req) {
        access.manage(req.merchantId());
        validate(req);
        ledger.initializeStandardChartOfAccounts(req.merchantId());
        LocalDate issue = req.issueDate() == null ? LocalDate.now() : req.issueDate();
        LocalDate due = req.dueDate() == null ? issue.plusDays(14) : req.dueDate();
        if (due.isBefore(issue)) throw ApiException.badRequest("due_date_invalid", "Due date cannot be before the issue date");
        return invoices.createInvoice(new InvoiceAndQuoteService.CreateInvoiceCommand(req.merchantId(),
                invoices.nextNumber(req.merchantId(), "INV"), null, null, req.customerName().strip(), blank(req.customerEmail()),
                blank(req.customerPhone()), blank(req.customerTin()), issue, due, "GHS", lines(req), blank(req.notes())));
    }

    @PostMapping("/quotes")
    public InvoiceAndQuoteService.QuoteSummary createQuote(@RequestBody DocumentRequest req) {
        access.manage(req.merchantId());
        validate(req);
        LocalDate issue = req.issueDate() == null ? LocalDate.now() : req.issueDate();
        LocalDate expiry = req.dueDate() == null ? issue.plusDays(30) : req.dueDate();
        return invoices.createQuote(req.merchantId(), invoices.nextNumber(req.merchantId(), "QTE"), null,
                req.customerName().strip(), blank(req.customerEmail()), blank(req.customerPhone()), issue, expiry,
                lines(req), blank(req.notes()));
    }

    public record ConvertRequest(LocalDate dueDate) {}

    @PostMapping("/quotes/{quoteId}/convert")
    public InvoiceAndQuoteService.InvoiceSummary convert(@PathVariable UUID quoteId, @RequestBody(required = false) ConvertRequest req) {
        UUID merchantId = access.manage(QUOTE, quoteId);
        ledger.initializeStandardChartOfAccounts(merchantId);
        LocalDate due = req == null || req.dueDate() == null ? LocalDate.now().plusDays(14) : req.dueDate();
        return invoices.convertQuoteToInvoice(merchantId, quoteId, invoices.nextNumber(merchantId, "INV"), due);
    }

    public enum PaymentMethod { CASH, BANK, MOMO }

    public record PaymentRequest(long amountPesewas, PaymentMethod method) {}

    /** Records money the merchant already received outside ELEPAY (cash, bank transfer, MoMo to their own number). */
    @PostMapping("/invoices/{invoiceId}/payments")
    public InvoiceAndQuoteService.InvoiceSummary recordPayment(@PathVariable UUID invoiceId, @RequestBody PaymentRequest req) {
        UUID merchantId = access.manage(INVOICE, invoiceId);
        if (req.amountPesewas() <= 0) throw ApiException.badRequest("amount_invalid", "Payment amount must be positive");
        if (req.method() == null) throw ApiException.badRequest("method_required", "Say how the payment was received");
        ledger.initializeStandardChartOfAccounts(merchantId);
        String account = switch (req.method()) { case CASH -> "1010"; case BANK -> "1020"; case MOMO -> "1030"; };
        return invoices.recordPayment(merchantId, invoiceId, req.amountPesewas(), account);
    }

    private static void validate(DocumentRequest req) {
        if (req.customerName() == null || req.customerName().isBlank())
            throw ApiException.badRequest("customer_required", "Customer name is required");
        if (req.items() == null || req.items().isEmpty())
            throw ApiException.badRequest("items_required", "Add at least one line");
        for (Line l : req.items()) {
            if (l.description() == null || l.description().isBlank() || l.unitPricePesewas() <= 0
                    || (l.quantity() != null && l.quantity().signum() <= 0))
                throw ApiException.badRequest("line_invalid", "Every line needs a description, a positive quantity and price");
            if (l.taxRateBps() != null && (l.taxRateBps() < 0 || l.taxRateBps() > 10_000))
                throw ApiException.badRequest("tax_invalid", "Tax rate must be between 0% and 100%");
        }
    }

    private static List<InvoiceAndQuoteService.InvoiceItemCommand> lines(DocumentRequest req) {
        return req.items().stream().map(l -> new InvoiceAndQuoteService.InvoiceItemCommand(l.description().strip(),
                l.quantity() == null ? java.math.BigDecimal.ONE : l.quantity(), l.unitPricePesewas(),
                l.taxRateBps() == null ? 0 : l.taxRateBps(), l.discountPesewas() == null ? 0L : l.discountPesewas())).toList();
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
