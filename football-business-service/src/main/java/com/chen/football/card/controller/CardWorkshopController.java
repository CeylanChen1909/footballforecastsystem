package com.chen.football.card.controller;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.service.DistributedLockService;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.card.service.CardWorkshopRateLimitService;
import com.chen.football.card.service.VirtualPersonaPolicy;
import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ProxySelector;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 角色卡工坊：默认只开放用户私有的虚拟角色卡。
 * 真实球员卡的数据和同步代码保留在库中，但由配置开关控制，避免在未完成
 * 授权、统计和评分校准前误把实验性数据展示给用户。
 */
@RestController
@RequestMapping("/api/card-workshop")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "card-workshop", name = "enabled", havingValue = "true")
public class CardWorkshopController {

    private static final int MAX_PLAYERS = 200;
    private static final int DEFAULT_PAGE_SIZE = 24;
    private static final int MAX_SLOTS = 11;
    private static final int MAX_LINEUPS_PER_USER = 100;
    private static final int MAX_PERSONA_CANDIDATES = 8;
    private static final int MAX_TAGS_PER_CARD = 8;
    private static final int MAX_PERSONA_TAGS = 16;
    private static final int MAX_REROLLS_PER_DAY = 10;
    private static final int SHARE_TTL_DAYS = 30;
    private static final int DAILY_CHECKIN_POINTS = 10;
    private static final int DAILY_STREAK_BONUS_POINTS = 2;
    private static final int MAX_CATALOG_PRICE = 100000;
    private static final String CATALOG_CARD_RATING_ORIGIN = "CATALOG_PERSONA_FIXED";
    private static final String CATALOG_CARD_VERSION = "catalog-v1";
    private static final String CARD_LAB_SCHEMA_VERSION = "card-lab-v3";
    private static final String SYNERGY_RULE_VERSION = "card-lab-synergy-v2";
    private static final Set<String> ALLOWED_FORMATIONS = Set.of("4-3-3", "4-2-3-1", "4-4-2", "3-4-3", "3-5-2");
    private static final Set<String> ALLOWED_ARCHETYPES = Set.of("全能", "速度", "力量", "智谋", "魅力", "防守", "创造");
    private static final Set<String> ALLOWED_PERSONA_POSITIONS = Set.of("全能", "门将", "后卫", "中场", "前锋", "边锋", "前腰", "后腰", "中锋", "中后卫", "边后卫");
    private static final String PERSONA_POLICY_VERSION = "virtual-persona-policy-v2";
    private static final String WIKIPEDIA_LICENSE = "CC BY-SA 4.0";
    private static final String CARD_SOURCE = "roster-lab-v3";
    private static final String CUSTOM_SOURCE = "wikipedia-persona-v1";
    private static final int MAX_SYNERGY_BONUS = 8;
    private static final List<SynergyLevel> SYNERGY_LEVELS = List.of(
            new SynergyLevel("作品", 3, 1, "同一作品·初识"), new SynergyLevel("作品", 5, 2, "同一作品·共鸣"),
            new SynergyLevel("作品", 8, 3, "同一作品·集结"), new SynergyLevel("作品", 11, 4, "同一作品·全员"),
            new SynergyLevel("特征", 3, 2, "共同特征·显现"), new SynergyLevel("特征", 5, 3, "共同特征·强化"),
            new SynergyLevel("特征", 8, 4, "共同特征·极致"), new SynergyLevel("阵营", 3, 1, "同阵营·协作"),
            new SynergyLevel("阵营", 5, 2, "同阵营·共斗"), new SynergyLevel("身份", 3, 1, "同身份·默契"),
            new SynergyLevel("身份", 5, 2, "同身份·专精"), new SynergyLevel("种族", 3, 1, "同种族·共鸣"),
            new SynergyLevel("种族", 5, 2, "同种族·强化"));
    private static final Map<String, List<String>> FORMATION_POSITIONS = Map.of(
            "4-3-3", List.of("门将", "左后卫", "中后卫", "中后卫", "右后卫", "中场", "中场", "前腰", "左边锋", "中锋", "右边锋"),
            "4-2-3-1", List.of("门将", "左后卫", "中后卫", "中后卫", "右后卫", "后腰", "后腰", "左边前卫", "前腰", "右边前卫", "中锋"),
            "4-4-2", List.of("门将", "左后卫", "中后卫", "中后卫", "右后卫", "左中场", "中场", "中场", "右中场", "前锋", "前锋"),
            "3-4-3", List.of("门将", "中后卫", "中后卫", "中后卫", "左中场", "中场", "中场", "右中场", "左边锋", "中锋", "右边锋"),
            "3-5-2", List.of("门将", "中后卫", "中后卫", "中后卫", "左翼卫", "中场", "前腰", "中场", "右翼卫", "前锋", "前锋"));
    private static final Logger log = LoggerFactory.getLogger(CardWorkshopController.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CardWorkshopRateLimitService rateLimitService;
    private final VirtualPersonaPolicy personaPolicy;
    private final DistributedLockService distributedLockService;
    private final AtomicLong lastWarmAt = new AtomicLong(0L);

    @Value("${card-workshop.max-team-caches:500}")
    private int maxTeamCaches;

    @Value("${card-workshop.wiki-cache-ttl-seconds:900}")
    private long wikiCacheTtlSeconds;

    @Value("${card-workshop.real-players-enabled:false}")
    private boolean realPlayersEnabled;

    private final Map<String, WikiCacheEntry> wikiCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong wikiCircuitOpenUntil = new AtomicLong(0L);
    private volatile HttpClient wikipediaHttpClient;

    @Value("${crawler.proxy.host:}")
    private String crawlerProxyHost;

    @Value("${crawler.proxy.port:0}")
    private int crawlerProxyPort;

    @PostConstruct
    void ensureTables() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_player_cards (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, card_key VARCHAR(255) NOT NULL, canonical_player_id VARCHAR(192), " +
                "player_source_id VARCHAR(128), player_name VARCHAR(160) NOT NULL, position VARCHAR(64), " +
                "number VARCHAR(16), age VARCHAR(16), nationality VARCHAR(80), photo_url VARCHAR(512), " +
                "team_name VARCHAR(160), league_name VARCHAR(120), source VARCHAR(64) NOT NULL, source_updated_at DATETIME NULL, " +
                "card_type VARCHAR(32) NOT NULL DEFAULT 'REAL_PLAYER', rating_origin VARCHAR(32) NOT NULL DEFAULT 'RULE_BASED', rating_version VARCHAR(32) NOT NULL DEFAULT 'roster-lab-v1', " +
                "pace INT NOT NULL DEFAULT 60, shooting INT NOT NULL DEFAULT 60, passing INT NOT NULL DEFAULT 60, " +
                "dribbling INT NOT NULL DEFAULT 60, defending INT NOT NULL DEFAULT 60, physical INT NOT NULL DEFAULT 60, " +
                "overall INT NOT NULL DEFAULT 60, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id), UNIQUE KEY uk_fc_player_card_key (card_key), KEY idx_fc_player_card_name (player_name), " +
                "KEY idx_fc_player_card_team (team_name), KEY idx_fc_player_card_league (league_name)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_user_lineups (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, name VARCHAR(64) NOT NULL, " +
                "formation VARCHAR(32) NOT NULL DEFAULT '4-3-3', created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id), UNIQUE KEY uk_fc_user_lineup_name (user_id, name), KEY idx_fc_user_lineup_user (user_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_user_lineup_slots (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, lineup_id BIGINT NOT NULL, slot_index INT NOT NULL, " +
                "position VARCHAR(32) NOT NULL, player_card_id BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id), UNIQUE KEY uk_fc_lineup_slot (lineup_id, slot_index), KEY idx_fc_lineup_card (player_card_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("fc_player_cards", "owner_user_id", "BIGINT NULL");
        addColumnIfMissing("fc_player_cards", "source_url", "VARCHAR(512) NULL");
        addColumnIfMissing("fc_player_cards", "bio_summary", "TEXT NULL");
        addColumnIfMissing("fc_player_cards", "visibility", "VARCHAR(16) NOT NULL DEFAULT 'PUBLIC'");
        addColumnIfMissing("fc_player_cards", "canonical_player_id", "VARCHAR(192) NULL");
        addColumnIfMissing("fc_player_cards", "rating_version", "VARCHAR(32) NOT NULL DEFAULT 'roster-lab-v1'");
        addColumnIfMissing("fc_player_cards", "source_updated_at", "DATETIME NULL");
        addColumnIfMissing("fc_player_cards", "last_seen_at", "DATETIME NULL");
        addColumnIfMissing("fc_player_cards", "source_status", "VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'");
        addColumnIfMissing("fc_player_cards", "rating_basis_json", "TEXT NULL");
        addColumnIfMissing("fc_player_cards", "source_revision", "VARCHAR(128) NULL");
        addColumnIfMissing("fc_player_cards", "content_hash", "VARCHAR(96) NULL");
        addColumnIfMissing("fc_player_cards", "generation_seed", "INT NULL");
        addColumnIfMissing("fc_player_cards", "archetype", "VARCHAR(32) NULL");
        addColumnIfMissing("fc_player_cards", "attribute_explanation", "TEXT NULL");
        addColumnIfMissing("fc_player_cards", "moderation_status", "VARCHAR(16) NOT NULL DEFAULT 'APPROVED'");
        addColumnIfMissing("fc_player_cards", "source_snapshot", "TEXT NULL");
        addColumnIfMissing("fc_player_cards", "persona_source_key", "VARCHAR(192) NULL");
        addColumnIfMissing("fc_player_cards", "source_language", "VARCHAR(16) NULL");
        addColumnIfMissing("fc_player_cards", "source_license", "VARCHAR(64) NULL");
        addColumnIfMissing("fc_player_cards", "source_attribution", "VARCHAR(512) NULL");
        addColumnIfMissing("fc_player_cards", "policy_version", "VARCHAR(64) NULL");
        addColumnIfMissing("fc_player_cards", "options_hash", "VARCHAR(96) NULL");
        addColumnIfMissing("fc_player_cards", "source_fetched_at", "DATETIME NULL");
        addColumnIfMissing("fc_player_cards", "moderation_reason", "VARCHAR(500) NULL");
        addColumnIfMissing("fc_player_cards", "moderated_by", "BIGINT NULL");
        addColumnIfMissing("fc_player_cards", "moderated_at", "DATETIME NULL");
        addColumnIfMissing("fc_player_cards", "skills_json", "TEXT NULL");
        addColumnIfMissing("fc_player_cards", "traits_json", "TEXT NULL");
        addColumnIfMissing("fc_player_cards", "tags_json", "TEXT NULL");
        addColumnIfMissing("fc_player_cards", "upgrade_level", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("fc_player_cards", "upgrade_points_spent", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("fc_player_cards", "reroll_count", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("fc_player_cards", "reroll_window_started_at", "DATETIME NULL");
        // Cards created before moderation existed must re-enter the review
        // queue; cards explicitly reviewed by an admin have moderated_at set
        // and are left untouched on subsequent restarts.
        try { jdbcTemplate.update("UPDATE fc_player_cards SET moderation_status = 'PENDING', visibility = 'PRIVATE' WHERE card_type = 'CUSTOM_PERSONA' AND owner_user_id IS NOT NULL AND moderation_status = 'APPROVED' AND moderated_at IS NULL"); } catch (Exception ignored) { }
        try { jdbcTemplate.execute("ALTER TABLE fc_player_cards ADD KEY idx_fc_player_card_owner (owner_user_id)"); } catch (Exception ignored) { }
        try { jdbcTemplate.execute("ALTER TABLE fc_player_cards ADD KEY idx_fc_player_card_canonical (canonical_player_id)"); } catch (Exception ignored) { }
        try { jdbcTemplate.execute("ALTER TABLE fc_player_cards ADD KEY idx_fc_persona_source_key (persona_source_key)"); } catch (Exception ignored) { }
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_player_card_rating_history (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, rating_version VARCHAR(32) NOT NULL, " +
                "pace INT NOT NULL, shooting INT NOT NULL, passing INT NOT NULL, dribbling INT NOT NULL, " +
                "defending INT NOT NULL, physical INT NOT NULL, overall INT NOT NULL, basis_json TEXT NULL, " +
                "source_updated_at DATETIME NULL, captured_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id), KEY idx_fc_card_rating_history_card (card_id), KEY idx_fc_card_rating_history_time (captured_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_persona_card_version (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, owner_user_id BIGINT NOT NULL, " +
                "source_title VARCHAR(255) NOT NULL, source_url VARCHAR(512) NULL, source_revision VARCHAR(128) NULL, " +
                "content_hash VARCHAR(96) NULL, generation_seed INT NULL, archetype VARCHAR(32) NULL, position VARCHAR(64) NULL, " +
                "options_hash VARCHAR(96) NULL, policy_version VARCHAR(64) NULL, source_language VARCHAR(16) NULL, source_license VARCHAR(64) NULL, " +
                "stats_json TEXT NOT NULL, explanation TEXT NULL, snapshot_json TEXT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id), KEY idx_fc_persona_version_card (card_id, created_at), KEY idx_fc_persona_version_owner (owner_user_id, created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        // Older installations created this table before the metadata columns
        // were introduced. Keep the version-history endpoint compatible with
        // those databases instead of failing with an unknown-column 500.
        addColumnIfMissing("fc_persona_card_version", "options_hash", "VARCHAR(96) NULL");
        addColumnIfMissing("fc_persona_card_version", "policy_version", "VARCHAR(64) NULL");
        addColumnIfMissing("fc_persona_card_version", "source_language", "VARCHAR(16) NULL");
        addColumnIfMissing("fc_persona_card_version", "source_license", "VARCHAR(64) NULL");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_card_report (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, reporter_user_id BIGINT NOT NULL, reason VARCHAR(64) NOT NULL, " +
                "detail VARCHAR(500) NULL, status VARCHAR(16) NOT NULL DEFAULT 'OPEN', created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, resolved_at DATETIME NULL, resolved_by BIGINT NULL, resolution_note VARCHAR(500) NULL, " +
                "PRIMARY KEY (id), UNIQUE KEY uk_fc_card_report_user (card_id, reporter_user_id), KEY idx_fc_card_report_status (status, created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("fc_card_report", "resolved_by", "BIGINT NULL");
        addColumnIfMissing("fc_card_report", "resolution_note", "VARCHAR(500) NULL");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_card_report_history (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, report_id BIGINT NOT NULL, card_id BIGINT NOT NULL, operator_user_id BIGINT NOT NULL, " +
                "from_status VARCHAR(16) NULL, to_status VARCHAR(16) NOT NULL, note VARCHAR(500) NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id), KEY idx_fc_card_report_history_report (report_id, created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_card_moderation_history (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, operator_user_id BIGINT NOT NULL, " +
                "from_status VARCHAR(16) NULL, to_status VARCHAR(16) NOT NULL, note VARCHAR(500) NULL, " +
                "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), KEY idx_fc_card_moderation_history_card (card_id, created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_lineup_share (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, lineup_id BIGINT NOT NULL, owner_user_id BIGINT NOT NULL, share_token VARCHAR(96) NOT NULL, " +
                "visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC', expires_at DATETIME NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, revoked_at DATETIME NULL, " +
                "PRIMARY KEY (id), UNIQUE KEY uk_fc_lineup_share_token (share_token), UNIQUE KEY uk_fc_lineup_share_lineup (lineup_id), KEY idx_fc_lineup_share_owner (owner_user_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("fc_lineup_share", "view_count", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("fc_lineup_share", "last_viewed_at", "DATETIME NULL");
        // Historical shares created before expiry was introduced must not remain
        // permanent bearer links. Backfill a bounded lifetime once at startup.
        try { jdbcTemplate.update("UPDATE fc_lineup_share SET expires_at = DATE_ADD(created_at, INTERVAL " + SHARE_TTL_DAYS + " DAY) WHERE expires_at IS NULL"); } catch (Exception ignored) { }
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_user_card_tag (" +
                "user_id BIGINT NOT NULL, card_id BIGINT NOT NULL, tag VARCHAR(32) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (user_id, card_id, tag), KEY idx_fc_user_card_tag_card (card_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_card_public_like (" +
                "card_id BIGINT NOT NULL, user_id BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (card_id, user_id), KEY idx_fc_card_public_like_card (card_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_persona_source_cache (" +
                "cache_key VARCHAR(192) NOT NULL, source_language VARCHAR(16) NOT NULL, payload_json MEDIUMTEXT NOT NULL, " +
                "source_url VARCHAR(512) NULL, source_revision VARCHAR(128) NULL, content_hash VARCHAR(96) NULL, " +
                "fetched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, expires_at DATETIME NOT NULL, " +
                "PRIMARY KEY (cache_key, source_language), KEY idx_fc_persona_cache_expiry (expires_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("fc_player_cards", "catalog_id", "BIGINT NULL");
        addColumnIfMissing("fc_player_cards", "catalog_version", "VARCHAR(64) NULL");
        addColumnIfMissing("fc_player_cards", "catalog_snapshot", "MEDIUMTEXT NULL");
        try { jdbcTemplate.execute("ALTER TABLE fc_player_cards ADD KEY idx_fc_player_card_catalog (catalog_id)"); } catch (Exception ignored) { }
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_persona_catalog (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, persona_key VARCHAR(192) NOT NULL, name VARCHAR(160) NOT NULL, description TEXT NULL, " +
                "source_title VARCHAR(255) NULL, source_url VARCHAR(512) NULL, source_attribution VARCHAR(512) NULL, source_license VARCHAR(64) NULL, photo_url VARCHAR(512) NULL, " +
                "position VARCHAR(64) NOT NULL DEFAULT '全能', archetype VARCHAR(32) NOT NULL DEFAULT '全能', " +
                "pace INT NOT NULL DEFAULT 60, shooting INT NOT NULL DEFAULT 60, passing INT NOT NULL DEFAULT 60, dribbling INT NOT NULL DEFAULT 60, defending INT NOT NULL DEFAULT 60, physical INT NOT NULL DEFAULT 60, overall INT NOT NULL DEFAULT 60, " +
                "skills_json TEXT NULL, traits_json TEXT NULL, tags_json TEXT NULL, price_points INT NOT NULL DEFAULT 10, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', " +
                "created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, published_at DATETIME NULL, " +
                "PRIMARY KEY (id), UNIQUE KEY uk_fc_persona_catalog_key (persona_key), KEY idx_fc_persona_catalog_status (status, updated_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("fc_persona_catalog", "tags_json", "TEXT NULL");
        addColumnIfMissing("fc_persona_catalog", "source_page_id", "VARCHAR(64) NULL");
        addColumnIfMissing("fc_persona_catalog", "source_language", "VARCHAR(16) NULL");
        addColumnIfMissing("fc_persona_catalog", "source_revision", "VARCHAR(128) NULL");
        addColumnIfMissing("fc_persona_catalog", "content_hash", "VARCHAR(96) NULL");
        addColumnIfMissing("fc_persona_catalog", "catalog_version", "VARCHAR(64) NOT NULL DEFAULT 'catalog-v1'");
        addColumnIfMissing("fc_persona_catalog", "published_by", "BIGINT NULL");
        addColumnIfMissing("fc_persona_catalog", "updated_by", "BIGINT NULL");
        addColumnIfMissing("fc_persona_catalog", "moderation_status", "VARCHAR(16) NOT NULL DEFAULT 'DRAFT'");
        addColumnIfMissing("fc_persona_catalog", "moderation_reason", "VARCHAR(500) NULL");
        addColumnIfMissing("fc_persona_catalog", "reviewed_by", "BIGINT NULL");
        addColumnIfMissing("fc_persona_catalog", "reviewed_at", "DATETIME NULL");
        try { jdbcTemplate.update("UPDATE fc_persona_catalog SET catalog_version = CONCAT('catalog-', id) WHERE catalog_version IS NULL OR catalog_version = ''"); } catch (Exception error) { log.warn("[CardLab] unable to backfill catalog versions", error); }
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_persona_inventory (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, catalog_id BIGINT NOT NULL, card_id BIGINT NOT NULL, redeemed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id), UNIQUE KEY uk_fc_persona_inventory_user_catalog (user_id, catalog_id), KEY idx_fc_persona_inventory_user (user_id), KEY idx_fc_persona_inventory_catalog (catalog_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("fc_persona_inventory", "catalog_version", "VARCHAR(64) NULL");
        addColumnIfMissing("fc_persona_inventory", "price_points", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("fc_persona_inventory", "catalog_snapshot", "MEDIUMTEXT NULL");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_persona_catalog_version (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, catalog_id BIGINT NOT NULL, version VARCHAR(64) NOT NULL, snapshot_json MEDIUMTEXT NOT NULL, " +
                "change_reason VARCHAR(500) NULL, changed_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id), UNIQUE KEY uk_fc_catalog_version (catalog_id, version), KEY idx_fc_catalog_version_time (catalog_id, created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_card_lab_audit_log (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, operator_user_id BIGINT NULL, action VARCHAR(64) NOT NULL, entity_type VARCHAR(64) NOT NULL, entity_id BIGINT NULL, detail_json MEDIUMTEXT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id), KEY idx_fc_card_lab_audit_time (created_at), KEY idx_fc_card_lab_audit_entity (entity_type, entity_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_card_lab_schema_version (version VARCHAR(64) NOT NULL, applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (version)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_card_lab_synergy_rule (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, rule_version VARCHAR(64) NOT NULL, tag_prefix VARCHAR(32) NOT NULL, threshold INT NOT NULL, bonus INT NOT NULL, description VARCHAR(128) NOT NULL, enabled TINYINT NOT NULL DEFAULT 1, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id), UNIQUE KEY uk_fc_synergy_rule (rule_version, tag_prefix, threshold)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_user_points_wallet (" +
                "user_id BIGINT NOT NULL, balance INT NOT NULL DEFAULT 0, total_earned INT NOT NULL DEFAULT 0, total_spent INT NOT NULL DEFAULT 0, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (user_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_user_points_ledger (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, event_type VARCHAR(32) NOT NULL, event_key VARCHAR(128) NOT NULL, amount INT NOT NULL, balance_after INT NOT NULL, reference_id BIGINT NULL, description VARCHAR(255) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id), UNIQUE KEY uk_fc_points_event (user_id, event_key), KEY idx_fc_points_user_time (user_id, created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        seedSynergyRules();
        try { jdbcTemplate.update("INSERT IGNORE INTO fc_card_lab_schema_version (version) VALUES (?)", CARD_LAB_SCHEMA_VERSION); } catch (Exception error) { log.warn("[CardLab] schema version marker unavailable", error); }
        addForeignKeyIfMissing("fc_user_lineup_slots", "fk_fc_lineup_slot_lineup", "FOREIGN KEY (lineup_id) REFERENCES fc_user_lineups(id) ON DELETE CASCADE");
        addForeignKeyIfMissing("fc_user_lineup_slots", "fk_fc_lineup_slot_card", "FOREIGN KEY (player_card_id) REFERENCES fc_player_cards(id) ON DELETE CASCADE");
        addForeignKeyIfMissing("fc_lineup_share", "fk_fc_lineup_share_lineup", "FOREIGN KEY (lineup_id) REFERENCES fc_user_lineups(id) ON DELETE CASCADE");
        addForeignKeyIfMissing("fc_persona_inventory", "fk_fc_inventory_catalog", "FOREIGN KEY (catalog_id) REFERENCES fc_persona_catalog(id) ON DELETE CASCADE");
        addForeignKeyIfMissing("fc_persona_inventory", "fk_fc_inventory_card", "FOREIGN KEY (card_id) REFERENCES fc_player_cards(id) ON DELETE CASCADE");
        addForeignKeyIfMissing("fc_persona_catalog_version", "fk_fc_catalog_version_catalog", "FOREIGN KEY (catalog_id) REFERENCES fc_persona_catalog(id) ON DELETE CASCADE");
    }

    @GetMapping("/players")
    public ApiResponse<Map<String, Object>> players(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String team,
                                                     @RequestParam(required = false) String position,
                                                     @RequestParam(required = false) String league,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "24") int size,
                                                     @RequestParam(required = false) Integer limit) {
        int safeSize = Math.max(1, Math.min(size > 0 ? size : (limit == null ? DEFAULT_PAGE_SIZE : limit), MAX_PLAYERS));
        int safePage = Math.max(1, page);
        int offset = (safePage - 1) * safeSize;
        String key = keyword == null ? "" : keyword.trim();
        String teamKey = team == null ? "" : team.trim();
        String positionKey = position == null ? "" : position.trim();
        String leagueKey = league == null ? "" : league.trim();
        String like = "%" + key + "%";
        String teamLike = "%" + teamKey + "%";
        String positionLike = "%" + positionKey + "%";
        String leagueLike = "%" + leagueKey + "%";
        Long viewerId = UserContext.getUserId();
        long viewer = viewerId == null ? -1L : viewerId;
        String cardTypeFilter = realPlayersEnabled
                ? "AND (card_type <> 'CUSTOM_PERSONA' OR rating_origin = '" + CATALOG_CARD_RATING_ORIGIN + "') "
                : "AND card_type = 'CUSTOM_PERSONA' AND rating_origin = '" + CATALOG_CARD_RATING_ORIGIN + "' ";
        String where = "FROM fc_player_cards WHERE ((visibility = 'PUBLIC' AND owner_user_id IS NULL) OR owner_user_id = ?) " +
                cardTypeFilter +
                "AND (? = '' OR player_name LIKE ?) " +
                "AND (? = '' OR team_name LIKE ?) AND (? = '' OR position LIKE ?) AND (? = '' OR league_name LIKE ?)";
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + where,
                Integer.class, viewer, key, like, teamKey, teamLike, positionKey, positionLike, leagueKey, leagueLike);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, player_source_id, player_name, position, number, age, nationality, photo_url, " +
                        "team_name, league_name, source, source_url, bio_summary, visibility, card_type, rating_origin, " +
                        "canonical_player_id, rating_version, rating_basis_json, source_status, source_updated_at, last_seen_at, " +
                        "source_revision, content_hash, generation_seed, archetype, attribute_explanation, moderation_status, source_snapshot, persona_source_key, source_language, source_license, source_attribution, policy_version, options_hash, source_fetched_at, skills_json, traits_json, tags_json, reroll_count, catalog_id, " +
                        "pace, shooting, passing, dribbling, defending, physical, overall, upgrade_level, upgrade_points_spent, updated_at " + where +
                        " ORDER BY overall DESC, player_name ASC LIMIT ? OFFSET ?",
                viewer, key, like, teamKey, teamLike, positionKey, positionLike, leagueKey, leagueLike, safeSize, offset);
        attachPersonaTags(rows);
        if (viewerId != null) attachUserTags(viewerId, rows);
        Object lastUpdatedValue = jdbcTemplate.queryForObject("SELECT MAX(source_updated_at) " + where,
                Object.class, viewer, key, like, teamKey, teamLike, positionKey, positionLike, leagueKey, leagueLike);
        String lastUpdatedAt = lastUpdatedValue == null ? "" : String.valueOf(lastUpdatedValue);
        Integer teamCount = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT team_name) " + where,
                Integer.class, viewer, key, like, teamKey, teamLike, positionKey, positionLike, leagueKey, leagueLike);
        Integer leagueCount = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT league_name) " + where,
                Integer.class, viewer, key, like, teamKey, teamLike, positionKey, positionLike, leagueKey, leagueLike);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", rows);
        response.put("total", total == null ? 0 : total);
        response.put("page", safePage);
        response.put("size", safeSize);
        response.put("source", realPlayersEnabled ? CARD_SOURCE : CUSTOM_SOURCE);
        response.put("lastUpdatedAt", lastUpdatedAt == null ? "" : lastUpdatedAt);
        response.put("freshnessStatus", freshnessStatus(lastUpdatedAt));
        response.put("coverage", Map.of("teams", teamCount == null ? 0 : teamCount, "leagues", leagueCount == null ? 0 : leagueCount,
                "maxTeamCaches", Math.max(1, maxTeamCaches)));
        response.put("ratingVersion", realPlayersEnabled ? "roster-lab-v3" : "virtual-persona-v1");
        response.put("realPlayersEnabled", realPlayersEnabled);
        response.put("mode", realPlayersEnabled ? "MIXED" : "VIRTUAL_ONLY");
        response.put("notice", realPlayersEnabled
                ? "真实球员卡仍处于实验室灰度，不代表官方评分；人物灵感卡仅限虚拟角色"
                : "当前仅开放虚拟角色卡；真实球员卡已暂时隐藏，历史数据保留待后续授权与校准");
        return ApiResponse.ok(response);
    }

    @PostMapping("/custom-cards/preview")
    public ApiResponse<Map<String, Object>> previewCustomCard(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        throw userCreationDisabled();
    }

    @PostMapping("/custom-cards")
    @Transactional
    public ApiResponse<Map<String, Object>> createCustomCard(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        if (!legacyCreationAllowed()) throw userCreationDisabled();
        Long userId = requireUser();
        String requestedName = text(body == null ? null : body.get("sourceTitle"));
        if (requestedName.isBlank()) requestedName = text(body == null ? null : body.get("name"));
        Map<String, Object> preview = fetchPersonaPreview(requestedName, personaOptions(body));
        String name = text(preview.get("name"));
        String sourceTitle = text(preview.getOrDefault("sourceTitle", name));
        String personaSourceKey = personaSourceKey(preview);
        String cardKey = CUSTOM_SOURCE + "|" + userId + "|" + personaSourceKey;
        Map<String, Object> rawStats = preview.get("stats") instanceof Map<?, ?> map ? toStringMap(map) : Map.of();
        List<Integer> stats = List.of(intValue(rawStats.get("pace"), 60), intValue(rawStats.get("shooting"), 60), intValue(rawStats.get("passing"), 60), intValue(rawStats.get("dribbling"), 60), intValue(rawStats.get("defending"), 60), intValue(rawStats.get("physical"), 60), intValue(rawStats.get("overall"), 60));
        String canonicalPersonaId = "persona:" + personaSourceKey;
        String basis = statBasis(preview, stats);
        jdbcTemplate.update("INSERT INTO fc_player_cards (card_key, owner_user_id, canonical_player_id, player_source_id, player_name, position, nationality, team_name, league_name, source, source_status, source_url, bio_summary, photo_url, visibility, card_type, rating_origin, rating_version, rating_basis_json, source_updated_at, last_seen_at, pace, shooting, passing, dribbling, defending, physical, overall) " +
                "VALUES (?, ?, ?, ?, ?, '全能', '', '虚拟人物', '人物灵感卡', ?, 'ACTIVE', ?, ?, ?, 'PRIVATE', 'CUSTOM_PERSONA', 'VIRTUAL_PERSONA_RULE_BASED', 'virtual-persona-v1', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE player_name=VALUES(player_name), source_url=VALUES(source_url), bio_summary=VALUES(bio_summary), photo_url=VALUES(photo_url), source_status='ACTIVE', rating_basis_json=VALUES(rating_basis_json), source_updated_at=CURRENT_TIMESTAMP, last_seen_at=CURRENT_TIMESTAMP, rating_version='virtual-persona-v1', pace=VALUES(pace), shooting=VALUES(shooting), passing=VALUES(passing), dribbling=VALUES(dribbling), defending=VALUES(defending), physical=VALUES(physical), overall=VALUES(overall), updated_at=CURRENT_TIMESTAMP",
                cardKey, userId, canonicalPersonaId, "wiki:" + sourceTitle, name, CUSTOM_SOURCE, preview.get("sourceUrl"), preview.get("summary"), preview.get("photoUrl"), basis, stats.get(0), stats.get(1), stats.get(2), stats.get(3), stats.get(4), stats.get(5), stats.get(6));
        jdbcTemplate.update("UPDATE fc_player_cards SET canonical_player_id = ?, persona_source_key = ?, position = ?, source_revision = ?, content_hash = ?, generation_seed = ?, archetype = ?, attribute_explanation = ?, moderation_status = 'PENDING', moderation_reason = NULL, moderated_by = NULL, moderated_at = NULL, source_snapshot = ?, source_language = ?, source_license = ?, source_attribution = ?, policy_version = ?, options_hash = ?, source_fetched_at = CURRENT_TIMESTAMP, source_status = 'ACTIVE', skills_json = ?, traits_json = ?, rating_basis_json = ?, visibility = 'PRIVATE' WHERE card_key = ? AND owner_user_id = ?",
                canonicalPersonaId, personaSourceKey, allowedPosition(text(preview.getOrDefault("position", "全能"))), text(preview.get("sourceRevision")), text(preview.get("contentHash")), intValue(preview.get("generationSeed"), 0), text(preview.getOrDefault("archetype", "全能")), text(preview.get("attributeExplanation")), snapshotJson(preview), text(preview.getOrDefault("sourceLanguage", "zh")), text(preview.getOrDefault("sourceLicense", WIKIPEDIA_LICENSE)), text(preview.getOrDefault("sourceAttribution", "Wikipedia，CC BY-SA 4.0")), text(preview.getOrDefault("policyVersion", PERSONA_POLICY_VERSION)), text(preview.get("optionsHash")), jsonText(preview.get("skills")), jsonText(preview.get("traits")), basis, cardKey, userId);
        Long cardId = jdbcTemplate.queryForObject("SELECT id FROM fc_player_cards WHERE card_key = ? AND owner_user_id = ?", Long.class, cardKey, userId);
        savePersonaVersion(userId, cardId, preview, stats);
        return ApiResponse.ok(jdbcTemplate.queryForMap("SELECT * FROM fc_player_cards WHERE card_key = ? AND owner_user_id = ?", cardKey, userId));
    }

    @GetMapping("/custom-cards")
    public ApiResponse<List<Map<String, Object>>> customCards() {
        Long userId = requireUser();
        List<Map<String, Object>> cards = jdbcTemplate.queryForList("SELECT * FROM fc_player_cards WHERE owner_user_id = ? AND card_type = 'CUSTOM_PERSONA' AND rating_origin = '" + CATALOG_CARD_RATING_ORIGIN + "' ORDER BY updated_at DESC", userId);
        cards.forEach(card -> card.put("tags", cardTags(userId, number(card.get("id")))));
        return ApiResponse.ok(cards);
    }

    @GetMapping("/showcase")
    public ApiResponse<Map<String, Object>> showcase(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String archetype,
                                                     @RequestParam(required = false) String position,
                                                     @RequestParam(defaultValue = "updated") String sort,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "24") int size) {
        throw new ResponseStatusException(HttpStatus.GONE, "公开展厅已下线，请使用角色兑换中心");
    }

    @PostMapping("/custom-cards/{id}/public-like")
    @Transactional
    public ApiResponse<Map<String, Object>> likePublicCard(@PathVariable Long id) {
        Long userId = requireUser();
        Integer visible = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_player_cards WHERE id = ? AND card_type = 'CUSTOM_PERSONA' AND visibility = 'PUBLIC' AND moderation_status = 'APPROVED'", Integer.class, id);
        if (visible == null || visible == 0) throw new NoSuchElementException("公开角色卡不存在");
        jdbcTemplate.update("INSERT IGNORE INTO fc_card_public_like (card_id, user_id) VALUES (?, ?)", id, userId);
        return publicLikeResult(id, userId);
    }

    @DeleteMapping("/custom-cards/{id}/public-like")
    @Transactional
    public ApiResponse<Map<String, Object>> unlikePublicCard(@PathVariable Long id) {
        Long userId = requireUser();
        jdbcTemplate.update("DELETE FROM fc_card_public_like WHERE card_id = ? AND user_id = ?", id, userId);
        return publicLikeResult(id, userId);
    }

    @PostMapping("/custom-cards/{id}/clone")
    public ApiResponse<Map<String, Object>> clonePublicCard(@PathVariable Long id, HttpServletRequest request) {
        if (!legacyCreationAllowed()) throw userCreationDisabled();
        Long userId = requireUser();
        Map<String, Object> source;
        try { source = jdbcTemplate.queryForMap("SELECT player_source_id, archetype, position FROM fc_player_cards WHERE id = ? AND card_type = 'CUSTOM_PERSONA' AND visibility = 'PUBLIC' AND moderation_status = 'APPROVED'", id); }
        catch (Exception error) { throw new NoSuchElementException("公开角色卡不存在或未通过审核"); }
        String sourceTitle = text(source.get("player_source_id"));
        if (sourceTitle.startsWith("wiki:")) sourceTitle = sourceTitle.substring(5);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceTitle", sourceTitle);
        body.put("archetype", source.get("archetype"));
        body.put("position", source.get("position"));
        body.put("seed", Math.floorMod((sourceTitle + "|" + userId).hashCode(), 100000));
        ApiResponse<Map<String, Object>> result = createCustomCard(body, request);
        return result;
    }

    private ApiResponse<Map<String, Object>> publicLikeResult(Long id, Long userId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_card_public_like WHERE card_id = ?", Integer.class, id);
        Integer mine = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_card_public_like WHERE card_id = ? AND user_id = ?", Integer.class, id, userId);
        return ApiResponse.ok(Map.of("cardId", id, "likeCount", count == null ? 0 : count, "liked", mine != null && mine > 0));
    }

    @GetMapping("/custom-cards/collection/summary")
    public ApiResponse<Map<String, Object>> collectionSummary() {
        Long userId = requireUser();
        String catalogFilter = " AND rating_origin = '" + CATALOG_CARD_RATING_ORIGIN + "'";
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_player_cards WHERE owner_user_id = ? AND card_type = 'CUSTOM_PERSONA'" + catalogFilter, Integer.class, userId);
        Integer favorites = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_user_card_tag t JOIN fc_player_cards c ON c.id = t.card_id WHERE t.user_id = ? AND t.tag = 'favorite'" + catalogFilter, Integer.class, userId);
        Integer pending = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_player_cards WHERE owner_user_id = ? AND card_type = 'CUSTOM_PERSONA' AND moderation_status = 'PENDING'" + catalogFilter, Integer.class, userId);
        Integer publicCards = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_player_cards WHERE owner_user_id = ? AND card_type = 'CUSTOM_PERSONA' AND visibility = 'PUBLIC' AND moderation_status = 'APPROVED'" + catalogFilter, Integer.class, userId);
        List<Map<String, Object>> archetypes = jdbcTemplate.queryForList("SELECT COALESCE(archetype, '全能') AS label, COUNT(*) AS count FROM fc_player_cards WHERE owner_user_id = ? AND card_type = 'CUSTOM_PERSONA'" + catalogFilter + " GROUP BY archetype ORDER BY count DESC", userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("favorites", favorites == null ? 0 : favorites);
        result.put("pendingModeration", pending == null ? 0 : pending);
        result.put("publicCards", publicCards == null ? 0 : publicCards);
        result.put("archetypes", archetypes);
        result.put("achievements", List.of(
                Map.of("key", "first-card", "label", "兑换第一张", "done", (total != null && total >= 1)),
                Map.of("key", "collector-5", "label", "收藏 5 张", "done", (total != null && total >= 5)),
                Map.of("key", "collector-11", "label", "组建完整阵容", "done", hasCompleteLineup(userId))));
        return ApiResponse.ok(result);
    }

    @GetMapping("/points")
    public ApiResponse<Map<String, Object>> points() {
        Long userId = requireUser();
        return ApiResponse.ok(pointsSummary(userId, false));
    }

    @GetMapping("/points/ledger")
    public ApiResponse<Map<String, Object>> pointsLedger(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        Long userId = requireUser();
        int safePage = Math.max(1, page); int safeSize = Math.max(1, Math.min(size, 50));
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_user_points_ledger WHERE user_id = ?", Integer.class, userId);
        List<Map<String, Object>> items = jdbcTemplate.queryForList("SELECT id, event_type AS eventType, event_key AS eventKey, amount, balance_after AS balanceAfter, reference_id AS referenceId, description, created_at AS createdAt FROM fc_user_points_ledger WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?", userId, safeSize, (safePage - 1) * safeSize);
        return ApiResponse.ok(Map.of("items", items, "total", total == null ? 0 : total, "page", safePage, "size", safeSize));
    }

    @PostMapping("/points/check-in")
    @Transactional
    public ApiResponse<Map<String, Object>> checkIn() {
        Long userId = requireUser();
        ensurePointsWallet(userId);
        String day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        String eventKey = "checkin:" + day;
        Map<String, Object> wallet = jdbcTemplate.queryForMap("SELECT balance, total_earned, total_spent FROM fc_user_points_wallet WHERE user_id = ? FOR UPDATE", userId);
        int current = intValue(wallet.get("balance"), 0);
        int streak = checkInStreak(userId, day);
        int points = DAILY_CHECKIN_POINTS + Math.min(DAILY_STREAK_BONUS_POINTS * Math.max(0, streak), 20);
        int updated = jdbcTemplate.update("INSERT IGNORE INTO fc_user_points_ledger (user_id, event_type, event_key, amount, balance_after, description) VALUES (?, 'CHECK_IN', ?, ?, ?, ?)", userId, eventKey, points, current + points, "每日签到" + (streak > 0 ? " · 连续第" + (streak + 1) + "天" : ""));
        if (updated == 0) {
            Map<String, Object> result = pointsSummary(userId, true);
            result.put("checkedIn", true);
            result.put("pointsEarned", 0);
            result.put("message", "今天已经签到过了");
            return ApiResponse.ok(result);
        }
        jdbcTemplate.update("UPDATE fc_user_points_wallet SET balance = balance + ?, total_earned = total_earned + ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?", points, points, userId);
        Map<String, Object> result = pointsSummary(userId, true);
        result.put("checkedIn", true);
        result.put("pointsEarned", points);
        result.put("message", "签到成功");
        return ApiResponse.ok(result);
    }

    @GetMapping("/daily-challenge")
    public ApiResponse<Map<String, Object>> dailyChallenge(@RequestParam(required = false) Long lineupId) {
        Long userId = requireUser();
        return ApiResponse.ok(dailyChallengeStatus(userId, lineupId));
    }

    @PostMapping("/daily-challenge/claim")
    @Transactional
    public ApiResponse<Map<String, Object>> claimDailyChallenge(@RequestBody(required = false) Map<String, Object> body) {
        Long userId = requireUser();
        Long lineupId = body == null ? null : number(body.get("lineupId"));
        Map<String, Object> challenge = dailyChallengeStatus(userId, lineupId);
        if (!Boolean.TRUE.equals(challenge.get("done"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "完成今日阵容目标后才能领取奖励");
        String eventKey = text(challenge.get("eventKey"));
        ensurePointsWallet(userId);
        Map<String, Object> wallet = jdbcTemplate.queryForMap("SELECT balance FROM fc_user_points_wallet WHERE user_id = ? FOR UPDATE", userId);
        int current = intValue(wallet.get("balance"), 0);
        int reward = intValue(challenge.get("reward"), 5);
        int updated = jdbcTemplate.update("INSERT IGNORE INTO fc_user_points_ledger (user_id, event_type, event_key, amount, balance_after, reference_id, description) VALUES (?, 'CHALLENGE', ?, ?, ?, ?, ?)", userId, eventKey, reward, current + reward, lineupId, "完成每日阵容目标：" + text(challenge.get("label")));
        if (updated > 0) jdbcTemplate.update("UPDATE fc_user_points_wallet SET balance = balance + ?, total_earned = total_earned + ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?", reward, reward, userId);
        Map<String, Object> result = dailyChallengeStatus(userId, lineupId);
        result.put("pointsEarned", updated > 0 ? reward : 0);
        result.put("points", pointsSummary(userId, false));
        return ApiResponse.ok(result);
    }

    private Map<String, Object> dailyChallengeStatus(Long userId, Long lineupId) {
        java.time.LocalDate day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        int index = day.getDayOfMonth() % 3;
        String key = switch (index) { case 0 -> "chemistry"; case 1 -> "complete"; default -> "high-score"; };
        String label = switch (key) { case "chemistry" -> "默契搭档"; case "complete" -> "全位置阵容"; default -> "高分首发"; };
        String detail = switch (key) { case "chemistry" -> "让阵容默契达到 60%"; case "complete" -> "让四类位置各至少出现 1 张卡"; default -> "让阵容评分达到 80"; };
        boolean done = false;
        if (lineupId != null) {
            try {
                Map<String, Object> rating = (Map<String, Object>) readLineup(userId, lineupId).get("rating");
                int filled = intValue(rating.get("filledCount"), 0);
                int chemistry = intValue(rating.get("chemistryScore"), 0);
                int score = intValue(rating.get("finalScore"), 0);
                done = switch (key) { case "chemistry" -> chemistry >= 60; case "complete" -> filled >= 11; default -> score >= 80; };
            } catch (Exception ignored) { }
        }
        String eventKey = "challenge:" + day + ":" + key;
        Integer claimed = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_user_points_ledger WHERE user_id = ? AND event_key = ?", Integer.class, userId, eventKey);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key); result.put("label", label); result.put("detail", detail); result.put("reward", 5); result.put("eventKey", eventKey); result.put("done", done); result.put("claimed", claimed != null && claimed > 0); result.put("date", day.toString());
        return result;
    }

    @GetMapping("/catalog")
    public ApiResponse<Map<String, Object>> catalog(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String archetype,
                                                     @RequestParam(required = false) String position,
                                                     @RequestParam(defaultValue = "false") boolean ownedOnly,
                                                     @RequestParam(defaultValue = "price") String sort,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "24") int size) {
        int safePage = Math.max(1, page); int safeSize = Math.max(1, Math.min(size, 48));
        String key = keyword == null ? "" : keyword.trim(); String like = "%" + key + "%";
        String archetypeKey = archetype == null ? "" : archetype.trim(); String positionKey = position == null ? "" : position.trim();
        Long viewerId = UserContext.getUserId(); long viewer = viewerId == null ? -1L : viewerId;
        String where = " FROM fc_persona_catalog c WHERE c.status = 'PUBLISHED' AND (? = '' OR c.name LIKE ? OR c.description LIKE ?) AND (? = '' OR c.archetype = ?) AND (? = '' OR c.position = ?) AND (? = 0 OR EXISTS (SELECT 1 FROM fc_persona_inventory oi WHERE oi.catalog_id = c.id AND oi.user_id = ?))";
        int ownedFlag = ownedOnly && viewerId != null ? 1 : 0;
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + where, Integer.class, key, like, like, archetypeKey, archetypeKey, positionKey, positionKey, ownedFlag, viewer);
        String order = switch (sort == null ? "price" : sort.trim().toLowerCase(Locale.ROOT)) {
            case "overall" -> "c.overall DESC, c.updated_at DESC";
            case "name" -> "c.name ASC";
            case "new" -> "c.created_at DESC";
            default -> "c.price_points ASC, c.updated_at DESC";
        };
        List<Map<String, Object>> items = jdbcTemplate.queryForList("SELECT c.*, EXISTS(SELECT 1 FROM fc_persona_inventory i WHERE i.catalog_id = c.id AND i.user_id = ?) AS owned, (SELECT card_id FROM fc_persona_inventory i2 WHERE i2.catalog_id = c.id AND i2.user_id = ? LIMIT 1) AS owned_card_id" + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", viewer, viewer, key, like, like, archetypeKey, archetypeKey, positionKey, positionKey, ownedFlag, viewer, safeSize, (safePage - 1) * safeSize);
        items.forEach(this::addCatalogRarity);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("items", items); result.put("total", total == null ? 0 : total); result.put("page", safePage); result.put("size", safeSize);
        result.put("points", viewerId == null ? Map.of("balance", 0, "checkedIn", false, "guest", true) : pointsSummary(viewerId, false));
        return ApiResponse.ok(result);
    }

    @PostMapping("/catalog/{id}/redeem")
    @Transactional
    public ApiResponse<Map<String, Object>> redeemCatalogCard(@PathVariable Long id) {
        Long userId = requireUser();
        Map<String, Object> catalog;
        try { catalog = jdbcTemplate.queryForMap("SELECT * FROM fc_persona_catalog WHERE id = ? AND status = 'PUBLISHED' FOR UPDATE", id); }
        catch (Exception error) { throw new NoSuchElementException("角色暂未上架或已下架"); }
        Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_persona_inventory WHERE user_id = ? AND catalog_id = ?", Integer.class, userId, id);
        if (existing != null && existing > 0) {
            Map<String, Object> owned = jdbcTemplate.queryForMap("SELECT card_id FROM fc_persona_inventory WHERE user_id = ? AND catalog_id = ?", userId, id);
            return ApiResponse.ok(Map.of("alreadyOwned", true, "cardId", number(owned.get("card_id")), "points", pointsSummary(userId, false)));
        }
        ensurePointsWallet(userId);
        Map<String, Object> wallet = jdbcTemplate.queryForMap("SELECT balance FROM fc_user_points_wallet WHERE user_id = ? FOR UPDATE", userId);
        int price = Math.max(0, intValue(catalog.get("price_points"), 0)); int balance = intValue(wallet.get("balance"), 0);
        if (balance < price) throw new ResponseStatusException(HttpStatus.CONFLICT, "点数不足，请先完成今日签到或任务");
        List<Integer> stats = List.of(intValue(catalog.get("pace"), 60), intValue(catalog.get("shooting"), 60), intValue(catalog.get("passing"), 60), intValue(catalog.get("dribbling"), 60), intValue(catalog.get("defending"), 60), intValue(catalog.get("physical"), 60), intValue(catalog.get("overall"), 60));
        String personaKey = text(catalog.get("persona_key")); String cardKey = "catalog|" + userId + "|" + id;
        String snapshot = jsonText(catalog);
        String catalogVersion = text(catalog.getOrDefault("catalog_version", CATALOG_CARD_VERSION));
        jdbcTemplate.update("INSERT INTO fc_player_cards (card_key, canonical_player_id, player_source_id, player_name, position, nationality, team_name, league_name, source, source_status, source_url, bio_summary, photo_url, visibility, card_type, rating_origin, rating_version, rating_basis_json, source_updated_at, last_seen_at, pace, shooting, passing, dribbling, defending, physical, overall, owner_user_id, catalog_id, catalog_version, catalog_snapshot, moderation_status, source_snapshot, source_attribution, source_license, skills_json, traits_json, tags_json) VALUES (?, ?, ?, ?, ?, '', '虚拟人物', '角色目录', 'catalog-persona-v1', 'ACTIVE', ?, ?, ?, 'PRIVATE', 'CUSTOM_PERSONA', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'APPROVED', ?, ?, ?, ?, ?, ?)", cardKey, "persona:" + personaKey, "catalog:" + id, text(catalog.get("name")), text(catalog.get("position")), text(catalog.get("source_url")), text(catalog.get("description")), text(catalog.get("photo_url")), CATALOG_CARD_RATING_ORIGIN, catalogVersion, jsonText(Map.of("method", "admin-curated-catalog", "catalogId", id, "catalogVersion", catalogVersion, "competitiveEligible", false)), stats.get(0), stats.get(1), stats.get(2), stats.get(3), stats.get(4), stats.get(5), stats.get(6), userId, id, catalogVersion, snapshot, snapshot, text(catalog.get("source_attribution")), text(catalog.get("source_license")), text(catalog.get("skills_json")), text(catalog.get("traits_json")), text(catalog.get("tags_json")));
        Long cardId = jdbcTemplate.queryForObject("SELECT id FROM fc_player_cards WHERE card_key = ? AND owner_user_id = ?", Long.class, cardKey, userId);
        jdbcTemplate.update("INSERT INTO fc_persona_inventory (user_id, catalog_id, card_id, catalog_version, price_points, catalog_snapshot) VALUES (?, ?, ?, ?, ?, ?)", userId, id, cardId, catalogVersion, price, snapshot);
        int nextBalance = balance - price;
        jdbcTemplate.update("UPDATE fc_user_points_wallet SET balance = ?, total_spent = total_spent + ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?", nextBalance, price, userId);
        jdbcTemplate.update("INSERT INTO fc_user_points_ledger (user_id, event_type, event_key, amount, balance_after, reference_id, description) VALUES (?, 'REDEEM', ?, ?, ?, ?, ?)", userId, "redeem:" + id, -price, nextBalance, id, "兑换角色：" + text(catalog.get("name")));
        Map<String, Object> card = jdbcTemplate.queryForMap("SELECT * FROM fc_player_cards WHERE id = ?", cardId);
        addCatalogRarity(card);
        recordAudit("CATALOG_REDEEMED", "catalog", id, Map.of("userId", userId, "cardId", cardId, "version", catalogVersion, "price", price));
        return ApiResponse.ok(Map.of("alreadyOwned", false, "cardId", cardId, "catalogId", id, "card", card, "points", pointsSummary(userId, false)));
    }

    @PostMapping("/custom-cards/{id}/upgrade")
    @Transactional
    public ApiResponse<Map<String, Object>> upgradeCatalogCard(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Long userId = requireUser();
        Map<String, Object> card;
        try { card = jdbcTemplate.queryForMap("SELECT * FROM fc_player_cards WHERE id = ? AND owner_user_id = ? FOR UPDATE", id, userId); }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色卡不存在或不属于当前用户"); }
        if (card.get("catalog_id") == null || !CATALOG_CARD_RATING_ORIGIN.equals(text(card.get("rating_origin")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有兑换得到的虚拟角色卡可以升级");
        }
        String stat = text(body == null ? null : body.get("stat")).toLowerCase(Locale.ROOT);
        Set<String> allowedStats = Set.of("pace", "shooting", "passing", "dribbling", "defending", "physical");
        if (!allowedStats.contains(stat)) throw new IllegalArgumentException("请选择有效的能力项");
        int level = intValue(card.get("upgrade_level"), 0);
        if (level >= 20) throw new ResponseStatusException(HttpStatus.CONFLICT, "角色卡已达到最高等级");
        int cost = 15 + level * 10;
        ensurePointsWallet(userId);
        Map<String, Object> wallet = jdbcTemplate.queryForMap("SELECT balance FROM fc_user_points_wallet WHERE user_id = ? FOR UPDATE", userId);
        int balance = intValue(wallet.get("balance"), 0);
        if (balance < cost) throw new ResponseStatusException(HttpStatus.CONFLICT, "点数不足，需要 " + cost + " 点");
        int nextValue = Math.min(99, intValue(card.get(stat), 60) + 2);
        Map<String, Integer> nextStats = new LinkedHashMap<>();
        for (String key : allowedStats) nextStats.put(key, intValue(card.get(key), 60));
        nextStats.put(stat, nextValue);
        int overall = Math.round(nextStats.values().stream().mapToInt(Integer::intValue).sum() / 6f);
        int nextLevel = level + 1; int nextBalance = balance - cost;
        jdbcTemplate.update("UPDATE fc_player_cards SET " + stat + " = ?, overall = ?, upgrade_level = ?, upgrade_points_spent = upgrade_points_spent + ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND owner_user_id = ?", nextValue, overall, nextLevel, cost, id, userId);
        String eventKey = "upgrade:" + id + ":" + nextLevel;
        jdbcTemplate.update("UPDATE fc_user_points_wallet SET balance = ?, total_spent = total_spent + ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?", nextBalance, cost, userId);
        jdbcTemplate.update("INSERT INTO fc_user_points_ledger (user_id, event_type, event_key, amount, balance_after, reference_id, description) VALUES (?, 'UPGRADE', ?, ?, ?, ?, ?)", userId, eventKey, -cost, nextBalance, id, "升级角色卡：" + text(card.get("player_name")) + " · " + stat);
        Map<String, Object> updated = jdbcTemplate.queryForMap("SELECT * FROM fc_player_cards WHERE id = ? AND owner_user_id = ?", id, userId);
        addCatalogRarity(updated);
        recordAudit("CARD_UPGRADED", "card", id, Map.of("userId", userId, "stat", stat, "level", nextLevel, "cost", cost));
        return ApiResponse.ok(Map.of("card", updated, "points", pointsSummary(userId, false), "upgradeLevel", nextLevel, "cost", cost, "nextCost", 15 + nextLevel * 10));
    }

    @GetMapping("/custom-cards/{id}/versions")
    public ApiResponse<List<Map<String, Object>>> cardVersions(@PathVariable Long id) {
        Long userId = requireUser();
        // Catalog cards are fixed administrator snapshots and do not have
        // per-user Wiki/reroll history. Return an empty history instead of
        // treating a redeemed catalog card as a legacy custom card.
        if (isCatalogCard(id)) return ApiResponse.ok(List.of());
        ensureOwnCard(userId, id);
        return ApiResponse.ok(jdbcTemplate.queryForList("SELECT id, source_title, source_url, source_revision, content_hash, generation_seed, archetype, position, options_hash, policy_version, source_language, source_license, stats_json, explanation, snapshot_json, created_at FROM fc_persona_card_version WHERE card_id = ? AND owner_user_id = ? ORDER BY created_at DESC LIMIT 30", id, userId));
    }

    @PostMapping("/custom-cards/{id}/reroll")
    @Transactional
    public ApiResponse<Map<String, Object>> rerollCustomCard(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        if (!legacyCreationAllowed()) throw userCreationDisabled();
        Long userId = requireUser();
        Map<String, Object> existing = ensureOwnCard(userId, id);
        enforceRerollQuota(existing);
        String source = text(existing.get("player_source_id"));
        if (source.startsWith("wiki:")) source = source.substring(5);
        Map<String, Object> preview = fetchPersonaPreview(source, personaOptions(body));
        List<Integer> stats = statsFromPreview(preview);
        updatePersonaCardFromPreview(userId, id, preview, stats);
        savePersonaVersion(userId, id, preview, stats);
        return ApiResponse.ok(jdbcTemplate.queryForMap("SELECT * FROM fc_player_cards WHERE id = ? AND owner_user_id = ?", id, userId));
    }

    @PutMapping("/custom-cards/{id}/tags")
    @Transactional
    public ApiResponse<List<String>> updateCardTags(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Long userId = requireUser();
        ensureOwnCard(userId, id);
        jdbcTemplate.update("DELETE FROM fc_user_card_tag WHERE user_id = ? AND card_id = ?", userId, id);
        Object raw = body == null ? null : body.get("tags");
        if (raw instanceof List<?> values) {
            values.stream().map(CardWorkshopController::text).map(String::trim).filter(value -> !value.isBlank()).map(value -> value.substring(0, Math.min(32, value.length()))).distinct().limit(MAX_TAGS_PER_CARD).forEach(tag -> jdbcTemplate.update("INSERT IGNORE INTO fc_user_card_tag (user_id, card_id, tag) VALUES (?, ?, ?)", userId, id, tag));
        }
        return ApiResponse.ok(cardTags(userId, id));
    }

    @PutMapping("/custom-cards/{id}/visibility")
    @Transactional
    public ApiResponse<Map<String, Object>> updateCardVisibility(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        if (isCatalogCard(id)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "目录角色卡由管理员统一管理");
        Long userId = requireUser();
        ensureOwnCard(userId, id);
        String visibility = text(body == null ? null : body.get("visibility")).toUpperCase(Locale.ROOT);
        if (!Set.of("PRIVATE", "PUBLIC").contains(visibility)) throw new IllegalArgumentException("可见性无效");
        if ("PUBLIC".equals(visibility)) {
            jdbcTemplate.update("UPDATE fc_player_cards SET visibility = 'PUBLIC', moderation_status = 'PENDING', moderation_reason = NULL, moderated_by = NULL, moderated_at = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND owner_user_id = ?", id, userId);
        } else {
            jdbcTemplate.update("UPDATE fc_player_cards SET visibility = 'PRIVATE', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND owner_user_id = ?", id, userId);
        }
        return ApiResponse.ok(jdbcTemplate.queryForMap("SELECT id, visibility, moderation_status AS moderationStatus FROM fc_player_cards WHERE id = ? AND owner_user_id = ?", id, userId));
    }

    @PostMapping("/custom-cards/{id}/report")
    @Transactional
    public ApiResponse<Map<String, Object>> reportCard(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Long userId = requireUser();
        enforcePersonaRateLimit(userId, "report", null);
        Integer reportable = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_player_cards WHERE id = ? AND card_type = 'CUSTOM_PERSONA' AND owner_user_id <> ? AND visibility = 'PUBLIC' AND moderation_status = 'APPROVED'", Integer.class, id, userId);
        if (reportable == null || reportable == 0) throw new NoSuchElementException("角色卡不存在或不可举报");
        String reason = text(body == null ? null : body.get("reason"));
        if (reason.isBlank()) reason = "内容不合适";
        String detail = text(body == null ? null : body.get("detail"));
        jdbcTemplate.update("INSERT INTO fc_card_report (card_id, reporter_user_id, reason, detail) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE reason = VALUES(reason), detail = VALUES(detail), status = 'OPEN', created_at = CURRENT_TIMESTAMP", id, userId, reason.substring(0, Math.min(64, reason.length())), detail.substring(0, Math.min(500, detail.length())));
        return ApiResponse.ok(Map.of("reported", true, "status", "OPEN"));
    }

    @DeleteMapping("/custom-cards/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> deleteCustomCard(@PathVariable Long id) {
        Long userId = requireUser();
        if (isCatalogCard(id)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "兑换获得的角色卡属于个人收藏，不能删除");
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_player_cards WHERE id = ? AND owner_user_id = ? AND card_type = 'CUSTOM_PERSONA'", Integer.class, id, userId);
        if (count == null || count == 0) throw new NoSuchElementException("自定义卡不存在或无权访问");
        jdbcTemplate.update("DELETE s FROM fc_user_lineup_slots s JOIN fc_user_lineups l ON l.id = s.lineup_id WHERE s.player_card_id = ? AND l.user_id = ?", id, userId);
        jdbcTemplate.update("DELETE FROM fc_user_card_tag WHERE card_id = ?", id);
        jdbcTemplate.update("DELETE FROM fc_persona_card_version WHERE card_id = ? AND owner_user_id = ?", id, userId);
        jdbcTemplate.update("DELETE FROM fc_card_report_history WHERE card_id = ?", id);
        jdbcTemplate.update("DELETE FROM fc_card_report WHERE card_id = ?", id);
        jdbcTemplate.update("DELETE FROM fc_player_cards WHERE id = ? AND owner_user_id = ?", id, userId);
        return ApiResponse.ok(Map.of("deleted", true, "id", id));
    }

    @GetMapping("/admin/reports")
    public ApiResponse<List<Map<String, Object>>> adminReports(@RequestParam(defaultValue = "OPEN") String status) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(jdbcTemplate.queryForList("SELECT r.*, c.player_name, c.source_url FROM fc_card_report r JOIN fc_player_cards c ON c.id = r.card_id WHERE (? = '' OR r.status = ?) ORDER BY r.created_at DESC LIMIT 200", status == null ? "" : status.trim(), status == null ? "" : status.trim()));
    }

    @GetMapping("/admin/moderation/pending")
    public ApiResponse<List<Map<String, Object>>> pendingModerationCards() {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(jdbcTemplate.queryForList("SELECT id, player_name, position, archetype, photo_url, source_url, source_attribution, source_license, visibility, moderation_status, moderation_reason, owner_user_id, created_at, updated_at FROM fc_player_cards WHERE card_type = 'CUSTOM_PERSONA' AND moderation_status = 'PENDING' ORDER BY updated_at ASC LIMIT 200"));
    }

    @PutMapping("/admin/moderation/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> moderateCard(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        AdminGuard.requireAdmin();
        String status = text(body == null ? null : body.get("status")).toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVED", "HIDDEN", "REVIEWED").contains(status)) throw new IllegalArgumentException("角色卡审核状态无效");
        String note = text(body == null ? null : body.get("note"));
        note = note.substring(0, Math.min(500, note.length()));
        Long operator = UserContext.getUserId();
        Map<String, Object> card;
        try { card = jdbcTemplate.queryForMap("SELECT id, moderation_status FROM fc_player_cards WHERE id = ? AND card_type = 'CUSTOM_PERSONA'", id); }
        catch (Exception error) { throw new NoSuchElementException("角色卡不存在"); }
        String previous = text(card.get("moderation_status"));
        if ("APPROVED".equals(status)) jdbcTemplate.update("UPDATE fc_player_cards SET moderation_status = 'APPROVED', visibility = 'PUBLIC', moderation_reason = NULL, moderated_by = ?, moderated_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?", operator, id);
        else if ("HIDDEN".equals(status)) jdbcTemplate.update("UPDATE fc_player_cards SET moderation_status = 'HIDDEN', visibility = 'PRIVATE', moderation_reason = ?, moderated_by = ?, moderated_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?", note, operator, id);
        else jdbcTemplate.update("UPDATE fc_player_cards SET moderation_status = 'PENDING', moderation_reason = ?, moderated_by = ?, moderated_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?", note, operator, id);
        jdbcTemplate.update("INSERT INTO fc_card_moderation_history (card_id, operator_user_id, from_status, to_status, note) VALUES (?, ?, ?, ?, ?)", id, operator, previous, status, note);
        return ApiResponse.ok(jdbcTemplate.queryForMap("SELECT id, moderation_status AS moderationStatus, visibility, moderation_reason AS moderationReason FROM fc_player_cards WHERE id = ?", id));
    }

    @PostMapping("/admin/catalog/preview")
    public ApiResponse<Map<String, Object>> previewCatalogCard(@RequestBody(required = false) Map<String, Object> body) {
        AdminGuard.requireAdmin();
        String sourceTitle = text(body == null ? null : body.get("sourceTitle"));
        if (sourceTitle.isBlank()) sourceTitle = text(body == null ? null : body.get("name"));
        if (sourceTitle.isBlank()) throw new IllegalArgumentException("请输入 Wikipedia 词条名");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("archetype", text(body == null ? null : body.getOrDefault("archetype", "全能")));
        options.put("position", text(body == null ? null : body.getOrDefault("position", "全能")));
        if (body != null && body.get("seed") != null) options.put("seed", intValue(body.get("seed"), 0));
        Map<String, Object> preview = fetchPersonaPreview(sourceTitle, options);
        if (!Boolean.TRUE.equals(preview.get("selectionRequired"))) enrichAdminPreview(preview);
        return ApiResponse.ok(preview);
    }

    @GetMapping("/admin/catalog")
    public ApiResponse<List<Map<String, Object>>> adminCatalog(@RequestParam(defaultValue = "") String status) {
        AdminGuard.requireAdmin();
        String filter = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM fc_persona_catalog WHERE (? = '' OR status = ?) ORDER BY updated_at DESC LIMIT 500", filter, filter);
        rows.forEach(this::addCatalogRarity);
        return ApiResponse.ok(rows);
    }

    @GetMapping("/admin/catalog/{id}/versions")
    public ApiResponse<List<Map<String, Object>>> adminCatalogVersions(@PathVariable Long id) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(jdbcTemplate.queryForList("SELECT id, catalog_id AS catalogId, version, change_reason AS changeReason, changed_by AS changedBy, created_at AS createdAt, snapshot_json AS snapshotJson FROM fc_persona_catalog_version WHERE catalog_id = ? ORDER BY created_at DESC LIMIT 50", id));
    }

    @GetMapping("/admin/audit")
    public ApiResponse<List<Map<String, Object>>> adminAudit(@RequestParam(defaultValue = "50") int limit) {
        AdminGuard.requireAdmin();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return ApiResponse.ok(jdbcTemplate.queryForList("SELECT id, operator_user_id AS operatorUserId, action, entity_type AS entityType, entity_id AS entityId, detail_json AS detailJson, created_at AS createdAt FROM fc_card_lab_audit_log ORDER BY created_at DESC LIMIT ?", safeLimit));
    }

    @PostMapping("/admin/catalog")
    @Transactional
    public ApiResponse<Map<String, Object>> createCatalogCard(@RequestBody(required = false) Map<String, Object> body) {
        AdminGuard.requireAdmin();
        Map<String, Object> payload = normalizeCatalogPayload(body);
        String personaKey = normalizeKey(text(payload.get("sourceTitle")).isBlank() ? text(payload.get("name")) : text(payload.get("sourceTitle")));
        if (personaKey.isBlank()) throw new IllegalArgumentException("角色唯一标识不能为空");
        Integer duplicate = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_persona_catalog WHERE persona_key = ?", Integer.class, personaKey);
        if (duplicate != null && duplicate > 0) throw new IllegalArgumentException("目录中已存在同名角色，请编辑原角色");
        Long operator = UserContext.getUserId();
        String version = nextCatalogVersion(null);
        jdbcTemplate.update("INSERT INTO fc_persona_catalog (persona_key, name, description, source_title, source_page_id, source_url, source_revision, content_hash, source_language, source_attribution, source_license, photo_url, position, archetype, pace, shooting, passing, dribbling, defending, physical, overall, skills_json, traits_json, tags_json, price_points, status, moderation_status, catalog_version, created_by, updated_by, published_by, published_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", personaKey, payload.get("name"), payload.get("description"), payload.get("sourceTitle"), payload.get("sourcePageId"), payload.get("sourceUrl"), payload.get("sourceRevision"), payload.get("contentHash"), payload.get("sourceLanguage"), payload.get("sourceAttribution"), payload.get("sourceLicense"), payload.get("photoUrl"), payload.get("position"), payload.get("archetype"), payload.get("pace"), payload.get("shooting"), payload.get("passing"), payload.get("dribbling"), payload.get("defending"), payload.get("physical"), payload.get("overall"), payload.get("skillsJson"), payload.get("traitsJson"), payload.get("tagsJson"), payload.get("pricePoints"), payload.get("status"), "PUBLISHED".equals(text(payload.get("status"))) ? "APPROVED" : "DRAFT", version, operator, operator, "PUBLISHED".equals(text(payload.get("status"))) ? operator : null, "PUBLISHED".equals(text(payload.get("status"))) ? new Timestamp(System.currentTimeMillis()) : null);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM fc_persona_catalog WHERE persona_key = ?", Long.class, personaKey);
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM fc_persona_catalog WHERE id = ?", id);
        saveCatalogVersion(id, version, row, operator, "创建目录卡");
        recordAudit("CATALOG_CREATED", "catalog", id, Map.of("version", version, "status", text(payload.get("status"))));
        addCatalogRarity(row);
        return ApiResponse.ok(row);
    }

    @PutMapping("/admin/catalog/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> updateCatalogCard(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        AdminGuard.requireAdmin();
        Map<String, Object> payload = normalizeCatalogPayload(body);
        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_persona_catalog WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0) throw new NoSuchElementException("目录角色不存在");
        String personaKey = normalizeKey(text(payload.get("sourceTitle")).isBlank() ? text(payload.get("name")) : text(payload.get("sourceTitle")));
        Integer duplicate = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_persona_catalog WHERE persona_key = ? AND id <> ?", Integer.class, personaKey, id);
        if (duplicate != null && duplicate > 0) throw new IllegalArgumentException("目录中已存在同名角色，请选择其他条目或编辑原角色");
        String version = nextCatalogVersion(id);
        Long operator = UserContext.getUserId();
        jdbcTemplate.update("UPDATE fc_persona_catalog SET persona_key = ?, name = ?, description = ?, source_title = ?, source_page_id = ?, source_url = ?, source_revision = ?, content_hash = ?, source_language = ?, source_attribution = ?, source_license = ?, photo_url = ?, position = ?, archetype = ?, pace = ?, shooting = ?, passing = ?, dribbling = ?, defending = ?, physical = ?, overall = ?, skills_json = ?, traits_json = ?, tags_json = ?, price_points = ?, status = ?, moderation_status = CASE WHEN ? = 'PUBLISHED' THEN 'APPROVED' ELSE 'DRAFT' END, catalog_version = ?, updated_by = ?, published_by = CASE WHEN ? = 'PUBLISHED' THEN ? ELSE published_by END, published_at = CASE WHEN ? = 'PUBLISHED' AND published_at IS NULL THEN CURRENT_TIMESTAMP WHEN ? <> 'PUBLISHED' THEN NULL ELSE published_at END, updated_at = CURRENT_TIMESTAMP WHERE id = ?", personaKey, payload.get("name"), payload.get("description"), payload.get("sourceTitle"), payload.get("sourcePageId"), payload.get("sourceUrl"), payload.get("sourceRevision"), payload.get("contentHash"), payload.get("sourceLanguage"), payload.get("sourceAttribution"), payload.get("sourceLicense"), payload.get("photoUrl"), payload.get("position"), payload.get("archetype"), payload.get("pace"), payload.get("shooting"), payload.get("passing"), payload.get("dribbling"), payload.get("defending"), payload.get("physical"), payload.get("overall"), payload.get("skillsJson"), payload.get("traitsJson"), payload.get("tagsJson"), payload.get("pricePoints"), payload.get("status"), payload.get("status"), version, operator, payload.get("status"), operator, payload.get("status"), payload.get("status"), id);
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM fc_persona_catalog WHERE id = ?", id);
        saveCatalogVersion(id, version, row, operator, "更新目录卡");
        recordAudit("CATALOG_UPDATED", "catalog", id, Map.of("version", version, "status", text(payload.get("status"))));
        addCatalogRarity(row);
        return ApiResponse.ok(row);
    }

    @PutMapping("/admin/reports/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> resolveReport(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        AdminGuard.requireAdmin();
        String status = text(body == null ? null : body.get("status"));
        if (!Set.of("OPEN", "REVIEWED", "APPROVED", "REJECTED", "ACTIONED", "RESTORED").contains(status)) throw new IllegalArgumentException("审核状态无效");
        String note = text(body == null ? null : body.get("note"));
        Long operator = UserContext.getUserId();
        Map<String, Object> report;
        try { report = jdbcTemplate.queryForMap("SELECT id, card_id, status FROM fc_card_report WHERE id = ?", id); }
        catch (Exception error) { throw new NoSuchElementException("举报记录不存在"); }
        String previous = text(report.get("status"));
        jdbcTemplate.update("UPDATE fc_card_report SET status = ?, resolved_at = CASE WHEN ? = 'OPEN' THEN NULL ELSE CURRENT_TIMESTAMP END, resolved_by = ?, resolution_note = ? WHERE id = ?", status, status, operator, note.substring(0, Math.min(500, note.length())), id);
        Long cardId = number(report.get("card_id"));
        if (Set.of("ACTIONED", "REJECTED").contains(status)) jdbcTemplate.update("UPDATE fc_player_cards SET moderation_status = 'HIDDEN', visibility = 'PRIVATE', moderation_reason = ?, moderated_by = ?, moderated_at = CURRENT_TIMESTAMP WHERE id = ?", note, operator, cardId);
        if (Set.of("APPROVED", "RESTORED").contains(status)) jdbcTemplate.update("UPDATE fc_player_cards SET moderation_status = 'APPROVED', visibility = 'PUBLIC', moderation_reason = NULL, moderated_by = ?, moderated_at = CURRENT_TIMESTAMP WHERE id = ?", operator, cardId);
        jdbcTemplate.update("INSERT INTO fc_card_report_history (report_id, card_id, operator_user_id, from_status, to_status, note) VALUES (?, ?, ?, ?, ?, ?)", id, cardId, operator, previous, status, note.substring(0, Math.min(500, note.length())));
        return ApiResponse.ok(Map.of("id", id, "status", status));
    }

    @GetMapping("/lineups")
    public ApiResponse<List<Map<String, Object>>> lineups() {
        Long userId = requireUser();
        String lineupCardJoin = realPlayersEnabled
                ? ""
                : " AND c.card_type = 'CUSTOM_PERSONA' AND c.rating_origin = '" + CATALOG_CARD_RATING_ORIGIN + "'";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT l.id, l.name, l.formation, l.created_at, l.updated_at, COUNT(c.id) AS slot_count " +
                        "FROM fc_user_lineups l LEFT JOIN fc_user_lineup_slots s ON s.lineup_id = l.id " +
                        "LEFT JOIN fc_player_cards c ON c.id = s.player_card_id" + lineupCardJoin + " " +
                        "WHERE l.user_id = ? GROUP BY l.id ORDER BY l.updated_at DESC", userId);
        return ApiResponse.ok(rows);
    }

    @GetMapping("/synergy-rules")
    public ApiResponse<Map<String, Object>> synergyRules() {
        List<Map<String, Object>> rules = synergyLevels().stream().map(level -> Map.<String, Object>of("prefix", level.prefix(), "threshold", level.threshold(), "bonus", level.bonus(), "description", level.description())).toList();
        return ApiResponse.ok(Map.of("version", SYNERGY_RULE_VERSION, "maxBonus", MAX_SYNERGY_BONUS, "rules", rules));
    }

    @GetMapping("/lineups/{id}")
    public ApiResponse<Map<String, Object>> lineup(@PathVariable Long id) {
        return ApiResponse.ok(readLineup(requireUser(), id));
    }

    @GetMapping("/lineups/{id}/share")
    public ApiResponse<Map<String, Object>> lineupShare(@PathVariable Long id) {
        Long userId = requireUser();
        ensureOwnLineup(userId, id);
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT share_token AS shareToken, visibility, expires_at AS expiresAt, revoked_at AS revokedAt, view_count AS viewCount, last_viewed_at AS lastViewedAt FROM fc_lineup_share WHERE lineup_id = ? AND owner_user_id = ? ORDER BY id DESC LIMIT 1", id, userId);
            if (!text(row.get("revokedAt")).isBlank() || isExpired(row.get("expiresAt"))) return ApiResponse.ok(Map.of("active", false));
            row.put("active", true);
            return ApiResponse.ok(row);
        } catch (Exception ignored) {
            return ApiResponse.ok(Map.of("active", false));
        }
    }

    @PostMapping("/lineups/{id}/share")
    @Transactional
    public ApiResponse<Map<String, Object>> shareLineup(@PathVariable Long id) {
        Long userId = requireUser();
        ensureOwnLineup(userId, id);
        List<Map<String, Object>> existingRows = jdbcTemplate.queryForList("SELECT id, share_token, visibility, expires_at, revoked_at FROM fc_lineup_share WHERE lineup_id = ? AND owner_user_id = ? ORDER BY id DESC LIMIT 1", id, userId);
        if (!existingRows.isEmpty() && text(existingRows.get(0).get("revoked_at")).isBlank() && !text(existingRows.get(0).get("share_token")).isBlank() && !isExpired(existingRows.get(0).get("expires_at"))) return ApiResponse.ok(existingRows.get(0));
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        if (!existingRows.isEmpty()) jdbcTemplate.update("UPDATE fc_lineup_share SET share_token = ?, visibility = 'PUBLIC', expires_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 DAY), revoked_at = NULL WHERE id = ? AND owner_user_id = ?", token, existingRows.get(0).get("id"), userId);
        else jdbcTemplate.update("INSERT INTO fc_lineup_share (lineup_id, owner_user_id, share_token, visibility, expires_at) VALUES (?, ?, ?, 'PUBLIC', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 DAY))", id, userId, token);
        return ApiResponse.ok(jdbcTemplate.queryForMap("SELECT share_token AS shareToken, visibility, expires_at AS expiresAt, view_count AS viewCount, last_viewed_at AS lastViewedAt FROM fc_lineup_share WHERE lineup_id = ? AND owner_user_id = ?", id, userId));
    }

    @DeleteMapping("/lineups/{id}/share")
    @Transactional
    public ApiResponse<Map<String, Object>> revokeLineupShare(@PathVariable Long id) {
        Long userId = requireUser();
        ensureOwnLineup(userId, id);
        jdbcTemplate.update("UPDATE fc_lineup_share SET revoked_at = CURRENT_TIMESTAMP WHERE lineup_id = ? AND owner_user_id = ?", id, userId);
        return ApiResponse.ok(Map.of("revoked", true));
    }

    @GetMapping("/lineups/shared/{token}")
    public ApiResponse<Map<String, Object>> sharedLineup(@PathVariable String token) {
        if (token == null || token.length() < 24 || token.length() > 96) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分享链接无效");
        Map<String, Object> share;
        try { share = jdbcTemplate.queryForMap("SELECT lineup_id, share_token, visibility, expires_at FROM fc_lineup_share WHERE share_token = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)", token); }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分享链接不存在或已失效"); }
        Map<String, Object> result;
        try {
            result = new LinkedHashMap<>(readLineupSnapshot(((Number) share.get("lineup_id")).longValue()));
            jdbcTemplate.update("UPDATE fc_lineup_share SET view_count = view_count + 1, last_viewed_at = CURRENT_TIMESTAMP WHERE share_token = ?", token);
        }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分享阵容不存在"); }
        result.put("shareToken", share.get("share_token"));
        result.put("visibility", share.get("visibility"));
        return ApiResponse.ok(result);
    }

    @PostMapping("/lineups")
    @Transactional
    public ApiResponse<Map<String, Object>> createLineup(@RequestBody(required = false) Map<String, Object> body) {
        Long userId = requireUser();
        LineupPayload payload = parsePayload(body);
        validateSlots(userId, payload);
        Integer lineupCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_user_lineups WHERE user_id = ?", Integer.class, userId);
        if (lineupCount != null && lineupCount >= MAX_LINEUPS_PER_USER) throw new IllegalArgumentException("每个账号最多保存 100 套阵容");
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_user_lineups WHERE user_id = ? AND name = ?", Integer.class, userId, payload.name()) > 0) {
            throw new IllegalArgumentException("已存在同名阵容，请换一个名称");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO fc_user_lineups (user_id, name, formation) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId); ps.setString(2, payload.name()); ps.setString(3, payload.formation()); return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        replaceSlots(id, payload.slots());
        return ApiResponse.ok(readLineup(userId, id));
    }

    @PutMapping("/lineups/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> updateLineup(@PathVariable Long id,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        Long userId = requireUser();
        ensureOwnLineup(userId, id);
        LineupPayload payload = parsePayload(body);
        validateSlots(userId, payload);
        Integer duplicate = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fc_user_lineups WHERE user_id = ? AND name = ? AND id <> ?", Integer.class, userId, payload.name(), id);
        if (duplicate != null && duplicate > 0) throw new IllegalArgumentException("已存在同名阵容，请换一个名称");
        jdbcTemplate.update("UPDATE fc_user_lineups SET name = ?, formation = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?",
                payload.name(), payload.formation(), id, userId);
        replaceSlots(id, payload.slots());
        return ApiResponse.ok(readLineup(userId, id));
    }

    @DeleteMapping("/lineups/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> deleteLineup(@PathVariable Long id) {
        Long userId = requireUser();
        ensureOwnLineup(userId, id);
        jdbcTemplate.update("DELETE FROM fc_lineup_share WHERE lineup_id = ? AND owner_user_id = ?", id, userId);
        jdbcTemplate.update("DELETE FROM fc_user_lineup_slots WHERE lineup_id = ?", id);
        jdbcTemplate.update("DELETE FROM fc_user_lineups WHERE id = ? AND user_id = ?", id, userId);
        return ApiResponse.ok(Map.of("deleted", true, "id", id));
    }

    private Long requireUser() {
        AdminGuard.requireLogin();
        return UserContext.getUserId();
    }

    private void enforcePersonaRateLimit(Long userId, String operation, HttpServletRequest request) {
        String clientIp = request == null ? "" : request.getRemoteAddr();
        if (!rateLimitService.allow(userId, operation, clientIp)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "人物灵感卡请求过于频繁，请稍后再试");
        }
    }

    @Scheduled(initialDelayString = "${card-workshop.sync-initial-delay-ms:15000}",
            fixedDelayString = "${card-workshop.sync-fixed-delay-ms:21600000}")
    void syncCardsFromSquadCache() {
        if (!realPlayersEnabled) return;
        String lockToken = distributedLockService.tryLock("card-workshop:squad-sync", Duration.ofMinutes(15));
        if (lockToken == null) return;
        try {
            long now = System.currentTimeMillis();
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_player_cards", Integer.class);
            if (count != null && count > 0 && now - lastWarmAt.get() < 5 * 60 * 1000L) return;
            lastWarmAt.set(now);
            List<Map<String, Object>> caches = jdbcTemplate.queryForList(
                    "SELECT team_name, league_name, source, squad_json, updated_at FROM t_team_squad_cache WHERE squad_json IS NOT NULL AND squad_json <> '[]' ORDER BY updated_at DESC LIMIT ?",
                    Math.max(1, maxTeamCaches));
            int processed = 0;
            for (Map<String, Object> cache : caches) {
                String json = String.valueOf(cache.getOrDefault("squad_json", "[]"));
                List<Map<String, Object>> players;
                try { players = objectMapper.readValue(json, new TypeReference<>() {}); }
                catch (Exception ex) { log.warn("[CardSync] invalid squad JSON team={} err={}", cache.get("team_name"), ex.getMessage()); continue; }
                for (Map<String, Object> player : players) {
                    upsertCard(player, text(cache.get("team_name")), text(cache.get("league_name")), text(cache.get("source")), cache.get("updated_at"));
                    processed++;
                }
            }
            jdbcTemplate.update("UPDATE fc_player_cards SET source_status = CASE WHEN source_updated_at IS NULL THEN 'UNKNOWN' WHEN source_updated_at < DATE_SUB(NOW(), INTERVAL 7 DAY) THEN 'EXPIRED' WHEN source_updated_at < DATE_SUB(NOW(), INTERVAL 1 DAY) THEN 'STALE' ELSE 'ACTIVE' END WHERE card_type = 'REAL_PLAYER'");
            log.info("[CardSync] caches={} players={} maxTeamCaches={}", caches.size(), processed, maxTeamCaches);
        } finally {
            distributedLockService.unlock("card-workshop:squad-sync", lockToken);
        }
    }

    /** Backfills avatars for persona cards created before photo support was added. */
    @Scheduled(initialDelayString = "${card-workshop.persona-photo-initial-delay-ms:30000}",
            fixedDelayString = "${card-workshop.persona-photo-fixed-delay-ms:21600000}")
    void syncPersonaPhotos() {
        String lockToken = distributedLockService.tryLock("card-workshop:persona-photo-sync", Duration.ofMinutes(10));
        if (lockToken == null) return;
        try {
            List<Map<String, Object>> cards = jdbcTemplate.queryForList(
                    "SELECT c.id, c.player_name, c.player_source_id, c.source_url, cat.source_title FROM fc_player_cards c LEFT JOIN fc_persona_catalog cat ON cat.id = c.catalog_id " +
                            "WHERE c.card_type = 'CUSTOM_PERSONA' AND c.rating_origin IN ('VIRTUAL_PERSONA_RULE_BASED','CATALOG_PERSONA_FIXED') " +
                            "AND (c.photo_url IS NULL OR c.photo_url = '') ORDER BY c.updated_at DESC LIMIT 30");
            for (Map<String, Object> card : cards) {
                String source = text(card.get("player_source_id"));
                String name = source.startsWith("wiki:") ? source.substring(5) : (text(card.get("source_title")).isBlank() ? text(card.get("player_name")) : text(card.get("source_title")));
                Map<String, Object> summary = fetchWikipediaSummary(name);
                String photoUrl = wikipediaThumbnailUrl(summary);
                if (!photoUrl.isBlank()) jdbcTemplate.update("UPDATE fc_player_cards SET photo_url = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", photoUrl, card.get("id"));
            }
        } finally {
            distributedLockService.unlock("card-workshop:persona-photo-sync", lockToken);
        }
    }

    @Scheduled(fixedDelayString = "${card-workshop.wiki-cache-cleanup-ms:3600000}")
    void purgeExpiredPersonaSourceCache() {
        try { jdbcTemplate.update("DELETE FROM fc_persona_source_cache WHERE expires_at <= CURRENT_TIMESTAMP"); }
        catch (Exception error) { log.debug("[CardLab] unable to purge expired Wikipedia cache", error); }
    }

    @Scheduled(fixedDelayString = "${card-workshop.persona-source-stale-ms:21600000}")
    void markStalePersonaSources() {
        try { jdbcTemplate.update("UPDATE fc_player_cards SET source_status = 'STALE' WHERE card_type = 'CUSTOM_PERSONA' AND source_fetched_at IS NOT NULL AND source_fetched_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 30 DAY) AND source_status <> 'STALE'"); }
        catch (Exception error) { log.debug("[CardLab] unable to mark stale persona sources", error); }
    }

    private void upsertCard(Map<String, Object> player, String team, String league, String source, Object sourceUpdatedAt) {
        String name = text(player.get("name"));
        if (name.isBlank()) return;
        String sourceId = text(player.get("id"));
        String nationality = text(player.get("nationality"));
        String provider = normalizeProvider(source);
        String canonicalPlayerId = canonicalPlayerId(player, name, nationality, provider);
        String cardKey = String.join("|", "roster-lab-v3", canonicalPlayerId);
        int seed = Math.floorMod((canonicalPlayerId + "|" + text(player.get("position"))).hashCode(), 7);
        int appearances = intValue(player.get("appearances"), 0);
        int starts = intValue(player.get("starts"), 0);
        int goals = intValue(player.get("goals"), 0);
        int assists = intValue(player.get("assists"), 0);
        int minutesSignal = Math.min(14, starts / 3 + appearances / 8);
        int attackingSignal = Math.min(18, goals * 2 + assists);
        int base = 56 + Math.min(12, minutesSignal) + seed;
        int pace = bounded(base + Math.min(8, appearances / 8));
        int shooting = bounded(base - 3 + attackingSignal);
        int passing = bounded(base + Math.min(10, assists) + 2);
        int dribbling = bounded(base + Math.min(8, starts / 5));
        int defending = bounded(base - 2 + Math.min(10, starts / 6));
        int physical = bounded(base + Math.min(8, appearances / 8));
        String position = text(player.get("position"));
        if (position.toLowerCase(Locale.ROOT).contains("goal") || position.toLowerCase(Locale.ROOT).contains("门")) {
            defending = Math.min(92, defending + 5); physical = Math.min(92, physical + 3);
        } else if (position.toLowerCase(Locale.ROOT).contains("forward") || position.toLowerCase(Locale.ROOT).contains("striker") || position.toLowerCase(Locale.ROOT).contains("前") || position.toLowerCase(Locale.ROOT).contains("锋")) {
            shooting = Math.min(94, shooting + 6); pace = Math.min(94, pace + 3);
        } else if (position.toLowerCase(Locale.ROOT).contains("defend") || position.toLowerCase(Locale.ROOT).contains("后")) {
            defending = Math.min(94, defending + 8); physical = Math.min(94, physical + 3);
        } else {
            passing = Math.min(94, passing + 4); dribbling = Math.min(94, dribbling + 3);
        }
        int overall = Math.round((pace + shooting + passing + dribbling + defending + physical) / 6f);
        String ratingOrigin = appearances > 0 || goals > 0 || assists > 0 ? "ROSTER_STATS_RULE_V3" : "ROSTER_RULE_V3";
        String ratingBasis = ratingBasis(position, appearances, starts, goals, assists);
        Timestamp sourceTimestamp = parseTimestamp(sourceUpdatedAt);
        String sourceStatus = sourceTimestamp == null ? "UNKNOWN" : ("FRESH".equals(freshnessStatus(sourceTimestamp.toString())) ? "ACTIVE" : freshnessStatus(sourceTimestamp.toString()));
        // Resolve identity in descending confidence order.  The source id is
        // preferred even when a provider changes; otherwise a second provider
        // could create a visually identical duplicate card.  Name + nationality
        // is the final fallback for feeds that do not expose stable ids.
        List<Long> existing = jdbcTemplate.query("SELECT id FROM fc_player_cards WHERE card_type = 'REAL_PLAYER' AND (canonical_player_id = ? OR (? <> '' AND player_source_id = ?) OR (player_name = ? AND nationality = ?)) ORDER BY CASE WHEN canonical_player_id = ? THEN 0 WHEN player_source_id = ? THEN 1 ELSE 2 END, id LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"), canonicalPlayerId, sourceId, sourceId, name, nationality, canonicalPlayerId, sourceId);
        if (!existing.isEmpty()) {
            Long cardId = existing.get(0);
            recordRatingHistoryIfChanged(cardId, ratingVersion(), ratingBasis, sourceTimestamp, pace, shooting, passing, dribbling, defending, physical, overall);
            jdbcTemplate.update("UPDATE fc_player_cards SET card_key = ?, canonical_player_id = ?, player_source_id = ?, player_name = ?, position = ?, number = ?, age = ?, nationality = ?, photo_url = ?, team_name = ?, league_name = ?, source = ?, source_status = ?, rating_origin = ?, rating_version = ?, rating_basis_json = ?, pace = ?, shooting = ?, passing = ?, dribbling = ?, defending = ?, physical = ?, overall = ?, source_updated_at = ?, last_seen_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    cardKey, canonicalPlayerId, sourceId, name, position, text(player.get("number")), text(player.get("age")), nationality, text(player.get("photo")), team, league, provider, sourceStatus, ratingOrigin, ratingVersion(), ratingBasis, pace, shooting, passing, dribbling, defending, physical, overall, sourceTimestamp, cardId);
            return;
        }
        jdbcTemplate.update("INSERT INTO fc_player_cards (card_key, canonical_player_id, player_source_id, player_name, position, number, age, nationality, photo_url, team_name, league_name, source, source_status, card_type, rating_origin, rating_version, rating_basis_json, source_updated_at, last_seen_at, pace, shooting, passing, dribbling, defending, physical, overall) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REAL_PLAYER', ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE canonical_player_id=VALUES(canonical_player_id), player_name=VALUES(player_name), position=VALUES(position), number=VALUES(number), age=VALUES(age), nationality=VALUES(nationality), photo_url=VALUES(photo_url), team_name=VALUES(team_name), league_name=VALUES(league_name), source=VALUES(source), source_status=VALUES(source_status), rating_origin=VALUES(rating_origin), rating_version=VALUES(rating_version), rating_basis_json=VALUES(rating_basis_json), source_updated_at=VALUES(source_updated_at), last_seen_at=CURRENT_TIMESTAMP, pace=VALUES(pace), shooting=VALUES(shooting), passing=VALUES(passing), dribbling=VALUES(dribbling), defending=VALUES(defending), physical=VALUES(physical), overall=VALUES(overall), updated_at=CURRENT_TIMESTAMP",
                cardKey, canonicalPlayerId, sourceId, name, position, text(player.get("number")), text(player.get("age")), nationality, text(player.get("photo")), team, league, provider, sourceStatus, ratingOrigin, ratingVersion(), ratingBasis, sourceTimestamp, pace, shooting, passing, dribbling, defending, physical, overall);
        Long cardId = jdbcTemplate.queryForObject("SELECT id FROM fc_player_cards WHERE card_key = ?", Long.class, cardKey);
        if (cardId != null) recordRatingHistoryIfChanged(cardId, ratingVersion(), ratingBasis, sourceTimestamp, pace, shooting, passing, dribbling, defending, physical, overall);
    }

    private Map<String, Object> readLineup(Long userId, Long id) {
        ensureOwnLineup(userId, id);
        Map<String, Object> lineup = jdbcTemplate.queryForMap("SELECT id, name, formation, created_at, updated_at FROM fc_user_lineups WHERE id = ? AND user_id = ?", id, userId);
        String cardFilter = realPlayersEnabled
                ? ""
                : " AND c.card_type = 'CUSTOM_PERSONA' AND c.rating_origin = '" + CATALOG_CARD_RATING_ORIGIN + "'";
        List<Map<String, Object>> slots = jdbcTemplate.queryForList("SELECT s.slot_index, s.position AS slot_position, c.id, c.id AS player_card_id, c.player_name, c.position AS card_position, c.photo_url, c.overall, c.pace, c.shooting, c.passing, c.dribbling, c.defending, c.physical, c.archetype, c.skills_json, c.traits_json, c.tags_json, c.source_attribution, c.source_license, c.catalog_id, c.catalog_version, c.card_type, c.canonical_player_id, c.player_source_id FROM fc_user_lineup_slots s JOIN fc_player_cards c ON c.id = s.player_card_id WHERE s.lineup_id = ?" + cardFilter + " ORDER BY s.slot_index", id);
        slots.forEach(slot -> slot.put("position", slot.get("slot_position")));
        Map<String, Object> result = new LinkedHashMap<>(lineup); result.put("slots", slots);
        result.put("rating", lineupRating(slots));
        return result;
    }

    private Map<String, Object> lineupRating(List<Map<String, Object>> slots) {
        List<Map<String, Object>> cards = slots == null ? List.of() : slots;
        int filled = cards.size();
        int total = cards.stream().mapToInt(card -> intValue(card.get("overall"), 0)).sum();
        float positionFitPoints = 0f;
        for (Map<String, Object> card : cards) {
            String slotPosition = text(card.get("slot_position"));
            String cardPosition = text(card.get("card_position"));
            positionFitPoints += positionFitWeight(slotPosition, cardPosition, "CUSTOM_PERSONA".equals(text(card.get("card_type"))));
        }
        int positionFit = filled == 0 ? 0 : Math.round(positionFitPoints * 100f / 11f);
        int baseScore = filled == 0 ? 0 : Math.round((total / 11f) * (0.7f + positionFit / 100f * 0.3f));
        List<Map<String, Object>> active = activeSynergies(cards);
        int synergyBonus = Math.min(MAX_SYNERGY_BONUS, active.stream().mapToInt(item -> intValue(item.get("bonus"), 0)).sum());
        int finalScore = Math.min(99, baseScore + synergyBonus);
        int chemistry = Math.min(100, active.size() * 14 + synergyBonus * 8 + (filled == 11 ? 10 : 0));
        Map<String, Object> rating = new LinkedHashMap<>();
        rating.put("filledCount", filled); rating.put("positionFit", positionFit); rating.put("baseScore", baseScore);
        rating.put("synergyBonus", synergyBonus); rating.put("finalScore", finalScore); rating.put("chemistryScore", chemistry);
        rating.put("activeSynergies", active); rating.put("nextSynergies", nextSynergies(cards));
        rating.put("version", SYNERGY_RULE_VERSION);
        return rating;
    }

    private float positionFitWeight(String slot, String cardPosition, boolean customPersona) {
        if (slot == null || slot.isBlank() || cardPosition == null || cardPosition.isBlank() || "全能".equals(cardPosition)) return 1f;
        if (positionMatches(slot, cardPosition, customPersona)) {
            if (!customPersona) return 1f;
            String normalizedSlot = slot.replace("左", "").replace("右", "");
            String normalizedCard = cardPosition.replace("左", "").replace("右", "");
            return normalizedSlot.equals(normalizedCard) ? 1f : 0.84f;
        }
        return customPersona ? 0.38f : 0f;
    }

    private List<Map<String, Object>> activeSynergies(List<Map<String, Object>> cards) {
        Map<String, Integer> counts = synergyTagCounts(cards);
        List<Map<String, Object>> active = new ArrayList<>();
        counts.forEach((tag, count) -> {
            String prefix = tagPrefix(tag);
            List<SynergyLevel> rules = synergyLevels();
            SynergyLevel level = rules.stream().filter(item -> item.prefix().equals(prefix) && item.threshold() <= count)
                    .max(Comparator.comparingInt(SynergyLevel::threshold)).orElse(null);
            if (level == null) return;
            String label = tag.substring(Math.min(tag.length(), prefix.length() + 1));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", tag); item.put("prefix", prefix); item.put("label", label); item.put("count", count);
            item.put("threshold", level.threshold()); item.put("bonus", level.bonus()); item.put("description", level.description());
            rules.stream().filter(next -> next.prefix().equals(prefix) && next.threshold() > count).min(Comparator.comparingInt(SynergyLevel::threshold))
                    .ifPresent(next -> { item.put("nextThreshold", next.threshold()); item.put("need", next.threshold() - count); item.put("nextBonus", next.bonus()); });
            active.add(item);
        });
        active.sort(Comparator.comparingInt((Map<String, Object> item) -> intValue(item.get("bonus"), 0)).reversed().thenComparing(item -> text(item.get("label"))));
        return active;
    }

    private List<Map<String, Object>> nextSynergies(List<Map<String, Object>> cards) {
        Map<String, Integer> counts = synergyTagCounts(cards);
        List<Map<String, Object>> next = new ArrayList<>();
        counts.forEach((tag, count) -> {
            String prefix = tagPrefix(tag);
            synergyLevels().stream().filter(level -> level.prefix().equals(prefix) && level.threshold() > count)
                    .min(Comparator.comparingInt(SynergyLevel::threshold)).ifPresent(level -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("code", tag); item.put("prefix", prefix); item.put("label", tag.substring(Math.min(tag.length(), prefix.length() + 1)));
                        item.put("count", count); item.put("threshold", level.threshold()); item.put("need", level.threshold() - count); item.put("bonus", level.bonus());
                        next.add(item);
                    });
        });
        next.sort(Comparator.comparingInt((Map<String, Object> item) -> intValue(item.get("need"), 99)).thenComparing(item -> text(item.get("label"))));
        return next.stream().limit(6).toList();
    }

