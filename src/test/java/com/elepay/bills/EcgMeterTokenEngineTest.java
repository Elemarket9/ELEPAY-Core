package com.elepay.bills;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EcgMeterTokenEngineTest extends PostgresTestBase {

    @Autowired
    private EcgMeterTokenEngine tokenEngine;

    @Test
    @DisplayName("Calculate ECG tariff with street light and electrification levies")
    void testTariffCalculation() {
        long grossAmountPesewas = 10_000L; // 100.00 GHS

        var tariff = tokenEngine.calculateTariff(
                grossAmountPesewas,
                EcgMeterTokenEngine.CustomerCategory.RESIDENTIAL_STANDARD
        );

        assertThat(tariff).isNotNull();
        assertThat(tariff.grossAmountPesewas()).isEqualTo(10_000L);
        assertThat(tariff.streetLightLevyPesewas()).isGreaterThan(0L);
        assertThat(tariff.electrificationLevyPesewas()).isGreaterThan(0L);
        assertThat(tariff.netEnergyChargePesewas()).isLessThan(grossAmountPesewas);
        assertThat(tariff.energyUnitsKwh()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Purchase ECG prepaid token: 20-digit STS generation and ledger deduction")
    void testPurchaseEcgPrepaidToken() {
        UUID customerId = UUID.randomUUID();
        String meterNumber = "14123456789";

        var token = tokenEngine.purchaseToken(
                customerId,
                meterNumber,
                "Ama Serwaa",
                5_000L, // 50.00 GHS
                EcgMeterTokenEngine.CustomerCategory.RESIDENTIAL_STANDARD
        );

        assertThat(token).isNotNull();
        assertThat(token.reference()).startsWith("ECG-STS-");
        assertThat(token.stsToken()).hasSize(24); // 20 digits + 4 dashes = 24 chars
        assertThat(token.stsToken().replace("-", "")).hasSize(20);
        assertThat(token.status()).isEqualTo("COMPLETED");
        assertThat(token.journalTxId()).isNotNull();
    }
}
