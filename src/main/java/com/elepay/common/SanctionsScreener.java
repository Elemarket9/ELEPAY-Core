package com.elepay.common;

/**
 * Module-boundary SPI for sanctions name screening so financial execution modules
 * (e.g. payout) can screen names without creating a Spring Modulith cycle with compliance.
 */
public interface SanctionsScreener {
    boolean isSanctionedName(String candidateName);
}
