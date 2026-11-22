package com.elepay.enterprise.accounts;

public record BulkItemError(
        String externalCustomerId,
        String errorCode,
        String errorMessage
) {}
