package com.elepay.emtech;

import java.util.ArrayList;
import java.util.List;

/**
 * One synthetic day of ELEPAY activity in Accra, Tema and Kumasi.
 * Places are already the server-side result: terminal city for POS, profile, coarse IP,
 * MNO or branch for other channels. No customer GPS is included.
 */
public final class AccraDay {
    private AccraDay() {}

    public record Item(String resource, String idempotencyKey, String sourceType, String sourceId, Object fact) {}

    public static List<Item> day(String run, String emoneyClassification) {
        String accraPos = "pos-accra-" + run;
        String temaPos = "pos-tema-" + run;
        String kumasiPos = "pos-kumasi-" + run;
        String ama = "customer-ama-" + run;
        String kofi = "customer-kofi-" + run;
        String akua = "customer-akua-" + run;
        String yaw = "customer-yaw-" + run;
        String osu = "merchant-osu-" + run;
        String community = "merchant-community-" + run;
        String kejetia = "merchant-kejetia-" + run;
        List<Item> items = new ArrayList<>();
        items.add(device(accraPos, "Accra", "GH-AA", "5.603717", "-0.186964", "NETWORK", "Osu counter"));
        items.add(device(temaPos, "Tema", "GH-AA", "5.669800", "-0.016600", "WIRELESS_PHONE", "Community 1 stall"));
        items.add(device(kumasiPos, "Kumasi", "GH-AH", "6.688480", "-1.624430", "NETWORK", "Kejetia lane"));
        items.add(device("elepay-web-" + osu, "Accra", "GH-AA", "5.603717", "-0.186964", "NETWORK", "ELEPAY web channel"));
        items.add(device("elepay-ussd-" + community, "Tema", "GH-AA", "5.669800", "-0.016600", "WIRELESS_PHONE", "ELEPAY ussd channel"));
        items.add(device("elepay-app-" + kejetia, "Kumasi", "GH-AH", "6.688480", "-1.624430", "NETWORK", "ELEPAY app channel"));
        String platformWeb = "elepay-channel-web-" + run;
        items.add(device(platformWeb, "Accra", "GH-AA", "5.603717", "-0.186964", "NETWORK", "ELEPAY platform web channel"));

        items.add(event(accraPos, "OPERATIONAL", "ONLINE", "2026-11-23T08:00:00Z", "Terminal online"));
        items.add(event(accraPos, "IMPAIRED", "NETWORK_FAILURE", "2026-11-23T12:05:00Z", "Mobile data dropped"));
        items.add(event(accraPos, "OPERATIONAL", "ONLINE", "2026-11-23T12:40:00Z", "Link restored"));
        items.add(event(temaPos, "OPERATIONAL", "ONLINE", "2026-11-23T08:10:00Z", "Terminal online"));
        items.add(event(kumasiPos, "IMPAIRED", "MAINTENANCE", "2026-11-23T16:00:00Z", "Paper roll replaced"));

        items.add(payment("momo-accra-" + run, "POS", accraPos, 15050, 150, 14900,
                ama, "233241110001", "momo", "MTN", null, "Accra", "GH-AA", "TERMINAL", 2, "VERIFIED", false,
                osu, "wallet", "ELEPAY", emoneyClassification, "Accra", "GH-AA", "BRANCH", "succeeded", null));
        items.add(payment("card-tema-" + run, "POS", temaPos, 8000, 120, 7880,
                kofi, kofi, "card", "VISA", null, "Tema", "GH-AA", "TERMINAL", 1, null, false,
                community, "wallet", "ELEPAY", emoneyClassification, "Tema", "GH-AA", "BRANCH", "succeeded", null));
        items.add(payment("cash-kumasi-" + run, "POS", kumasiPos, 5000, 0, 5000,
                akua, akua, "cash", "ELEPAY", null, "Kumasi", "GH-AH", "TERMINAL", 3, "VERIFIED", true,
                kejetia, "wallet", "ELEPAY", emoneyClassification, "Kumasi", "GH-AH", "BRANCH", "succeeded", null));
        items.add(payment("web-wallet-" + run, "WEB", "elepay-web-" + osu, 2500, 0, 2500,
                ama, ama, "wallet", "ELEPAY", emoneyClassification, "Accra", "GH-AA", "PROFILE", 2, "VERIFIED", false,
                osu, "wallet", "ELEPAY", emoneyClassification, "Accra", "GH-AA", "BRANCH", "succeeded", null));
        items.add(payment("ussd-momo-" + run, "MOBILE_USSD", "elepay-ussd-" + community, 1000, 0, 1000,
                kofi, "233201110002", "momo", "Telecel", null, "Tema", "GH-AA", "MNO", 1, null, false,
                community, "wallet", "ELEPAY", emoneyClassification, "Tema", "GH-AA", "BRANCH", "succeeded", null));
        items.add(payment("app-airtime-" + run, "MOBILE_APP", "elepay-app-" + kejetia, 2000, 0, 2000,
                akua, akua, "wallet", "ELEPAY", emoneyClassification, "Kumasi", "GH-AH", "PROFILE", 3, "VERIFIED", true,
                "airtime-" + run, "airtime", "MTN", null, "Kumasi", "GH-AH", "PROFILE", "succeeded", null));
        items.add(payment("web-ip-" + run, "WEB", "elepay-web-" + osu, 750, 0, 750,
                yaw, yaw, "momo", "MTN", null, "Kumasi", "GH-AH", "COARSE_IP", 2, null, false,
                kejetia, "wallet", "ELEPAY", emoneyClassification, "Kumasi", "GH-AH", "BRANCH", "succeeded", null));
        items.add(payment("web-branch-" + run, "WEB", "elepay-web-" + osu, 640, 0, 640,
                "guest-" + run, "guest-" + run, "card", "CARD", null, "Accra", "GH-AA", "BRANCH", 0, null, false,
                osu, "wallet", "ELEPAY", emoneyClassification, "Accra", "GH-AA", "BRANCH", "succeeded", null));
        items.add(transfer("w2w-" + run, platformWeb, ama, "Accra", "GH-AA", akua, "Kumasi", "GH-AH", emoneyClassification));
        items.add(payment("momo-failed-" + run, "WEB", "elepay-web-" + osu, 3000, 0, 3000,
                kofi, "233241110003", "momo", "MTN", null, "Tema", "GH-AA", "PROFILE", 1, null, false,
                osu, "wallet", "ELEPAY", emoneyClassification, "Accra", "GH-AA", "BRANCH", "failed", "gateway timeout"));
        items.add(payment("card-rejected-" + run, "MOBILE_APP", "elepay-app-" + kejetia, 90000, 0, 90000,
                akua, akua, "card", "VISA", null, "Kumasi", "GH-AH", "PROFILE", 3, "VERIFIED", true,
                kejetia, "wallet", "ELEPAY", emoneyClassification, "Kumasi", "GH-AH", "BRANCH",
                "rejected", "insufficient funds"));
        items.add(payment("wallet-suspect-" + run, "WEB", "elepay-web-" + osu, 200000, 0, 200000,
                yaw, yaw, "wallet", "ELEPAY", emoneyClassification, "Accra", "GH-AA", "PROFILE", 2, "MISMATCHED", false,
                osu, "wallet", "ELEPAY", emoneyClassification, "Accra", "GH-AA", "BRANCH", "suspect", "sanctions review"));
        items.add(refund("refund-web-" + run, osu, "Accra", "GH-AA", ama, "Accra", "GH-AA", emoneyClassification));
        items.add(withdrawal("payout-tema-" + run, platformWeb, community, "Tema", "GH-AA", emoneyClassification));

        String fraud = "complaint-fraud-" + run;
        items.add(complaint(fraud, ama, "FRAUD", "OPENED", "2026-11-23T13:00:00Z", "Unrecognised MoMo debit", null));
        items.add(complaint(fraud, ama, "FRAUD", "PENDING_RESOLUTION", "2026-11-23T15:30:00Z",
                "Unrecognised MoMo debit", "Waiting on the issuer"));
        items.add(complaint("complaint-dispute-" + run, kofi, "DISPUTE", "OPENED", "2026-11-23T14:00:00Z",
                "Charged twice at Community 1", null));
        items.add(complaint("complaint-service-" + run, akua, "SERVICE_ERROR", "REOPENED", "2026-11-23T17:00:00Z",
                "Airtime not delivered", "Customer reopened the case"));
        items.add(complaint("complaint-down-" + run, yaw, "SYSTEM_DOWNTIME", "CLOSED", "2026-11-23T12:50:00Z",
                "Could not pay while the Accra terminal was offline", "Service restored"));
        items.add(complaint("complaint-conduct-" + run, ama, "MISCONDUCT OTHER", "RESOLVED", "2026-11-23T18:00:00Z",
                "Cashier refused a receipt", "Merchant apologised and reissued the receipt"));
        return List.copyOf(items);
    }

