package com.elepay.agents;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AgentConfigService {

    private final JdbcClient db;

    public AgentConfigService(JdbcClient db) {
        this.db = db;
    }

    public boolean isLiveAgentEnabled() {
        return db.sql("SELECT live_agent_enabled FROM agent_config LIMIT 1")
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    public void setLiveAgentEnabled(boolean enabled) {
        db.sql("UPDATE agent_config SET live_agent_enabled = ?, updated_at = now()")
                .param(enabled)
                .update();
    }

    public void assertPermitted(boolean isSimulation) {
        if (isSimulation) throw new IllegalStateException("SIMULATIONS_NOT_PERMITTED");
        if (!isLiveAgentEnabled()) {
            throw new IllegalStateException("LIVE_AGENT_DISABLED: Agent live operations are disabled by policy until regulatory clearance.");
        }
    }
}
