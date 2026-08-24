-- Card Lab pipeline hardening: source freshness, rating provenance and history.
-- This migration uses information_schema guards because MySQL does not support
-- `CREATE INDEX IF NOT EXISTS` on all supported versions. The business
-- service also keeps defensive DDL for installations that do not run SQL
-- migrations during startup.

SET @card_table_exists := (
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'fc_player_cards'
);
SET @card_table_sql := IF(@card_table_exists = 0,
    'CREATE TABLE fc_player_cards (id BIGINT NOT NULL AUTO_INCREMENT, card_key VARCHAR(255) NOT NULL, canonical_player_id VARCHAR(192) NULL, player_source_id VARCHAR(128) NULL, player_name VARCHAR(160) NOT NULL, position VARCHAR(64), number VARCHAR(16), age VARCHAR(16), nationality VARCHAR(80), photo_url VARCHAR(512), team_name VARCHAR(160), league_name VARCHAR(120), source VARCHAR(64) NOT NULL, source_updated_at DATETIME NULL, last_seen_at DATETIME NULL, source_status VARCHAR(16) NOT NULL DEFAULT ''ACTIVE'', rating_basis_json TEXT NULL, source_url VARCHAR(512) NULL, bio_summary TEXT NULL, visibility VARCHAR(16) NOT NULL DEFAULT ''PUBLIC'', owner_user_id BIGINT NULL, card_type VARCHAR(32) NOT NULL DEFAULT ''REAL_PLAYER'', rating_origin VARCHAR(32) NOT NULL DEFAULT ''RULE_BASED'', rating_version VARCHAR(32) NOT NULL DEFAULT ''roster-lab-v1'', pace INT NOT NULL DEFAULT 60, shooting INT NOT NULL DEFAULT 60, passing INT NOT NULL DEFAULT 60, dribbling INT NOT NULL DEFAULT 60, defending INT NOT NULL DEFAULT 60, physical INT NOT NULL DEFAULT 60, overall INT NOT NULL DEFAULT 60, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id), UNIQUE KEY uk_fc_player_card_key (card_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
    'SELECT 1');
PREPARE stmt_card_table FROM @card_table_sql;
EXECUTE stmt_card_table;
DEALLOCATE PREPARE stmt_card_table;

SET @column_sql := IF(NOT EXISTS (
    SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'fc_player_cards' AND column_name = 'last_seen_at'
), 'ALTER TABLE fc_player_cards ADD COLUMN last_seen_at DATETIME NULL', 'SELECT 1');
PREPARE stmt_column FROM @column_sql; EXECUTE stmt_column; DEALLOCATE PREPARE stmt_column;

SET @column_sql := IF(NOT EXISTS (
    SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'fc_player_cards' AND column_name = 'source_status'
), 'ALTER TABLE fc_player_cards ADD COLUMN source_status VARCHAR(16) NOT NULL DEFAULT ''ACTIVE''', 'SELECT 1');
PREPARE stmt_column FROM @column_sql; EXECUTE stmt_column; DEALLOCATE PREPARE stmt_column;

SET @column_sql := IF(NOT EXISTS (
    SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'fc_player_cards' AND column_name = 'rating_basis_json'
), 'ALTER TABLE fc_player_cards ADD COLUMN rating_basis_json TEXT NULL', 'SELECT 1');
PREPARE stmt_column FROM @column_sql; EXECUTE stmt_column; DEALLOCATE PREPARE stmt_column;

SET @index_sql := IF(NOT EXISTS (
    SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'fc_player_cards' AND index_name = 'idx_fc_player_card_source_status'
), 'ALTER TABLE fc_player_cards ADD INDEX idx_fc_player_card_source_status (source_status)', 'SELECT 1');
PREPARE stmt_index FROM @index_sql; EXECUTE stmt_index; DEALLOCATE PREPARE stmt_index;

SET @index_sql := IF(NOT EXISTS (
    SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'fc_player_cards' AND index_name = 'idx_fc_player_card_last_seen'
), 'ALTER TABLE fc_player_cards ADD INDEX idx_fc_player_card_last_seen (last_seen_at)', 'SELECT 1');
PREPARE stmt_index FROM @index_sql; EXECUTE stmt_index; DEALLOCATE PREPARE stmt_index;

SET @index_sql := IF(NOT EXISTS (
    SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'fc_player_cards' AND index_name = 'idx_fc_player_card_source_name'
), 'ALTER TABLE fc_player_cards ADD INDEX idx_fc_player_card_source_name (source, player_name, nationality)', 'SELECT 1');
PREPARE stmt_index FROM @index_sql; EXECUTE stmt_index; DEALLOCATE PREPARE stmt_index;

CREATE TABLE IF NOT EXISTS fc_player_card_rating_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    card_id BIGINT NOT NULL,
    rating_version VARCHAR(32) NOT NULL,
    pace INT NOT NULL,
    shooting INT NOT NULL,
    passing INT NOT NULL,
    dribbling INT NOT NULL,
    defending INT NOT NULL,
    physical INT NOT NULL,
    overall INT NOT NULL,
    basis_json TEXT NULL,
    source_updated_at DATETIME NULL,
    captured_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_fc_card_rating_history_card (card_id),
    KEY idx_fc_card_rating_history_time (captured_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
