package com.elepay.identity;

import java.util.List;
import java.util.regex.Pattern;

/** The only requests a paired kitchen device (kd_live_* key) may make. Everything else is refused at the gateway. */
public final class KitchenDevicePaths {
    private static final String UUID = "[0-9a-fA-F-]{36}";
    private static final List<Pattern> GET = List.of(
            Pattern.compile("^/v1/restaurant/kds/sync$"),
            Pattern.compile("^/v1/restaurant/kds/stations/merchant/" + UUID + "$"));
    private static final List<Pattern> POST = List.of(
            Pattern.compile("^/v1/restaurant/kds/push/register$"),
            Pattern.compile("^/v1/restaurant/kds/push/unregister$"),
            Pattern.compile("^/v1/restaurant/kds/tickets/" + UUID + "/advance$"),
            Pattern.compile("^/v1/restaurant/kds/tickets/" + UUID + "/stations/" + UUID + "/advance$"),
            Pattern.compile("^/v1/restaurant/kds/tickets/" + UUID + "/cancel$"),
            Pattern.compile("^/v1/restaurant/kds/tickets/" + UUID + "/reprint$"),
            Pattern.compile("^/v1/restaurant/kds/print-jobs/claim$"),
            Pattern.compile("^/v1/restaurant/kds/print-jobs/" + UUID + "/result$"));

    private KitchenDevicePaths() {}

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
