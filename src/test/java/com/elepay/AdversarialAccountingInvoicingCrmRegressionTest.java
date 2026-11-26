package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.merchanttools.accounting.InvoiceAndQuoteService;
import com.elepay.merchanttools.accounting.MerchantGeneralLedgerService;
import com.elepay.merchanttools.crm.LoyaltyProgramService;
import com.elepay.merchanttools.crm.MarketingCampaignService;
import com.elepay.merchanttools.crm.RfmSegmentationService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

class AdversarialAccountingInvoicingCrmRegressionTest extends PostgresTestBase {

    @Autowired
    private MerchantGeneralLedgerService glService;

    @Autowired
    private InvoiceAndQuoteService invoiceService;

    @Autowired
    private LoyaltyProgramService loyaltyService;

    @Autowired
    private RfmSegmentationService rfmService;

    @Autowired
    private MarketingCampaignService customerService;

    @Test
    void glReportsHonorAsOfAndPeriodDateFilters() {
        UUID merchantId = UUID.randomUUID();
        glService.initializeStandardChartOfAccounts(merchantId);

        LocalDate postingDate = LocalDate.of(2026, 11, 26);
        invoiceService.createInvoice(new InvoiceAndQuoteService.CreateInvoiceCommand(
                merchantId,
                null,
                null,
                null,
                "Date Filter Customer",
                null,
                null,
                null,
                postingDate,
                postingDate.plusDays(14),
                "GHS",
                List.of(new InvoiceAndQuoteService.InvoiceItemCommand("Consulting", 1, 10_000L, 0, 0L)),
                null
        ));

        LocalDate historicalDate = LocalDate.of(2020, 1, 1);
        var historicalTb = glService.generateTrialBalance(merchantId, historicalDate);
        assertThat(historicalTb.isBalanced()).isTrue();
        assertThat(historicalTb.totalDebitPesewas()).isZero();
        assertThat(historicalTb.totalCreditPesewas()).isZero();

        var historicalBs = glService.generateBalanceSheet(merchantId, historicalDate);
        assertThat(historicalBs.isBalanced()).isTrue();
        assertThat(historicalBs.totalAssetsPesewas()).isZero();
        assertThat(historicalBs.netIncomePesewas()).isZero();

        var historicalPl = glService.generateProfitAndLoss(merchantId, historicalDate, LocalDate.of(2020, 1, 31));
        assertThat(historicalPl.totalRevenuePesewas()).isZero();
        assertThat(historicalPl.netProfitPesewas()).isZero();

        var currentTb = glService.generateTrialBalance(merchantId, postingDate);
        assertThat(currentTb.isBalanced()).isTrue();
        assertThat(currentTb.totalDebitPesewas()).isEqualTo(10_000L);
        assertThat(currentTb.totalCreditPesewas()).isEqualTo(10_000L);

        var currentBs = glService.generateBalanceSheet(merchantId, postingDate);
        assertThat(currentBs.isBalanced()).isTrue();
        assertThat(currentBs.totalAssetsPesewas()).isEqualTo(10_000L);
        assertThat(currentBs.netIncomePesewas()).isEqualTo(10_000L);

        var currentPl = glService.generateProfitAndLoss(merchantId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        assertThat(currentPl.totalRevenuePesewas()).isEqualTo(10_000L);
        assertThat(currentPl.netProfitPesewas()).isEqualTo(10_000L);
    }

    @Test
    void invoiceRejectsNegativeAndExcessDiscounts() {
        UUID merchantId = UUID.randomUUID();
        glService.initializeStandardChartOfAccounts(merchantId);
        LocalDate today = LocalDate.now();

        assertThatThrownBy(() -> invoiceService.createInvoice(new InvoiceAndQuoteService.CreateInvoiceCommand(
                merchantId, null, null, null, "Neg Discount", null, null, null, today, today.plusDays(7), "GHS",
                List.of(new InvoiceAndQuoteService.InvoiceItemCommand("Item", 1, 10_000L, 0, -5_000L)), null
        )))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("invalid_argument");
                });

