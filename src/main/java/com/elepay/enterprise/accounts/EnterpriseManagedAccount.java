package com.elepay.enterprise.accounts;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record EnterpriseManagedAccount(
        UUID id,
        UUID enterpriseId,
        String externalCustomerId,
        String accountType,
        String displayName,
        String phone,
        String email,
        String nationalId,
        int kycTier,
        String status,
        String currency,
        Map<String, Object> metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public boolean isActive() {
        return "active".equalsIgnoreCase(status) || "flagged_duplicate".equalsIgnoreCase(status);
    }

    public boolean isFlaggedDuplicate() {
        return "flagged_duplicate".equalsIgnoreCase(status);
    }
}
