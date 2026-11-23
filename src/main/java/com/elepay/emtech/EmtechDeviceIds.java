package com.elepay.emtech;

import java.util.UUID;

/** Stable Emtech device ids. Physical terminals use their UUID. Channels use a virtual id. */
public final class EmtechDeviceIds {
    private EmtechDeviceIds() {}

    public static String terminal(UUID terminalId) {
        return terminalId.toString();
    }

    public static String merchantChannel(UUID merchantId, String channel) {
        return "elepay-" + slug(channel) + "-" + merchantId;
    }

    public static String platform(String channel) {
        return "elepay-channel-" + slug(channel);
    }

    public static String slug(String channel) {
        return switch (channel == null ? "" : channel) {
            case "MOBILE_APP" -> "app";
            case "MOBILE_USSD" -> "ussd";
            case "WEB" -> "web";
            case "POS" -> "pos";
            case "ATM" -> "atm";
            case "KIOSK" -> "kiosk";
            default -> "other";
        };
    }

    public static String connectionFor(String channel) {
        return "MOBILE_USSD".equals(channel) ? "WIRELESS_PHONE" : "NETWORK";
    }
}
