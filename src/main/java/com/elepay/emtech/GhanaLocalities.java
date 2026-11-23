package com.elepay.emtech;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Public reference data for Ghanaian cities: ISO-3166-2 region and a city-centre
 * coordinate used only when registering a channel device. These are not a person's location.
 */
public final class GhanaLocalities {
    private record City(String region, String latitude, String longitude) {}

    private static final Map<String, City> CITIES = Map.ofEntries(
            Map.entry("accra", new City("GH-AA", "5.603717", "-0.186964")),
            Map.entry("tema", new City("GH-AA", "5.669800", "-0.016600")),
            Map.entry("madina", new City("GH-AA", "5.683300", "-0.166700")),
            Map.entry("ashaiman", new City("GH-AA", "5.700000", "-0.033300")),
            Map.entry("teshie", new City("GH-AA", "5.583300", "-0.100000")),
            Map.entry("kumasi", new City("GH-AH", "6.688480", "-1.624430")),
            Map.entry("obuasi", new City("GH-AH", "6.200000", "-1.666700")),
            Map.entry("takoradi", new City("GH-WP", "4.884500", "-1.755400")),
            Map.entry("sekondi", new City("GH-WP", "4.933300", "-1.716700")),
            Map.entry("tamale", new City("GH-NP", "9.400800", "-0.839300")),
            Map.entry("cape coast", new City("GH-CP", "5.103600", "-1.246600")),
            Map.entry("sunyani", new City("GH-BO", "7.339900", "-2.326800")),
            Map.entry("ho", new City("GH-TV", "6.600800", "0.471300")),
            Map.entry("koforidua", new City("GH-EP", "6.094100", "-0.259100")),
            Map.entry("wa", new City("GH-UW", "10.060100", "-2.509900")),
            Map.entry("bolgatanga", new City("GH-UE", "10.785600", "-0.851400")));

    private GhanaLocalities() {}

    public static Optional<String> region(String city) {
        City found = city == null ? null : CITIES.get(city.trim().toLowerCase(Locale.ROOT));
        return found == null ? Optional.empty() : Optional.of(found.region());
    }

    /** City-centre reference point for a device registration. Empty when the city is unknown. */
    public static Optional<String[]> deviceReferencePoint(String city) {
        City found = city == null ? null : CITIES.get(city.trim().toLowerCase(Locale.ROOT));
        return found == null ? Optional.empty() : Optional.of(new String[]{found.latitude(), found.longitude()});
    }
}
