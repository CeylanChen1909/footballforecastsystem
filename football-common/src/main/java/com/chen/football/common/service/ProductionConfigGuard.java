package com.chen.football.common.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * Prevents a production process from silently starting with development
 * security/schema defaults.  Local profiles deliberately keep the legacy
 * defaults for backwards compatibility; the prod profile enables this guard.
 */
@Component
@ConditionalOnProperty(name = "app.security.production-guard.enabled", havingValue = "true")
public class ProductionConfigGuard {

    @Value("${app.runtime-ddl-enabled:false}")
    private boolean runtimeDdlEnabled;

    @Value("${app.schema.require-migrations:true}")
    private boolean requireMigrations;

    @Value("${security.refresh-token-cookie-only:true}")
    private boolean refreshTokenCookieOnly;

    @Value("${security.demo-accounts-enabled:false}")
    private boolean demoAccountsEnabled;

    @Value("${security.email-verification.console-mode:false}")
    private boolean emailConsoleMode;

    @PostConstruct
    void verify() {
        List<String> violations = new ArrayList<>();
        if (runtimeDdlEnabled) violations.add("APP_RUNTIME_DDL_ENABLED must be false");
        if (!requireMigrations) violations.add("APP_SCHEMA_REQUIRE_MIGRATIONS must be true");
        if (!refreshTokenCookieOnly) violations.add("SECURITY_REFRESH_TOKEN_COOKIE_ONLY must be true");
        if (demoAccountsEnabled) violations.add("SECURITY_DEMO_ACCOUNTS_ENABLED must be false");
        if (emailConsoleMode) violations.add("EMAIL_VERIFICATION_CONSOLE_MODE must be false");
        if (!violations.isEmpty()) {
            throw new IllegalStateException("生产安全配置不合规，拒绝启动: " + String.join("; ", violations));
        }
    }
}
