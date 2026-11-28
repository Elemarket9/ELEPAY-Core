package com.elepay.rails;

import java.time.OffsetDateTime;
import java.util.List;

/** Bank-specific implementation must use authenticated live reporting and payment APIs. */
public interface SettlementBank extends CustodianAccountProvider, PaymentRail {
    /** True only if this collection rail is mandated to settle into this account. */
    boolean mayCollect(Account account, String collectionRail);
    boolean mayPay(Account account);
    /** Posted credit and bank-supplied remittance references, obtained from the bank, never the merchant. */
    record Credit(Account account, String reference, long amount, OffsetDateTime bookedAt, List<String> collectionReferences) {}
    /** Returns null while the collection has no booked credit. */
    String creditReference(Account account, String collectionReference);
    Credit bookedCredit(Account account, String reference);
    /** Booked statement debit, independently retrieved using the original instruction reference. */
    record Debit(Account source, String reference, String instructionReference, String beneficiaryNetwork,
                 String beneficiaryAccount, String currency, long amount, OffsetDateTime bookedAt) {}
    Debit bookedDebit(Account account, String instructionReference);
    /** The bank must enforce this source binding and idempotency by payout.reference(). */
    Result payFrom(Account source, Payout payout);
    /** Verify the source, beneficiary and original instruction before returning a definitive result. */
    Result queryPayout(Account source, Payout payout, String bankReference);
}
