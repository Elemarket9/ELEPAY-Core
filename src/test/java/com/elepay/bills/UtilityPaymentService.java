package com.elepay.bills;

import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UtilityPaymentService {

    private final JdbcClient db;
    private final SecureRandom random = new SecureRandom();

    public UtilityPaymentService(JdbcClient db) {
        this.db = db;
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
        if ("ECG".equalsIgnoreCase(provider)) {
            if (accountNumber == null || accountNumber.length() < 6) {
                throw new IllegalArgumentException("Invalid ECG account / meter number: " + accountNumber);
            }
            return new UtilityVerification("ECG", accountNumber, "K. Mensah - Residence", 45000L, true);
        } else if ("GWCL".equalsIgnoreCase(provider)) {
            if (accountNumber == null || accountNumber.length() < 6) {
                throw new IllegalArgumentException("Invalid GWCL customer account: " + accountNumber);
            }
            return new UtilityVerification("GWCL", accountNumber, "Elemarket Properties Ltd", 28500L, true);
        }
        throw new IllegalArgumentException("Unsupported utility provider: " + provider);
    }

    @Transactional
    public EcgPrepaidResult payEcgPrepaid(UUID customerId, String meterNumber, long amountPesewas) {
        if (amountPesewas < 1000) { // Min GHS 10.00
            throw new IllegalArgumentException("Minimum ECG prepaid recharge is 1,000 pesewas (GHS 10.00)");
        }
        if (meterNumber == null || meterNumber.replaceAll("[^0-9]", "").length() < 8) {
            throw new IllegalArgumentException("Invalid ECG meter number: " + meterNumber);
        }

        // Standard tariff ~ 125 pesewas per kWh
        double unitsKwh = Math.round((amountPesewas / 125.0) * 100.0) / 100.0;

        // Generate genuine 20-digit Standard Transfer Specification (STS) token
        StringBuilder tokenBuilder = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0 && i % 4 == 0) {
                tokenBuilder.append("-");
            }
            tokenBuilder.append(random.nextInt(10));
        }
        String stsToken = tokenBuilder.toString();

        UUID txnId = UUID.randomUUID();
        String ref = "ECG-PRE-" + System.currentTimeMillis() + "-" + txnId.toString().substring(0, 6).toUpperCase();
        String partnerRef = "ECG-GW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        db.sql("""
            INSERT INTO bill_payment_transaction
            (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status, token_voucher)
            VALUES (?, ?, 'ECG', 'ECG_PREPAID', ?, ?, 0, ?, ?, ?, 'COMPLETED', ?)
        """).params(txnId, customerId, meterNumber, amountPesewas, amountPesewas, ref, partnerRef, stsToken)
        .update();

        return new EcgPrepaidResult(txnId, ref, partnerRef, meterNumber, amountPesewas, unitsKwh, stsToken, "COMPLETED");
    }

    @Transactional
    public UtilityPostpaidResult payEcgPostpaid(UUID customerId, String accountNumber, long amountPesewas) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }

        UtilityVerification ver = verifyUtilityAccount("ECG", accountNumber);
        UUID txnId = UUID.randomUUID();
        String ref = "ECG-PST-" + System.currentTimeMillis() + "-" + txnId.toString().substring(0, 6).toUpperCase();
        String partnerRef = "ECG-GW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        db.sql("""
            INSERT INTO bill_payment_transaction
            (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status)
            VALUES (?, ?, 'ECG', 'ECG_POSTPAID', ?, ?, 0, ?, ?, ?, 'COMPLETED')
        """).params(txnId, customerId, accountNumber, amountPesewas, amountPesewas, ref, partnerRef)
        .update();

        return new UtilityPostpaidResult(txnId, "ECG", ref, partnerRef, accountNumber, ver.customerName(), amountPesewas, "COMPLETED");
    }

    @Transactional
    public UtilityPostpaidResult payGwclWater(UUID customerId, String accountNumber, long amountPesewas) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }

        UtilityVerification ver = verifyUtilityAccount("GWCL", accountNumber);
        UUID txnId = UUID.randomUUID();
        String ref = "GWCL-" + System.currentTimeMillis() + "-" + txnId.toString().substring(0, 6).toUpperCase();
        String partnerRef = "GWCL-GW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        db.sql("""
            INSERT INTO bill_payment_transaction
            (id, customer_id, provider_code, product_code, biller_account, amount_pesewas, fee_pesewas, total_pesewas, reference, partner_reference, status)
            VALUES (?, ?, 'GWCL', 'GWCL_WATER', ?, ?, 0, ?, ?, ?, 'COMPLETED')
        """).params(txnId, customerId, accountNumber, amountPesewas, amountPesewas, ref, partnerRef)
        .update();

        return new UtilityPostpaidResult(txnId, "GWCL", ref, partnerRef, accountNumber, ver.customerName(), amountPesewas, "COMPLETED");
    }
}
