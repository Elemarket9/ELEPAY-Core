package com.elepay.merchanttools.accounting;

import com.elepay.PostgresTestBase;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AccountingAndInvoicingTest extends PostgresTestBase {

    @Autowired
    private MerchantGeneralLedgerService glService;

    @Autowired
    private InvoiceAndQuoteService invoiceService;

    @Autowired
    private AccountsReceivableAndPayableService arApService;

    @Test
    @DisplayName("Domain 5: Standard Ghanaian Chart of Accounts Initialization and Verification")
    void testStandardGhanaianChartOfAccounts() {
        UUID merchantId = UUID.randomUUID();
        glService.initializeStandardChartOfAccounts(merchantId);

        var accounts = glService.listAccounts(merchantId);
        assertThat(accounts).isNotEmpty();
        assertThat(accounts.size()).isGreaterThanOrEqualTo(20);

        var cash = glService.getAccountByCode(merchantId, "1010");
        assertThat(cash).isPresent();
        assertThat(cash.get().category()).isEqualTo(GlAccountCategory.ASSET);
        assertThat(cash.get().normalBalance()).isEqualTo(NormalBalance.DEBIT);

        var ar = glService.getAccountByCode(merchantId, "1040");
        assertThat(ar).isPresent();
        assertThat(ar.get().category()).isEqualTo(GlAccountCategory.ASSET);

        var ap = glService.getAccountByCode(merchantId, "2010");
        assertThat(ap).isPresent();
        assertThat(ap.get().category()).isEqualTo(GlAccountCategory.LIABILITY);
        assertThat(ap.get().normalBalance()).isEqualTo(NormalBalance.CREDIT);

        var vat = glService.getAccountByCode(merchantId, "2020");
        assertThat(vat).isPresent();

        var sales = glService.getAccountByCode(merchantId, "4010");
        assertThat(sales).isPresent();
        assertThat(sales.get().category()).isEqualTo(GlAccountCategory.REVENUE);
        assertThat(sales.get().normalBalance()).isEqualTo(NormalBalance.CREDIT);
    }

    @Test
    @DisplayName("Domain 5: Balanced Double-Entry Journal Postings and Imbalance Rejection")
    void testDoubleEntryPostings() {
        UUID merchantId = UUID.randomUUID();
        glService.initializeStandardChartOfAccounts(merchantId);

        var bank = glService.getAccountByCode(merchantId, "1020").orElseThrow();
        var sales = glService.getAccountByCode(merchantId, "4010").orElseThrow();

        // 1. Rejection of out-of-balance journal entry
        assertThatThrownBy(() -> glService.postJournalEntry(new MerchantGeneralLedgerService.PostJournalEntryCommand(
                merchantId,
                "IMBALANCED-01",
                LocalDate.now(),
                "Unbalanced sale",
                "MANUAL",
                null,
                "GHS",
                List.of(
                        new MerchantGeneralLedgerService.JournalLineCommand(bank.id(), 100_000L, 0L, "Bank debit"),
                        new MerchantGeneralLedgerService.JournalLineCommand(sales.id(), 0L, 80_000L, "Sales credit")
                )
        ))).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("out of balance");

        // 2. Successful balanced journal posting: Debit Bank GHS 1,000 (100,000 pesewas), Credit Sales GHS 1,000
        var res = glService.postJournalEntry(new MerchantGeneralLedgerService.PostJournalEntryCommand(
                merchantId,
                "BALANCED-01",
                LocalDate.now(),
                "Cash sale",
                "MANUAL",
                null,
                "GHS",
                List.of(
                        new MerchantGeneralLedgerService.JournalLineCommand(bank.id(), 100_000L, 0L, "Bank deposit"),
                        new MerchantGeneralLedgerService.JournalLineCommand(sales.id(), 0L, 100_000L, "Sales revenue")
                )
        ));

        assertThat(res.status()).isEqualTo("POSTED");
        assertThat(res.totalAmountPesewas()).isEqualTo(100_000L);

        // Verify account balance updates
        var updatedBank = glService.getAccountByCode(merchantId, "1020").orElseThrow();
        assertThat(updatedBank.balancePesewas()).isEqualTo(100_000L);

        var updatedSales = glService.getAccountByCode(merchantId, "4010").orElseThrow();
        assertThat(updatedSales.balancePesewas()).isEqualTo(100_000L);

        // 3. Test Journal Entry Reversal
        var reversal = glService.reverseJournalEntry(merchantId, res.id(), "Customer refunded");
        assertThat(reversal.status()).isEqualTo("POSTED");

        var revertedBank = glService.getAccountByCode(merchantId, "1020").orElseThrow();
        assertThat(revertedBank.balancePesewas()).isEqualTo(0L);

        var revertedSales = glService.getAccountByCode(merchantId, "4010").orElseThrow();
        assertThat(revertedSales.balancePesewas()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Domain 5: Complete Invoicing, Quote Conversion, Payment Links, and AR Aging")
    void testInvoicingQuoteAndArAging() {
        UUID merchantId = UUID.randomUUID();
        glService.initializeStandardChartOfAccounts(merchantId);

        // 1. Create Quote
        var quote = invoiceService.createQuote(
                merchantId,
                "QT-2026-001",
                UUID.randomUUID(),
                "Kofi Mensah",
                "kofi@example.com",
                "+233241234567",
                LocalDate.now(),
                LocalDate.now().plusDays(14),
                List.of(
                        new InvoiceAndQuoteService.InvoiceItemCommand("Solar Inverter 5kVA", 1, 500_000L, 2100, 0L),
                        new InvoiceAndQuoteService.InvoiceItemCommand("Lithium Battery 48V", 2, 300_000L, 2100, 0L)
                ),
                "Valid for 14 days"
        );

        assertThat(quote.status()).isEqualTo("DRAFT");
        assertThat(quote.totalPesewas()).isGreaterThan(1_100_000L);

        // 2. Convert Quote to Invoice
        var inv = invoiceService.convertQuoteToInvoice(
                merchantId,
                quote.id(),
                "INV-2026-001",
                LocalDate.now().plusDays(30)
        );

        assertThat(inv.status()).isEqualTo("SENT");
        assertThat(inv.paymentLinkUrl()).contains("https://pay.elepay.com/invoice/");
        assertThat(inv.graSdcReceiptNumber()).contains("SDC-GRA-GH");

        // Verify GL auto-posting for invoice issuance (Debit AR, Credit Revenue & VAT)
        var arAcc = glService.getAccountByCode(merchantId, "1040").orElseThrow();
        assertThat(arAcc.balancePesewas()).isEqualTo(inv.totalPesewas());

        // 3. Record Partial Payment
        long partialAmount = 500_000L;
        var paidInv = invoiceService.recordPayment(merchantId, inv.id(), partialAmount, "1020");
        assertThat(paidInv.status()).isEqualTo("PARTIALLY_PAID");
        assertThat(paidInv.paidAmountPesewas()).isEqualTo(partialAmount);

        // Verify GL auto-posting for payment: AR reduced, Bank increased
        var bankAcc = glService.getAccountByCode(merchantId, "1020").orElseThrow();
        assertThat(bankAcc.balancePesewas()).isEqualTo(partialAmount);

        var arUpdated = glService.getAccountByCode(merchantId, "1040").orElseThrow();
        assertThat(arUpdated.balancePesewas()).isEqualTo(inv.totalPesewas() - partialAmount);

        // 4. Issue Credit Note to adjust remaining balance
        long creditNoteAmt = 50_000L;
        var cn = invoiceService.issueCreditNote(merchantId, inv.id(), "CN-2026-001", creditNoteAmt, "Discount adjustment");
        assertThat(cn.status()).isEqualTo("ISSUED");

        // 5. Verify AR Aging Report
        var arReport = arApService.getArAgingReport(merchantId, LocalDate.now());
        assertThat(arReport.totalUnpaidInvoicesCount()).isEqualTo(1);
        assertThat(arReport.aging().currentPesewas()).isGreaterThan(0L);

        // 6. Generate Trial Balance & Financial Statements
        var tb = glService.generateTrialBalance(merchantId, LocalDate.now());
        assertThat(tb.isBalanced()).isTrue();
        assertThat(tb.totalDebitPesewas()).isEqualTo(tb.totalCreditPesewas());

        var pl = glService.generateProfitAndLoss(merchantId, LocalDate.now().minusDays(30), LocalDate.now().plusDays(30));
        assertThat(pl.totalRevenuePesewas()).isGreaterThan(0L);

        var bs = glService.generateBalanceSheet(merchantId, LocalDate.now());
        assertThat(bs.isBalanced()).isTrue();
    }
}
