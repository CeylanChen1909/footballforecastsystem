-- Production cleanup: the platform currently writes matches only from the
-- BBC primary source (plus explicit admin overrides).  Older demo databases
-- may still contain rows written by disabled Juhe/API-Football/football-data
-- providers.  Run this migration only after taking a backup.
-- @destructive: removes legacy provider match rows from the active database.
START TRANSACTION;

DELETE FROM crawler_matches
WHERE LOWER(COALESCE(source, '')) IN ('juhe', 'api-football', 'football-data', 'worldfootball', 'zq123');

COMMIT;
