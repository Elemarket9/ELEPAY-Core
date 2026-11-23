package com.elepay.emtech;

/**
 * Derives the city, region and country of a payment without asking the customer
 * for browser or handset geolocation.
 *
 * <p>POS, ATM and kiosk transactions use the terminal's registered city. Web, app
 * and USSD use the customer's KYC or saved profile city (Ghana Card locality or
 * profile address), then a coarse IP city, then an MNO coarse city, then the
 * merchant branch. Only city, region and country are returned. Coordinates are
 * not part of this result.
 */
public final class ServerSidePlaces {
    private ServerSidePlaces() {}

    public record Place(String city, String region, String country, String source) {
        public static Place of(String city, String region, String country, String source) {
            return new Place(city, region, country, source).enrich();
        }

        public static Place none() {
            return new Place(null, null, null, null);
        }

        public boolean usable() {
            return city != null && !city.isBlank() && country != null && !country.isBlank();
        }

        public Place enrich() {
            if (city == null || city.isBlank()) return this;
            String regionValue = region == null || region.isBlank()
                    ? GhanaLocalities.region(city).orElse(null) : region.trim();
            String countryValue = country == null || country.isBlank() ? "GH" : country.trim();
            return new Place(city.trim(), regionValue, countryValue, source);
        }

        public Place sourced(String fallbackSource) {
            String chosen = source == null || source.isBlank() ? fallbackSource : source;
            return new Place(city, region, country, chosen);
        }
    }

    public static Place origin(String channel, Place terminal, Place profile, Place coarseIp, Place mno, Place branch) {
        if (isShopChannel(channel)) {
            Place shop = enrich(terminal);
            return shop.usable() ? shop.sourced("TERMINAL") : Place.none();
        }
        return first(enrich(profile), "PROFILE", enrich(coarseIp), "COARSE_IP", enrich(mno), "MNO", enrich(branch), "BRANCH");
    }

    /** Where the merchant side of a payment sits: branch, then merchant profile, then settlement, then the shop. */
    public static Place merchantDestination(Place branch, Place merchant, Place settlement, Place terminal) {
        return first(enrich(branch), "BRANCH", enrich(merchant), "PROFILE", enrich(settlement), "SETTLEMENT",
                enrich(terminal), "TERMINAL");
    }

    /** The other ELEPAY customer in a wallet transfer. Profile, then MNO coarse city. No handset GPS. */
    public static Place recipient(Place profile, Place mno) {
        return first(enrich(profile), "PROFILE", enrich(mno), "MNO", null, null, null, null);
    }

    public static boolean isShopChannel(String channel) {
        return "POS".equals(channel) || "ATM".equals(channel) || "KIOSK".equals(channel);
    }

    private static Place first(Place a, String aSource, Place b, String bSource, Place c, String cSource, Place d, String dSource) {
        if (a != null && a.usable()) return a.sourced(aSource);
        if (b != null && b.usable()) return b.sourced(bSource);
        if (c != null && c.usable()) return c.sourced(cSource);
        if (d != null && d.usable()) return d.sourced(dSource);
        return Place.none();
    }

    private static Place enrich(Place place) {
        return place == null ? Place.none() : place.enrich();
    }
}
