package com.elepay.emtech;

import com.elepay.identity.Actor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operations replay for reports the sandbox rejected or that exhausted retries. */
@RestController
public class EmtechOpsController {
    private final EmtechOutbox outbox;
    private final EmtechDispatcher dispatcher;

    public EmtechOpsController(EmtechOutbox outbox, EmtechDispatcher dispatcher) {
        this.outbox = outbox;
        this.dispatcher = dispatcher;
    }

    @GetMapping("/v1/ops/emtech/submissions")
    public List<EmtechOutbox.Row> list(@RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "100") int limit) {
        Actor.current().requireOps();
        return outbox.list(status, limit);
    }

    @PostMapping("/v1/ops/emtech/submissions/{id}/replay")
    public Map<String, String> replay(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean sendNow) {
        Actor.current().requireOps();
        var row = outbox.replay(id);
        if (sendNow) dispatcher.dispatchAvailable(20);
        return Map.of("id", row.id().toString(), "status", sendNow ? "replayed" : "pending");
    }
}
