-- Card Lab catalog governance, versioned redemption snapshots and audit trail.
-- Apply this migration before enabling the administrator catalog in a new
-- environment. The runtime service keeps compatibility checks for legacy DBs.

ALTER TABLE fc_persona_catalog
    ADD COLUMN IF NOT EXISTS source_page_id VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS source_language VARCHAR(16) NULL,
    ADD COLUMN IF NOT EXISTS source_revision VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS content_hash VARCHAR(96) NULL,
    ADD COLUMN IF NOT EXISTS catalog_version VARCHAR(64) NOT NULL DEFAULT 'catalog-v1',
    ADD COLUMN IF NOT EXISTS published_by BIGINT NULL,
    ADD COLUMN IF NOT EXISTS updated_by BIGINT NULL,
    ADD COLUMN IF NOT EXISTS moderation_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN IF NOT EXISTS moderation_reason VARCHAR(500) NULL,
    ADD COLUMN IF NOT EXISTS reviewed_by BIGINT NULL,
    ADD COLUMN IF NOT EXISTS reviewed_at DATETIME NULL;

ALTER TABLE fc_player_cards
    ADD COLUMN IF NOT EXISTS catalog_version VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS catalog_snapshot MEDIUMTEXT NULL;

ALTER TABLE fc_persona_inventory
    ADD COLUMN IF NOT EXISTS catalog_version VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS price_points INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS catalog_snapshot MEDIUMTEXT NULL;

ALTER TABLE fc_lineup_share
    ADD COLUMN IF NOT EXISTS view_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_viewed_at DATETIME NULL;

CREATE TABLE IF NOT EXISTS fc_persona_catalog_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    catalog_id BIGINT NOT NULL,
    version VARCHAR(64) NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    change_reason VARCHAR(500) NULL,
    changed_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fc_catalog_version (catalog_id, version),
    KEY idx_fc_catalog_version_time (catalog_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fc_card_lab_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operator_user_id BIGINT NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id BIGINT NULL,
    detail_json MEDIUMTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_fc_card_lab_audit_time (created_at),
    KEY idx_fc_card_lab_audit_entity (entity_type, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fc_card_lab_schema_version (
    version VARCHAR(64) NOT NULL,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fc_card_lab_synergy_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rule_version VARCHAR(64) NOT NULL,
    tag_prefix VARCHAR(32) NOT NULL,
    threshold INT NOT NULL,
    bonus INT NOT NULL,
    description VARCHAR(128) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fc_synergy_rule (rule_version, tag_prefix, threshold)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
