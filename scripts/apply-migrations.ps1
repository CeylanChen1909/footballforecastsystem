param(
  [switch]$DryRun,
  [switch]$AllowDestructive
)

$ErrorActionPreference = 'Stop'
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $projectRoot
& (Join-Path $PSScriptRoot 'migration-check.ps1')

if (-not (Get-Command mysql -ErrorAction SilentlyContinue)) { throw 'mysql client is required.' }
$dbHost = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' }
$dbPort = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { '3306' }
$dbName = if ($env:MYSQL_DB) { $env:MYSQL_DB } else { 'football_forecast' }
$dbUser = if ($env:MYSQL_USER) { $env:MYSQL_USER } else { 'root' }
if (-not $env:MYSQL_PASSWORD) { throw 'MYSQL_PASSWORD must be set; refusing to prompt or print credentials.' }

$env:MYSQL_PWD = $env:MYSQL_PASSWORD
try {
  $bootstrap = @"
CREATE TABLE IF NOT EXISTS schema_migrations (
  version VARCHAR(32) NOT NULL PRIMARY KEY,
  description VARCHAR(255) NOT NULL,
  checksum CHAR(64) NOT NULL,
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  applied_by VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"@
  $bootstrap | & mysql --host=$dbHost --port=$dbPort --user=$dbUser --database=$dbName
  if ($LASTEXITCODE -ne 0) { throw 'Failed to bootstrap schema_migrations.' }

  $files = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'sql/migrations') -Filter 'V*.sql' -File | Sort-Object Name
  foreach ($file in $files) {
    $version = [regex]::Match($file.Name, '^V([0-9]+)__').Groups[1].Value
    $description = [regex]::Match($file.Name, '^V[0-9]+__(.+)\.sql$').Groups[1].Value
    $checksum = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $escapedVersion = $version.Replace("'", "''")
    $existing = & mysql --host=$dbHost --port=$dbPort --user=$dbUser --database=$dbName --batch --skip-column-names --execute="SELECT checksum FROM schema_migrations WHERE version='$escapedVersion' LIMIT 1"
    if ($LASTEXITCODE -ne 0) { throw "Failed to read migration state for $($file.Name)." }
    $existing = ($existing | Out-String).Trim()
    if ($existing) {
      if ($existing -ne $checksum) { throw "Checksum mismatch for applied migration $($file.Name)." }
      Write-Host "SKIP $($file.Name)"
      continue
    }
    $sql = Get-Content -LiteralPath $file.FullName -Raw
    if (-not $AllowDestructive -and $sql -match '(?im)\bDROP\s+(DATABASE|TABLE)\b|\bTRUNCATE\s+TABLE\b|\bDELETE\s+') {
      throw "Destructive migration blocked: $($file.Name). Use -AllowDestructive only after a backup."
    }
    if ($DryRun) { Write-Host "WOULD APPLY $($file.Name)"; continue }
    Write-Host "APPLY $($file.Name)"
    $sql | & mysql --host=$dbHost --port=$dbPort --user=$dbUser --database=$dbName
    if ($LASTEXITCODE -ne 0) { throw "Migration failed: $($file.Name)" }
    $safeDescription = $description.Replace("'", "''")
    $safeUser = if ($env:USERNAME) { $env:USERNAME.Replace("'", "''") } else { 'release' }
    & mysql --host=$dbHost --port=$dbPort --user=$dbUser --database=$dbName --execute="INSERT INTO schema_migrations(version,description,checksum,applied_by) VALUES('$escapedVersion','$safeDescription','$checksum','$safeUser')"
    if ($LASTEXITCODE -ne 0) { throw "Failed to record migration: $($file.Name)" }
  }
} finally {
  Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
Write-Host 'Migration run completed.' -ForegroundColor Green
