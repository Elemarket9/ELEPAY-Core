package com.elepay.cards;

import java.util.UUID;

public interface CardProcessorContract {

    record IssueRequest(
            UUID customerId,
            UUID merchantId,
            String cardType, // VIRTUAL, PHYSICAL
            String scheme    // VISA, MASTERCARD
    ) {}

    record AuthorizationRequest(
            String pan,
            String cvv2,
            int expiryMonth,
            int expiryYear,
            String merchantName,
            String merchantCity,
            String mcc,
            long amountPesewas,
            String currency
    ) {}

    record AuthorizationResponse(
            boolean approved,
            String authCode,
            String responseCode,
            String declineReason
    ) {}

    AuthorizationResponse processAuthorization(AuthorizationRequest request);
}
