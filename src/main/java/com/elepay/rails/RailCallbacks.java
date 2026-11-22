package com.elepay.rails;

import com.elepay.identity.Actor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Inbound rail notifications and operator recovery. Authenticity is verified by each rail. */
@RestController
class RailCallbacks {
    private final RailTransactions rails;

    RailCallbacks(RailTransactions rails) {
        this.rails = rails;
    }

    @PostMapping("/v1/rails/{rail}/callbacks")
    public Map<String, String> callback(@PathVariable String rail, @RequestBody String body, HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        var outcome = rails.handleCallback(rail, headers, body);
        return Map.of("status", outcome.duplicate() ? "duplicate" : "accepted", "outcome", outcome.outcome());
    }

    @PostMapping("/v1/ops/rails/recover")
    public Map<String, Integer> recover() {
        Actor.current().requireOps();
        int count = rails.recover(200, true);
        return Map.of("resolved", count, "recovered", count);
    }

    @GetMapping("/v1/ops/rails/open")
    public List<RailTransactions.RailTx> open() {
        Actor.current().requireOps();
        return rails.open();
    }
}
