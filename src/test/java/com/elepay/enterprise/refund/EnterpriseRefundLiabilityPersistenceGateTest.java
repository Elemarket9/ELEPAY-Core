package com.elepay.enterprise.refund;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A production liability service must never substitute unpersisted local state for PostgreSQL. */
class EnterpriseRefundLiabilityPersistenceGateTest {
    @Test
    void unavailablePostgresCannotAcknowledgeALiabilityOrSettlement() {
        var service = new EnterpriseRefundLiabilityTracker(null);
        UUID enterprise = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();

        assertThatThrownBy(() -> service.recordLiability(enterprise, merchant, UUID.randomUUID(), 10_000))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DATABASE_NOT_CONFIGURED");
        assertThatThrownBy(() -> service.getOutstandingLiability(enterprise, merchant))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DATABASE_NOT_CONFIGURED");
        assertThatThrownBy(() -> service.settleLiabilities(enterprise, merchant, 10_000))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DATABASE_NOT_CONFIGURED");
    }
}
