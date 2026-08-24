$ErrorActionPreference = 'Stop'

if (-not (Get-Command mysql -ErrorAction SilentlyContinue)) {
  throw 'mysql client is required for schema preflight.'
}
$dbHost = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' }
$dbPort = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { '3306' }
$dbName = if ($env:MYSQL_DB) { $env:MYSQL_DB } else { 'football_forecast' }
$dbUser = if ($env:MYSQL_USER) { $env:MYSQL_USER } else { 'root' }
if (-not $env:MYSQL_PASSWORD) { throw 'MYSQL_PASSWORD must be set; refusing to prompt or print credentials.' }

$env:MYSQL_PWD = $env:MYSQL_PASSWORD
try {
  $sql = @"
SELECT 'crawler_matches' AS table_name, COUNT(*) AS rows_count FROM crawler_matches
UNION ALL SELECT 't_match_prediction', COUNT(*) FROM t_match_prediction
UNION ALL SELECT 't_prediction', COUNT(*) FROM t_prediction
UNION ALL SELECT 't_user', COUNT(*) FROM t_user;
SELECT source, COUNT(*) AS rows_count FROM crawler_matches GROUP BY source ORDER BY rows_count DESC;
SELECT source, external_match_id, COUNT(*) AS duplicates
FROM crawler_matches
WHERE external_match_id IS NOT NULL AND external_match_id <> ''
GROUP BY source, external_match_id HAVING COUNT(*) > 1 LIMIT 50;
SELECT fixture_id, COUNT(*) AS duplicates
FROM crawler_matches
WHERE fixture_id IS NOT NULL
GROUP BY fixture_id HAVING COUNT(*) > 1 LIMIT 50;
"@
  & mysql --host=$dbHost --port=$dbPort --user=$dbUser --database=$dbName --batch --raw --skip-column-names --execute=$sql
  if ($LASTEXITCODE -ne 0) { throw 'Schema preflight query failed.' }
} finally {
  Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
