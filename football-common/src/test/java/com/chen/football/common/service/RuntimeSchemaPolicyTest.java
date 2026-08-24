package com.chen.football.common.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSchemaPolicyTest {
    @AfterEach
    void clear() {
        System.clearProperty("app.runtime-ddl-enabled");
    }

    @Test
    void defaultsToCompatibilityModeForLocalDevelopment() {
        System.clearProperty("app.runtime-ddl-enabled");
        assertTrue(RuntimeSchemaPolicy.runtimeDdlEnabled());
    }

    @Test
    void canDisableLegacyDdlExplicitly() {
        System.setProperty("app.runtime-ddl-enabled", "false");
        assertFalse(RuntimeSchemaPolicy.runtimeDdlEnabled());
    }
}