    private Map<String, Integer> synergyTagCounts(List<Map<String, Object>> cards) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (cards == null) return counts;
        for (Map<String, Object> card : cards) {
            Set<String> unique = new HashSet<>(personaTags(card));
            unique.stream().filter(tag -> synergyLevels().stream().anyMatch(level -> level.prefix().equals(tagPrefix(tag))))
                    .forEach(tag -> counts.merge(tag, 1, Integer::sum));
        }
        return counts;
    }

    private String tagPrefix(String tag) {
        String value = text(tag); int separator = value.indexOf(':');
        return separator > 0 ? value.substring(0, separator) : "";
    }

    private List<String> personaTags(Map<String, Object> card) {
        if (card == null) return List.of();
        Object raw = card.get("tags_json");
        if (raw instanceof List<?> values) return values.stream().map(CardWorkshopController::text).filter(value -> !value.isBlank()).map(this::canonicalTag).distinct().toList();
        if (raw != null && !text(raw).isBlank()) {
            try { return objectMapper.readValue(text(raw), new TypeReference<List<String>>() {}).stream().map(this::canonicalTag).distinct().toList(); }
            catch (Exception ignored) { return List.of(); }
        }
        return List.of();
    }

    private String canonicalPlayerId(Map<String, Object> player, String name, String nationality, String provider) {
        String sourceId = text(player.get("id"));
        if (!sourceId.isBlank()) return "source:" + provider + ":" + sourceId.toLowerCase(Locale.ROOT);
        String normalizedName = Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
        String normalizedNationality = Normalizer.normalize(nationality, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
        return "name:" + provider + ":" + normalizedName + (normalizedNationality.isBlank() ? "" : "|" + normalizedNationality);
    }

    private boolean positionMatches(String slot, String cardPosition, boolean customPersona) {
        if (cardPosition == null || cardPosition.isBlank() || "全能".equals(cardPosition)) return true;
        String value = cardPosition.toLowerCase(Locale.ROOT);
        String role = value.contains("goal") || value.contains("gk") || value.contains("门") ? "goalkeeper"
                : value.contains("def") || value.contains("back") || value.contains("后") || value.contains("守") ? "defender"
                : value.contains("forward") || value.contains("strik") || value.contains("wing") || value.contains("前") || value.contains("锋") ? "forward"
                : value.contains("mid") || value.contains("中") || value.contains("half") ? "midfielder" : "other";
        if ("other".equals(role)) return false;
        if (slot.contains("门将")) return "goalkeeper".equals(role);
        if (slot.contains("后腰")) return "midfielder".equals(role);
        if (slot.contains("后卫") || slot.contains("翼卫")) return "defender".equals(role);
        if (slot.contains("边前卫") || slot.contains("边锋")) return "midfielder".equals(role) || "forward".equals(role);
        if (slot.contains("锋") || slot.contains("前锋")) return "forward".equals(role);
        return "midfielder".equals(role);
    }

    private String normalizeProvider(String source) {
        String value = source == null || source.isBlank() ? CARD_SOURCE : source;
        return normalizeKey(value);
    }

    private String ratingVersion() {
        return "roster-lab-v3";
    }

    private String ratingBasis(String position, int appearances, int starts, int goals, int assists) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "method", "activity-production-role-v3",
                    "position", position == null ? "" : position,
                    "appearances", appearances,
                    "starts", starts,
                    "goals", goals,
                    "assists", assists,
                    "note", "规则估值，不代表官方评分；缺少高级统计时不会伪装成模型预测"));
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private void recordRatingHistoryIfChanged(Long cardId, String version, String basis, Timestamp sourceUpdatedAt,
                                              int pace, int shooting, int passing, int dribbling, int defending, int physical, int overall) {
        if (cardId == null) return;
        Map<String, Object> previous;
        try {
            previous = jdbcTemplate.queryForMap("SELECT pace, shooting, passing, dribbling, defending, physical, overall FROM fc_player_cards WHERE id = ?", cardId);
        } catch (Exception ignored) {
            return;
        }
        boolean changed = intValue(previous.get("pace"), -1) != pace || intValue(previous.get("shooting"), -1) != shooting
                || intValue(previous.get("passing"), -1) != passing || intValue(previous.get("dribbling"), -1) != dribbling
                || intValue(previous.get("defending"), -1) != defending || intValue(previous.get("physical"), -1) != physical
                || intValue(previous.get("overall"), -1) != overall;
        Integer historyCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_player_card_rating_history WHERE card_id = ?", Integer.class, cardId);
        if (!changed && historyCount != null && historyCount > 0) return;
        jdbcTemplate.update("INSERT INTO fc_player_card_rating_history (card_id, rating_version, pace, shooting, passing, dribbling, defending, physical, overall, basis_json, source_updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                cardId, version, pace, shooting, passing, dribbling, defending, physical, overall, basis, sourceUpdatedAt);
    }

    private Timestamp parseTimestamp(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp;
        if (value instanceof java.util.Date date) return new Timestamp(date.getTime());
        String valueText = String.valueOf(value).trim();
        if (valueText.isBlank()) return null;
        try { return Timestamp.valueOf(valueText.replace('T', ' ').replaceFirst("Z$", "")); }
        catch (Exception ignored) { return null; }
    }

    private String freshnessStatus(String value) {
        Timestamp timestamp = parseTimestamp(value);
        if (timestamp == null) return "UNKNOWN";
        long age = Math.max(0L, System.currentTimeMillis() - timestamp.getTime());
        if (age <= Duration.ofHours(24).toMillis()) return "FRESH";
        if (age <= Duration.ofDays(7).toMillis()) return "STALE";
        return "EXPIRED";
    }

    private void replaceSlots(long lineupId, List<Map<String, Object>> slots) {
        jdbcTemplate.update("DELETE FROM fc_user_lineup_slots WHERE lineup_id = ?", lineupId);
        for (Map<String, Object> slot : slots) {
            Long cardId = number(slot.get("cardId"));
            jdbcTemplate.update("INSERT INTO fc_user_lineup_slots (lineup_id, slot_index, position, player_card_id) VALUES (?, ?, ?, ?)",
                    lineupId, Math.max(0, Math.min(10, intValue(slot.get("slotIndex"), 0))), text(slot.get("position")), cardId);
        }
    }

    private void ensureOwnLineup(Long userId, Long id) {
        if (id == null || jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_user_lineups WHERE id = ? AND user_id = ?", Integer.class, id, userId) == 0) {
            throw new NoSuchElementException("阵容不存在或无权访问");
        }
    }

    private LineupPayload parsePayload(Map<String, Object> body) {
        String name = text(body == null ? null : body.get("name"));
        String formation = text(body == null ? null : body.get("formation"));
        if (name.isBlank()) name = "我的梦之队";
        if (name.length() > 64) throw new IllegalArgumentException("阵容名称不能超过 64 个字符");
        if (formation.isBlank()) formation = "4-3-3";
        if (!ALLOWED_FORMATIONS.contains(formation)) throw new IllegalArgumentException("不支持的阵型");
        List<Map<String, Object>> slots = new ArrayList<>();
        Object raw = body == null ? null : body.get("slots");
        if (raw instanceof List<?> list) for (Object value : list) if (value instanceof Map<?, ?> map) {
            Map<String, Object> slot = new LinkedHashMap<>(); map.forEach((k, v) -> slot.put(String.valueOf(k), v)); slots.add(slot);
        }
        return new LineupPayload(name, formation, slots);
    }

    private void validateSlots(Long userId, LineupPayload payload) {
        List<Map<String, Object>> slots = payload.slots();
        if (slots.size() > MAX_SLOTS) throw new IllegalArgumentException("首发最多 11 人");
        Set<Integer> indexes = new HashSet<>(); Set<Long> cards = new HashSet<>();
        List<String> expectedPositions = FORMATION_POSITIONS.get(payload.formation());
        for (Map<String, Object> slot : slots) {
            int index = intValue(slot.get("slotIndex"), -1); Long cardId = number(slot.get("cardId"));
            if (index < 0 || index >= MAX_SLOTS || !indexes.add(index)) throw new IllegalArgumentException("阵容位置无效或重复");
            if (cardId == null || !cards.add(cardId) || jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_player_cards WHERE id = ? AND ((visibility = 'PUBLIC' AND owner_user_id IS NULL) OR owner_user_id = ?)", Integer.class, cardId, userId) == 0) {
                throw new IllegalArgumentException("球员卡不存在或重复加入");
            }
            String position = text(slot.get("position"));
            if (position.isBlank() || position.length() > 32) throw new IllegalArgumentException("阵容位置无效");
            if (expectedPositions == null || !expectedPositions.get(index).equals(position)) {
                throw new IllegalArgumentException("阵容位置与所选阵型不匹配");
            }
            Map<String, Object> card = jdbcTemplate.queryForMap("SELECT position, card_type FROM fc_player_cards WHERE id = ?", cardId);
            if (!realPlayersEnabled && !"CUSTOM_PERSONA".equals(text(card.get("card_type")))) {
                throw new IllegalArgumentException("真实球员卡暂未开放，请使用虚拟角色卡");
            }
            if (!"CUSTOM_PERSONA".equals(text(card.get("card_type"))) && !positionMatches(position, text(card.get("position")), false)) {
                throw new IllegalArgumentException("球员位置与阵容位置不匹配");
            }
        }
    }

    private Map<String, Object> fetchPersonaPreview(String rawName) {
        return fetchPersonaPreview(rawName, Map.of());
    }

    private Map<String, Object> fetchPersonaPreview(String rawName, Map<String, Object> options) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.length() < 2 || name.length() > 80) throw new IllegalArgumentException("请输入 2～80 个字符的人物名称");
        if (personaPolicy.isBlockedInput(name)) {
            throw new IllegalArgumentException("该人物类型暂不支持生成娱乐卡");
        }
        Map<String, Object> result = fetchWikipediaSummary(name);
        if (result.isEmpty()) {
            List<Map<String, Object>> candidates = searchPersonaCandidates(name);
            if (!candidates.isEmpty()) return candidatesOrSelection(name, candidates, options);
            throw new IllegalArgumentException("Wikipedia 没有找到该人物，请换一个更准确的名称");
        }
        if ("disambiguation".equalsIgnoreCase(text(result.get("type")))) {
            List<Map<String, Object>> candidates = searchPersonaCandidates(name);
            if (!candidates.isEmpty()) return candidatesOrSelection(name, candidates, options);
            throw new IllegalArgumentException("该名称对应多个百科条目，但没有找到可用的虚拟角色，请输入更具体的名称");
        }
        // A resolved non-disambiguation page that fails the persona policy is
        // a work/list/place/etc. Do not search inside that work and silently
        // turn its title into an unrelated character suggestion.
        if (!personaPolicy.accepts(text(result.get("title")), text(result.get("description")),
                text(result.get("extract")), text(result.get("type")))) {
            // Wikipedia's first fuzzy hit is often the work itself (for
            // example “银魂” when the administrator types “银时”). Search the
            // full result set once more so a partial character name can still
            // resolve to the concrete virtual character entry.
            List<Map<String, Object>> candidates = searchPersonaCandidates(name);
            if (!candidates.isEmpty()) return candidatesOrSelection(name, candidates, options);
            throw new IllegalArgumentException("这是作品或其他非人物条目，请输入具体的虚拟角色名称，例如“漩涡鸣人”");
        }
        if (!name.contains("(") && !name.contains("（")) {
            // The summary endpoint resolves a fuzzy name to only one page and
            // that page is frequently the wrong entity (for example “神乐”
            // resolves to the VTuber 神乐七奈 while the Gintama character is
            // a lower-ranked search hit). Always inspect the candidate set for
            // short/ambiguous names before accepting that fuzzy page.
            List<Map<String, Object>> candidates = searchPersonaCandidates(name);
            if (candidates.size() > 1) return personaSelection(name, candidates);
            if (candidates.size() == 1) {
                String candidateTitle = text(candidates.get(0).get("sourceTitle"));
                String resolvedTitle = text(result.get("title"));
                if (!normalizeKey(candidateTitle).equals(normalizeKey(resolvedTitle))) {
                    return candidatesOrSelection(name, candidates, options);
                }
            }
        }
        return buildPersonaPreview(result, options);
    }

    private Map<String, Object> candidatesOrSelection(String name, List<Map<String, Object>> candidates, Map<String, Object> options) {
        if (candidates.size() == 1) {
            String sourceTitle = text(candidates.get(0).get("sourceTitle"));
            if (!sourceTitle.contains("(") && !sourceTitle.contains("（")) {
                return buildPersonaPreview(fetchWikipediaSummary(sourceTitle), options);
            }
        }
        return personaSelection(name, candidates);
    }

    private Map<String, Object> fetchWikipediaSummary(String name) {
        String normalized = normalizeKey(name);
        String cacheKey = "summary:" + normalized;
        WikiCacheEntry cached = wikiCache.get(cacheKey);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis() && !cached.payload().isEmpty()) return cached.payload();
        Map<String, Object> persistentZh = readPersistentWikiCache(normalized, "zh");
        if (!persistentZh.isEmpty()) {
            wikiCache.put(cacheKey, new WikiCacheEntry(persistentZh, System.currentTimeMillis() + Math.max(60L, wikiCacheTtlSeconds) * 1000L));
            return persistentZh;
        }
        Map<String, Object> persistentEn = readPersistentWikiCache(normalized, "en");
        if (!persistentEn.isEmpty()) {
            wikiCache.put(cacheKey, new WikiCacheEntry(persistentEn, System.currentTimeMillis() + Math.max(60L, wikiCacheTtlSeconds) * 1000L));
            return persistentEn;
        }
        if (cached != null) wikiCache.remove(cacheKey, cached);
        if (wikiCircuitOpenUntil.get() > System.currentTimeMillis()) return Map.of();
        String encoded = URLEncoder.encode(name.replaceAll("\\s+", "_"), StandardCharsets.UTF_8).replace("+", "%20");
        Map<String, Object> result = requestWikipedia("https://zh.wikipedia.org/api/rest_v1/page/summary/" + encoded);
        if (result.isEmpty()) {
            Map<String, Object> search = requestWikipedia("https://zh.wikipedia.org/w/api.php?action=query&list=search&srsearch=" + encoded + "&srlimit=1&format=json&utf8=1", true);
            String zhTitle = firstSearchTitle(search);
            if (!zhTitle.isBlank()) result = requestWikipedia("https://zh.wikipedia.org/api/rest_v1/page/summary/" + encodedTitle(zhTitle), true);
        }
        // A regional endpoint can be unavailable while the English endpoint is
        // healthy.  Do not let the first circuit-breaker event suppress the
        // fallback attempt for the same user request.
        if (result.isEmpty()) result = requestWikipedia("https://en.wikipedia.org/api/rest_v1/page/summary/" + encoded, true);
        long ttl = result.isEmpty() ? 60L : Math.max(60L, wikiCacheTtlSeconds);
        wikiCache.put(cacheKey, new WikiCacheEntry(result, System.currentTimeMillis() + ttl * 1000L));
        if (!result.isEmpty()) writePersistentWikiCache(normalized, result);
        return result;
    }

    /**
     * Admin-only enrichment: use the full plain-text Wiki extract for the
     * initial attributes. The public/user path remains summary-only, while
     * this slower request is acceptable because only administrators can call
     * it and they explicitly asked for content-driven values.
     */
    private void enrichAdminPreview(Map<String, Object> preview) {
        String sourceTitle = text(preview.get("sourceTitle"));
        if (sourceTitle.isBlank()) return;
        String articleText = fetchWikipediaArticleText(sourceTitle);
        if (articleText.isBlank()) return;
        String evidence = (text(preview.get("name")) + "\n" + text(preview.get("summary")) + "\n" + articleText).trim();
        List<String> tags = personaTagsFor(text(preview.get("sourceTitle")), evidence);
        preview.put("tags", tags);
        preview.put("tagsJson", jsonText(tags));
        Map<String, Object> locked = Map.of();
        int seed = intValue(preview.get("generationSeed"), Math.floorMod(evidence.hashCode(), 100000));
        String archetype = text(preview.getOrDefault("archetype", "全能"));
        String position = text(preview.getOrDefault("position", "全能"));
        List<Integer> stats = statsFor(evidence, position, archetype, seed, locked);
        preview.put("stats", Map.of("pace", stats.get(0), "shooting", stats.get(1), "passing", stats.get(2), "dribbling", stats.get(3), "defending", stats.get(4), "physical", stats.get(5), "overall", stats.get(6)));
        preview.put("attributeExplanation", explainStats(stats, archetype, position) + " 管理员模式额外分析了 Wikipedia 正文摘要（" + articleText.length() + " 字）。");
        preview.put("contentHash", contentHash(text(preview.get("sourceTitle")) + "|" + evidence + "|" + text(preview.get("sourceLanguage")) + "|" + PERSONA_POLICY_VERSION));
        preview.put("articleTextLength", articleText.length());
    }

    @SuppressWarnings("unchecked")
    private String fetchWikipediaArticleText(String title) {
        String encoded = encodedTitle(title);
        for (String language : List.of("zh", "en")) {
            String host = "zh".equals(language) ? "zh.wikipedia.org" : "en.wikipedia.org";
            Map<String, Object> response = requestWikipedia("https://" + host + "/w/api.php?action=query&prop=extracts&explaintext=1&exlimit=1&titles=" + encoded + "&format=json&utf8=1", true);
            Object rawQuery = response.get("query");
            if (!(rawQuery instanceof Map<?, ?> query) || !(query.get("pages") instanceof Map<?, ?> pages)) continue;
            for (Object value : pages.values()) {
                if (value instanceof Map<?, ?> page) {
                    String extract = text(page.get("extract"));
                    if (!extract.isBlank()) return extract.substring(0, Math.min(12000, extract.length()));
                }
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private String firstSearchTitle(Map<String, Object> search) {
        Object rawQuery = search.get("query");
        if (!(rawQuery instanceof Map<?, ?> query) || !(query.get("search") instanceof List<?> results) || results.isEmpty()) return "";
        Object first = results.get(0);
        return first instanceof Map<?, ?> item ? text(item.get("title")) : "";
    }

    private Map<String, Object> readPersistentWikiCache(String cacheKey, String language) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT payload_json, expires_at FROM fc_persona_source_cache WHERE cache_key = ? AND source_language = ? AND expires_at > CURRENT_TIMESTAMP", cacheKey, language);
            String payload = text(row.get("payload_json"));
            if (payload.isBlank()) return Map.of();
            return objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) { return Map.of(); }
    }

    private void writePersistentWikiCache(String cacheKey, Map<String, Object> payload) {
        try {
            String sourceUrl = wikipediaSourceUrl(payload, encodedTitle(text(payload.get("title"))));
            String language = sourceUrl.contains("en.wikipedia.org") ? "en" : "zh";
            String json = objectMapper.writeValueAsString(payload);
            Timestamp expiresAt = new Timestamp(System.currentTimeMillis() + Math.max(60L, wikiCacheTtlSeconds) * 1000L);
            jdbcTemplate.update("INSERT INTO fc_persona_source_cache (cache_key, source_language, payload_json, source_url, source_revision, content_hash, fetched_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?) ON DUPLICATE KEY UPDATE payload_json = VALUES(payload_json), source_url = VALUES(source_url), source_revision = VALUES(source_revision), content_hash = VALUES(content_hash), fetched_at = CURRENT_TIMESTAMP, expires_at = VALUES(expires_at)", cacheKey, language, json, sourceUrl, text(payload.getOrDefault("timestamp", payload.get("revision"))), contentHash(json), expiresAt);
        } catch (Exception error) { log.debug("[CardLab] unable to persist wiki cache", error); }
    }

    private Map<String, Object> personaSelection(String name, List<Map<String, Object>> candidates) {
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("selectionRequired", true);
        selection.put("query", name);
        selection.put("candidates", candidates);
        selection.put("notice", "找到多个可能的虚拟角色，请选择具体作品或版本后继续");
        return selection;
    }

    private Map<String, Object> buildPersonaPreview(Map<String, Object> result) {
        return buildPersonaPreview(result, Map.of());
    }

    private Map<String, Object> buildPersonaPreview(Map<String, Object> result, Map<String, Object> options) {
        String sourceTitle = text(result.get("title"));
        String extract = text(result.get("extract"));
        String description = text(result.get("description"));
        if (sourceTitle.isBlank() || extract.isBlank()) throw new IllegalArgumentException("该条目缺少可核验的公开摘要");
        if (!personaPolicy.accepts(sourceTitle, description, extract, text(result.get("type")))) {
            throw new IllegalArgumentException("人物灵感卡仅支持 ACGN、Meme 等明确的虚拟角色，不支持真人或普通百科条目");
        }
        String displayName = simpleChinese(sourceTitle);
        String simpleExtract = simpleChinese(extract);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("name", displayName);
        preview.put("sourceTitle", sourceTitle);
        preview.put("summary", simpleExtract.substring(0, Math.min(900, simpleExtract.length())));
        preview.put("description", simpleChinese(description));
        List<String> tags = personaTagsFor(sourceTitle, sourceTitle + "\n" + description + "\n" + simpleExtract);
        preview.put("tags", tags);
        preview.put("tagsJson", jsonText(tags));
        String sourceUrl = wikipediaSourceUrl(result, encodedTitle(sourceTitle));
        String sourceLanguage = sourceUrl.contains("en.wikipedia.org") ? "en" : "zh";
        preview.put("source", "Wikipedia");
        preview.put("sourceUrl", sourceUrl);
        preview.put("photoUrl", wikipediaThumbnailUrl(result));
        preview.put("sourceLanguage", sourceLanguage);
        preview.put("sourceLicense", WIKIPEDIA_LICENSE);
        preview.put("sourceAttribution", "Wikipedia：" + sourceTitle + "（CC BY-SA 4.0）");
        preview.put("sourcePageId", result.getOrDefault("pageid", result.getOrDefault("pageId", "")));
        preview.put("policyVersion", PERSONA_POLICY_VERSION);
        preview.put("cardType", "CUSTOM_PERSONA");
        preview.put("entityType", "VIRTUAL_PERSONA");
        preview.put("visibility", "PRIVATE");
        preview.put("notice", "这是基于公开摘要生成的虚拟角色娱乐卡，不代表现实能力，也不适用于真人评价");
        String archetype = "全能";
        String requestedPosition = text(options.getOrDefault("position", "全能"));
        String position = allowedPosition(requestedPosition);
        if (position.isBlank() || "全能".equals(position)) position = inferPersonaPosition(displayName + " " + simpleExtract);
        int seed = intValue(options.get("seed"), Math.floorMod((displayName + sourceTitle).hashCode(), 100000));
        Map<String, Object> locked = options.get("lockedStats") instanceof Map<?, ?> map ? toStringMap(map) : Map.of();
        List<Integer> stats = statsFor(displayName + simpleExtract, position, archetype, seed, locked);
        preview.put("sourceRevision", text(result.getOrDefault("timestamp", result.getOrDefault("revision", ""))));
        Map<String, Object> optionSnapshot = new LinkedHashMap<>();
        optionSnapshot.put("archetype", archetype); optionSnapshot.put("position", position); optionSnapshot.put("seed", seed); optionSnapshot.put("lockedStats", locked);
        preview.put("optionsHash", contentHash(jsonText(optionSnapshot)));
        preview.put("contentHash", contentHash(sourceTitle + "|" + simpleExtract + "|" + sourceLanguage + "|" + PERSONA_POLICY_VERSION + "|" + jsonText(optionSnapshot)));
        preview.put("generationSeed", seed);
        preview.put("archetype", archetype);
        preview.put("position", position);
        preview.put("positionOrigin", "WIKI_CONTENT_INFERENCE");
        preview.put("moderationStatus", "PENDING");
        preview.put("matchConfidence", 0.92);
        preview.put("attributeExplanation", explainStats(stats, archetype, position));
        preview.put("stats", Map.of("pace", stats.get(0), "shooting", stats.get(1), "passing", stats.get(2), "dribbling", stats.get(3), "defending", stats.get(4), "physical", stats.get(5), "overall", stats.get(6)));
        preview.put("skills", personaSkills(archetype, position));
        preview.put("traits", personaTraits(archetype, position));
        preview.put("gameplayNotice", "娱乐属性仅用于幻想阵容，不适用于竞技排名或现实人物评价");
        return preview;
    }

    private List<Map<String, Object>> searchPersonaCandidates(String rawName) {
        String encoded = URLEncoder.encode(rawName.trim(), StandardCharsets.UTF_8).replace("+", "%20");
        Map<String, Object> search = requestWikipedia("https://zh.wikipedia.org/w/api.php?action=query&list=search&srsearch=" + encoded + "&srlimit=" + MAX_PERSONA_CANDIDATES + "&format=json&utf8=1");
        if (search.isEmpty()) search = requestWikipedia("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" + encoded + "&srlimit=" + MAX_PERSONA_CANDIDATES + "&format=json&utf8=1", true);
        Object rawQuery = search.get("query");
        if (!(rawQuery instanceof Map<?, ?> query)) return List.of();
        Object rawResults = query.get("search");
        if (!(rawResults instanceof List<?> results)) return List.of();
        List<Map<String, Object>> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object value : results) {
            if (!(value instanceof Map<?, ?> item)) continue;
            String title = text(item.get("title"));
            if (title.isBlank() || !seen.add(title) || candidates.size() >= MAX_PERSONA_CANDIDATES) continue;
            String normalizedQuery = simpleChinese(rawName).replaceAll("\\s+", "");
            String normalizedTitle = simpleChinese(title).replaceAll("\\s+", "");
            if (!normalizedQuery.isBlank() && !normalizedTitle.contains(normalizedQuery)) continue;
            Map<String, Object> summary = fetchWikipediaSummary(title);
            if (summary.isEmpty() || "disambiguation".equalsIgnoreCase(text(summary.get("type")))) continue;
            String extract = text(summary.get("extract"));
            if (!personaPolicy.accepts(title, text(summary.get("description")), extract, text(summary.get("type")))) continue;
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("name", simpleChinese(title));
            candidate.put("sourceTitle", title);
            candidate.put("description", simpleChinese(text(summary.get("description"))));
            String simpleExtract = simpleChinese(extract);
            candidate.put("summary", simpleExtract.substring(0, Math.min(220, simpleExtract.length())));
            candidate.put("sourceUrl", wikipediaSourceUrl(summary, encodedTitle(title)));
            candidate.put("photoUrl", wikipediaThumbnailUrl(summary));
            candidate.put("sourcePageId", summary.getOrDefault("pageid", summary.getOrDefault("pageId", "")));
            candidate.put("sourceLanguage", text(candidate.get("sourceUrl")).contains("en.wikipedia.org") ? "en" : "zh");
            candidate.put("confidence", candidateConfidence(rawName, title, text(summary.get("description"))));
            candidates.add(candidate);
        }
        // MediaWiki's relevance order is not a character disambiguation
        // order. Rank concrete character pages ahead of similarly named
        // creators, works and generic entries so an administrator can make a
        // meaningful choice without losing less popular variants.
        candidates.sort(Comparator
                .comparingDouble((Map<String, Object> candidate) -> candidateMatchScore(rawName, candidate)).reversed()
                .thenComparing(candidate -> text(candidate.get("sourceTitle")), String.CASE_INSENSITIVE_ORDER));
        return candidates;
    }

    private double candidateMatchScore(String query, Map<String, Object> candidate) {
        String normalizedQuery = normalizeKey(simpleChinese(query));
        String sourceTitle = text(candidate.get("sourceTitle"));
        String normalizedTitle = normalizeKey(simpleChinese(sourceTitle));
        double score;
        if (!normalizedQuery.isBlank() && normalizedTitle.equals(normalizedQuery)) score = 120;
        else if (!normalizedQuery.isBlank() && normalizedTitle.startsWith(normalizedQuery)) score = 95;
        else if (!normalizedQuery.isBlank() && normalizedTitle.contains(normalizedQuery)) score = 78;
        else score = 35;

        String title = simpleChinese(sourceTitle).toLowerCase(Locale.ROOT);
        String evidence = (text(candidate.get("description")) + " " + text(candidate.get("summary")))
                .toLowerCase(Locale.ROOT);
        if (title.contains("(") || title.contains("（")) score += 18;
        if (evidence.contains("虚构角色") || evidence.contains("虛構角色")
                || evidence.contains("fictional character") || evidence.contains("protagonist")) score += 32;
        if (evidence.contains("vtuber") || evidence.contains("虚拟youtuber") || evidence.contains("虛擬youtuber")
                || evidence.contains("插画师") || evidence.contains("插畫師") || evidence.contains("角色设计师")) score -= 10;
        if (title.contains("系列") || title.contains("作品") || title.contains("剧场版") || title.contains("劇場版")) score -= 40;
        return score;
    }

    private String encodedTitle(String title) {
        return URLEncoder.encode(title.replaceAll("\\s+", "_"), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private double candidateConfidence(String query, String title, String description) {
        String q = normalizeKey(query); String t = normalizeKey(title);
        if (q.equals(t)) return 0.98;
        if (!q.isBlank() && t.startsWith(q)) return 0.92;
        if (!q.isBlank() && t.contains(q)) return 0.84;
        return description == null || description.isBlank() ? 0.62 : 0.7;
    }

    @SuppressWarnings("unchecked")
    private String wikipediaThumbnailUrl(Map<String, Object> result) {
        for (String key : List.of("thumbnail", "originalimage")) {
            Object raw = result.get(key);
            if (raw instanceof Map<?, ?> image) {
                String source = text(image.get("source"));
                if (!source.isBlank()) return source;
            }
        }
        return "";
    }

    private String simpleChinese(String value) {
        if (value == null || value.isBlank()) return "";
        try { return ZhConverterUtil.toSimple(value); }
        catch (Exception ignored) { return value; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestWikipedia(String endpoint) {
        return requestWikipedia(endpoint, false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestWikipedia(String endpoint, boolean bypassCircuit) {
        if (!bypassCircuit && wikiCircuitOpenUntil.get() > System.currentTimeMillis()) return Map.of();
        try {
            HttpClient client = wikipediaClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json").header("User-Agent", "ChenFootballCardLab/1.0 (educational project)").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) return Map.of();
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                wikiCircuitOpenUntil.set(System.currentTimeMillis() + 30_000L);
                return Map.of();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) return Map.of();
            wikiCircuitOpenUntil.set(0L);
            return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            wikiCircuitOpenUntil.set(System.currentTimeMillis() + 30_000L);
            return Map.of();
        }
    }

    private HttpClient wikipediaClient() {
        HttpClient client = wikipediaHttpClient;
        if (client != null) return client;
        synchronized (this) {
            if (wikipediaHttpClient == null) {
                HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
                if (crawlerProxyHost != null && !crawlerProxyHost.isBlank() && crawlerProxyPort > 0) builder.proxy(ProxySelector.of(new InetSocketAddress(crawlerProxyHost.trim(), crawlerProxyPort)));
                wikipediaHttpClient = builder.build();
            }
            return wikipediaHttpClient;
        }
    }

    private String wikipediaSourceUrl(Map<String, Object> result, String encoded) {
        Object rawContentUrls = result.get("content_urls");
        if (rawContentUrls instanceof Map<?, ?> contentUrls) {
            Object rawDesktop = contentUrls.get("desktop");
            if (rawDesktop instanceof Map<?, ?> desktop) {
                String page = text(desktop.get("page"));
                if (!page.isBlank()) return page;
            }
        }
        return "https://zh.wikipedia.org/wiki/" + encoded;
    }

    private List<Integer> statsFor(String value, String position) {
        return statsFor(value, position, "全能", Math.floorMod(value.hashCode(), 100000), Map.of());
    }

    private List<Integer> statsFor(String value, String position, String archetype, int seed, Map<String, Object> locked) {
        int stable = Math.floorMod((value + "|" + seed).hashCode(), Integer.MAX_VALUE);
        // Keep the hash only as a small tie-breaker. The dominant part of the
        // score now comes from lexical signals in the Wiki title and summary,
        // so two characters with different descriptions do not receive an
        // arbitrary-looking rating just because their names hash differently.
        int pace = bounded(57 + stable % 10); int shooting = bounded(56 + (stable / 7) % 11);
        int passing = bounded(58 + (stable / 13) % 9); int dribbling = bounded(57 + (stable / 17) % 10);
        int defending = bounded(55 + (stable / 23) % 12); int physical = bounded(57 + (stable / 31) % 11);
        Map<String, Integer> signals = statSignals(value);
        pace = bounded(pace + signals.getOrDefault("pace", 0)); shooting = bounded(shooting + signals.getOrDefault("shooting", 0));
        passing = bounded(passing + signals.getOrDefault("passing", 0)); dribbling = bounded(dribbling + signals.getOrDefault("dribbling", 0));
        defending = bounded(defending + signals.getOrDefault("defending", 0)); physical = bounded(physical + signals.getOrDefault("physical", 0));
        switch (allowedArchetype(archetype)) {
            case "速度" -> { pace = bounded(pace + 12); dribbling = bounded(dribbling + 5); defending = bounded(defending - 4); }
            case "力量" -> { physical = bounded(physical + 12); shooting = bounded(shooting + 4); pace = bounded(pace - 4); }
            case "智谋" -> { passing = bounded(passing + 12); defending = bounded(defending + 4); pace = bounded(pace - 3); }
            case "魅力" -> { shooting = bounded(shooting + 8); passing = bounded(passing + 8); }
            case "防守" -> { defending = bounded(defending + 13); physical = bounded(physical + 5); shooting = bounded(shooting - 5); }
            case "创造" -> { dribbling = bounded(dribbling + 10); passing = bounded(passing + 8); defending = bounded(defending - 4); }
            default -> { }
        }
        if (position.contains("守门")) defending = Math.min(95, defending + 6);
        pace = lockedValue(locked, "pace", pace); shooting = lockedValue(locked, "shooting", shooting);
        passing = lockedValue(locked, "passing", passing); dribbling = lockedValue(locked, "dribbling", dribbling);
        defending = lockedValue(locked, "defending", defending); physical = lockedValue(locked, "physical", physical);
        int overall = Math.round((pace + shooting + passing + dribbling + defending + physical) / 6f);
        return List.of(pace, shooting, passing, dribbling, defending, physical, overall);
    }

    private Map<String, Integer> statSignals(String value) {
        String text = simpleChinese(value).toLowerCase(Locale.ROOT);
        Map<String, Integer> signals = new LinkedHashMap<>();
        addSignal(signals, text, "pace", 7, "速度", "迅速", "飞快", "敏捷", "机动", "快攻", "fast", "speed", "quick");
        addSignal(signals, text, "shooting", 6, "射击", "攻击", "战斗", "火焰", "炮", "能量", "武器", "剑术", "招式", "shooter", "attack", "power");
        addSignal(signals, text, "passing", 6, "智慧", "策略", "领导", "指挥", "战术", "谋略", "发明", "tactician", "leader", "smart");
        addSignal(signals, text, "dribbling", 6, "敏捷", "灵活", "幻术", "魔法", "变身", "忍术", "技巧", "舞蹈", "agile", "magic", "trick");
        addSignal(signals, text, "defending", 6, "守护", "防御", "骑士", "盾", "保护", "正义", "坚韧", "guardian", "defense", "protect");
        addSignal(signals, text, "physical", 7, "力量", "巨人", "强壮", "肌肉", "格斗", "武术", "武士", "赛亚人", "strong", "giant", "muscle");
        return signals;
    }

    private void addSignal(Map<String, Integer> signals, String text, String key, int boost, String... markers) {
        int hits = 0;
        for (String marker : markers) {
            String normalized = marker.toLowerCase(Locale.ROOT);
            int from = 0;
            while (!normalized.isBlank() && (from = text.indexOf(normalized, from)) >= 0) {
                hits++;
                from += normalized.length();
            }
        }
        if (hits > 0) signals.put(key, Math.min(boost + (hits - 1) * 2, boost + 8));
    }

    private int lockedValue(Map<String, Object> locked, String key, int fallback) {
        if (locked == null || !locked.containsKey(key)) return fallback;
        return bounded(intValue(locked.get(key), fallback));
    }

    private String allowedArchetype(String value) { return ALLOWED_ARCHETYPES.contains(value) ? value : "全能"; }

    private String allowedPosition(String value) { return ALLOWED_PERSONA_POSITIONS.contains(value) ? value : "全能"; }

    private String inferPersonaPosition(String value) {
        String text = simpleChinese(value).toLowerCase(Locale.ROOT);
        if (containsAny(text, "守门", "门将", "goalkeeper", "keeper")) return "门将";
        if (containsAny(text, "后卫", "中后卫", "边后卫", "防御", "守护", "骑士", "盾", "defender", "guardian")) return text.contains("边") ? "边后卫" : "后卫";
        if (containsAny(text, "边锋", "winger", "边路", "速度", "敏捷", "快攻", "forward")) return "边锋";
        if (containsAny(text, "前腰", "指挥", "策略", "智慧", "谋略", "创造", "playmaker", "tactician")) return "前腰";
        if (containsAny(text, "后腰", "拦截", "中场", "战术", "防守型", "midfielder")) return "后腰";
        if (containsAny(text, "中锋", "射手", "攻击", "战斗", "力量", "武士", "striker", "shooter")) return "中锋";
        return "中场";
    }

    private boolean containsAny(String text, String... markers) {
        for (String marker : markers) if (text.contains(marker.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private boolean legacyCreationAllowed() { return false; }

    private ResponseStatusException userCreationDisabled() {
        return new ResponseStatusException(HttpStatus.GONE, "角色卡已改为管理员策展，用户请前往兑换中心获取角色");
    }

    private boolean isCatalogCard(Long cardId) {
        if (cardId == null) return false;
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_player_cards WHERE id = ? AND catalog_id IS NOT NULL", Integer.class, cardId);
        return count != null && count > 0;
    }

    private void ensurePointsWallet(Long userId) {
        jdbcTemplate.update("INSERT IGNORE INTO fc_user_points_wallet (user_id, balance, total_earned, total_spent) VALUES (?, 0, 0, 0)", userId);
    }

    private Map<String, Object> pointsSummary(Long userId, boolean includeCheckIn) {
        ensurePointsWallet(userId);
        Map<String, Object> wallet = jdbcTemplate.queryForMap("SELECT balance, total_earned, total_spent FROM fc_user_points_wallet WHERE user_id = ?", userId);
        String day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        Integer checked = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_user_points_ledger WHERE user_id = ? AND event_key = ?", Integer.class, userId, "checkin:" + day);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("balance", intValue(wallet.get("balance"), 0)); result.put("totalEarned", intValue(wallet.get("total_earned"), 0)); result.put("totalSpent", intValue(wallet.get("total_spent"), 0));
        result.put("checkedIn", checked != null && checked > 0); result.put("checkInPoints", DAILY_CHECKIN_POINTS); result.put("streak", checkInStreak(userId, day)); result.put("timezone", "Asia/Shanghai");
        if (includeCheckIn) result.put("checkInDate", day);
        return result;
    }

    private int checkInStreak(Long userId, String today) {
        if (userId == null) return 0;
        List<String> recentEvents = jdbcTemplate.queryForList(
                "SELECT event_key FROM fc_user_points_ledger WHERE user_id = ? AND event_type = 'CHECK_IN' ORDER BY created_at DESC LIMIT 31",
                String.class, userId);
        Set<String> checkedDays = recentEvents.stream().map(value -> value == null ? "" : value.replaceFirst("^checkin:", "")).collect(Collectors.toSet());
        int streak = 0;
        java.time.LocalDate cursor = java.time.LocalDate.parse(today).minusDays(1);
        while (streak < 30) {
            if (!checkedDays.contains(cursor.toString())) break;
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private Map<String, Object> normalizeCatalogPayload(Map<String, Object> body) {
        if (body == null) body = Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        String name = text(body == null ? null : body.get("name")).trim();
        if (name.length() < 1 || name.length() > 160) throw new IllegalArgumentException("角色名称长度必须为 1～160 个字符");
        String suppliedDescription = text(body.get("description"));
        if (personaPolicy.isBlockedInput(name) || personaPolicy.containsUnsafeContent(name + "\n" + suppliedDescription + "\n" + text(body.get("tags")))) throw new IllegalArgumentException("该角色内容不符合虚拟角色内容规则");
        result.put("name", name);
        result.put("description", truncate(text(body.get("description")), 2000));
        result.put("sourceTitle", truncate(text(body.get("sourceTitle")), 255)); result.put("sourcePageId", truncate(text(body.get("sourcePageId")), 64));
        result.put("sourceUrl", truncate(text(body.get("sourceUrl")), 512)); result.put("sourceRevision", truncate(text(body.get("sourceRevision")), 128));
        result.put("contentHash", truncate(text(body.get("contentHash")), 96)); result.put("sourceLanguage", truncate(text(body.getOrDefault("sourceLanguage", "zh")), 16));
        String sourceUrl = text(body.get("sourceUrl"));
        if (!sourceUrl.isBlank() && !(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://"))) throw new IllegalArgumentException("来源 URL 必须以 http:// 或 https:// 开头");
        result.put("sourceAttribution", truncate(text(body.getOrDefault("sourceAttribution", "Wikipedia · CC BY-SA 4.0 · 管理员策展")), 512));
        result.put("sourceLicense", truncate(text(body.getOrDefault("sourceLicense", WIKIPEDIA_LICENSE)), 64)); result.put("photoUrl", truncate(text(body.get("photoUrl")), 512));
        if (text(body.get("photoUrl")).length() > 0) {
            try {
                URI photoUri = URI.create(text(body.get("photoUrl")));
                String host = photoUri.getHost() == null ? "" : photoUri.getHost().toLowerCase(Locale.ROOT);
                if (!"https".equalsIgnoreCase(photoUri.getScheme()) || !(allowedWikiHost(host))) throw new IllegalArgumentException("头像只允许使用 HTTPS 的 Wikimedia/Wikipedia 素材地址");
            } catch (IllegalArgumentException error) { throw new IllegalArgumentException("头像 URL 无效或不在允许的素材域名内"); }
        }
        result.put("position", allowedPosition(text(body.getOrDefault("position", "全能")))); result.put("archetype", allowedArchetype(text(body.getOrDefault("archetype", "全能"))));
        Object rawTags = body.get("tags");
        if (!(rawTags instanceof List<?>)) rawTags = body.get("tagsJson");
        List<String> tags = normalizePersonaTags(rawTags, personaTagsFor(text(body.get("sourceTitle")), name + "\n" + text(body.get("description"))));
        result.put("tags", tags); result.put("tagsJson", jsonText(tags));
        Map<String, Object> stats = body.get("stats") instanceof Map<?, ?> map ? toStringMap(map) : body;
        int pace = boundedCatalogStat(intValue(stats.get("pace"), 60)); int shooting = boundedCatalogStat(intValue(stats.get("shooting"), 60)); int passing = boundedCatalogStat(intValue(stats.get("passing"), 60)); int dribbling = boundedCatalogStat(intValue(stats.get("dribbling"), 60)); int defending = boundedCatalogStat(intValue(stats.get("defending"), 60)); int physical = boundedCatalogStat(intValue(stats.get("physical"), 60));
        int overall = boundedCatalogStat(Math.round((pace + shooting + passing + dribbling + defending + physical) / 6f));
        result.put("pace", pace); result.put("shooting", shooting); result.put("passing", passing); result.put("dribbling", dribbling); result.put("defending", defending); result.put("physical", physical); result.put("overall", overall);
        result.put("skillsJson", jsonText(stringList(body.get("skills"), List.of("全能适应")))); result.put("traitsJson", jsonText(stringList(body.get("traits"), List.of("角色成长"))));
        int price = intValue(body.get("pricePoints"), 10); if (price < 0 || price > MAX_CATALOG_PRICE) throw new IllegalArgumentException("兑换点数必须在 0～" + MAX_CATALOG_PRICE + " 之间"); result.put("pricePoints", price);
        String status = text(body.getOrDefault("status", "DRAFT")).toUpperCase(Locale.ROOT); if (!Set.of("DRAFT", "PUBLISHED", "OFFLINE").contains(status)) throw new IllegalArgumentException("目录状态无效"); result.put("status", status);
        if ("PUBLISHED".equals(status)) {
            if (text(result.get("sourceTitle")).isBlank() || text(result.get("sourceUrl")).isBlank()) throw new IllegalArgumentException("上架前必须填写并核验来源条目与来源 URL");
            if (!isAllowedWikipediaUrl(text(result.get("sourceUrl")))) throw new IllegalArgumentException("当前目录仅允许使用 Wikipedia 来源条目");
        }
        return result;
    }

    private String nextCatalogVersion(Long catalogId) {
        if (catalogId == null) return "catalog-1";
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_persona_catalog_version WHERE catalog_id = ?", Integer.class, catalogId);
        return "catalog-" + ((count == null ? 0 : count) + 1);
    }

    private void saveCatalogVersion(Long catalogId, String version, Map<String, Object> snapshot, Long operator, String reason) {
        if (catalogId == null || version == null || version.isBlank() || operator == null) return;
        jdbcTemplate.update("INSERT IGNORE INTO fc_persona_catalog_version (catalog_id, version, snapshot_json, change_reason, changed_by) VALUES (?, ?, ?, ?, ?)", catalogId, version, jsonText(snapshot), reason, operator);
    }

    private void recordAudit(String action, String entityType, Long entityId, Map<String, Object> detail) {
        try { jdbcTemplate.update("INSERT INTO fc_card_lab_audit_log (operator_user_id, action, entity_type, entity_id, detail_json) VALUES (?, ?, ?, ?, ?)", UserContext.getUserId(), action, entityType, entityId, jsonText(detail)); }
        catch (Exception error) { log.warn("[CardLab] audit write failed action={} entity={}:{}", action, entityType, entityId, error); }
    }

    private List<String> stringList(Object raw, List<String> fallback) {
        if (raw instanceof String textValue && !textValue.isBlank()) {
            try { raw = objectMapper.readValue(textValue, new TypeReference<List<String>>() {}); }
            catch (Exception ignored) { raw = Arrays.asList(textValue.split("[,，、]")); }
        }
        if (!(raw instanceof List<?> values)) return fallback;
        List<String> result = values.stream().map(CardWorkshopController::text).map(String::trim).filter(value -> !value.isBlank()).map(value -> truncate(value, 64)).distinct().limit(8).toList();
        return result.isEmpty() ? fallback : result;
    }

    private List<String> normalizePersonaTags(Object raw, List<String> fallback) {
        List<String> source = raw == null ? List.of() : stringList(raw, List.of());
        if (source.isEmpty()) source = fallback == null ? List.of() : fallback;
        return source.stream().map(CardWorkshopController::text).map(String::trim)
                .filter(value -> !value.isBlank()).map(value -> value.replaceAll("\\s+", " "))
                .map(value -> simpleChinese(value.replace('：', ':')))
                .map(value -> canonicalTag(value))
                .map(value -> truncate(value, 64)).distinct().limit(MAX_PERSONA_TAGS).toList();
    }

    private String canonicalTag(String value) {
        int separator = value.indexOf(':');
        if (separator <= 0) return "特征:" + value;
        String prefix = value.substring(0, separator).trim();
        String label = value.substring(separator + 1).trim().replaceAll("\\s+", "");
        prefix = switch (prefix) {
            case "作品", "作品/系列", "系列", "出处", "出處" -> "作品";
            case "特征", "特点", "特點", "外观", "外觀" -> "特征";
            case "阵营", "陣營", "组织", "組織" -> "阵营";
            case "身份", "職業", "职业" -> "身份";
            case "种族", "種族" -> "种族";
            default -> prefix;
        };
        return prefix + ":" + label;
    }

    /** Generate conservative suggestions from the Wiki evidence; admins can edit them before publishing. */
    private List<String> personaTagsFor(String title, String evidence) {
        String titleText = simpleChinese(text(title));
        String source = simpleChinese(text(title) + "\n" + text(evidence));
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        String leadSource = source;
        int paragraphBreak = leadSource.indexOf("\\n\\n");
        if (paragraphBreak > 0) leadSource = leadSource.substring(0, paragraphBreak);
        leadSource = leadSource.substring(0, Math.min(1200, leadSource.length()));
        java.util.regex.Matcher work = java.util.regex.Pattern.compile("《([^》]{1,40})》").matcher(leadSource);
        while (work.find() && tags.stream().filter(tag -> tag.startsWith("作品:")).count() < 4) {
            String value = text(work.group(1));
            if (!value.isBlank() && !value.matches("\\d{2,4}") && value.length() <= 32) tags.add("作品:" + value);
        }
        java.util.regex.Matcher titleVariant = java.util.regex.Pattern.compile("\\(([^()]{1,24})\\)|（([^（）]{1,24})）").matcher(titleText);
        while (titleVariant.find()) {
            String value = text(titleVariant.group(1));
            if (value.isBlank()) value = text(titleVariant.group(2));
            if (!value.isBlank() && !value.matches("\\d{2,4}") && value.length() <= 24) tags.add("作品:" + value);
        }
        String featureSource = leadSource;
        Map<String, String> featureMarkers = new LinkedHashMap<>();
        featureMarkers.put("银白发", "银白发"); featureMarkers.put("银发", "银白发"); featureMarkers.put("银色头发", "银白发");
        featureMarkers.put("银白色头发", "银白发"); featureMarkers.put("白色头发", "银白发"); featureMarkers.put("白色长发", "银白发"); featureMarkers.put("白发", "银白发");
        featureMarkers.put("红发", "红发"); featureMarkers.put("金发", "金发"); featureMarkers.put("蓝发", "蓝发");
        featureMarkers.put("绿发", "绿发"); featureMarkers.put("紫发", "紫发"); featureMarkers.put("黑发", "黑发");
        featureMarkers.put("忍者", "忍者"); featureMarkers.put("武士", "武士"); featureMarkers.put("魔法", "魔法");
        featureMarkers.put("吸血鬼", "吸血鬼"); featureMarkers.put("赛亚人", "赛亚人"); featureMarkers.put("机器人", "机器人");
        featureMarkers.forEach((marker, label) -> { if (featureSource.contains(marker) && tags.size() < MAX_PERSONA_TAGS) tags.add("特征:" + label); });
        return List.copyOf(tags);
    }

    private int boundedCatalogStat(int value) { return Math.max(1, Math.min(99, value)); }

    /** Game-facing quality tier derived only from the catalog's overall value. */
    private void addCatalogRarity(Map<String, Object> card) {
        if (card == null) return;
        String overallKey = card.containsKey("overall") ? "overall" : "overall_value";
        int overall = intValue(card.get(overallKey), 60);
        String rarity = catalogRarity(overall);
        card.put("rarity", rarity);
        card.put("rarityLabel", rarity);
    }

    private String catalogRarity(int overall) {
        if (overall >= 90) return "UR";
        if (overall >= 85) return "SSR";
        if (overall >= 78) return "SR";
        if (overall >= 70) return "R";
        return "N";
    }

    private static String truncate(String value, int max) { return text(value).substring(0, Math.min(max, text(value).length())); }

    private String explainStats(List<Integer> stats, String archetype, String position) {
        int max = stats.subList(0, 6).stream().max(Integer::compareTo).orElse(0);
        String label = List.of("速度", "射门", "传球", "盘带", "防守", "身体").get(Math.max(0, stats.subList(0, 6).indexOf(max)));
        return "基于 Wikipedia 内容关键词与稳定种子生成；根据性格/能力倾向分配默认位置" + position + "，主导属性为" + label + "（" + max + "）。所有数值均为娱乐性虚构属性，不可用于现实人物评价或竞技排名。";
    }

    private Map<String, Object> personaOptions(Map<String, Object> body) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (body == null) return options;
        options.put("archetype", allowedArchetype(text(body.get("archetype"))));
        options.put("position", allowedPosition(text(body.getOrDefault("position", "全能"))));
        options.put("seed", intValue(body.get("seed"), 0));
        if (body.get("lockedStats") instanceof Map<?, ?> map) options.put("lockedStats", toStringMap(map));
        return options;
    }

    private List<Integer> statsFromPreview(Map<String, Object> preview) {
        Map<String, Object> stats = preview.get("stats") instanceof Map<?, ?> map ? toStringMap(map) : Map.of();
        return List.of(intValue(stats.get("pace"), 60), intValue(stats.get("shooting"), 60), intValue(stats.get("passing"), 60), intValue(stats.get("dribbling"), 60), intValue(stats.get("defending"), 60), intValue(stats.get("physical"), 60), intValue(stats.get("overall"), 60));
    }

    private void enforceRerollQuota(Map<String, Object> card) {
        int count = intValue(card.get("reroll_count"), 0);
        Timestamp started = parseTimestamp(card.get("reroll_window_started_at"));
        if (started != null && System.currentTimeMillis() - started.getTime() < Duration.ofDays(1).toMillis() && count >= MAX_REROLLS_PER_DAY) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "同一张角色卡每天最多重抽 " + MAX_REROLLS_PER_DAY + " 次");
        }
    }

    private void updatePersonaCardFromPreview(Long userId, Long cardId, Map<String, Object> preview, List<Integer> stats) {
        jdbcTemplate.update("UPDATE fc_player_cards SET player_name = ?, position = ?, source_url = ?, bio_summary = ?, photo_url = ?, source_revision = ?, content_hash = ?, generation_seed = ?, archetype = ?, attribute_explanation = ?, moderation_status = 'PENDING', visibility = 'PRIVATE', moderation_reason = NULL, moderated_by = NULL, moderated_at = NULL, source_snapshot = ?, source_language = ?, source_license = ?, source_attribution = ?, policy_version = ?, options_hash = ?, source_fetched_at = CURRENT_TIMESTAMP, source_status = 'ACTIVE', skills_json = ?, traits_json = ?, reroll_count = CASE WHEN reroll_window_started_at IS NULL OR reroll_window_started_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY) THEN 1 ELSE reroll_count + 1 END, reroll_window_started_at = CASE WHEN reroll_window_started_at IS NULL OR reroll_window_started_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY) THEN CURRENT_TIMESTAMP ELSE reroll_window_started_at END, pace = ?, shooting = ?, passing = ?, dribbling = ?, defending = ?, physical = ?, overall = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND owner_user_id = ?",
                text(preview.get("name")), allowedPosition(text(preview.getOrDefault("position", "全能"))), text(preview.get("sourceUrl")), text(preview.get("summary")), text(preview.get("photoUrl")), text(preview.get("sourceRevision")), text(preview.get("contentHash")), intValue(preview.get("generationSeed"), 0), text(preview.getOrDefault("archetype", "全能")), text(preview.get("attributeExplanation")), snapshotJson(preview), text(preview.getOrDefault("sourceLanguage", "zh")), text(preview.getOrDefault("sourceLicense", WIKIPEDIA_LICENSE)), text(preview.getOrDefault("sourceAttribution", "Wikipedia，CC BY-SA 4.0")), text(preview.getOrDefault("policyVersion", PERSONA_POLICY_VERSION)), text(preview.get("optionsHash")), jsonText(preview.get("skills")), jsonText(preview.get("traits")), stats.get(0), stats.get(1), stats.get(2), stats.get(3), stats.get(4), stats.get(5), stats.get(6), cardId, userId);
    }

    private void savePersonaVersion(Long userId, Long cardId, Map<String, Object> preview, List<Integer> stats) {
        if (cardId == null) return;
        try {
            String statsJson = objectMapper.writeValueAsString(Map.of("pace", stats.get(0), "shooting", stats.get(1), "passing", stats.get(2), "dribbling", stats.get(3), "defending", stats.get(4), "physical", stats.get(5), "overall", stats.get(6)));
            jdbcTemplate.update("INSERT INTO fc_persona_card_version (card_id, owner_user_id, source_title, source_url, source_revision, content_hash, generation_seed, archetype, position, options_hash, policy_version, source_language, source_license, stats_json, explanation, snapshot_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", cardId, userId, text(preview.getOrDefault("sourceTitle", preview.get("name"))), text(preview.get("sourceUrl")), text(preview.get("sourceRevision")), text(preview.get("contentHash")), intValue(preview.get("generationSeed"), 0), text(preview.getOrDefault("archetype", "全能")), allowedPosition(text(preview.getOrDefault("position", "全能"))), text(preview.get("optionsHash")), text(preview.getOrDefault("policyVersion", PERSONA_POLICY_VERSION)), text(preview.getOrDefault("sourceLanguage", "zh")), text(preview.getOrDefault("sourceLicense", WIKIPEDIA_LICENSE)), statsJson, text(preview.get("attributeExplanation")), snapshotJson(preview));
        } catch (Exception error) { log.warn("[CardLab] unable to save persona version cardId={}", cardId, error); }
    }

    private Map<String, Object> ensureOwnCard(Long userId, Long cardId) {
        if (cardId == null) throw new NoSuchElementException("角色卡不存在或无权访问");
        try { return jdbcTemplate.queryForMap("SELECT * FROM fc_player_cards WHERE id = ? AND owner_user_id = ? AND card_type = 'CUSTOM_PERSONA'", cardId, userId); }
        catch (Exception error) { throw new NoSuchElementException("角色卡不存在或无权访问"); }
    }

    private List<String> cardTags(Long userId, Long cardId) {
        return jdbcTemplate.query("SELECT tag FROM fc_user_card_tag WHERE user_id = ? AND card_id = ? ORDER BY created_at ASC", (rs, rowNum) -> rs.getString("tag"), userId, cardId);
    }

    private void attachUserTags(Long userId, List<Map<String, Object>> rows) {
        if (userId == null || rows == null || rows.isEmpty()) return;
        List<Long> ids = rows.stream().filter(row -> "CUSTOM_PERSONA".equals(text(row.get("card_type")))).map(row -> number(row.get("id"))).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 1]; args[0] = userId; for (int i = 0; i < ids.size(); i++) args[i + 1] = ids.get(i);
        Map<Long, List<String>> tags = new HashMap<>();
        try {
            for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT card_id, tag FROM fc_user_card_tag WHERE user_id = ? AND card_id IN (" + placeholders + ") ORDER BY created_at ASC", args)) {
                Long cardId = number(row.get("card_id")); if (cardId != null) tags.computeIfAbsent(cardId, ignored -> new ArrayList<>()).add(text(row.get("tag")));
            }
            rows.forEach(row -> { Long id = number(row.get("id")); if (id != null) row.put("tags", tags.getOrDefault(id, List.of())); });
        } catch (Exception error) { rows.forEach(row -> row.putIfAbsent("tags", List.of())); }
    }

    /** Attach canonical administrator-defined persona tags to list projections. */
    private void attachPersonaTags(List<Map<String, Object>> rows) {
        if (rows == null) return;
        List<Long> ids = rows.stream().filter(row -> !(row.containsKey("card_type") && !"CUSTOM_PERSONA".equals(text(row.get("card_type")))) && !row.containsKey("tags_json")).map(row -> number(row.get("id"))).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        try {
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            Map<Long, String> tagMap = new HashMap<>();
            for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT id, tags_json FROM fc_player_cards WHERE id IN (" + placeholders + ")", ids.toArray())) {
                Long cardId = number(row.get("id")); if (cardId != null) tagMap.put(cardId, Optional.ofNullable(row.get("tags_json")).map(String::valueOf).orElse("[]"));
            }
            rows.forEach(row -> { Long id = number(row.get("id")); if (id != null && !row.containsKey("tags_json")) row.put("tags_json", tagMap.getOrDefault(id, "[]")); });
        } catch (Exception error) {
            log.debug("[CardLab] batch persona tag lookup failed", error);
            rows.forEach(row -> row.putIfAbsent("tags_json", "[]"));
        }
    }

    private boolean hasCompleteLineup(Long userId) {
        List<Integer> counts = jdbcTemplate.query("SELECT COUNT(*) FROM fc_user_lineups l JOIN fc_user_lineup_slots s ON s.lineup_id = l.id WHERE l.user_id = ? GROUP BY l.id HAVING COUNT(*) >= 11 LIMIT 1", (rs, rowNum) -> rs.getInt(1), userId);
        return !counts.isEmpty() && counts.get(0) >= 11;
    }

    private Map<String, Object> readLineupSnapshot(Long id) {
        Map<String, Object> lineup = jdbcTemplate.queryForMap("SELECT id, name, formation, created_at, updated_at FROM fc_user_lineups WHERE id = ?", id);
        List<Map<String, Object>> slots = jdbcTemplate.queryForList("SELECT s.slot_index, s.position, c.id, c.id AS player_card_id, c.player_name, c.position AS card_position, c.photo_url, c.overall, c.pace, c.shooting, c.passing, c.dribbling, c.defending, c.physical, c.archetype, c.skills_json, c.traits_json, c.tags_json, c.source_attribution, c.source_license, c.catalog_id, c.catalog_version, c.card_type, c.canonical_player_id, c.player_source_id FROM fc_user_lineup_slots s JOIN fc_player_cards c ON c.id = s.player_card_id WHERE s.lineup_id = ? AND c.card_type = 'CUSTOM_PERSONA' AND c.moderation_status <> 'HIDDEN' ORDER BY s.slot_index", id);
        slots.forEach(slot -> slot.put("slot_position", slot.get("position")));
        attachPersonaTags(slots);
        lineup.put("slots", slots);
        lineup.put("rating", lineupRating(slots));
        lineup.put("competitiveEligible", false);
        lineup.put("scorePolicyVersion", "card-lab-game-v1");
        lineup.put("notice", "阵容评分仅用于虚拟角色玩法，不代表现实人物能力或竞技排名");
        return lineup;
    }

    private static Map<String, Object> toStringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String contentHash(String value) {
        try { byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(text(value).getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for (byte item : digest) out.append(String.format("%02x", item)); return out.toString(); }
        catch (Exception ignored) { return Integer.toHexString(text(value).hashCode()); }
    }

    private String statBasis(Map<String, Object> preview, List<Integer> stats) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("method", "wikipedia-summary-signals-deterministic-v3");
        basis.put("policyVersion", text(preview.getOrDefault("policyVersion", PERSONA_POLICY_VERSION)));
        basis.put("sourceLanguage", text(preview.getOrDefault("sourceLanguage", "zh")));
        basis.put("sourceRevision", text(preview.get("sourceRevision")));
        basis.put("optionsHash", text(preview.get("optionsHash")));
        basis.put("signalSource", "title+summary keyword markers");
        basis.put("stats", stats);
        basis.put("competitiveEligible", false);
        return jsonText(basis);
    }

    private String personaSourceKey(Map<String, Object> preview) {
        String pageId = text(preview.get("sourcePageId"));
        String sourceTitle = text(preview.getOrDefault("sourceTitle", preview.get("name")));
        // The page id is language-specific. Use a normalized title as the
        // canonical identity so zh/en or punctuation variants do not create a
        // second card for the same persona. Keep the page id only as a fallback
        // for malformed pages without a title.
        String titleKey = normalizeKey(simpleChinese(sourceTitle));
        if (!titleKey.isBlank()) return titleKey;
        String language = text(preview.getOrDefault("sourceLanguage", "zh"));
        return normalizeKey(language + ":" + pageId);
    }

    private List<String> personaSkills(String archetype, String position) {
        List<String> skills = new ArrayList<>();
        if ("速度".equals(archetype)) skills.add("闪电突袭");
        if ("力量".equals(archetype)) skills.add("强力对抗");
        if ("智谋".equals(archetype)) skills.add("预判传球");
        if ("魅力".equals(archetype)) skills.add("鼓舞士气");
        if ("防守".equals(archetype)) skills.add("封锁路线");
        if ("创造".equals(archetype)) skills.add("灵感创造");
        if (skills.isEmpty()) skills.add("全能适应");
        if (position.contains("门将")) skills.add("禁区指挥");
        return skills;
    }

    private List<String> personaTraits(String archetype, String position) {
        if ("防守".equals(archetype) || position.contains("后卫")) return List.of("稳健", "阅读比赛");
        if ("速度".equals(archetype) || position.contains("前锋")) return List.of("冲击", "快速转换");
        if ("创造".equals(archetype) || position.contains("中场")) return List.of("想象力", "寻找空间");
        return List.of("适应力", "角色成长");
    }

    private String snapshotJson(Map<String, Object> preview) { return jsonText(preview); }

    private String jsonText(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ignored) { return "{}"; }
    }

    private boolean isExpired(Object value) {
        Timestamp expiry = parseTimestamp(value);
        return expiry != null && expiry.getTime() <= System.currentTimeMillis();
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("[CardLab] added missing column {}.{}", table, column);
        } catch (Exception error) {
            StringBuilder details = new StringBuilder();
            Throwable cursor = error;
            while (cursor != null) { if (cursor.getMessage() != null) details.append(' ').append(cursor.getMessage()); cursor = cursor.getCause(); }
            String message = details.toString().toLowerCase(Locale.ROOT);
            // MySQL reports duplicate-column errors when a concurrent instance
            // has already applied the change. That is safe; all other errors
            // must remain visible instead of silently creating schema drift.
            if (!message.contains("duplicate") && !message.contains("already exists")) {
                log.error("[CardLab] schema migration failed for {}.{}", table, column, error);
            }
        }
    }

    private void addForeignKeyIfMissing(String table, String constraint, String definition) {
        try {
            Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = ?", Integer.class, table, constraint);
            if (exists != null && exists > 0) return;
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " " + definition);
            log.info("[CardLab] added foreign key {}", constraint);
        } catch (Exception error) {
            log.warn("[CardLab] unable to add foreign key {}. Existing orphan rows may need cleanup", constraint, error);
        }
    }

    private void seedSynergyRules() {
        try {
            for (SynergyLevel level : SYNERGY_LEVELS) {
                jdbcTemplate.update("INSERT IGNORE INTO fc_card_lab_synergy_rule (rule_version, tag_prefix, threshold, bonus, description, enabled) VALUES (?, ?, ?, ?, ?, 1)",
                        SYNERGY_RULE_VERSION, level.prefix(), level.threshold(), level.bonus(), level.description());
            }
        } catch (Exception error) {
            log.error("[CardLab] unable to seed synergy rules", error);
        }
    }

    private List<SynergyLevel> synergyLevels() {
        try {
            List<SynergyLevel> rules = jdbcTemplate.query("SELECT tag_prefix, threshold, bonus, description FROM fc_card_lab_synergy_rule WHERE rule_version = ? AND enabled = 1 ORDER BY tag_prefix, threshold", (rs, rowNum) -> new SynergyLevel(rs.getString("tag_prefix"), rs.getInt("threshold"), rs.getInt("bonus"), rs.getString("description")), SYNERGY_RULE_VERSION);
            return rules.isEmpty() ? SYNERGY_LEVELS : rules;
        } catch (Exception error) {
            return SYNERGY_LEVELS;
        }
    }

    private static int bounded(int value) { return Math.max(45, Math.min(95, value)); }
    private static String normalizeKey(String value) {
        return Normalizer.normalize(text(value), Normalizer.Form.NFKD).toLowerCase(Locale.ROOT)
                .replaceAll("\\p{M}", "").replaceAll("[^\\p{L}\\p{N}]", "");
    }
    private static int intValue(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; } }
    private static Long number(Object value) { try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (Exception ignored) { return null; } }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private static boolean allowedWikiHost(String host) {
        if (host == null || host.isBlank()) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("wikipedia.org") || normalized.endsWith(".wikipedia.org")
                || normalized.equals("wikimedia.org") || normalized.endsWith(".wikimedia.org");
    }

    private static boolean isAllowedWikipediaUrl(String value) {
        try {
            URI uri = URI.create(text(value));
            String host = uri.getHost();
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && host != null && (host.equalsIgnoreCase("wikipedia.org") || host.toLowerCase(Locale.ROOT).endsWith(".wikipedia.org"));
        } catch (Exception ignored) { return false; }
    }

    private record WikiCacheEntry(Map<String, Object> payload, long expiresAt) {}
    private record SynergyLevel(String prefix, int threshold, int bonus, String description) {}
    private record LineupPayload(String name, String formation, List<Map<String, Object>> slots) {}
}
