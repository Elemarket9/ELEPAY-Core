package com.elepay.rails;

/**
 * Beneficiary name verification offered by a connected network (mobile-money
 * operator account lookup, bank account name enquiry). Implementations must
 * return the name supplied by the account system itself, never a guess.
 */
public interface NameVerification {

    enum Outcome { VERIFIED, NOT_FOUND, UNAVAILABLE }

    record Result(Outcome outcome, String name, String source) {
        public static Result unavailable(String source) {
            return new Result(Outcome.UNAVAILABLE, null, source);
        }
    }

    /** Rail served: {@code momo} or {@code bank}. */
    String rail();

    /**
     * @param network MoMo network (mtn, telecel, airteltigo) or bank code
     * @param account normalized MSISDN or bank account number
     */
    Result verify(String network, String account);
}
