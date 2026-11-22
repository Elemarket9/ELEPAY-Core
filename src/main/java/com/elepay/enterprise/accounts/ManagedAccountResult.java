package com.elepay.enterprise.accounts;

import java.util.UUID;

public record ManagedAccountResult(
        String externalCustomerId,
        UUID elepayAccountId,
        String status,
        boolean created,
        boolean duplicateFound,
        SubaccountBalances subaccountBalances
) {}
