package com.elepay.rails;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class MtnProductionRailTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void productionHostCannotBeReplacedBySandboxOrArbitraryDestination() {
        assertThatThrownBy(() -> MtnProductionRail.productionBase("https://sandbox.momodeveloper.mtn.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MtnProductionRail.productionBase("https://example.org"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MtnProductionRail.productionBase("https://proxy.momoapi.mtn.com").getHost())
                .isEqualTo("proxy.momoapi.mtn.com");
    }

    @Test void providerIdIsStableAndDirectionSpecific() {
        var id=MtnProductionRail.providerId("charge","EP-123");
        assertThat(id.version()).isEqualTo(4);
        assertThat(id).isEqualTo(MtnProductionRail.providerId("charge","EP-123"));
        assertThat(id).isNotEqualTo(MtnProductionRail.providerId("payout","EP-123"));
    }

    @Test void successRequiresReferenceAmountCurrencyAndFinancialId() {
        var confirmed = json.readTree("""
                {"externalId":"EPC123","status":"SUCCESSFUL","amount":"10.25","currency":"GHS","financialTransactionId":"F-123"}
                """);
        var result=MtnProductionRail.parseStatus(confirmed,"EPC123");
        assertThat(result.status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(result.amount()).isEqualTo(1025L);
        assertThat(result.railReference()).isEqualTo("F-123");
        assertThat(MtnProductionRail.parseStatus(confirmed,"other").status()).isEqualTo(PaymentRail.Status.UNKNOWN);
        var missing = json.readTree("""
                {"externalId":"EPC123","status":"SUCCESSFUL","amount":"10.25","currency":"GHS"}
                """);
        assertThat(MtnProductionRail.parseStatus(missing,"EPC123").status()).isEqualTo(PaymentRail.Status.UNKNOWN);
    }
}
