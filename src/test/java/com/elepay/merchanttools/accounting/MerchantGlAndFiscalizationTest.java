package com.elepay.merchanttools.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Domain 5: Unit and integration tests for Merchant General Ledger and GRA e-VAT Fiscalization.
 * Verifies that all calculations are strictly in integer minor units (pesewas/cents),
 * with externalized, configurable, and versioned GRA statutory tax parameters.
 */
class MerchantGlAndFiscalizationTest extends PostgresTestBase {

    @Autowired GraFiscalDataService fiscalService;
    @Autowired InvoiceAndQuoteService invoiceService;
    @Autowired MerchantGeneralLedgerService glService;
    @Autowired JdbcClient db;

    private UUID merchantId;

    @BeforeEach
    void setup() {
        merchantId = UUID.randomUUID();
        // Insert active merchant
        db.sql("""
                INSERT INTO merchant (id, legal_name, trading_name, kind, status, merchant_code, contact_phone, created_by)
                VALUES (?, 'Kofi Trading Ltd', 'Kofi Store', 'merchant', 'active', ?, '0241112233', 'test')
                """).params(merchantId, String.format("%06d", (int) (Math.random() * 900000) + 100000)).update();

        // Initialize Standard Chart of Accounts
        glService.initializeStandardChartOfAccounts(merchantId);
    }

    @Test
    void defaultFiscalParametersMatchGhanaianTaxLaw() {
        var config = fiscalService.getFiscalConfig(merchantId);
        assertThat(config.vatRateBps()).isEqualTo(1500);       // 15.0%
        assertThat(config.nhilRateBps()).isEqualTo(250);       // 2.5%
        assertThat(config.getfundRateBps()).isEqualTo(250);    // 2.5%
        assertThat(config.covidLevyRateBps()).isEqualTo(100); // 1.0%
        assertThat(config.totalTaxRateBps()).isEqualTo(2100);  // 21.0%
    }

    @Test
    void statutoryTaxCalculationStrictlyUsesIntegerMinorUnits() {
        // Base taxable amount: GHS 1000.00 = 100,000 pesewas
        long taxablePesewas = 100_000L;
        var breakdown = fiscalService.calculateStatutoryTaxes(taxablePesewas, merchantId);

        assertThat(breakdown.taxableAmountPesewas()).isEqualTo(100_000L);
        assertThat(breakdown.vatAmountPesewas()).isEqualTo(15_000L);       // 15%
        assertThat(breakdown.nhilAmountPesewas()).isEqualTo(2_500L);       // 2.5%
        assertThat(breakdown.getfundAmountPesewas()).isEqualTo(2_500L);    // 2.5%
        assertThat(breakdown.covidLevyAmountPesewas()).isEqualTo(1_000L);  // 1.0%
        assertThat(breakdown.totalTaxPesewas()).isEqualTo(21_000L);        // 21.0%
        assertThat(breakdown.grossTotalPesewas()).isEqualTo(121_000L);      // 100,000 + 21,000

        // Invariant: sum of statutory components exactly equals total tax
        assertThat(breakdown.vatAmountPesewas() + breakdown.nhilAmountPesewas() +
                breakdown.getfundAmountPesewas() + breakdown.covidLevyAmountPesewas())
                .isEqualTo(breakdown.totalTaxPesewas());
    }

