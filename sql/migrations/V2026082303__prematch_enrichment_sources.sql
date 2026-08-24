-- 赛前增强数据：Understat 仅存 xG 供应商缓存，不参与比赛主表身份。
CREATE TABLE IF NOT EXISTS t_understat_league_cache (
  league_code VARCHAR(16) NOT NULL,
  season INT NOT NULL,
  payload_json MEDIUMTEXT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'EMPTY',
  error_message VARCHAR(1000) NULL,
  fetched_at DATETIME NOT NULL,
  PRIMARY KEY (league_code, season),
  KEY idx_understat_fetched (fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_understat_team_xg_cache (
  league_code VARCHAR(16) NOT NULL,
  season INT NOT NULL,
  source_match_id VARCHAR(32) NOT NULL,
  team_id VARCHAR(32) NOT NULL,
  team_name VARCHAR(255) NOT NULL,
  match_time DATETIME NOT NULL,
  xg DECIMAL(8,4) NOT NULL,
  xga DECIMAL(8,4) NOT NULL,
  fetched_at DATETIME NOT NULL,
  PRIMARY KEY (league_code, season, source_match_id, team_id),
  KEY idx_understat_team_time (team_name, match_time),
  KEY idx_understat_xg_time (match_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

-- xG 快照复用既有详情表，detail_type='xg'、source='understat'。
-- 不新增 fixture 行，也不改变 t_match_detail_snapshot 的唯一键。
