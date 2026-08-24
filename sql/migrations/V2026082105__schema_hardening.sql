-- Schema hardening for existing installations.
-- Run after a database backup. Conditional DDL keeps this migration repeatable.
START TRANSACTION;

SET @identity_table_exists := (
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 't_identity_alias'
);
SET @identity_sql := IF(@identity_table_exists = 0,
    'CREATE TABLE t_identity_alias (id BIGINT NOT NULL AUTO_INCREMENT, entity_type VARCHAR(16) NOT NULL, canonical_key VARCHAR(128) NOT NULL, source VARCHAR(64) NOT NULL, external_id VARCHAR(128) NOT NULL, source_name VARCHAR(128) DEFAULT NULL, canonical_name VARCHAR(128) DEFAULT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id), UNIQUE KEY uk_identity_source (entity_type, source, external_id), KEY idx_identity_canonical (entity_type, canonical_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
    'SELECT 1');
PREPARE stmt_identity FROM @identity_sql;
EXECUTE stmt_identity;
DEALLOCATE PREPARE stmt_identity;

SET @analytics_sql := IF(
    (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 't_analytics_event') = 1
    AND NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_analytics_event' AND index_name = 'uk_analytics_event_id')
    AND NOT EXISTS (SELECT 1 FROM t_analytics_event WHERE event_id IS NOT NULL AND event_id <> '' GROUP BY event_id HAVING COUNT(*) > 1),
    'ALTER TABLE t_analytics_event ADD UNIQUE KEY uk_analytics_event_id (event_id)',
    'SELECT 1');
PREPARE stmt_analytics FROM @analytics_sql;
EXECUTE stmt_analytics;
DEALLOCATE PREPARE stmt_analytics;

-- Do not silently rename duplicate user nicknames. Resolve them intentionally
-- first, then rerun this migration to enable the database constraint.
SET @nickname_duplicates := IF(
    (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 't_user') = 1,
    (SELECT COUNT(*) FROM (SELECT nickname FROM t_user WHERE nickname IS NOT NULL AND nickname <> '' GROUP BY nickname HAVING COUNT(*) > 1) d),
    0
);
SET @nickname_sql := IF(@nickname_duplicates = 0 AND NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 't_user' AND index_name = 'uk_user_nickname'
), 'ALTER TABLE t_user ADD UNIQUE KEY uk_user_nickname (nickname)', 'SELECT 1');
PREPARE stmt_nickname FROM @nickname_sql;
EXECUTE stmt_nickname;
DEALLOCATE PREPARE stmt_nickname;

COMMIT;
