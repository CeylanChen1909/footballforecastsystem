-- Ensure profile fields exist on databases created before email registration/avatar support.
-- Run after a backup; this migration is additive and intentionally does not mark
-- legacy email accounts as verified without an actual verification event.
SET @sql := IF(
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_user' AND column_name = 'email'),
    'ALTER TABLE t_user ADD COLUMN email VARCHAR(254) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_user' AND column_name = 'nickname'),
    'ALTER TABLE t_user ADD COLUMN nickname VARCHAR(64) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_user' AND column_name = 'avatar_data'),
    'ALTER TABLE t_user ADD COLUMN avatar_data MEDIUMTEXT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_user' AND column_name = 'nickname_updated_at'),
    'ALTER TABLE t_user ADD COLUMN nickname_updated_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_user' AND column_name = 'email_verified'),
    'ALTER TABLE t_user ADD COLUMN email_verified TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE t_user SET nickname = username WHERE (nickname IS NULL OR nickname = '') AND username IS NOT NULL;
