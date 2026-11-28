package com.elepay.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime optional-module capability discovery endpoint (Interface Contract #3).
 *
 * <p>Allows web and mobile clients to discover which of the 7 optional product modules
 * ({@code merchanttools}, {@code lending}, {@code credit}, {@code ussd}, {@code growth},
 * {@code bills}, {@code agents}) are loaded and enabled in the current deployment, so UI tabs
 * and CTAs for disabled modules can be hidden cleanly without triggering 404 errors.
 */
@RestController
public class CapabilitiesController {
    private static final List<String> OPTIONAL_MODULES = List.of(
            "merchanttools", "lending", "credit", "ussd", "growth", "bills", "agents"
    );

    private final KillSwitchService killSwitchService;

    public CapabilitiesController(KillSwitchService killSwitchService) {
        this.killSwitchService = killSwitchService;
    }

    public record ModuleCapability(boolean loaded, boolean enabled) {}

    public record CapabilitiesResponse(
            Map<String, ModuleCapability> modules,
            List<String> enabledModules
    ) {}

    @GetMapping("/v1/capabilities")
    public CapabilitiesResponse capabilities() {
        Map<String, ModuleCapability> modules = new LinkedHashMap<>();
        List<String> enabledModules = new ArrayList<>();
        for (String mod : OPTIONAL_MODULES) {
            boolean loaded = killSwitchService.isOptionalModuleLoaded(mod);
            boolean enabled = killSwitchService.isOptionalModuleActive(mod);
            modules.put(mod, new ModuleCapability(loaded, enabled));
            if (enabled) {
                enabledModules.add(mod);
            }
        }
        return new CapabilitiesResponse(modules, List.copyOf(enabledModules));
    }
}