    private static Item device(String id, String city, String region, String lat, String lon, String connection, String name) {
        var fact = new RegulatoryFacts.Device(id, id.startsWith("pos-") ? "POS" : "OTHER", name, city, region, "GH",
                name, name, lat, lon, "ELEPAY-T1", "ELEPAY", "Sandbox day device", "2026-01-15T09:00:00Z", connection, 2025);
        return new Item("DEVICE", "device:" + id, "device", id, fact);
    }

    private static Item event(String deviceId, String health, String status, String at, String description) {
        var fact = new RegulatoryFacts.DeviceEvent(deviceId, health, status, at, description);
        return new Item("DEVICE_EVENT", "device-event:" + deviceId + ":" + at + ":" + status, "device_event", deviceId, fact);
    }

    private static Item payment(String id, String channel, String device, long origin, long fee, long destination,
                                String user, String account, String instrument, String provider, String regulatory,
                                String city, String region, String source, int tier, String card, boolean face,
                                String destAccount, String destInstrument, String destProvider, String destRegulatory,
                                String destCity, String destRegion, String destSource, String status, String reason) {
        var fact = new RegulatoryFacts.Transaction(id, "payment", status, channel, "2026-11-23T10:00:00Z", device,
                fee, "GHS", user, account, instrument, provider, regulatory, city, region, "GH", source, origin, "GHS",
                tier, null, null, null, null, null, card, face,
                destAccount, destInstrument, destProvider, destRegulatory, destCity, destRegion, "GH", destSource,
                destination, "GHS", "gw-" + id, provider, status, reason, reason);
        return new Item("TRANSACTION", "transaction:" + id + ":" + status, "checkout", id, fact);
    }

