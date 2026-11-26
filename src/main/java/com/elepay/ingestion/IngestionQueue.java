package com.elepay.ingestion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public entry point for enqueueing asynchronous commands, polling ingestion status,
 * inspecting dead-lettered events, and replaying failed/dead events.
 */
public interface IngestionQueue {

    IngestionReceipt enqueue(IngestionCommand cmd);

    List<IngestionReceipt> enqueueBatch(List<IngestionCommand> cmds);

    Optional<IngestionStatus> findStatus(UUID id, UUID tenantId);

    IngestionStatus replay(UUID id);

    List<IngestionStatus> listDead(int limit);
}
