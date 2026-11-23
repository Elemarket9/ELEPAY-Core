package com.elepay.bills;

import com.elepay.PostgresTestBase;
import com.elepay.ledger.Ledger;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

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

    @Autowired
    private Ledger ledger;

    @Autowired
    private JdbcClient db;

    @Test
    @DisplayName("Domain 9: Authentic Bill Payments & Utility Engine - Simulation Elimination, Catalog, Airtime, Utilities, Tuition, Recurring Bills")
    void testBillPaymentsEngine() {
        // 1. Verify Catalog
        var providers = catalogService.listActiveProviders();
        assertThat(providers).isNotEmpty();
        assertThat(providers.stream().anyMatch(p -> "MTN".equals(p.providerCode()))).isTrue();
        assertThat(providers.stream().anyMatch(p -> "ECG".equals(p.providerCode()))).isTrue();

        var mtnBundles = catalogService.listProductsForProvider("MTN");
        assertThat(mtnBundles).isNotEmpty();

        // 2. Airtime & Data Bundles: Network Detection & Unconfigured Behavior
        UUID customerId = UUID.randomUUID();

        // Network detection
        assertThat(airtimeEngine.detectNetwork("0244123456")).isEqualTo("MTN");
        assertThat(airtimeEngine.detectNetwork("+233201234567")).isEqualTo("TELECEL");
        assertThat(airtimeEngine.detectNetwork("0277987654")).isEqualTo("AIRTELTIGO");
        assertThatThrownBy(() -> airtimeEngine.detectNetwork("0123456789")).isInstanceOf(IllegalArgumentException.class);

        // Simulation eliminated: When carrier integration is unconfigured, operations must return UNAVAILABLE
        var unavailAirtime = airtimeEngine.purchaseAirtime(new AirtimeDataPurchaseEngine.AirtimePurchaseRequest(
                customerId, "MTN", "0244123456", 2000L
        ));
        assertThat(unavailAirtime.status()).isEqualTo("UNAVAILABLE");
        assertThat(unavailAirtime.partnerReference()).isNull();

        var unavailBundle = airtimeEngine.purchaseBundle(new AirtimeDataPurchaseEngine.BundlePurchaseRequest(
                customerId, "TELECEL_DATA_2GB", "0201234567"
        ));
        assertThat(unavailBundle.status()).isEqualTo("UNAVAILABLE");
        assertThat(unavailBundle.partnerReference()).isNull();

        // 3. Utility Payments: ECG & GWCL Simulation Elimination
        // When utility provider is unconfigured, must return UNAVAILABLE without random STS tokens or hardcoded balances
        var unavailEcg = utilityService.payEcgPrepaid(customerId, "14285719302", 5000L);
        assertThat(unavailEcg.status()).isEqualTo("UNAVAILABLE");
        assertThat(unavailEcg.stsToken()).isNull();
        assertThat(unavailEcg.partnerReference()).isNull();

        var unavailPostpaid = utilityService.payEcgPostpaid(customerId, "ECG-ACC-9988", 15000L);
        assertThat(unavailPostpaid.status()).isEqualTo("UNAVAILABLE");
        assertThat(unavailPostpaid.partnerReference()).isNull();

        var unavailGwcl = utilityService.payGwclWater(customerId, "GW-102938", 10000L);
        assertThat(unavailGwcl.status()).isEqualTo("UNAVAILABLE");
        assertThat(unavailGwcl.partnerReference()).isNull();

        // 4. Authorized Fulfillment with Authoritative Provider and Authoritative Ledger
        // Test with configured mock provider client
        UtilityProviderClient authoritativeProvider = new UtilityProviderClient() {
            @Override
            public boolean isConfigured(String provider) { return true; }

            @Override
            public UtilityVerificationResponse verifyAccount(String provider, String accountNumber) {
                return new UtilityVerificationResponse("VERIFIED", provider, accountNumber, "Mensah Residence", 12000L, true, "OK");
            }

            @Override
            public PrepaidPurchaseResponse purchasePrepaid(String provider, String meterNumber, long amountPesewas) {
                return new PrepaidPurchaseResponse("COMPLETED", "GW-ECG-AUTH-9912", "1234-5678-9012-3456-7890", BigDecimal.valueOf(40.00), "SUCCESS");
            }

            @Override
            public PostpaidPaymentResponse payPostpaid(String provider, String accountNumber, long amountPesewas) {
                return new PostpaidPaymentResponse("COMPLETED", "GW-POST-AUTH-1123", "REC-887766", "Mensah Residence", "SUCCESS");
            }
        };

        UtilityPaymentService liveUtilityService = new UtilityPaymentService(db, authoritativeProvider, ledger);
        // Pre-fund customer wallet
        ledger.open("customer", customerId, "available", Ledger.GHS);
        String testSource = ledger.openSystem("system:test_utility_source:GHS", "clearing", Ledger.GHS, true);
        ledger.post(Ledger.Posting.of("test-fund-cust-util", customerId.toString(), "test_funding", List.of(
                Ledger.Leg.debit(testSource, 100000L),
                Ledger.Leg.credit(Ledger.code("customer", customerId, "available", Ledger.GHS), 100000L)
        )));

        var livePrepaid = liveUtilityService.payEcgPrepaid(customerId, "14285719302", 5000L);
        assertThat(livePrepaid.status()).isEqualTo("COMPLETED");
        assertThat(livePrepaid.stsToken()).isEqualTo("1234-5678-9012-3456-7890");
        assertThat(livePrepaid.partnerReference()).isEqualTo("GW-ECG-AUTH-9912");

        var livePostpaid = liveUtilityService.payEcgPostpaid(customerId, "ECG-ACC-9988", 15000L);
        assertThat(livePostpaid.status()).isEqualTo("COMPLETED");
        assertThat(livePostpaid.partnerReference()).isEqualTo("GW-POST-AUTH-1123");

        // 5. School and Tuition Fee Payments with Student ID Validation
        db.sql("""
                INSERT INTO student_fee_record (
                    id, institution_code, student_id, student_name, program_name,
                    total_fee_pesewas, paid_fee_pesewas, outstanding_fee_pesewas, academic_term, is_active
                ) VALUES (
                    'b0000004-0000-0000-0000-000000000001'::uuid,
                    'UG_LEGON', '10982341', 'Kwame Mensah', 'BSc Computer Science',
                    500000, 250000, 250000, '2026/2027 Sem 1', true
                )
                ON CONFLICT (institution_code, student_id) DO UPDATE
                SET paid_fee_pesewas = EXCLUDED.paid_fee_pesewas,
                    outstanding_fee_pesewas = EXCLUDED.outstanding_fee_pesewas
                """).update();
        var studentOpt = schoolFeeService.verifyStudent("UG_LEGON", "10982341");
        assertThat(studentOpt).isPresent();
        var student = studentOpt.get();
        assertThat(student.studentName()).isEqualTo("Kwame Mensah");
        long initialOutstanding = student.outstandingFeePesewas();
        assertThat(initialOutstanding).isGreaterThan(0L);

        long tuitionPayment = 50000L; // 500.00 GHS
        var tuitionReceipt = schoolFeeService.payTuition(
                customerId, "UG_LEGON", "10982341", tuitionPayment, "Kwame Mensah Senior"
        );
        assertThat(tuitionReceipt.status()).isEqualTo("COMPLETED");
        assertThat(tuitionReceipt.amountPaidPesewas()).isEqualTo(tuitionPayment);
        assertThat(tuitionReceipt.remainingOutstandingPesewas()).isEqualTo(initialOutstanding - tuitionPayment);
        assertThat(tuitionReceipt.voucherCode()).startsWith("VOUCH-UG_LEGON-");

        // 6. Scheduled Recurring Bill Payments & Pre-Debit Notifications
        var plan = scheduledBillService.createSchedule(
                customerId, "MTN", "MTN_AIRTIME", "0244123456", 1000L,
                "WEEKLY", null, LocalDate.now()
        );
        assertThat(plan.status()).isEqualTo("ACTIVE");
        assertThat(plan.amountPesewas()).isEqualTo(1000L);

        // Pre-debit notifications
        var notices = scheduledBillService.generatePreDebitNotifications(LocalDate.now().plusDays(1));
        assertThat(notices).isNotEmpty();
        assertThat(notices.stream().anyMatch(n -> n.planId().equals(plan.id()))).isTrue();

        // Execution of scheduled recurring bills
        int executed = scheduledBillService.executeDueScheduledBills(LocalDate.now());
        assertThat(executed).isGreaterThanOrEqualTo(1);

        var updatedPlan = scheduledBillService.getSchedule(plan.id());
        assertThat(updatedPlan.lastExecutedAt()).isNotNull();
        assertThat(updatedPlan.nextRunDate()).isAfter(LocalDate.now());
    }
}