    private static Item transfer(String id, String device, String from, String fromCity, String fromRegion, String to,
                                 String toCity, String toRegion, String emoney) {
        var fact = new RegulatoryFacts.Transaction(id, "wallet_transfer", "succeeded", "WEB", "2026-11-23T11:30:00Z",
                device, 0, "GHS",
                from, from, "wallet", "ELEPAY", emoney, fromCity, fromRegion, "GH", "PROFILE", 4500, "GHS",
                2, null, null, null, null, null, "VERIFIED", false,
                to, "wallet", "ELEPAY", emoney, toCity, toRegion, "GH", "MNO", 4500, "GHS",
                null, null, null, null, "ELEPAY wallet transfer");
        return new Item("TRANSACTION", "transaction:" + id + ":succeeded", "wallet", id, fact);
    }

    private static Item refund(String id, String merchant, String merchantCity, String merchantRegion,
                               String customer, String customerCity, String customerRegion, String emoney) {
        var fact = new RegulatoryFacts.Transaction(id, "refund", "succeeded", "WEB", "2026-11-23T16:30:00Z",
                "elepay-web-" + merchant, 0, "GHS", merchant, merchant, "wallet", "ELEPAY", emoney,
                merchantCity, merchantRegion, "GH", "BRANCH", 2500, "GHS", 0, null, null, null, null, null, null, false,
                customer, "wallet", "ELEPAY", emoney, customerCity, customerRegion, "GH", "PROFILE", 2500, "GHS",
                null, null, null, null, "Refund of a web wallet payment");
        return new Item("TRANSACTION", "transaction:" + id + ":succeeded", "refund", id, fact);
    }

    private static Item withdrawal(String id, String device, String merchant, String city, String region, String emoney) {
        var fact = new RegulatoryFacts.Transaction(id, "withdrawal", "succeeded", "WEB", "2026-11-23T17:30:00Z",
                device, 0, "GHS", merchant, merchant, "wallet", "ELEPAY", emoney,
                city, region, "GH", "PROFILE", 10000, "GHS", 0, null, null, null, null, null, null, false,
                "233241119999", "momo", "MTN", null, city, region, "GH", "SETTLEMENT", 10000, "GHS",
                "gw-" + id, "MTN", "succeeded", null, null);
        return new Item("TRANSACTION", "transaction:" + id + ":succeeded", "payout", id, fact);
    }

    private static Item complaint(String id, String user, String type, String status, String at, String description, String resolution) {
        var fact = new RegulatoryFacts.Complaint(user, id, type, description, status, at, resolution);
        return new Item("COMPLAINT", "complaint:" + id + ":" + status + ":" + at, "complaint", id, fact);
    }
}
