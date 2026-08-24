package com.chen.football.common.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Fails fast when production is configured to rely on migrations but a table is missing. */
@Component
@ConditionalOnBean(JdbcTemplate.class)
@ConditionalOnProperty(name = "app.schema.require-migrations", havingValue = "true")
public class SchemaReadinessGuard {
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.schema.required-tables:t_user,crawler_matches,t_match_prediction,t_prematch_feature_snapshot,t_agent_conversation,t_analytics_event}")
    private String requiredTables;

    public SchemaReadinessGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void verify() {
        // Legal consent is part of the authentication contract.  Do not let
        // an outdated APP_SCHEMA_REQUIRED_TABLES override silently disable
        // this check; otherwise login succeeds but the mandatory consent gate
        // can never be completed.
        Set<String> tables = new LinkedHashSet<>(Arrays.asList(requiredTables.split(",")));
        tables.add("t_user_legal_consent");
        List<String> missing = tables.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .filter(table -> !exists(table))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("数据库迁移未完成，缺少表: " + String.join(", ", missing)
                    + ". 请先运行 scripts/apply-migrations.ps1，再启动服务。");
        }
    }

    private boolean exists(String table) {
        if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Invalid schema table name");
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, table);
        return count != null && count > 0;
    }
}
