package com.elepay.bills;

import com.elepay.PostgresTestBase;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class BillPaymentsTest extends PostgresTestBase {

    @Autowired
    private BillCatalogService catalogService;

    @Autowired
    private AirtimeDataPurchaseEngine airtimeEngine;

    @Autowired
    private UtilityPaymentService utilityService;

    @Autowired
    private SchoolFeePaymentService schoolFeeService;

    @Autowired
    private ScheduledBillPaymentService scheduledBillService;

    @Test
    @DisplayName("Domain 9: Everyday Financial Services & Bill Payments - Airtime, Bundles, ECG, GWCL, Tuition, Recurring Bills")
    void testBillPaymentsEngine() {
        // 1. Verify Catalog
        var providers = catalogService.listActiveProviders();
        assertThat(providers).isNotEmpty();
        assertThat(providers.stream().anyMatch(p -> "MTN".equals(p.providerCode()))).isTrue();
        assertThat(providers.stream().anyMatch(p -> "ECG".equals(p.providerCode()))).isTrue();

        var mtnBundles = catalogService.listProductsForProvider("MTN");
        assertThat(mtnBundles).isNotEmpty();

        // 2. Airtime & Data Bundles Purchase
        UUID customerId = UUID.randomUUID();

        // Network detection
        assertThat(airtimeEngine.detectNetwork("0244123456")).isEqualTo("MTN");
        assertThat(airtimeEngine.detectNetwork("+233201234567")).isEqualTo("TELECEL");
        assertThat(airtimeEngine.detectNetwork("0277987654")).isEqualTo("AIRTELTIGO");

        // Purchase MTN Airtime
        var airtimeReceipt = airtimeEngine.purchaseAirtime(new AirtimeDataPurchaseEngine.AirtimePurchaseRequest(
                customerId, "MTN", "0244123456", 2000L // 20.00 GHS
        ));
        assertThat(airtimeReceipt.status()).isEqualTo("COMPLETED");
        assertThat(airtimeReceipt.reference()).startsWith("AIR-");
        assertThat(airtimeReceipt.amountPesewas()).isEqualTo(2000L);

        // Purchase Telecel Data Bundle
        var bundleReceipt = airtimeEngine.purchaseBundle(new AirtimeDataPurchaseEngine.BundlePurchaseRequest(
                customerId, "TELECEL_DATA_2GB", "0201234567"
        ));
        assertThat(bundleReceipt.status()).isEqualTo("COMPLETED");
        assertThat(bundleReceipt.tokenVoucher()).isNotNull();
        assertThat(bundleReceipt.productCode()).isEqualTo("TELECEL_DATA_2GB");

        // 3. Utility Payments: ECG & GWCL
        // ECG Prepaid (Generates STS 20-digit token)
        var ecgPrepaid = utilityService.payEcgPrepaid(customerId, "14285719302", 5000L); // 50.00 GHS
        assertThat(ecgPrepaid.status()).isEqualTo("COMPLETED");
        assertThat(ecgPrepaid.unitsKwh()).isGreaterThan(0.0);
        assertThat(ecgPrepaid.stsToken()).hasSize(24); // 20 digits + 4 dashes: XXXX-XXXX-XXXX-XXXX-XXXX
        assertThat(ecgPrepaid.stsToken()).matches("^\\d{4}-\\d{4}-\\d{4}-\\d{4}-\\d{4}$");

        // ECG Postpaid Bill Payment
        var ecgPostpaid = utilityService.payEcgPostpaid(customerId, "ECG-ACC-9988", 15000L);
        assertThat(ecgPostpaid.status()).isEqualTo("COMPLETED");
        assertThat(ecgPostpaid.provider()).isEqualTo("ECG");

        // GWCL Water Payment
        var gwclReceipt = utilityService.payGwclWater(customerId, "GW-102938", 10000L);
        assertThat(gwclReceipt.status()).isEqualTo("COMPLETED");
        assertThat(gwclReceipt.provider()).isEqualTo("GWCL");

        // 4. School and Tuition Fee Payments with Student ID Validation
        // Verify student record
        var studentOpt = schoolFeeService.verifyStudent("UG_LEGON", "10982341");
        assertThat(studentOpt).isPresent();
        var student = studentOpt.get();
        assertThat(student.studentName()).isEqualTo("Kwame Mensah");
        long initialOutstanding = student.outstandingFeePesewas();
        assertThat(initialOutstanding).isGreaterThan(0L);

        // Pay tuition fees
        long tuitionPayment = 50000L; // 500.00 GHS
        var tuitionReceipt = schoolFeeService.payTuition(
                customerId, "UG_LEGON", "10982341", tuitionPayment, "Kwame Mensah Senior"
        );
        assertThat(tuitionReceipt.status()).isEqualTo("COMPLETED");
        assertThat(tuitionReceipt.amountPaidPesewas()).isEqualTo(tuitionPayment);
        assertThat(tuitionReceipt.remainingOutstandingPesewas()).isEqualTo(initialOutstanding - tuitionPayment);
        assertThat(tuitionReceipt.voucherCode()).startsWith("VOUCH-UG_LEGON-");

        // 5. Scheduled Recurring Bill Payments & Pre-Debit Notifications
        var plan = scheduledBillService.createSchedule(
                customerId, "MTN", "MTN_AIRTIME", "0244123456", 1000L,
                "WEEKLY", null, LocalDate.now()
        );
        assertThat(plan.status()).isEqualTo("ACTIVE");
        assertThat(plan.amountPesewas()).isEqualTo(1000L);

        // Generate pre-debit notifications
        var notices = scheduledBillService.generatePreDebitNotifications(LocalDate.now().plusDays(1));
        assertThat(notices).isNotEmpty();
        assertThat(notices.stream().anyMatch(n -> n.planId().equals(plan.id()))).isTrue();

        // Execute due scheduled recurring bills
        int executed = scheduledBillService.executeDueScheduledBills(LocalDate.now());
        assertThat(executed).isGreaterThanOrEqualTo(1);

        var updatedPlan = scheduledBillService.getSchedule(plan.id());
        assertThat(updatedPlan.lastExecutedAt()).isNotNull();
        assertThat(updatedPlan.nextRunDate()).isAfter(LocalDate.now());
    }
}
