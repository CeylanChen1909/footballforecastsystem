-- Additive column for multi-group cup standings (UEFA Nations League).
-- Safe to re-run: ignore duplicate-column errors when applying manually.
ALTER TABLE crawler_standings
  ADD COLUMN group_label VARCHAR(64) NULL AFTER team_logo;
