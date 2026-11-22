package com.elepay.enterprise.accounts;

import java.util.Map;
import java.util.UUID;

public record KycSyncRequest(
        UUID accountId,
        int newTier,
        String reason,
        Map<String, Object> kycEvidence
) {
    public KycSyncRequest {
        if (kycEvidence == null) {
            kycEvidence = Map.of();
        }
    }
}