        assertThatThrownBy(() -> invoiceService.createInvoice(new InvoiceAndQuoteService.CreateInvoiceCommand(
                merchantId, null, null, null, "Excess Multi-line", null, null, null, today, today.plusDays(7), "GHS",
                List.of(
                        new InvoiceAndQuoteService.InvoiceItemCommand("Taxed Item", 1, 1_000L, 1500, 0L),
                        new InvoiceAndQuoteService.InvoiceItemCommand("Over-discounted", 1, 1_000L, 0, 5_000L)
                ), null
        )))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("invalid_argument");
                });
    }

    @Test
    void invoiceRejectsOverpaymentAndPreservesNonNegativeAr() {
        UUID merchantId = UUID.randomUUID();
        glService.initializeStandardChartOfAccounts(merchantId);
        LocalDate today = LocalDate.now();

        var inv = invoiceService.createInvoice(new InvoiceAndQuoteService.CreateInvoiceCommand(
                merchantId, null, null, null, "AR Customer", null, null, null, today, today.plusDays(14), "GHS",
                List.of(new InvoiceAndQuoteService.InvoiceItemCommand("Service", 1, 10_000L, 0, 0L)), null
        ));

        assertThatThrownBy(() -> invoiceService.recordPayment(merchantId, inv.id(), 0L, "1010"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> invoiceService.recordPayment(merchantId, inv.id(), 25_000L, "1010"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("invoice_overpayment");
                });

        var partial = invoiceService.recordPayment(merchantId, inv.id(), 6_000L, "1010");
        assertThat(partial.status()).isEqualTo("PARTIALLY_PAID");
        assertThat(partial.paidAmountPesewas()).isEqualTo(6_000L);

        assertThatThrownBy(() -> invoiceService.recordPayment(merchantId, inv.id(), 5_000L, "1010"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("invoice_overpayment"));

        var settled = invoiceService.recordPayment(merchantId, inv.id(), 4_000L, "1010");
        assertThat(settled.status()).isEqualTo("PAID");
        assertThat(settled.paidAmountPesewas()).isEqualTo(10_000L);

        var ar = glService.getAccountByCode(merchantId, "1040").orElseThrow();
        assertThat(ar.balancePesewas()).isZero();

        assertThatThrownBy(() -> invoiceService.recordPayment(merchantId, inv.id(), 1L, "1010"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("invoice_overpayment"));
    }

    @Test
    void quotesPreserveDiscountValidateDatesAndBlockExpiredOrDoubleConversion() throws Exception {
        UUID merchantId = UUID.randomUUID();
        glService.initializeStandardChartOfAccounts(merchantId);
        LocalDate today = LocalDate.now();

        // expiryDate < issueDate -> 400
        assertThatThrownBy(() -> invoiceService.createQuote(
                merchantId, null, null, "Bad Dates", null, null,
                LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 1),
                List.of(new InvoiceAndQuoteService.InvoiceItemCommand("Item", 1, 10_000L, 0, 0L)), null
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("invalid_argument");
                });

        // Discount preserved on quote and carried through conversion
        var quote = invoiceService.createQuote(
                merchantId, null, null, "Discounted Quote", null, null,
                today, today.plusDays(10),
                List.of(new InvoiceAndQuoteService.InvoiceItemCommand("Item", 1, 10_000L, 0, 2_000L)), null
        );
        assertThat(quote.discountPesewas()).isEqualTo(2_000L);
        assertThat(quote.totalPesewas()).isEqualTo(8_000L);

        var converted = invoiceService.convertQuoteToInvoice(merchantId, quote.id(), null, today.plusDays(14));
        assertThat(converted.discountPesewas()).isEqualTo(2_000L);
        assertThat(converted.totalPesewas()).isEqualTo(8_000L);

        // Second conversion -> 409 quote_already_converted
        assertThatThrownBy(() -> invoiceService.convertQuoteToInvoice(merchantId, quote.id(), null, today.plusDays(14)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("quote_already_converted");
                });

        // Expired quote -> 409 quote_expired
        var expiredQuote = invoiceService.createQuote(
                merchantId, null, null, "Expired Quote", null, null,
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2),
                List.of(new InvoiceAndQuoteService.InvoiceItemCommand("Item", 1, 10_000L, 0, 0L)), null
        );
        assertThatThrownBy(() -> invoiceService.convertQuoteToInvoice(merchantId, expiredQuote.id(), null, today.plusDays(14)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("quote_expired");
                });

        // Concurrent double conversion -> exactly 1 succeeds, 1 gets 409 quote_already_converted
        var raceQuote = invoiceService.createQuote(
                merchantId, null, null, "Race Quote", null, null,
                today, today.plusDays(10),
                List.of(new InvoiceAndQuoteService.InvoiceItemCommand("Item", 1, 5_000L, 0, 0L)), null
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return invoiceService.convertQuoteToInvoice(merchantId, raceQuote.id(), null, today.plusDays(14));
                    } catch (ApiException e) {
                        return e;
                    }
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            int successes = 0;
            int conflicts = 0;
            for (Future<Object> f : futures) {
                Object r = f.get(15, TimeUnit.SECONDS);
                if (r instanceof InvoiceAndQuoteService.InvoiceSummary) successes++;
                else if (r instanceof ApiException api && "quote_already_converted".equals(api.code())) conflicts++;
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentInvoiceCreationSerializesNumberingWithoutConflict() throws Exception {
        UUID merchantId = UUID.randomUUID();
        glService.initializeStandardChartOfAccounts(merchantId);
        LocalDate today = LocalDate.now();

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<InvoiceAndQuoteService.InvoiceSummary>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return invoiceService.createInvoice(new InvoiceAndQuoteService.CreateInvoiceCommand(
                            merchantId, null, null, null, "Customer " + idx, null, null, null,
                            today, today.plusDays(14), "GHS",
                            List.of(new InvoiceAndQuoteService.InvoiceItemCommand("Item " + idx, 1, 5_000L, 0, 0L)),
                            null
                    ));
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            Set<String> numbers = new HashSet<>();
            for (var f : futures) {
                var inv = f.get(20, TimeUnit.SECONDS);
                numbers.add(inv.invoiceNumber());
            }
            assertThat(numbers).hasSize(threads);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void loyaltyBlocksMicroPurchaseFarmingAndEnforcesIdempotency() {
        UUID merchantId = UUID.randomUUID();
        var cust = customerService.registerCustomer(
                merchantId, null, "Loyalty Tester", "+233595300001", "loyalty@test.com", "Accra");

        // 1 pesewa must NOT earn 1 point (worth 10 pesewas)
        var micro = loyaltyService.earnPointsForPurchase(
                merchantId, cust.id(), 1L, "MANUAL_PURCHASE", UUID.randomUUID(),
                "earn-micro-1", Idempotency.sha256("EARN:1"));
        assertThat(micro.pointsDelta()).isZero();
        assertThat(micro.newPointsBalance()).isZero();
        assertThat(micro.monetaryEquivalentPesewas()).isZero();

        // 10,000 pesewas -> 10 points; identical replay returns same transaction without double-crediting
        String earnKey = "earn-10k";
        String earnHash = Idempotency.sha256("EARN:10000");
        var earned1 = loyaltyService.earnPointsForPurchase(
                merchantId, cust.id(), 10_000L, "MANUAL_PURCHASE", UUID.randomUUID(), earnKey, earnHash);
        assertThat(earned1.pointsDelta()).isEqualTo(10L);
        assertThat(earned1.newPointsBalance()).isEqualTo(10L);

        var earnedReplay = loyaltyService.earnPointsForPurchase(
                merchantId, cust.id(), 10_000L, "MANUAL_PURCHASE", UUID.randomUUID(), earnKey, earnHash);
        assertThat(earnedReplay.transactionId()).isEqualTo(earned1.transactionId());
        assertThat(earnedReplay.newPointsBalance()).isEqualTo(10L);

        // Same key with changed payload -> 409 idempotency_conflict
        assertThatThrownBy(() -> loyaltyService.earnPointsForPurchase(
                merchantId, cust.id(), 20_000L, "MANUAL_PURCHASE", UUID.randomUUID(),
                earnKey, Idempotency.sha256("EARN:20000")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("idempotency_conflict");
                });

        // Redeem idempotency: identical replay returns same result; changed payload -> 409
        String redeemKey = "redeem-5";
        String redeemHash = Idempotency.sha256("REDEEM:5");
        var redeem1 = loyaltyService.redeemPoints(
                merchantId, cust.id(), 5L, "MANUAL_REDEMPTION", UUID.randomUUID(), redeemKey, redeemHash);
        assertThat(redeem1.newPointsBalance()).isEqualTo(5L);

        var redeemReplay = loyaltyService.redeemPoints(
                merchantId, cust.id(), 5L, "MANUAL_REDEMPTION", UUID.randomUUID(), redeemKey, redeemHash);
        assertThat(redeemReplay.transactionId()).isEqualTo(redeem1.transactionId());
        assertThat(redeemReplay.newPointsBalance()).isEqualTo(5L);

        assertThatThrownBy(() -> loyaltyService.redeemPoints(
                merchantId, cust.id(), 3L, "MANUAL_REDEMPTION", UUID.randomUUID(),
                redeemKey, Idempotency.sha256("REDEEM:3")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("idempotency_conflict"));
    }

    @Test
    void customerTotalSpendRejectsLongOverflow() {
        UUID merchantId = UUID.randomUUID();
        var cust = customerService.registerCustomer(
                merchantId, null, "Overflow Tester", "+233595300002", null, null);

        rfmService.recordCustomerPurchase(cust.id(), Long.MAX_VALUE);

        assertThatThrownBy(() -> rfmService.recordCustomerPurchase(cust.id(), 100L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("amount_overflow");
                });

        var rows = customerService.listCustomers(merchantId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().totalSpendPesewas()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void concurrentCustomerRegistrationSerializesCodesWithoutConflict() throws Exception {
        UUID merchantId = UUID.randomUUID();
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<MarketingCampaignService.CustomerProfile>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return customerService.registerCustomer(
                            merchantId, null, "Parallel Customer " + idx,
                            String.format("+2335953010%02d", idx), null, null);
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            Set<String> codes = new HashSet<>();
            for (var f : futures) {
                var c = f.get(20, TimeUnit.SECONDS);
                codes.add(c.customerCode());
            }
            assertThat(codes).hasSize(threads);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void customerRegistrationRejectsOversizedFullNameAndDuplicateNormalizedPhone() {
        UUID merchantId = UUID.randomUUID();
        String longName = "A".repeat(135);

        assertThatThrownBy(() -> customerService.registerCustomer(
                merchantId, null, longName, "+233595300064", null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("invalid_argument");
                });

        var first = customerService.registerCustomer(
                merchantId, null, "First Owner", "0595300065", null, null);
        assertThat(first.phone()).isEqualTo("+233595300065");

        assertThatThrownBy(() -> customerService.registerCustomer(
                merchantId, null, "Duplicate Phone", "+233 59-530-0065", null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("duplicate_phone");
                });

        // Different merchant may register the same phone number independently
        UUID otherMerchant = UUID.randomUUID();
        var other = customerService.registerCustomer(
                otherMerchant, null, "Other Merchant Customer", "+233595300065", null, null);
        assertThat(other.phone()).isEqualTo("+233595300065");
    }
}
