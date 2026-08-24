-- Card Lab creative loop: provenance, moderation, versions, sharing and tags.
-- The service also creates these structures defensively for existing installs.

ALTER TABLE fc_player_cards
    ADD COLUMN source_revision VARCHAR(128) NULL,
    ADD COLUMN content_hash VARCHAR(96) NULL,
    ADD COLUMN generation_seed INT NULL,
    ADD COLUMN archetype VARCHAR(32) NULL,
    ADD COLUMN attribute_explanation TEXT NULL,
    ADD COLUMN moderation_status VARCHAR(16) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN source_snapshot TEXT NULL,
    ADD COLUMN persona_source_key VARCHAR(192) NULL,
    ADD COLUMN source_language VARCHAR(16) NULL,
    ADD COLUMN source_license VARCHAR(64) NULL,
    ADD COLUMN source_attribution VARCHAR(512) NULL,
    ADD COLUMN policy_version VARCHAR(64) NULL,
    ADD COLUMN options_hash VARCHAR(96) NULL,
    ADD COLUMN source_fetched_at DATETIME NULL,
    ADD COLUMN moderation_reason VARCHAR(500) NULL,
    ADD COLUMN moderated_by BIGINT NULL,
    ADD COLUMN moderated_at DATETIME NULL,
    ADD COLUMN skills_json TEXT NULL,
    ADD COLUMN traits_json TEXT NULL,
    ADD COLUMN reroll_count INT NOT NULL DEFAULT 0,
    ADD COLUMN reroll_window_started_at DATETIME NULL;

ALTER TABLE fc_player_cards ADD COLUMN catalog_id BIGINT NULL;

CREATE TABLE IF NOT EXISTS fc_persona_card_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    card_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    source_title VARCHAR(255) NOT NULL,
    source_url VARCHAR(512) NULL,
    source_revision VARCHAR(128) NULL,
    content_hash VARCHAR(96) NULL,
    generation_seed INT NULL,
    archetype VARCHAR(32) NULL,
    position VARCHAR(64) NULL,
    options_hash VARCHAR(96) NULL,
    policy_version VARCHAR(64) NULL,
    source_language VARCHAR(16) NULL,
    source_license VARCHAR(64) NULL,
    stats_json TEXT NOT NULL,
    explanation TEXT NULL,
    snapshot_json TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_fc_persona_version_card (card_id, created_at),
    KEY idx_fc_persona_version_owner (owner_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fc_card_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    card_id BIGINT NOT NULL,
    reporter_user_id BIGINT NOT NULL,
    reason VARCHAR(64) NOT NULL,
    detail VARCHAR(500) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at DATETIME NULL,
    resolved_by BIGINT NULL,
    resolution_note VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fc_card_report_user (card_id, reporter_user_id),
    KEY idx_fc_card_report_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fc_card_report_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    card_id BIGINT NOT NULL,
    operator_user_id BIGINT NOT NULL,
    from_status VARCHAR(16) NULL,
    to_status VARCHAR(16) NOT NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_fc_card_report_history_report (report_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fc_card_moderation_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    card_id BIGINT NOT NULL,
    operator_user_id BIGINT NOT NULL,
    from_status VARCHAR(16) NULL,
    to_status VARCHAR(16) NOT NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_fc_card_moderation_history_card (card_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fc_lineup_share (
    id BIGINT NOT NULL AUTO_INCREMENT,
    lineup_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    share_token VARCHAR(96) NOT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    expires_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fc_lineup_share_token (share_token),
    UNIQUE KEY uk_fc_lineup_share_lineup (lineup_id),
    KEY idx_fc_lineup_share_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE fc_lineup_share SET expires_at = DATE_ADD(created_at, INTERVAL 30 DAY) WHERE expires_at IS NULL;

CREATE TABLE IF NOT EXISTS fc_persona_source_cache (
    cache_key VARCHAR(192) NOT NULL,
    source_language VARCHAR(16) NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    source_url VARCHAR(512) NULL,
    source_revision VARCHAR(128) NULL,
    content_hash VARCHAR(96) NULL,
    fetched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    PRIMARY KEY (cache_key, source_language),
    KEY idx_fc_persona_cache_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fc_user_card_tag (
    user_id BIGINT NOT NULL,
    card_id BIGINT NOT NULL,
    tag VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, card_id, tag),
    KEY idx_fc_user_card_tag_card (card_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fc_card_public_like (
    card_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (card_id, user_id),
    KEY idx_fc_card_public_like_card (card_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fc_persona_catalog (
    id BIGINT NOT NULL AUTO_INCREMENT,
    persona_key VARCHAR(192) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description TEXT NULL,
    source_title VARCHAR(255) NULL,
    source_url VARCHAR(512) NULL,
    source_attribution VARCHAR(512) NULL,
    source_license VARCHAR(64) NULL,
    photo_url VARCHAR(512) NULL,
    position VARCHAR(64) NOT NULL DEFAULT '全能',
    archetype VARCHAR(32) NOT NULL DEFAULT '全能',
    pace INT NOT NULL DEFAULT 60,
    shooting INT NOT NULL DEFAULT 60,
    passing INT NOT NULL DEFAULT 60,
    dribbling INT NOT NULL DEFAULT 60,
    defending INT NOT NULL DEFAULT 60,
    physical INT NOT NULL DEFAULT 60,
    overall INT NOT NULL DEFAULT 60,
    skills_json TEXT NULL,
    traits_json TEXT NULL,
    price_points INT NOT NULL DEFAULT 10,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    published_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fc_persona_catalog_key (persona_key),
    KEY idx_fc_persona_catalog_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fc_persona_inventory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    catalog_id BIGINT NOT NULL,
    card_id BIGINT NOT NULL,
    redeemed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fc_persona_inventory_user_catalog (user_id, catalog_id),
    KEY idx_fc_persona_inventory_user (user_id),
    KEY idx_fc_persona_inventory_catalog (catalog_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fc_user_points_wallet (
    user_id BIGINT NOT NULL,
    balance INT NOT NULL DEFAULT 0,
    total_earned INT NOT NULL DEFAULT 0,
    total_spent INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fc_user_points_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    amount INT NOT NULL,
    balance_after INT NOT NULL,
    reference_id BIGINT NULL,
    description VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fc_points_event (user_id, event_key),
    KEY idx_fc_points_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
