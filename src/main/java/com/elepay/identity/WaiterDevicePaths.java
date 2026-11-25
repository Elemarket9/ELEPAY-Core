package com.elepay.identity;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The only requests a paired waiter phone (wd_live_* key) may make: its own branch's tables, the menu, and taking,
 * tracking, serving, billing and closing waiter orders. No payments, cashier claims, table assignment, staff or
 * merchant data. Everything else is refused at the gateway.
 */
public final class WaiterDevicePaths {
    private static final String UUID = "[0-9a-fA-F-]{36}";
    private static final String BASE = "^/v1/restaurant/waiter/" + UUID;
    private static final List<Pattern> GET = List.of(
            Pattern.compile(BASE + "/tables$"),
            Pattern.compile(BASE + "/menu$"),
            Pattern.compile(BASE + "/orders$"),
            Pattern.compile(BASE + "/me$"));
    private static final List<Pattern> POST = List.of(
            Pattern.compile(BASE + "/orders$"),
            Pattern.compile(BASE + "/orders/" + UUID + "/(served|bill|close-table)$"));

    private WaiterDevicePaths() {}

    public static boolean allowed(String method, String path) {
        if (method == null || path == null) return false;
        List<Pattern> rules = switch (method.toUpperCase(java.util.Locale.ROOT)) {
            case "GET" -> GET;
            case "POST" -> POST;
            default -> List.of();
        };
        return rules.stream().anyMatch(p -> p.matcher(path).matches());
    }
}
