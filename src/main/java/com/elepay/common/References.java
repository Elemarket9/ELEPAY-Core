package com.elepay.common;

import java.security.SecureRandom;

/**
 * Short human-readable references for receipts and support. Crockford-style
 * alphabet without ambiguous characters; 8 symbols give ~40 bits.
 */
public final class References {
    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final ThreadLocal<SecureRandom> RANDOM = ThreadLocal.withInitial(SecureRandom::new);

    private References() {}

    public static String next(String prefix) {
        SecureRandom rng = RANDOM.get();
        StringBuilder sb = new StringBuilder(prefix.length() + 13).append(prefix).append('-');
        for (int i = 0; i < 12; i++) sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]);
        return sb.toString();
    }

    public static String digits(int length) {
        SecureRandom rng = RANDOM.get();
        StringBuilder sb = new StringBuilder(length);
        sb.append((char) ('1' + rng.nextInt(9)));
        for (int i = 1; i < length; i++) sb.append((char) ('0' + rng.nextInt(10)));
        return sb.toString();
    }

    /** Normalises Ghana phone numbers to 233XXXXXXXXX. */
    public static String msisdn(String phone) {
        if (phone == null) throw ApiException.badRequest("invalid_phone", "Phone number is required");
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("0") && digits.length() == 10) digits = "233" + digits.substring(1);
        if (!digits.matches("233[1-9][0-9]{8}")) throw ApiException.badRequest("invalid_phone", "Use a Ghana phone number such as 0241234567");
        return digits;
    }
}
