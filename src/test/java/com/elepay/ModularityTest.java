package com.elepay;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {
    @Test
    void modulesHaveNoCyclesOrInternalAccess() {
        ApplicationModules.of(SandboxApplication.class).verify();
    }
}
