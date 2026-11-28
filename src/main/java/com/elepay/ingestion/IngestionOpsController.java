package com.elepay.ingestion;

import com.elepay.identity.Actor;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations APIs for inspecting dead-lettered ingestion events and replaying them.
 */
@RestController
@RequestMapping("/v1/ops/ingestions")
class IngestionOpsController {

    private static final int MAX_DEAD_LIMIT = 500;

    private final IngestionQueue ingestionQueue;

    IngestionOpsController(IngestionQueue ingestionQueue) {
        this.ingestionQueue = ingestionQueue;
    }

    @GetMapping("/dead")
    public List<IngestionStatus> listDead(@RequestParam(defaultValue = "50") int limit) {
        Actor.current().requireFinance();
        int clampedLimit = Math.max(1, Math.min(limit, MAX_DEAD_LIMIT));
        return ingestionQueue.listDead(clampedLimit);
    }

    @PostMapping("/{ingestionId}/replay")
    public IngestionStatus replay(@PathVariable UUID ingestionId) {
        Actor.current().requireFinance();
        return ingestionQueue.replay(ingestionId);
    }
}
