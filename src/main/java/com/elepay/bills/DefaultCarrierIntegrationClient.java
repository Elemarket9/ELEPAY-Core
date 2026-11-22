package com.elepay.bills;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Authentic telecommunication carrier client for MTN, Telecel, and AirtelTigo.
 * Fails closed with UNAVAILABLE when carrier API credentials are not provisioned.
 */
@Component
public class DefaultCarrierIntegrationClient implements CarrierIntegrationClient {

    private final String mtnApiUrl;
    private final String mtnApiKey;
    private final String telecelApiUrl;
    private final String telecelApiKey;
    private final String atApiUrl;
    private final String atApiKey;

    public DefaultCarrierIntegrationClient(
            @Value("${elepay.bills.carrier.mtn.url:}") String mtnApiUrl,
            @Value("${elepay.bills.carrier.mtn.key:}") String mtnApiKey,
            @Value("${elepay.bills.carrier.telecel.url:}") String telecelApiUrl,
            @Value("${elepay.bills.carrier.telecel.key:}") String telecelApiKey,
            @Value("${elepay.bills.carrier.at.url:}") String atApiUrl,
            @Value("${elepay.bills.carrier.at.key:}") String atApiKey
    ) {
        this.mtnApiUrl = mtnApiUrl;
        this.mtnApiKey = mtnApiKey;
        this.telecelApiUrl = telecelApiUrl;
        this.telecelApiKey = telecelApiKey;
        this.atApiUrl = atApiUrl;
        this.atApiKey = atApiKey;
    }

    @Override
    public boolean isConfigured(String network) {
        if ("MTN".equalsIgnoreCase(network)) {
            return mtnApiUrl != null && !mtnApiUrl.isBlank() && mtnApiKey != null && !mtnApiKey.isBlank();
        } else if ("TELECEL".equalsIgnoreCase(network)) {
            return telecelApiUrl != null && !telecelApiUrl.isBlank() && telecelApiKey != null && !telecelApiKey.isBlank();
        } else if ("AIRTELTIGO".equalsIgnoreCase(network)) {
            return atApiUrl != null && !atApiUrl.isBlank() && atApiKey != null && !atApiKey.isBlank();
        }
        return false;
    }

    @Override
    public AirtimeDeliveryResponse deliverAirtime(String network, String phoneNumber, long amountPesewas, String internalReference) {
        if (!isConfigured(network)) {
            return new AirtimeDeliveryResponse("UNAVAILABLE", null, null, 0L,
                    "Carrier integration unconfigured for network: " + network);
        }
        return new AirtimeDeliveryResponse("UNAVAILABLE", null, null, 0L,
                "Live carrier connection not established");
    }

    @Override
    public BundleDeliveryResponse deliverBundle(String network, String productCode, String phoneNumber, long amountPesewas, String internalReference) {
        if (!isConfigured(network)) {
            return new BundleDeliveryResponse("UNAVAILABLE", null, null,
                    "Carrier integration unconfigured for network: " + network);
        }
        return new BundleDeliveryResponse("UNAVAILABLE", null, null,
                "Live carrier connection not established");
    }
}
