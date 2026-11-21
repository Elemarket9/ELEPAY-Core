package com.elepay.rails;

import com.elepay.common.ApiException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Rails enabled in this deployment. Only the simulated rail ships today. */
@Component
public class RailRegistry {
    private final Map<String, PaymentRail> rails;
    private final String defaultName;

    RailRegistry(List<PaymentRail> adapters, @Value("${elepay.rails.default:}") String configuredDefault) {
        this.rails = adapters.stream().collect(Collectors.toMap(PaymentRail::name, Function.identity()));
        if (!configuredDefault.isBlank()) {
            this.defaultName = configuredDefault;
        } else if (rails.containsKey("sandbox")) {
            this.defaultName = "sandbox";
        } else {
            this.defaultName = rails.keySet().stream().sorted().findFirst().orElse("none");
        }
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
