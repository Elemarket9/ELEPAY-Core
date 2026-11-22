package com.elepay.enterprise.accounts;

import java.util.List;
import java.util.UUID;

public record BulkCreateAccountsRequest(
        UUID enterpriseId,
        List<CreateManagedAccountRequest> accounts,
        DuplicateAction defaultDuplicateAction
) {
    public BulkCreateAccountsRequest {
        if (accounts == null) {
            accounts = List.of();
        } else {
            accounts = List.copyOf(accounts);
        }
        if (defaultDuplicateAction == null) {
            defaultDuplicateAction = DuplicateAction.FLAG;
        }
    }
}
