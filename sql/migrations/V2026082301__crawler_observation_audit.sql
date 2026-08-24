-- Durable crawler observations and per-run quality counters.
-- Apply after taking a database backup.  Runtime DDL creates the same tables
-- for local installs; this migration is the production source of truth.
START TRANSACTION;

CREATE TABLE IF NOT EXISTS t_crawler_ingestion_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_name VARCHAR(64) NOT NULL,
  source VARCHAR(64) NOT NULL,
  requested_date DATE NULL,
  result VARCHAR(24) NOT NULL,
  fetched_count INT NOT NULL DEFAULT 0,
  accepted_count INT NOT NULL DEFAULT 0,
  rejected_count INT NOT NULL DEFAULT 0,
  duplicate_count INT NOT NULL DEFAULT 0,
  inserted_count INT NOT NULL DEFAULT 0,
  updated_count INT NOT NULL DEFAULT 0,
  duration_ms BIGINT NOT NULL DEFAULT 0,
  parser_version VARCHAR(64) NOT NULL,
  error_message VARCHAR(1000) NULL,
  started_at DATETIME NOT NULL,
  finished_at DATETIME NULL,
  PRIMARY KEY (id),
  KEY idx_ingestion_date (source, requested_date, started_at),
  KEY idx_ingestion_finished (task_name, finished_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_match_source_observation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  observation_key CHAR(64) NOT NULL,
  canonical_key VARCHAR(255) NOT NULL,
  source VARCHAR(64) NOT NULL,
  external_match_id VARCHAR(128) NULL,
  fixture_id BIGINT NULL,
  league_id VARCHAR(64) NULL,
  league_name VARCHAR(128) NULL,
  home_team_id VARCHAR(128) NULL,
  home_team_name VARCHAR(160) NULL,
  away_team_id VARCHAR(128) NULL,
  away_team_name VARCHAR(160) NULL,
  match_time DATETIME NULL,
  status VARCHAR(32) NULL,
  home_score INT NULL,
  away_score INT NULL,
  normalized_json JSON NOT NULL,
  parser_version VARCHAR(64) NOT NULL,
  observed_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_match_observation (observation_key),
  KEY idx_match_observation_canonical (canonical_key, observed_at),
  KEY idx_match_observation_source (source, observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

COMMIT;
