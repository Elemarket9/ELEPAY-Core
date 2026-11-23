package com.elepay.enterprise.accounts;

import java.util.List;

public record BulkCreateResult(
        int totalRequested,
        int createdCount,
        int existingCount,
        int duplicatesFlagged,
        int failedCount,
        List<ManagedAccountResult> results,
        List<BulkItemError> errors
) {
    public boolean hasErrors() {
        return failedCount > 0;
    }

    public int successCount() {
        return createdCount;
    }
}
