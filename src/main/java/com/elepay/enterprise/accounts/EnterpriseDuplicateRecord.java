package com.elepay.enterprise.accounts;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EnterpriseDuplicateRecord(
        UUID id,
        UUID enterpriseId,
        UUID accountId,
        UUID matchedAccountId,
        String matchField,
        String matchValue,
        String resolution,
        String resolutionNotes,
        OffsetDateTime detectedAt,
        OffsetDateTime resolvedAt
) {}
