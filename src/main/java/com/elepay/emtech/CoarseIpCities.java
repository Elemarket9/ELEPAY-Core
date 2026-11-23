package com.elepay.emtech;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * City-level IP fallback. The map is operator configuration ({@code ip=City|Region|Country}),
 * not a call that sends the address to a third party. The address is not stored.
 * Unknown addresses resolve to nothing so a missing profile is not silently called Accra.
 */
@Component
public class CoarseIpCities {
    private final Map<String, ServerSidePlaces.Place> cities;

    public CoarseIpCities(EmtechSettings settings) {
        this.cities = parse(settings.coarseIpCities());
    }

    public Optional<ServerSidePlaces.Place> lookup(String ip) {
        if (ip == null || ip.isBlank()) return Optional.empty();
        return Optional.ofNullable(cities.get(ip.trim().toLowerCase(Locale.ROOT)));
    }

    static Map<String, ServerSidePlaces.Place> parse(String raw) {
        Map<String, ServerSidePlaces.Place> parsed = new HashMap<>();
        if (raw == null || raw.isBlank()) return parsed;
        for (String entry : raw.split(",")) {
            String[] pair = entry.trim().split("=", 2);
            if (pair.length != 2) continue;
            String[] parts = pair[1].split("\\|");
            if (parts.length < 3) continue;
            parsed.put(pair[0].trim().toLowerCase(Locale.ROOT),
                    ServerSidePlaces.Place.of(parts[0].trim(), parts[1].trim(), parts[2].trim(), "COARSE_IP"));
        }
        return parsed;
    }
}
