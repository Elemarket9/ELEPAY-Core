package com.elepay.enterprise.accounts;

import java.util.UUID;

public record SubaccountBalances(
        UUID accountId,
        long operating,
        long holding,
        long reserved,
        long rewards,
        long total,
        String currency
) {
    public static SubaccountBalances of(UUID accountId, long operating, long holding, long reserved, long rewards, String currency) {
        long total = Math.addExact(
                Math.addExact(operating, holding),
                Math.addExact(reserved, rewards)
        );
        return new SubaccountBalances(accountId, operating, holding, reserved, rewards, total, currency);
    }
}