    @Test
    void fiscalParametersCanBeExternalizedAndVersionedPerMerchant() {
        // Update merchant-specific fiscal configuration
        var updated = fiscalService.updateFiscalConfig(
                merchantId,
                1250, // Reduced VAT rate: 12.5%
                250,  // NHIL: 2.5%
                250,  // GETFund: 2.5%
                0,    // COVID Levy removed: 0.0%
                "https://sandbox.gra.gov.gh/vsdc/api/v2",
                3000,
                "GRA 2026 Tax Relief Scheme",
                "tax_auditor"
        );

        assertThat(updated.version()).isGreaterThan(1);
        assertThat(updated.vatRateBps()).isEqualTo(1250);
        assertThat(updated.covidLevyRateBps()).isZero();
        assertThat(updated.totalTaxRateBps()).isEqualTo(1750); // 17.5% total

        // Calculate with new rates: GHS 200.00 = 20,000 pesewas
        var breakdown = fiscalService.calculateStatutoryTaxes(20_000L, merchantId);
        // VAT = 20000 * 1250 / 10000 = 2500
        assertThat(breakdown.vatAmountPesewas()).isEqualTo(2_500L);
        // NHIL = 20000 * 250 / 10000 = 500
        assertThat(breakdown.nhilAmountPesewas()).isEqualTo(500L);
        // COVID = 0
        assertThat(breakdown.covidLevyAmountPesewas()).isZero();
        assertThat(breakdown.totalTaxPesewas()).isEqualTo(3_500L);
        assertThat(breakdown.grossTotalPesewas()).isEqualTo(23_500L);

        // Verify audit history record was persisted
        long historyCount = db.sql("SELECT count(*) FROM gra_fiscal_config_history WHERE merchant_id = ?")
                .param(merchantId).query(Long.class).single();
        assertThat(historyCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void invoiceCreationUsesBigDecimalQuantitiesAndPostsBalancedGL() {
        var item1 = new InvoiceAndQuoteService.InvoiceItemCommand(
                "Solar Panel 300W",
                new BigDecimal("2.5"), // fractional decimal quantity (e.g., 2.5 units or hours)
                40_000L,              // GHS 400.00 each = 40,000 pesewas
                2100,                 // 21% total tax rate
                5_000L                // GHS 50.00 discount
        );
        var item2 = new InvoiceAndQuoteService.InvoiceItemCommand(
                "Installation Labor",
                1.0,                  // double overload compatibility test
                15_000L,              // GHS 150.00
                2100,
                0L
        );

        var cmd = new InvoiceAndQuoteService.CreateInvoiceCommand(
                merchantId,
                "INV-2026-0099",
                null,
                null,
                "Ama Customer",
                "ama@example.com",
                "0241234567",
                "P0001234567",
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                "GHS",
                List.of(item1, item2),
                "Commercial solar install"
        );

        var invoice = invoiceService.createInvoice(cmd);

        // Item 1: 2.5 * 40,000 = 100,000 pesewas subtotal. Discount = 5,000. Taxable = 95,000. Tax = 95,000 * 21% = 19,950.
        // Item 2: 1.0 * 15,000 = 15,000 pesewas subtotal. Taxable = 15,000. Tax = 15,000 * 21% = 3,150.
        // Subtotal = 115,000. Discount = 5,000. Taxable net = 110,000. Total Tax = 23,100.
        // Grand Total = 110,000 + 23,100 = 133,100 pesewas.
        assertThat(invoice.subtotalPesewas()).isEqualTo(115_000L);
        assertThat(invoice.discountPesewas()).isEqualTo(5_000L);
        assertThat(invoice.taxPesewas()).isEqualTo(23_100L);
        assertThat(invoice.totalPesewas()).isEqualTo(133_100L);
        assertThat(invoice.graSdcReceiptNumber()).isNotNull().startsWith("SDC-GRA-GH-");

        // Verify GL accounts: AR (1040) debited 133,100, Sales Revenue (4010) credited 110,000, VAT (2020) credited 23,100
        var ar = glService.getAccountByCode(merchantId, "1040").orElseThrow();
        var rev = glService.getAccountByCode(merchantId, "4010").orElseThrow();
        var vat = glService.getAccountByCode(merchantId, "2020").orElseThrow();

        assertThat(ar.balancePesewas()).isEqualTo(133_100L);
        assertThat(rev.balancePesewas()).isEqualTo(110_000L);
        assertThat(vat.balancePesewas()).isEqualTo(23_100L);

        // Verify trial balance is balanced: debits == credits
        var tb = glService.generateTrialBalance(merchantId, LocalDate.now());
        assertThat(tb.totalDebitPesewas()).isEqualTo(tb.totalCreditPesewas());
        assertThat(tb.isBalanced()).isTrue();
    }
}
