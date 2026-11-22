package com.elepay.bills;

import java.math.BigDecimal;

public interface UtilityProviderClient {

    boolean isConfigured(String provider);

    UtilityVerificationResponse verifyAccount(String provider, String accountNumber);

    PrepaidPurchaseResponse purchasePrepaid(String provider, String meterNumber, long amountPesewas);

    PostpaidPaymentResponse payPostpaid(String provider, String accountNumber, long amountPesewas);

    record UtilityVerificationResponse(
            String status, // VERIFIED, UNAVAILABLE, NOT_FOUND
            String provider,
            String accountNumber,
            String customerName,
            long outstandingBalancePesewas,
            boolean isEligible,
            String message
    ) {}

    record PrepaidPurchaseResponse(
            String status, // COMPLETED, UNAVAILABLE, FAILED
            String partnerReference,
            String stsToken,
            BigDecimal unitsKwh,
            String message
    ) {}

    record PostpaidPaymentResponse(
            String status, // COMPLETED, UNAVAILABLE, FAILED
            String partnerReference,
            String receiptNumber,
            String customerName,
            String message
    ) {}
}
