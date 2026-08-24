-- Keep one current snapshot per fixture and feature version.  The previous
-- key included model_version, which allowed a PENDING ELO row and a READY
-- baseline row to coexist and made clients appear stuck in generation.
START TRANSACTION;

DELETE older
FROM t_match_prediction older
JOIN t_match_prediction newer
  ON newer.fixture_id = older.fixture_id
 AND newer.feature_version = older.feature_version
 AND (newer.updated_at > older.updated_at OR (newer.updated_at = older.updated_at AND newer.id > older.id));

SET @idx := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 't_match_prediction'
      AND index_name = 'uk_match_prediction_fixture_model'
);
SET @drop_sql := IF(@idx > 0,
    'ALTER TABLE t_match_prediction DROP INDEX uk_match_prediction_fixture_model',
    'SELECT 1');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

SET @new_idx := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 't_match_prediction'
      AND index_name = 'uk_match_prediction_fixture_feature'
);
SET @add_sql := IF(@new_idx = 0,
    'ALTER TABLE t_match_prediction ADD UNIQUE KEY uk_match_prediction_fixture_feature (fixture_id, feature_version)',
    'SELECT 1');
PREPARE add_stmt FROM @add_sql;
EXECUTE add_stmt;
DEALLOCATE PREPARE add_stmt;

COMMIT;
