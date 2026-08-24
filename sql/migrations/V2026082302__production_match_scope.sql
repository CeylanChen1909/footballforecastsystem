-- Keep the production match table aligned with the eight supported leagues.
-- This is intentionally explicit and reviewable: run scripts/apply-migrations.ps1
-- only after a database backup.  Provider rows are not deleted merely because
-- their source is disabled; source visibility is controlled by CRAWLER_PRIMARY_ONLY.
START TRANSACTION;

DELETE FROM crawler_matches
WHERE NOT (
  (TRIM(COALESCE(league_id, '')) <> '' AND LOWER(TRIM(league_id)) IN (
    '39','140','135','78','61','88','94','40','pl','pd','sa','bl1','fl1','ded','ppl','elc',
    'bbc-premier-league','bbc-spanish-la-liga','bbc-italian-serie-a','bbc-german-bundesliga',
    'bbc-french-ligue-one','bbc-dutch-eredivisie','bbc-portuguese-primeira-liga','bbc-championship'
  ))
  OR
  (TRIM(COALESCE(league_id, '')) = '' AND (
    TRIM(league_name) IN ('英超','西甲','意甲','德甲','法甲','荷甲','葡超','英冠')
    OR LOWER(REPLACE(REPLACE(REPLACE(TRIM(league_name), ' ', ''), '-', ''), '_', '')) IN (
      'premierleague','laliga','primeradivision','seriea','bundesliga','ligue1','eredivisie','primeiraliga','championship'
    )
  ))
);

-- Prediction/detail rows for deleted local matches are not useful and would
-- otherwise keep the UI in a permanent PENDING/UNAVAILABLE state.
DELETE p FROM t_match_prediction p
LEFT JOIN crawler_matches cm
  ON cm.id = p.fixture_id OR (cm.fixture_id IS NOT NULL AND cm.fixture_id = p.fixture_id)
WHERE cm.id IS NULL;

DELETE d FROM t_match_detail_snapshot d
LEFT JOIN crawler_matches cm
  ON cm.id = d.fixture_id OR (cm.fixture_id IS NOT NULL AND cm.fixture_id = d.fixture_id)
WHERE cm.id IS NULL;

COMMIT;
