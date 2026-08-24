-- Card Lab: stable identity, rating provenance and source freshness.
-- The business service keeps defensive DDL for older installations; this
-- migration is the canonical repeatable schema change for new deployments.
CREATE TABLE IF NOT EXISTS fc_player_cards (
    id BIGINT NOT NULL AUTO_INCREMENT,
    card_key VARCHAR(255) NOT NULL,
    canonical_player_id VARCHAR(192) NULL,
    player_source_id VARCHAR(128) NULL,
    player_name VARCHAR(160) NOT NULL,
    position VARCHAR(64),
    number VARCHAR(16),
    age VARCHAR(16),
    nationality VARCHAR(80),
    photo_url VARCHAR(512),
    team_name VARCHAR(160),
    league_name VARCHAR(120),
    source VARCHAR(64) NOT NULL,
    source_updated_at DATETIME NULL,
    source_url VARCHAR(512) NULL,
    bio_summary TEXT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    owner_user_id BIGINT NULL,
    card_type VARCHAR(32) NOT NULL DEFAULT 'REAL_PLAYER',
    rating_origin VARCHAR(32) NOT NULL DEFAULT 'RULE_BASED',
    rating_version VARCHAR(32) NOT NULL DEFAULT 'roster-lab-v1',
    pace INT NOT NULL DEFAULT 60,
    shooting INT NOT NULL DEFAULT 60,
    passing INT NOT NULL DEFAULT 60,
    dribbling INT NOT NULL DEFAULT 60,
    defending INT NOT NULL DEFAULT 60,
    physical INT NOT NULL DEFAULT 60,
    overall INT NOT NULL DEFAULT 60,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fc_player_card_key (card_key),
    KEY idx_fc_player_card_canonical (canonical_player_id),
    KEY idx_fc_player_card_owner (owner_user_id),
    KEY idx_fc_player_card_team (team_name),
    KEY idx_fc_player_card_league (league_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Do not make canonical identity unique yet: legacy installations may contain
-- duplicates that need an intentional merge. The service updates the oldest
-- matching card and stops creating new duplicates.
