package com.elepay.payout;

import com.elepay.identity.Actor;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Evidence register for platform earnings routes (Bolt, Yango, X, TikTok).
 * A route is reported as integrated only when its status is {@code verified}
 * and it names the evidence. Platform-funded batch payouts work today through
 * the generic merchant payout-batch API with {@code platform} set on each item.
 */
@RestController
class PlatformRoutes {
    private final List<Map<String, Object>> routes;

    PlatformRoutes(ObjectMapper json) throws IOException {
        try (var in = new ClassPathResource("platform-payout-routes.json").getInputStream()) {
            this.routes = json.readValue(in, new TypeReference<List<Map<String, Object>>>() {});
        }
    }

    @GetMapping("/v1/ops/platform-payout-routes")
    List<Map<String, Object>> routes() {
        Actor.current().requireOps();
        return routes;
    }
}
