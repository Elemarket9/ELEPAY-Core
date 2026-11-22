package com.elepay.enterprise.accounts;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EnterpriseIdentityMapping(
        UUID id,
        UUID enterpriseId,
        String externalCustomerId,
        UUID elepayAccountId,
        String idHash,
        OffsetDateTime mappedAt
) {}
