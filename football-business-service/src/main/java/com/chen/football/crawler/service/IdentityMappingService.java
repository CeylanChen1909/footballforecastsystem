package com.chen.football.crawler.service;

import com.chen.football.crawler.entity.CrawlerMatch;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Keeps provider IDs as aliases while exposing one deterministic canonical key.
 * This is additive: existing crawler rows are not rewritten or deleted.
 */
@Slf4j
@Service
public class IdentityMappingService {
    private final JdbcTemplate jdbcTemplate;

    public IdentityMappingService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void ensureTable() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_identity_alias (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, entity_type VARCHAR(16) NOT NULL, " +
                    "canonical_key VARCHAR(128) NOT NULL, source VARCHAR(64) NOT NULL, " +
                    "external_id VARCHAR(128) NOT NULL, source_name VARCHAR(128), canonical_name VARCHAR(128), " +
                    "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY (id), UNIQUE KEY uk_identity_source (entity_type, source, external_id), " +
                    "KEY idx_identity_canonical (entity_type, canonical_key)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (Exception ex) {
            log.warn("身份映射表初始化失败，将继续使用名称归一化: {}", ex.getMessage());
        }
    }

    public void ensureMatch(CrawlerMatch match) {
        if (match == null) return;
        String source = safe(match.getSource(), "unknown");
        upsert("TEAM", source, match.getHomeTeamId(), match.getHomeTeamName());
        upsert("TEAM", source, match.getAwayTeamId(), match.getAwayTeamName());
        upsert("LEAGUE", source, match.getLeagueId(), match.getLeagueName());
    }

    public String teamKey(String id, String name) { return IdentityNormalizer.key("TEAM", id, name); }
    public String leagueKey(String id, String name) { return IdentityNormalizer.key("LEAGUE", id, name); }

    private void upsert(String type, String source, String externalId, String name) {
        String effectiveId = safe(externalId, IdentityNormalizer.normalize(name));
        if (effectiveId.isBlank()) return;
        String canonicalKey = IdentityNormalizer.key(type, externalId, name);
        try {
            jdbcTemplate.update("INSERT INTO t_identity_alias (entity_type, canonical_key, source, external_id, source_name, canonical_name) " +
                            "VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE canonical_key=VALUES(canonical_key), source_name=VALUES(source_name), canonical_name=VALUES(canonical_name), updated_at=CURRENT_TIMESTAMP",
                    type, canonicalKey, source, effectiveId, name, name);
        } catch (Exception ex) {
            log.debug("写入身份映射失败: {}", ex.getMessage());
        }
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value.trim();
    }
}
