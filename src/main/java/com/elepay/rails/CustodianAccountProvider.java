package com.elepay.rails;

/** Implemented by the selected custodian's authenticated server-side API adapter. */
public interface CustodianAccountProvider {
    /** Return the active GHS trust account verified for this deployment by the provider. */
    Account verifiedTrustAccount();

    record Account(String bankCode, String accountNumber, String currency) {
        @Override public String toString() { return "CustodianAccount[redacted]"; }
    }
}
