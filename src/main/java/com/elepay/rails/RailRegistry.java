package com.elepay.rails;

import com.elepay.common.ApiException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Registered network adapters only. An absent connection is never treated as an operational rail. */
@Component
public class RailRegistry {
    private final Map<String, PaymentRail> rails;
    private final String defaultName;

    RailRegistry(List<PaymentRail> adapters, @Value("${elepay.rails.default:}") String configuredDefault) {
        this.rails = adapters.stream().collect(Collectors.toMap(PaymentRail::name, Function.identity()));
        if (!configuredDefault.isBlank() && !rails.containsKey(configuredDefault))
            throw new IllegalStateException("Configured default payment rail is not registered");
        this.defaultName = !configuredDefault.isBlank() ? configuredDefault
                : rails.size() == 1 ? rails.keySet().iterator().next() : "none";
    }

    public PaymentRail get(String name) {
        PaymentRail rail = rails.get(name);
        if (rail == null) throw ApiException.unprocessable("rail_unavailable", "Payment rail " + name + " is not configured");
        return rail;
    }

    public String defaultName() {
        return defaultName;
    }

    public java.util.Set<String> names() {
        return rails.keySet();
    }
}
