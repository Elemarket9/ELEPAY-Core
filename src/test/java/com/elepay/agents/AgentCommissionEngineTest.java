package com.elepay.agents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentCommissionEngineTest {

    private final AgentCommissionEngine engine = new AgentCommissionEngine(null, null);

    @Test
    @DisplayName("Fallback commission calculation: 50 bps, 80/20 agent/platform split with 50 pesewas minimum")
    void testFallbackCalculation() {
        // Small amount: 5,000 pesewas (GHS 50.00). 50 bps = 25 pesewas, but min is 50 pesewas.
        var comm = engine.calculateCommission("TIER_UNKNOWN", "CASH_IN", 5000L);
        assertThat(comm.grossCommissionPesewas()).isEqualTo(50L);
        assertThat(comm.platformSharePesewas()).isEqualTo(10L); // 20%
        assertThat(comm.agentSharePesewas()).isEqualTo(40L);    // 80%

        // Balanced invariant: gross = agentShare + platformShare
        assertThat(comm.grossCommissionPesewas()).isEqualTo(comm.agentSharePesewas() + comm.platformSharePesewas());
    }

    @Test
    @DisplayName("Large transaction commission calculation: integer arithmetic without floating-point drift")
    void testLargeAmountCalculation() {
        // 10,000,000 pesewas (GHS 100,000). 50 bps = 50,000 pesewas.
        var comm = engine.calculateCommission("TIER_UNKNOWN", "CASH_IN", 10_000_000L);
        assertThat(comm.grossCommissionPesewas()).isEqualTo(50_000L);
        assertThat(comm.platformSharePesewas()).isEqualTo(10_000L);
        assertThat(comm.agentSharePesewas()).isEqualTo(40_000L);

        // Sum invariant
        assertThat(comm.agentSharePesewas() + comm.platformSharePesewas()).isEqualTo(comm.grossCommissionPesewas());
    }
}
