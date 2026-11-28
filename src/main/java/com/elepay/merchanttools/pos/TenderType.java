package com.elepay.merchanttools.pos;

public enum TenderType {
    CASH,
    MOMO,
    CARD,
    WALLET,
    GHQR,
    BANK_TRANSFER,
    /** Restaurant's own external MoMo merchant SIM — recorded on POS only; ELEPAY never touches funds. */
    EXTERNAL_MOMO,
    /** Restaurant's own external bank card terminal — recorded on POS only; ELEPAY never touches funds. */
    EXTERNAL_CARD,
    /** Restaurant's own Hubtel POS/payment account — recorded on POS only; ELEPAY never touches funds. */
    EXTERNAL_HUBTEL;

    /** True for externally settled non-cash tenders recorded for POS/Z-report tracking without ELEPAY rails. */
    public boolean isExternalRecorded() {
        return this == BANK_TRANSFER
                || this == EXTERNAL_MOMO
                || this == EXTERNAL_CARD
                || this == EXTERNAL_HUBTEL;
    }

    /** True for tenders recorded directly on POS without creating an ELEPAY Core checkout. */
    public boolean isDirectPosTender() {
        return this == CASH || isExternalRecorded();
    }

    public String displayLabel() {
        return switch (this) {
            case CASH -> "Cash";
            case MOMO -> "ELEPAY MoMo";
            case CARD -> "ELEPAY Card";
            case WALLET -> "ELEPAY Wallet";
            case GHQR -> "ELEPAY GhQR";
            case BANK_TRANSFER -> "Bank transfer";
            case EXTERNAL_MOMO -> "Own MoMo";
            case EXTERNAL_CARD -> "External card";
            case EXTERNAL_HUBTEL -> "Hubtel";
        };
    }
}
