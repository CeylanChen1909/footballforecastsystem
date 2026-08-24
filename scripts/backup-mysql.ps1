param(
  [string]$OutputDirectory = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')) 'backups')
)

$ErrorActionPreference = 'Stop'
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

foreach ($name in @('MYSQL_HOST','MYSQL_PORT','MYSQL_DB','MYSQL_USER','MYSQL_PASSWORD')) {
  if (-not [Environment]::GetEnvironmentVariable($name)) { throw "$name must be configured before backup" }
}
if (-not (Get-Command mysqldump -ErrorAction SilentlyContinue)) {
  throw 'mysqldump is required; install MySQL client tools on the backup host'
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$target = Join-Path $OutputDirectory "football_forecast-$stamp.sql"
$env:MYSQL_PWD = [Environment]::GetEnvironmentVariable('MYSQL_PASSWORD')
try {
  & mysqldump --host=$env:MYSQL_HOST --port=$env:MYSQL_PORT --user=$env:MYSQL_USER `
    --single-transaction --quick --routines --events --triggers --hex-blob `
    --set-gtid-purged=OFF --databases $env:MYSQL_DB | Out-File -FilePath $target -Encoding utf8
  if ($LASTEXITCODE -ne 0) { throw "mysqldump failed with exit code $LASTEXITCODE" }
  if ((Get-Item $target).Length -lt 1024) { throw 'backup file is unexpectedly small' }
  Write-Host "Backup written to $target"
} finally {
  Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}

