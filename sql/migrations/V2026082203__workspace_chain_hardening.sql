-- Workspace chain hardening for existing installations.
-- Run after a database backup. The application is backward compatible with
-- rows that still contain a provider fixture id, but new favorites use the
-- crawler_matches local id as the canonical public match key.
START TRANSACTION;

-- When a provider-key favorite and a local-key favorite already represent
-- the same match, keep the newest row before rewriting the provider key. This
-- prevents uk_user_fixture from aborting the migration on duplicate data.
DELETE legacy
FROM t_user_favorite_match legacy
JOIN crawler_matches target ON target.fixture_id = legacy.fixture_id
LEFT JOIN crawler_matches local_match ON local_match.id = legacy.fixture_id
JOIN t_user_favorite_match canonical
  ON canonical.user_id = legacy.user_id
 AND canonical.fixture_id = target.id
 AND canonical.id > legacy.id
WHERE local_match.id IS NULL;

DELETE canonical
FROM t_user_favorite_match legacy
JOIN crawler_matches target ON target.fixture_id = legacy.fixture_id
LEFT JOIN crawler_matches local_match ON local_match.id = legacy.fixture_id
JOIN t_user_favorite_match canonical
  ON canonical.user_id = legacy.user_id
 AND canonical.fixture_id = target.id
 AND canonical.id < legacy.id
WHERE local_match.id IS NULL;

-- Convert legacy provider fixture ids to local match ids only when the value
-- is not already a valid local id. This avoids rewriting an existing canonical
-- favorite and avoids ambiguous provider/local collisions.
UPDATE t_user_favorite_match f
JOIN crawler_matches m ON m.fixture_id = f.fixture_id
LEFT JOIN crawler_matches local_match ON local_match.id = f.fixture_id
SET f.fixture_id = m.id,
    f.home_team_name = COALESCE(NULLIF(f.home_team_name, ''), m.home_team_name),
    f.away_team_name = COALESCE(NULLIF(f.away_team_name, ''), m.away_team_name),
    f.league_name = COALESCE(NULLIF(f.league_name, ''), m.league_name),
    f.match_time = COALESCE(f.match_time, m.match_time)
WHERE local_match.id IS NULL;

-- Fill metadata for newly canonical rows and legacy rows that already used a
-- local id. This keeps the Workspace card useful even when the original save
-- request only contained a match label.
UPDATE t_user_favorite_match f
JOIN crawler_matches m ON m.id = f.fixture_id
SET f.home_team_name = COALESCE(NULLIF(f.home_team_name, ''), m.home_team_name),
    f.away_team_name = COALESCE(NULLIF(f.away_team_name, ''), m.away_team_name),
    f.league_name = COALESCE(NULLIF(f.league_name, ''), m.league_name),
    f.match_time = COALESCE(f.match_time, m.match_time);

SET @notification_index := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 't_user_notification'
      AND index_name = 'idx_notification_dedupe'
);
SET @notification_sql := IF(
    @notification_index = 0,
    'ALTER TABLE t_user_notification ADD INDEX idx_notification_dedupe (user_id, type, link, created_at)',
    'SELECT 1'
);
PREPARE stmt_notification FROM @notification_sql;
EXECUTE stmt_notification;
DEALLOCATE PREPARE stmt_notification;

COMMIT;
