package com.elepay.bills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CarrierAndUtilityTest {

    private final AirtimeDataPurchaseEngine engine = new AirtimeDataPurchaseEngine(null, null, null, null);
    private final DefaultUtilityProviderClient utilityClient = new DefaultUtilityProviderClient("", "", "", "");
    private final DefaultCarrierIntegrationClient carrierClient = new DefaultCarrierIntegrationClient("", "", "", "", "", "");

    @Test
    @DisplayName("Detect mobile telco network from Ghanaian phone prefix")
    void testDetectNetwork() {
        // MTN prefixes
        assertThat(engine.detectNetwork("0241234567")).isEqualTo("MTN");
        assertThat(engine.detectNetwork("0541234567")).isEqualTo("MTN");
        assertThat(engine.detectNetwork("0551234567")).isEqualTo("MTN");
        assertThat(engine.detectNetwork("0591234567")).isEqualTo("MTN");
        assertThat(engine.detectNetwork("0251234567")).isEqualTo("MTN");

        // Telecel prefixes
        assertThat(engine.detectNetwork("0201234567")).isEqualTo("TELECEL");
        assertThat(engine.detectNetwork("0501234567")).isEqualTo("TELECEL");

        // AirtelTigo prefixes
        assertThat(engine.detectNetwork("0271234567")).isEqualTo("AIRTELTIGO");
        assertThat(engine.detectNetwork("0571234567")).isEqualTo("AIRTELTIGO");
        assertThat(engine.detectNetwork("0261234567")).isEqualTo("AIRTELTIGO");
        assertThat(engine.detectNetwork("0561234567")).isEqualTo("AIRTELTIGO");

        // International format
        assertThat(engine.detectNetwork("+233241234567")).isEqualTo("MTN");
        assertThat(engine.detectNetwork("233201234567")).isEqualTo("TELECEL");

        // Invalid prefix
        assertThatThrownBy(() -> engine.detectNetwork("0302123456")).isInstanceOf(IllegalArgumentException.class);
        // Too short
        assertThatThrownBy(() -> engine.detectNetwork("12")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Unconfigured default utility client fails closed with UNAVAILABLE")
    void testUnconfiguredUtilityClientFailsClosed() {
        assertThat(utilityClient.isConfigured("ECG")).isFalse();
        assertThat(utilityClient.isConfigured("GWCL")).isFalse();

        var ver = utilityClient.verifyAccount("ECG", "12345678");
        assertThat(ver.status()).isEqualTo("UNAVAILABLE");

        var prepaid = utilityClient.purchasePrepaid("ECG", "12345678", 5000L);
        assertThat(prepaid.status()).isEqualTo("UNAVAILABLE");
        assertThat(prepaid.stsToken()).isNull();

        var postpaid = utilityClient.payPostpaid("GWCL", "ACC-123456", 5000L);
        assertThat(postpaid.status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    @DisplayName("Unconfigured default carrier client fails closed with UNAVAILABLE")
    void testUnconfiguredCarrierClientFailsClosed() {
        assertThat(carrierClient.isConfigured("MTN")).isFalse();
        assertThat(carrierClient.isConfigured("TELECEL")).isFalse();
        assertThat(carrierClient.isConfigured("AIRTELTIGO")).isFalse();

        var airtime = carrierClient.deliverAirtime("MTN", "0241234567", 1000L, "REF-1");
        assertThat(airtime.status()).isEqualTo("UNAVAILABLE");
        assertThat(airtime.carrierReference()).isNull();

        var bundle = carrierClient.deliverBundle("TELECEL", "DATA_2GB", "0201234567", 1500L, "REF-2");
        assertThat(bundle.status()).isEqualTo("UNAVAILABLE");
        assertThat(bundle.carrierReference()).isNull();
    }

    @Test
    @DisplayName("UtilityPaymentService input validation rejects invalid inputs")
    void testUtilityValidation() {
        UtilityPaymentService service = new UtilityPaymentService(null, utilityClient, null);

        // Account number too short
        assertThatThrownBy(() -> service.verifyUtilityAccount("ECG", "123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid utility account");

        // Minimum ECG prepaid amount is 1000 pesewas
        assertThatThrownBy(() -> service.payEcgPrepaid(UUID.randomUUID(), "12345678", 500L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Minimum ECG prepaid recharge is 1,000 pesewas");

        // Meter number too short
        assertThatThrownBy(() -> service.payEcgPrepaid(UUID.randomUUID(), "1234", 2000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid ECG meter number");
    }
}
