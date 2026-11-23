package com.elepay.bills;

import com.elepay.ledger.Ledger;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UtilityPaymentService {

    private final JdbcClient db;
    private final UtilityProviderClient providerClient;
    private final Ledger ledger;

    public UtilityPaymentService(JdbcClient db, UtilityProviderClient providerClient, Ledger ledger) {
        this.db = db;
        this.providerClient = providerClient;
        this.ledger = ledger;
    }

    public record EcgPrepaidResult(
            UUID transactionId,
            String reference,
            String partnerReference,
            String meterNumber,
            long amountPesewas,
            double unitsKwh,
            String stsToken,
            String status
    ) {}

    public record UtilityPostpaidResult(
            UUID transactionId,
            String provider,
            String reference,
            String partnerReference,
            String accountNumber,
            String customerName,
            long amountPesewas,
            String status
    ) {}

    public record UtilityVerification(
            String provider,
            String accountNumber,
            String customerName,
            long outstandingBalancePesewas,
            boolean isEligible
    ) {}

    public UtilityVerification verifyUtilityAccount(String provider, String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 6) {
            throw new IllegalArgumentException("Invalid utility account / meter number: " + accountNumber);
        }
        var resp = providerClient.verifyAccount(provider, accountNumber);
        if (!"VERIFIED".equals(resp.status())) {
            return new UtilityVerification(provider, accountNumber, resp.customerName(), resp.outstandingBalancePesewas(), false);
        }
        return new UtilityVerification(provider, accountNumber, resp.customerName(), resp.outstandingBalancePesewas(), resp.isEligible());
    }

    @Transactional
    public EcgPrepaidResult payEcgPrepaid(UUID customerId, String meterNumber, long amountPesewas) {
        if (amountPesewas < 1000) { // Min GHS 10.00
            throw new IllegalArgumentException("Minimum ECG prepaid recharge is 1,000 pesewas (GHS 10.00)");
        }
        if (meterNumber == null || meterNumber.replaceAll("[^0-9]", "").length() < 8) {
            throw new IllegalArgumentException("Invalid ECG meter number: " + meterNumber);
        }

        // Standard tariff ~ 125 pesewas per kWh calculated via exact BigDecimal
        BigDecimal unitsKwh = BigDecimal.valueOf(amountPesewas).divide(BigDecimal.valueOf(125), 2, RoundingMode.HALF_UP);

        UUID txnId = UUID.randomUUID();
        String ref = "ECG-PRE-" + System.currentTimeMillis() + "-" + txnId.toString().substring(0, 6).toUpperCase();

        if (!providerClient.isConfigured("ECG")) {
            db.sql("""
                INSERT INTO bill_payment_transaction
                (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status)
                VALUES (?, ?, 'ECG', 'ECG_PREPAID', ?, ?, 0, ?, ?, NULL, 'UNAVAILABLE')
            """).params(txnId, customerId, meterNumber, amountPesewas, amountPesewas, ref)
            .update();

            return new EcgPrepaidResult(txnId, ref, null, meterNumber, amountPesewas, unitsKwh.doubleValue(), null, "UNAVAILABLE");
        }

        var purchase = providerClient.purchasePrepaid("ECG", meterNumber, amountPesewas);
        if (!"COMPLETED".equals(purchase.status())) {
            db.sql("""
                INSERT INTO bill_payment_transaction
                (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status)
                VALUES (?, ?, 'ECG', 'ECG_PREPAID', ?, ?, 0, ?, ?, NULL, ?)
            """).params(txnId, customerId, meterNumber, amountPesewas, amountPesewas, ref, purchase.status())
            .update();

            return new EcgPrepaidResult(txnId, ref, null, meterNumber, amountPesewas, unitsKwh.doubleValue(), null, purchase.status());
        }

        // Authoritative ledger posting when real utility confirmation is received
        String customerAccount = ledger.open("customer", customerId, "available", Ledger.GHS);
        String ecgClearing = ledger.openSystem("system:clearing:ecg:GHS", "clearing", Ledger.GHS, true);
        var posted = ledger.post(Ledger.Posting.of("bill-payment", ref, "utility_prepaid", List.of(
                Ledger.Leg.debit(customerAccount, amountPesewas),
                Ledger.Leg.credit(ecgClearing, amountPesewas)
        )).describedAs("ECG Prepaid recharge meter " + meterNumber));

        db.sql("""
            INSERT INTO bill_payment_transaction
            (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status, token_voucher, journal_tx)
            VALUES (?, ?, 'ECG', 'ECG_PREPAID', ?, ?, 0, ?, ?, ?, 'COMPLETED', ?, ?)
        """).params(txnId, customerId, meterNumber, amountPesewas, amountPesewas, ref, purchase.partnerReference(), purchase.stsToken(), posted.transactionId())
        .update();

        double reportedKwh = purchase.unitsKwh() != null && purchase.unitsKwh().compareTo(BigDecimal.ZERO) > 0
                ? purchase.unitsKwh().doubleValue() : unitsKwh.doubleValue();

        return new EcgPrepaidResult(txnId, ref, purchase.partnerReference(), meterNumber, amountPesewas, reportedKwh, purchase.stsToken(), "COMPLETED");
    }

    @Transactional
    public UtilityPostpaidResult payEcgPostpaid(UUID customerId, String accountNumber, long amountPesewas) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }

        UUID txnId = UUID.randomUUID();
        String ref = "ECG-PST-" + System.currentTimeMillis() + "-" + txnId.toString().substring(0, 6).toUpperCase();

        if (!providerClient.isConfigured("ECG")) {
            db.sql("""
                INSERT INTO bill_payment_transaction
                (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status)
                VALUES (?, ?, 'ECG', 'ECG_POSTPAID', ?, ?, 0, ?, ?, NULL, 'UNAVAILABLE')
            """).params(txnId, customerId, accountNumber, amountPesewas, amountPesewas, ref)
            .update();

            return new UtilityPostpaidResult(txnId, "ECG", ref, null, accountNumber, null, amountPesewas, "UNAVAILABLE");
        }

        var payment = providerClient.payPostpaid("ECG", accountNumber, amountPesewas);
        if (!"COMPLETED".equals(payment.status())) {
            db.sql("""
                INSERT INTO bill_payment_transaction
                (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status)
                VALUES (?, ?, 'ECG', 'ECG_POSTPAID', ?, ?, 0, ?, ?, NULL, ?)
            """).params(txnId, customerId, accountNumber, amountPesewas, amountPesewas, ref, payment.status())
            .update();

            return new UtilityPostpaidResult(txnId, "ECG", ref, null, accountNumber, payment.customerName(), amountPesewas, payment.status());
        }

        String customerAccount = ledger.open("customer", customerId, "available", Ledger.GHS);
        String ecgClearing = ledger.openSystem("system:clearing:ecg:GHS", "clearing", Ledger.GHS, true);
        var posted = ledger.post(Ledger.Posting.of("bill-payment", ref, "utility_postpaid", List.of(
                Ledger.Leg.debit(customerAccount, amountPesewas),
                Ledger.Leg.credit(ecgClearing, amountPesewas)
        )).describedAs("ECG Postpaid bill payment for " + accountNumber));

        db.sql("""
            INSERT INTO bill_payment_transaction
            (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status, journal_tx)
            VALUES (?, ?, 'ECG', 'ECG_POSTPAID', ?, ?, 0, ?, ?, ?, 'COMPLETED', ?)
        """).params(txnId, customerId, accountNumber, amountPesewas, amountPesewas, ref, payment.partnerReference(), posted.transactionId())
        .update();

        return new UtilityPostpaidResult(txnId, "ECG", ref, payment.partnerReference(), accountNumber, payment.customerName(), amountPesewas, "COMPLETED");
    }

    @Transactional
    public UtilityPostpaidResult payGwclWater(UUID customerId, String accountNumber, long amountPesewas) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }

        UUID txnId = UUID.randomUUID();
        String ref = "GWCL-" + System.currentTimeMillis() + "-" + txnId.toString().substring(0, 6).toUpperCase();

        if (!providerClient.isConfigured("GWCL")) {
            db.sql("""
                INSERT INTO bill_payment_transaction
                (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status)
                VALUES (?, ?, 'GWCL', 'GWCL_WATER', ?, ?, 0, ?, ?, NULL, 'UNAVAILABLE')
            """).params(txnId, customerId, accountNumber, amountPesewas, amountPesewas, ref)
            .update();

            return new UtilityPostpaidResult(txnId, "GWCL", ref, null, accountNumber, null, amountPesewas, "UNAVAILABLE");
        }

        var payment = providerClient.payPostpaid("GWCL", accountNumber, amountPesewas);
        if (!"COMPLETED".equals(payment.status())) {
            db.sql("""
                INSERT INTO bill_payment_transaction
                (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status)
                VALUES (?, ?, 'GWCL', 'GWCL_WATER', ?, ?, 0, ?, ?, NULL, ?)
            """).params(txnId, customerId, accountNumber, amountPesewas, amountPesewas, ref, payment.status())
            .update();

            return new UtilityPostpaidResult(txnId, "GWCL", ref, null, accountNumber, payment.customerName(), amountPesewas, payment.status());
        }

        String customerAccount = ledger.open("customer", customerId, "available", Ledger.GHS);
        String gwclClearing = ledger.openSystem("system:clearing:gwcl:GHS", "clearing", Ledger.GHS, true);
        var posted = ledger.post(Ledger.Posting.of("bill-payment", ref, "utility_water", List.of(
                Ledger.Leg.debit(customerAccount, amountPesewas),
                Ledger.Leg.credit(gwclClearing, amountPesewas)
        )).describedAs("GWCL Water payment for " + accountNumber));

        db.sql("""
            INSERT INTO bill_payment_transaction
            (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status, journal_tx)
            VALUES (?, ?, 'GWCL', 'GWCL_WATER', ?, ?, 0, ?, ?, ?, 'COMPLETED', ?)
        """).params(txnId, customerId, accountNumber, amountPesewas, amountPesewas, ref, payment.partnerReference(), posted.transactionId())
        .update();

        return new UtilityPostpaidResult(txnId, "GWCL", ref, payment.partnerReference(), accountNumber, payment.customerName(), amountPesewas, "COMPLETED");
    }
}
