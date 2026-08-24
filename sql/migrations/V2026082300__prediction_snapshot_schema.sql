-- Runtime DDL used to create these tables for local installations. Keep the
-- production migration path explicit before any cleanup migration references
-- the tables.
START TRANSACTION;

CREATE TABLE IF NOT EXISTS t_crawler_task_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_name VARCHAR(64) NOT NULL,
  result VARCHAR(16) NOT NULL,
  duration_ms BIGINT NOT NULL DEFAULT 0,
  processed_count INT NOT NULL DEFAULT 0,
  error_message VARCHAR(1000),
  finished_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_crawler_task_finished (task_name, finished_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_match_prediction (
  id BIGINT NOT NULL AUTO_INCREMENT,
  fixture_id BIGINT NOT NULL,
  external_match_id VARCHAR(64),
  home_team_id VARCHAR(64), home_team_name VARCHAR(128), home_team_logo VARCHAR(512),
  away_team_id VARCHAR(64), away_team_name VARCHAR(128), away_team_logo VARCHAR(512),
  league_id VARCHAR(64), league_name VARCHAR(128), match_time DATETIME,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  result_label VARCHAR(16), home_win_prob DOUBLE, draw_prob DOUBLE, away_win_prob DOUBLE,
  model_version VARCHAR(64) NOT NULL, feature_version VARCHAR(64) NOT NULL,
  top_features_json TEXT, feature_meta_json MEDIUMTEXT, explanation VARCHAR(1024),
  feature_complete TINYINT(1), feature_status VARCHAR(32), fallback_reason VARCHAR(512),
  generated_at DATETIME, source_updated_at DATETIME, expires_at DATETIME,
  error_message VARCHAR(512), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_match_prediction_fixture_model (fixture_id, model_version, feature_version),
  KEY idx_match_prediction_status_time (status, match_time),
  KEY idx_match_prediction_generated (fixture_id, generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_match_detail_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  fixture_id BIGINT NOT NULL,
  detail_type VARCHAR(32) NOT NULL,
  source VARCHAR(64) NOT NULL,
  payload_json LONGTEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  error_message VARCHAR(1000),
  fetched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_match_detail_source (fixture_id, detail_type, source),
  KEY idx_match_detail_fixture (fixture_id),
  KEY idx_match_detail_fetched (fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_prematch_feature_snapshot (
  fixture_id BIGINT NOT NULL,
  feature_version VARCHAR(64) NOT NULL,
  cutoff_time DATETIME NULL,
  source_updated_at DATETIME NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'NO_HISTORY',
  completeness DECIMAL(6,4) NOT NULL DEFAULT 0,
  source VARCHAR(64) NOT NULL DEFAULT 'local-history',
  payload_json MEDIUMTEXT NULL,
  error_message VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (fixture_id),
  KEY idx_prematch_cutoff (cutoff_time),
  KEY idx_prematch_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

COMMIT;
