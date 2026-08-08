package com.eventguard.auth.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionSecurityGuardTest {

    @Test
    void demo_keeps_default_demo_access_available() {
        ProductionSecurityGuard guard = new ProductionSecurityGuard(
                "demo", "eventguard-dev-secret-change-me-0123456789abcdef", "dev-machine-key",
                false, "", "*", false, true, "always");
        assertDoesNotThrow(guard::validate);
    }

    @Test
    void production_rejects_default_keys() {
        ProductionSecurityGuard guard = new ProductionSecurityGuard(
                "prod", "eventguard-dev-secret-change-me-0123456789abcdef", "dev-machine-key",
                true, "", "*", false, true, "always");
        assertThrows(IllegalStateException.class, guard::validate);
    }
}
