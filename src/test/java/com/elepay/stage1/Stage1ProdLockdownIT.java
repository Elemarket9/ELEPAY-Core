package com.elepay.stage1;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.identity.KillSwitchService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class Stage1ProdLockdownIT extends PostgresTestBase {

    @Autowired JdbcClient db;
    @Autowired KillSwitchService killSwitchService;

    @BeforeEach
    void setUp() {
        killSwitchService.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        killSwitchService.resetForTesting();
    }

    private KillSwitchService newProdKillSwitchService() {
        return new KillSwitchService(
                db, null, null, null,
                true, false, false, false, false, false, false
        );
    }

    @Test
    @DisplayName("V49 system seed rows do not override prod false defaults on startup")
    void v49SystemSeedRowsDoNotOverrideProdFalseDefaults() {
        for (String moduleKey : List.of("pos", "fx", "consumer-wallet", "consumer-w2w", "ussd", "mandates", "restaurant")) {
            db.sql("""
                    INSERT INTO module_kill_switch (module_key, enabled, stage, reason, updated_by, updated_at)
                    VALUES (:k, true, 1, 'V49 seed simulation', 'system', now())
                    ON CONFLICT (module_key) DO UPDATE SET
                        enabled = true, reason = 'V49 seed simulation', updated_by = 'system', updated_at = now()""")
                    .param("k", moduleKey)
                    .update();
        }

        KillSwitchService prodBoot = newProdKillSwitchService();

        assertThat(prodBoot.isModuleEnabled("pos")).isFalse();
        assertThat(prodBoot.isModuleEnabled("fx")).isFalse();
        assertThat(prodBoot.isModuleEnabled("consumer-wallet")).isFalse();
        assertThat(prodBoot.isModuleEnabled("consumer-w2w")).isFalse();
        assertThat(prodBoot.isConsumerWalletEnabled()).isFalse();
        assertThat(prodBoot.isModuleEnabled("ussd")).isFalse();
        assertThat(prodBoot.isModuleEnabled("mandates")).isFalse();
        assertThat(prodBoot.isModuleEnabled("restaurant")).isFalse();

        assertThat(prodBoot.isModuleEnabled("lending")).isFalse();
        assertThat(prodBoot.isModuleEnabled("virtual-cards")).isFalse();
        assertThat(prodBoot.isModuleEnabled("bill-payments")).isFalse();
        assertThat(prodBoot.isModuleEnabled("agent-banking")).isFalse();
        assertThat(prodBoot.isModuleEnabled("fx-trading")).isFalse();

        assertThat(prodBoot.isModuleEnabled(KillSwitchService.CARD_PAYMENTS)).isTrue();
        assertThat(prodBoot.isModuleEnabled(KillSwitchService.MTN_COLLECTIONS)).isTrue();
        assertThat(prodBoot.isModuleEnabled(KillSwitchService.MTN_PAYOUTS)).isTrue();
        assertThat(prodBoot.isModuleEnabled(KillSwitchService.CHECKOUT)).isTrue();
    }

    @Test
    @DisplayName("Non-system admin overrides and consumer-wallet alias sync persist across simulated restarts")
    void adminOverridesAndConsumerWalletAliasPersistAcrossRestart() {
        for (String moduleKey : List.of("pos", "fx", "consumer-wallet", "consumer-w2w", "ussd", "mandates", "restaurant")) {
            db.sql("""
                    INSERT INTO module_kill_switch (module_key, enabled, stage, reason, updated_by, updated_at)
                    VALUES (:k, true, 1, 'V49 seed simulation', 'system', now())
                    ON CONFLICT (module_key) DO UPDATE SET
                        enabled = true, reason = 'V49 seed simulation', updated_by = 'system', updated_at = now()""")
                    .param("k", moduleKey)
                    .update();
        }

        KillSwitchService firstBoot = newProdKillSwitchService();
        assertThat(firstBoot.isModuleEnabled("pos")).isFalse();
        assertThat(firstBoot.isConsumerWalletEnabled()).isFalse();

        firstBoot.setModuleEnabled("pos", true, "pilot launch", "ops-admin-2");
        firstBoot.setModuleEnabled("consumer-wallet", true, "wallet pilot", "ops-admin-2");
        firstBoot.setModuleEnabled("fx", true, "programmatic toggle", null);

        String walletDbActor = db.sql("SELECT updated_by FROM module_kill_switch WHERE module_key = 'consumer-wallet'")
                .query(String.class).single();
        String w2wDbActor = db.sql("SELECT updated_by FROM module_kill_switch WHERE module_key = 'consumer-w2w'")
                .query(String.class).single();
        String fxDbActor = db.sql("SELECT updated_by FROM module_kill_switch WHERE module_key = 'fx'")
                .query(String.class).single();

        assertThat(walletDbActor).isEqualTo("ops-admin-2");
        assertThat(w2wDbActor).isEqualTo("ops-admin-2");
        assertThat(fxDbActor).isEqualTo("runtime-system");

        // Simulated restart with prod defaults (all 6 configurable modules false)
        KillSwitchService restarted = newProdKillSwitchService();

        assertThat(restarted.isModuleEnabled("pos")).isTrue();
        assertThat(restarted.isModuleEnabled("consumer-wallet")).isTrue();
        assertThat(restarted.isModuleEnabled("consumer-w2w")).isTrue();
        assertThat(restarted.isConsumerWalletEnabled()).isTrue();
        assertThat(restarted.isModuleEnabled("fx")).isTrue();

        // Untouched modules remain false from prod defaults despite V49 system seed rows
        assertThat(restarted.isModuleEnabled("ussd")).isFalse();
        assertThat(restarted.isModuleEnabled("mandates")).isFalse();
        assertThat(restarted.isModuleEnabled("restaurant")).isFalse();
    }
}
