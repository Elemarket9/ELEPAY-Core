package com.elepay.identity;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The only requests a paired POS terminal ({@code pos_live_*} key) may make.
 * Everything else is refused at the gateway with {@code 403 pos_terminal_key_restricted}.
 */
public final class PosDevicePaths {
    private static final String SEG = "[^/]+";
    private static final List<Pattern> READ_METHODS = List.of(
            Pattern.compile("^/v1/pos/orders(?:/.*)?$"),
            Pattern.compile("^/v1/pos/carts(?:/.*)?$"),
            Pattern.compile("^/v1/pos/shifts(?:/.*)?$"),
            Pattern.compile("^/v1/pos/terminals/" + SEG + "/events$"),
            Pattern.compile("^/v1/pos/sync$")
    );
    private static final List<Pattern> WRITE_METHODS = List.of(
            Pattern.compile("^/v1/pos/orders(?:/.*)?$"),
            Pattern.compile("^/v1/pos/carts(?:/.*)?$"),
            Pattern.compile("^/v1/pos/shifts(?:/.*)?$"),
            Pattern.compile("^/v1/pos/cashiers/auth$"),
            Pattern.compile("^/v1/pos/terminals/" + SEG + "/events$"),
            Pattern.compile("^/v1/pos/orders/" + SEG + "/nfc-tap$"),
            Pattern.compile("^/v1/pos/terminals/" + SEG + "/nfc-tap$"),
            Pattern.compile("^/v1/pos/sync$")
    );

    private PosDevicePaths() {}

    public static boolean allowed(String method, String path) {
        if (method == null || path == null) return false;
        List<Pattern> rules = switch (method.toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD" -> READ_METHODS;
            case "POST", "PUT", "PATCH", "DELETE" -> WRITE_METHODS;
            default -> List.of();
        };
        return rules.stream().anyMatch(p -> p.matcher(path).matches());
    }
}
