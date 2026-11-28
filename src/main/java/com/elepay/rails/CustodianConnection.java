package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class CustodianConnection {
    private final List<CustodianAccountProvider> providers;
    private final JdbcClient db;

    CustodianConnection(List<CustodianAccountProvider> providers, JdbcClient db) {
        this.providers = providers;
        this.db = db;
    }

    void approve(String expected, String actor) {
        var account = discover();
        if (!fingerprint(account).equals(expected)) {
            throw ApiException.conflict("custodian_account_changed", "The provider returned a different account. Submit a new connection request.");
        }
        activate(account, actor);
    }

    CustodianAccountProvider.Account discover() {
        if (providers.size() != 1) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "custodian_api_not_configured",
                    "Configure one custodian API adapter to connect a trust account.");
        }
        var account = providers.get(0).verifiedTrustAccount();
        if (account == null || !"GHS".equals(account.currency()) || account.bankCode() == null
                || !account.bankCode().matches("[A-Za-z0-9_-]{1,32}") || account.accountNumber() == null
                || !account.accountNumber().matches("[A-Za-z0-9-]{6,64}")) {
            throw ApiException.unprocessable("invalid_custodian_account", "The custodian API did not return a valid GHS trust account.");
        }
        return account;
    }

    static String fingerprint(CustodianAccountProvider.Account account) {
        return Idempotency.sha256(account.bankCode() + "|" + account.accountNumber() + "|" + account.currency());
    }

    void activate(CustodianAccountProvider.Account account, String actor) {
        int updated = db.sql("""
                UPDATE custodian_trust_account SET bank_code = :bank, bank_account_number = :number,
                    last_reconciled_bank_balance_pesewas = NULL, updated_by = :actor, updated_at = now()
                WHERE account_code = :code AND bank_code IS NULL AND bank_account_number IS NULL""")
                .param("bank", account.bankCode()).param("number", account.accountNumber()).param("actor", actor)
                .param("code", CustodianBankBackstopService.PRIMARY_TRUST_CODE).update();
        if (updated != 1) {
            throw ApiException.conflict("custodian_connection_unavailable", "An unconfigured primary trust account is required.");
        }
    }
}
