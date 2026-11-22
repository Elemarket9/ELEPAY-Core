package com.elepay.enterprise.accounts;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Enterprise(
        UUID id,
        String code,
        String name,
        String status,
        String contactEmail,
        String contactPhone,
        String webhookUrl,
        String kycStatus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }
}
