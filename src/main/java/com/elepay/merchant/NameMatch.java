package com.elepay.merchant;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Compares a merchant legal name with the name returned by account enquiry. */
public final class NameMatch {
    private static final Set<String> STOP = Set.of("ltd", "limited", "inc", "plc", "co", "company", "the", "and");

    private NameMatch() {}

    /** 0–100. 100 is an exact token match after legal suffixes are removed. */
    public static int score(String legalName, String accountName) {
        Set<String> left = tokens(legalName);
        Set<String> right = tokens(accountName);
        if (left.isEmpty() || right.isEmpty()) return 0;
        if (left.equals(right)) return 100;
        long shared = left.stream().filter(right::contains).count();
        if (shared == 0) return 0;
        return (int) Math.round(100.0 * shared / Math.max(left.size(), right.size()));
    }

    private static Set<String> tokens(String value) {
        if (value == null) return Set.of();
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ");
        return Arrays.stream(cleaned.split("\\s+"))
                .filter(t -> t.length() > 1 && !STOP.contains(t))
                .collect(Collectors.toSet());
    }
}
