-- Match identity repair for existing installations.
-- Run this once during deployment, after taking a database backup.
START TRANSACTION;

-- Empty provider identifiers are not identities. Convert them to NULL so the
-- unique keys below do not reject multiple legitimate BBC rows.
UPDATE crawler_matches SET external_match_id = NULL
WHERE external_match_id IS NOT NULL AND TRIM(external_match_id) = '';
UPDATE crawler_matches SET fixture_id = NULL WHERE fixture_id IS NOT NULL AND fixture_id <= 0;

-- Keep the oldest row for the same provider event and remove stale copies.
DELETE older
FROM crawler_matches older
JOIN crawler_matches keeper
  ON keeper.source = older.source
 AND keeper.external_match_id = older.external_match_id
 AND older.external_match_id IS NOT NULL
 AND older.external_match_id <> ''
 AND keeper.id < older.id;

-- A provider fixture ID is also unique inside one provider.  NULL remains
-- allowed for BBC rows that do not expose a numeric fixture ID.
DELETE older
FROM crawler_matches older
JOIN crawler_matches keeper
  ON keeper.source = older.source
 AND keeper.fixture_id = older.fixture_id
 AND older.fixture_id IS NOT NULL
 AND keeper.id < older.id;

SET @idx_external := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'crawler_matches'
      AND index_name = 'uk_match_source_external'
);
SET @sql_external := IF(@idx_external = 0,
    'ALTER TABLE crawler_matches ADD UNIQUE KEY uk_match_source_external (source, external_match_id)',
    'SELECT 1');
PREPARE stmt_external FROM @sql_external;
EXECUTE stmt_external;
DEALLOCATE PREPARE stmt_external;

SET @idx_fixture := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'crawler_matches'
      AND index_name = 'uk_match_source_fixture'
);
SET @sql_fixture := IF(@idx_fixture = 0,
    'ALTER TABLE crawler_matches ADD UNIQUE KEY uk_match_source_fixture (source, fixture_id)',
    'SELECT 1');
PREPARE stmt_fixture FROM @sql_fixture;
EXECUTE stmt_fixture;
DEALLOCATE PREPARE stmt_fixture;

COMMIT;
