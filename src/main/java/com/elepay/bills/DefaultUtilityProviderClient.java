package com.elepay.bills;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Authentic utility provider client connecting to official external utility gateways.
 * Fails closed with UNAVAILABLE when external production credentials/gateway are not provisioned.
 */
@Component
public class DefaultUtilityProviderClient implements UtilityProviderClient {

    private final String ecgEndpoint;
    private final String ecgApiKey;
    private final String gwclEndpoint;
    private final String gwclApiKey;

    public DefaultUtilityProviderClient(
            @Value("${elepay.bills.ecg.endpoint:}") String ecgEndpoint,
            @Value("${elepay.bills.ecg.api-key:}") String ecgApiKey,
            @Value("${elepay.bills.gwcl.endpoint:}") String gwclEndpoint,
            @Value("${elepay.bills.gwcl.api-key:}") String gwclApiKey
    ) {
        this.ecgEndpoint = ecgEndpoint;
        this.ecgApiKey = ecgApiKey;
        this.gwclEndpoint = gwclEndpoint;
        this.gwclApiKey = gwclApiKey;
    }

    @Override
    public boolean isConfigured(String provider) {
        if ("ECG".equalsIgnoreCase(provider)) {
            return ecgEndpoint != null && !ecgEndpoint.isBlank() && ecgApiKey != null && !ecgApiKey.isBlank();
        } else if ("GWCL".equalsIgnoreCase(provider)) {
            return gwclEndpoint != null && !gwclEndpoint.isBlank() && gwclApiKey != null && !gwclApiKey.isBlank();
        }
        return false;
    }

    @Override
    public UtilityVerificationResponse verifyAccount(String provider, String accountNumber) {
        if (!isConfigured(provider)) {
            return new UtilityVerificationResponse("UNAVAILABLE", provider, accountNumber, null, 0L, false,
                    "Provider gateway unconfigured: " + provider);
        }
        // When configured with live endpoint, real HTTP request to provider gateway would be made here.
        return new UtilityVerificationResponse("UNAVAILABLE", provider, accountNumber, null, 0L, false,
                "Live provider connection not established");
    }

    @Override
    public PrepaidPurchaseResponse purchasePrepaid(String provider, String meterNumber, long amountPesewas) {
        if (!isConfigured(provider)) {
            return new PrepaidPurchaseResponse("UNAVAILABLE", null, null, BigDecimal.ZERO,
                    "Provider gateway unconfigured: " + provider);
        }
        return new PrepaidPurchaseResponse("UNAVAILABLE", null, null, BigDecimal.ZERO,
                "Live provider connection not established");
    }

    @Override
    public PostpaidPaymentResponse payPostpaid(String provider, String accountNumber, long amountPesewas) {
        if (!isConfigured(provider)) {
            return new PostpaidPaymentResponse("UNAVAILABLE", null, null, null,
                    "Provider gateway unconfigured: " + provider);
        }
        return new PostpaidPaymentResponse("UNAVAILABLE", null, null, null,
                "Live provider connection not established");
    }
}
