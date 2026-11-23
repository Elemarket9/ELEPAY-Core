package com.elepay.enterprise.payout;

import com.elepay.enterprise.accounts.EnterpriseManagedAccountService;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages enterprise settlement funding, prefunding allocations, and liquidity sufficiency checks.
 */
@Service
public class EnterpriseSettlementFundingService {

    private final Ledger ledger;

    public EnterpriseSettlementFundingService(Ledger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger cannot be null");
    }

    /**
     * Checks if the enterprise has sufficient available settlement funding to cover a requested payout.
     */
    public EnterpriseFundingCheckResult checkFunding(UUID enterpriseId, long requiredPesewas, String currency) {
        if (requiredPesewas <= 0) {
            throw new IllegalArgumentException("Required amount must be positive: " + requiredPesewas);
        }
        String curr = currency != null ? currency.toUpperCase() : "GHS";
        String availableAccount = Ledger.code("merchant", enterpriseId, "available", curr);

        long balance = ledger.balance(availableAccount);

        // If available bucket balance is zero or less, check enterprise clearing account
        if (balance < requiredPesewas) {
            String clearingAccount = EnterpriseManagedAccountService.enterpriseClearingCode(enterpriseId, curr);
            long clearingBal = ledger.balance(clearingAccount);
            if (clearingBal > 0) {
                balance = Math.addExact(balance, clearingBal);
            }
        }

        if (balance >= requiredPesewas) {
            return EnterpriseFundingCheckResult.ok(balance, requiredPesewas);
        } else {
            return EnterpriseFundingCheckResult.insufficient(balance, requiredPesewas);
        }
    }

    /**
     * Credits enterprise prefunded balance from the enterprise clearing account into its available settlement bucket.
     */
    @Transactional
    public void creditPrefunding(UUID enterpriseId, long amountPesewas, String currency, String reference) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Prefunding amount must be positive: " + amountPesewas);
        }
        String curr = currency != null ? currency.toUpperCase() : "GHS";
        String clearingAccount = EnterpriseManagedAccountService.enterpriseClearingCode(enterpriseId, curr);
        String availableAccount = ledger.open("merchant", enterpriseId, "available", curr);
        ledger.openSystem(clearingAccount, "clearing", curr, true);

        ledger.post(Ledger.Posting.of(
                "enterprise-prefunding",
                reference != null ? reference : UUID.randomUUID().toString(),
                "enterprise_prefunding_deposit",
                List.of(
                        Leg.debit(clearingAccount, amountPesewas),
                        Leg.credit(availableAccount, amountPesewas)
                )
        ).describedAs("Prefunding allocation for enterprise: " + enterpriseId));
    }
}
