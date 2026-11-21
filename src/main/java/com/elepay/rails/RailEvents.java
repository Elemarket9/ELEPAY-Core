package com.elepay.rails;

import java.util.UUID;

/**
 * Published synchronously inside the transaction that records a terminal
 * rail status, so listeners' ledger postings commit atomically with it.
 */
public final class RailEvents {
    private RailEvents() {}

    public record Succeeded(UUID railTxId, String rail, String direction, String purpose, UUID purposeId,
                            long amount, long railFee) {}

    public record Failed(UUID railTxId, String rail, String direction, String purpose, UUID purposeId,
                         long amount, String reason) {}

    /** A previously succeeded payout or refund came back from the receiving network. */
    public record Returned(UUID railTxId, String rail, String direction, String purpose, UUID purposeId,
                           long amount, String reason) {}
}
