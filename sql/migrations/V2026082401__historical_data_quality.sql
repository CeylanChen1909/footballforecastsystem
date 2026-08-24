-- 历史训练/赛前特征质量审计。
-- crawler_matches 仍是唯一比赛身份表，本表只保存可重算的质量结果。
CREATE TABLE IF NOT EXISTS t_match_data_quality (
  id BIGINT NOT NULL AUTO_INCREMENT,
  fixture_id BIGINT NULL,
  source VARCHAR(64) NOT NULL,
  league_id VARCHAR(64) NULL,
  league_name VARCHAR(128) NULL,
  canonical_key VARCHAR(255) NOT NULL,
  quality_status VARCHAR(24) NOT NULL,
  quality_score DECIMAL(6,4) NOT NULL DEFAULT 0,
  issue_codes VARCHAR(1000) NULL,
  home_sample_size INT NOT NULL DEFAULT 0,
  away_sample_size INT NOT NULL DEFAULT 0,
  xg_home_available BOOLEAN NOT NULL DEFAULT FALSE,
  xg_away_available BOOLEAN NOT NULL DEFAULT FALSE,
  checked_at DATETIME NOT NULL,
  source_updated_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_match_quality_fixture_source (fixture_id, source),
  KEY idx_match_quality_status (quality_status, checked_at),
  KEY idx_match_quality_league (league_id, league_name, checked_at),
  KEY idx_match_quality_canonical (canonical_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Understat 原始比赛与主爬虫比赛的连接审计。它不写入 crawler_matches，
-- 用于解释 xG 覆盖不足到底是源无数据还是身份/时间未匹配。
CREATE TABLE IF NOT EXISTS t_understat_match_join_audit (
  league_code VARCHAR(16) NOT NULL,
  season INT NOT NULL,
  source_match_id VARCHAR(64) NOT NULL,
  home_team_name VARCHAR(255) NULL,
  away_team_name VARCHAR(255) NULL,
  match_time DATETIME NULL,
  join_status VARCHAR(24) NOT NULL,
  fixture_id BIGINT NULL,
  message VARCHAR(255) NULL,
  checked_at DATETIME NOT NULL,
  PRIMARY KEY (league_code, season, source_match_id),
  KEY idx_understat_join_status (join_status, checked_at),
  KEY idx_understat_join_league (league_code, season, join_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
