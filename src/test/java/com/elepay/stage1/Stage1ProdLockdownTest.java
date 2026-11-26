package com.elepay.stage1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.DormantModules;
import com.elepay.identity.KillSwitchService;
import com.elepay.identity.Stage1KillSwitchFilter;
import com.elepay.security.ProductionSafetyCheck;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class Stage1ProdLockdownTest {

    private static StandardEnvironment loadProdEnvironment() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prod");
        env.getPropertySources().addFirst(
                new ResourcePropertySource("application.properties", new ClassPathResource("application.properties")));
        env.getPropertySources().addFirst(
                new ResourcePropertySource("application-prod.properties", new ClassPathResource("application-prod.properties")));
        return env;
    }

    private static Map<String, Object> requiredProdSecrets() {
        Map<String, Object> secrets = new LinkedHashMap<>();
        secrets.put("ELEPAY_OIDC_ISSUER", "https://id.elepay.example/realms/elepay");
        secrets.put("ELEPAY_OIDC_AUDIENCE", "elepay-api");
        secrets.put("ELEPAY_VAULT_MASTER_KEY", "o3Hc0gWk0Gk2cJ7Vq0r9b2p8Yz1mN4tE6aS5dF7gH8k=");
        secrets.put("ELEPAY_INTEGRITY_MAC_KEY", Base64.getEncoder().encodeToString(new byte[64]));
        secrets.put("ELEPAY_INTEGRITY_ED25519_KEY", "configured");
        secrets.put("ELEPAY_INTEGRITY_MLDSA65_KEY", "configured");
        secrets.put("ELEPAY_POS_DUKPT_BDK", "A1B2C3D4E5F60718293A4B5C6D7E8F90");
        secrets.put("ELEPAY_TRUSTED_PROXIES", "10.0.0.0/8");
        return secrets;
    }

    @Test
    @DisplayName("application-prod.properties pins dormant-modules off, strict kill switch on, and all 6 non-Stage-1 modules off")
    void applicationProdPropertiesPinsStage1LockdownDefaults() throws IOException {
        StandardEnvironment env = loadProdEnvironment();

        assertThat(env.getProperty(DormantModules.PROPERTY)).isEqualTo("false");
        assertThat(env.getProperty("elepay.stage1.strict-kill-switch-enabled")).isEqualTo("true");
        assertThat(env.getProperty("elepay.stage1.module.pos.enabled")).isEqualTo("false");
        assertThat(env.getProperty("elepay.stage1.module.fx.enabled")).isEqualTo("false");
        assertThat(env.getProperty("elepay.stage1.module.consumer-wallet.enabled")).isEqualTo("false");
        assertThat(env.getProperty("elepay.stage1.module.ussd.enabled")).isEqualTo("false");
        assertThat(env.getProperty("elepay.stage1.module.mandates.enabled")).isEqualTo("false");
        assertThat(env.getProperty("elepay.stage1.module.restaurant.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("ProductionSafetyCheck accepts application-prod.properties dormant-modules=false and rejects true")
    void productionSafetyCheckEnforcesDormantModulesDisabledOnProdConfig() throws IOException {
        StandardEnvironment safeProdEnv = loadProdEnvironment();
        safeProdEnv.getPropertySources().addFirst(new MapPropertySource("prod-secrets", requiredProdSecrets()));

        assertThatCode(() -> new ProductionSafetyCheck(safeProdEnv)).doesNotThrowAnyException();

        StandardEnvironment unsafeProdEnv = loadProdEnvironment();
        Map<String, Object> overrides = new LinkedHashMap<>(requiredProdSecrets());
        overrides.put(DormantModules.PROPERTY, "true");
        unsafeProdEnv.getPropertySources().addFirst(new MapPropertySource("prod-secrets-override", overrides));

        assertThatThrownBy(() -> new ProductionSafetyCheck(unsafeProdEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DormantModules.PROPERTY);
    }

    @Test
    @DisplayName("KillSwitchService + Stage1KillSwitchFilter with prod config block all 12 disabled modules and allow launch checkout/card rails")
    void killSwitchServiceAndFilterEnforceStage1ProdLockdown() throws Exception {
        StandardEnvironment env = loadProdEnvironment();
        boolean strictStage1 = Boolean.parseBoolean(env.getProperty("elepay.stage1.strict-kill-switch-enabled"));
        boolean posEnabled = Boolean.parseBoolean(env.getProperty("elepay.stage1.module.pos.enabled"));
        boolean fxEnabled = Boolean.parseBoolean(env.getProperty("elepay.stage1.module.fx.enabled"));
        boolean walletEnabled = Boolean.parseBoolean(env.getProperty("elepay.stage1.module.consumer-wallet.enabled"));
        boolean ussdEnabled = Boolean.parseBoolean(env.getProperty("elepay.stage1.module.ussd.enabled"));
        boolean mandatesEnabled = Boolean.parseBoolean(env.getProperty("elepay.stage1.module.mandates.enabled"));
        boolean restaurantEnabled = Boolean.parseBoolean(env.getProperty("elepay.stage1.module.restaurant.enabled"));

        KillSwitchService ks = new KillSwitchService(
                null, null, null, null,
                strictStage1, posEnabled, fxEnabled, walletEnabled, ussdEnabled, mandatesEnabled, restaurantEnabled
        );
        ReflectionTestUtils.setField(ks, "controlsUnreadable", false);

        for (String disabledModule : List.of(
                "lending", "virtual-cards", "bill-payments", "agent-banking", "fx-trading",
                "pos", "fx", "consumer-wallet", "consumer-w2w", "ussd", "mandates", "restaurant")) {
            assertThat(ks.isModuleEnabled(disabledModule))
                    .as("Module %s must be disabled in prod", disabledModule)
                    .isFalse();
        }
        assertThat(ks.isConsumerWalletEnabled()).isFalse();

        for (String launchSwitch : List.of(
                KillSwitchService.CARD_PAYMENTS,
                KillSwitchService.MTN_COLLECTIONS,
                KillSwitchService.MTN_PAYOUTS,
                KillSwitchService.CHECKOUT)) {
            assertThat(ks.isModuleEnabled(launchSwitch))
                    .as("Launch switch %s must stay enabled in prod", launchSwitch)
                    .isTrue();
        }

        Stage1KillSwitchFilter filter = new Stage1KillSwitchFilter(ks);
        Map<String, String> blockedRoutes = new LinkedHashMap<>();
        blockedRoutes.put("/v1/lending/applications", "lending");
        blockedRoutes.put("/v1/virtual-cards/issue", "virtual-cards");
        blockedRoutes.put("/v1/bill-payments/pay", "bill-payments");
        blockedRoutes.put("/v1/agent-banking/cash-in", "agent-banking");
        blockedRoutes.put("/v1/fx-trading/quotes", "fx-trading");
        blockedRoutes.put("/v1/pos/orders", "pos");
        blockedRoutes.put("/v1/fx/rates/latest", "fx-trading");
        blockedRoutes.put("/v1/wallet/transfers", "consumer-w2w");
        blockedRoutes.put("/v1/ussd/generic", "ussd");
        blockedRoutes.put("/v1/customers/mandates", "mandates");
        blockedRoutes.put("/v1/restaurant/tables", "restaurant");
        blockedRoutes.put("/v1/public/kitchen/pair", "restaurant");

        for (var entry : blockedRoutes.entrySet()) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", entry.getKey());
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(req, res, chain);

            assertThat(res.getStatus()).as("Route %s must return 404", entry.getKey()).isEqualTo(404);
            assertThat(res.getContentAsString()).contains("FEATURE_NOT_ENABLED");
            assertThat(res.getContentAsString()).contains("\"module\":\"" + entry.getValue() + "\"");
            assertThat(chain.getRequest()).isNull();
        }

        for (String allowedRoute : List.of("/v1/public/checkouts/EP-123", "/v1/rails/card/tokens")) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", allowedRoute);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(req, res, chain);

            assertThat(res.getStatus()).as("Allowed route %s must not be blocked", allowedRoute).isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
        }
    }
}
