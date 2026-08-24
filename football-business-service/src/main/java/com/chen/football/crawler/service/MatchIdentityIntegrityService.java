package com.chen.football.crawler.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Applies the match identity guard on existing installations as well as on a
 * fresh schema.  The standalone SQL migration remains available for audited
 * deployments; this initializer makes a normal service restart self-healing.
 */
@Slf4j
@Service
public class MatchIdentityIntegrityService {
    private final JdbcTemplate jdbcTemplate;
    private final boolean autoRepair;

    public MatchIdentityIntegrityService(JdbcTemplate jdbcTemplate,
                                         @Value("${data-integrity.match-identity-auto-repair:true}") boolean autoRepair) {
        this.jdbcTemplate = jdbcTemplate;
        this.autoRepair = autoRepair;
    }

    @PostConstruct
    void repair() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        if (!autoRepair) return;
        try {
            int external = jdbcTemplate.update("DELETE older FROM crawler_matches older JOIN crawler_matches keeper "
                    + "ON keeper.source=older.source AND keeper.external_match_id=older.external_match_id "
                    + "AND older.external_match_id IS NOT NULL AND older.external_match_id<>'' AND keeper.id<older.id");
            int fixture = jdbcTemplate.update("DELETE older FROM crawler_matches older JOIN crawler_matches keeper "
                    + "ON keeper.source=older.source AND keeper.fixture_id=older.fixture_id "
                    + "AND older.fixture_id IS NOT NULL AND keeper.id<older.id");
            ensureIndex("uk_match_source_external", "ALTER TABLE crawler_matches ADD UNIQUE KEY uk_match_source_external (source, external_match_id)");
            ensureIndex("uk_match_source_fixture", "ALTER TABLE crawler_matches ADD UNIQUE KEY uk_match_source_fixture (source, fixture_id)");
            if (external > 0 || fixture > 0) {
                log.warn("比赛身份完整性修复完成：按外部ID清理{}条，按fixture清理{}条", external, fixture);
            }
        } catch (Exception ex) {
            // A legacy database can be read even when it lacks ALTER rights;
            // keep the service available and leave the audited SQL migration
            // for an operator with schema privileges.
            log.warn("比赛身份完整性修复未完成，请执行 sql/migrations/V2026082103__match_identity_integrity.sql：{}", ex.getMessage());
        }
    }

    private void ensureIndex(String indexName, String ddl) {
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                    + "WHERE table_schema=DATABASE() AND table_name='crawler_matches' AND index_name=?", Integer.class, indexName);
            if (count == null || count == 0) jdbcTemplate.execute(ddl);
        } catch (Exception ex) {
            log.debug("比赛唯一索引 {} 未创建：{}", indexName, ex.getMessage());
        }
    }
}
