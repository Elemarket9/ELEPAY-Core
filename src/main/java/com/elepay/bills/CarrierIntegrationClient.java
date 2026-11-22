package com.elepay.bills;

public interface CarrierIntegrationClient {

    boolean isConfigured(String network);

    AirtimeDeliveryResponse deliverAirtime(String network, String phoneNumber, long amountPesewas, String internalReference);

    BundleDeliveryResponse deliverBundle(String network, String productCode, String phoneNumber, long amountPesewas, String internalReference);

    record AirtimeDeliveryResponse(
            String status, // COMPLETED, UNAVAILABLE, FAILED
            String carrierReference,
            String operatorTransactionId,
            long amountDeliveredPesewas,
            String message
    ) {}

    record BundleDeliveryResponse(
            String status, // COMPLETED, UNAVAILABLE, FAILED
            String carrierReference,
            String voucherCode,
            String message
    ) {}
}
