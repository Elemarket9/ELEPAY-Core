package com.elepay.lending;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class LendingConfigService {

    private final JdbcClient db;

    public LendingConfigService(JdbcClient db) {
        this.db = db;
    }

    public boolean isLiveLendingEnabled() {
        return db.sql("SELECT live_lending_enabled FROM lending_config LIMIT 1")
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    public void setLiveLendingEnabled(boolean enabled) {
        db.sql("UPDATE lending_config SET live_lending_enabled = ?, updated_at = now()")
                .param(enabled)
                .update();
    }

    public void assertPermitted(boolean isSimulation) {
        if (isSimulation) throw new IllegalStateException("SIMULATIONS_NOT_PERMITTED");
        if (!isLiveLendingEnabled()) {
            throw new IllegalStateException("LIVE_LENDING_DISABLED: Lending live operations are disabled by policy until regulatory clearance.");
        }
    }
}
