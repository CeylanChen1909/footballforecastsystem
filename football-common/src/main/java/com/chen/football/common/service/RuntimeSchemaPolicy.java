package com.chen.football.common.service;

/**
 * Controls the legacy defensive DDL kept for developer databases.
 *
 * Production deployments must run the versioned SQL migrations before startup
 * and set APP_RUNTIME_DDL_ENABLED=false.  The default remains true for local
 * installations that predate the migration directory.
 */
public final class RuntimeSchemaPolicy {
    private RuntimeSchemaPolicy() { }

    public static boolean runtimeDdlEnabled() {
        String value = System.getProperty("app.runtime-ddl-enabled");
        if (value == null || value.isBlank()) value = System.getenv("APP_RUNTIME_DDL_ENABLED");
        return value == null || value.isBlank() || Boolean.parseBoolean(value);
    }
}
