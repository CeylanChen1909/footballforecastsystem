$ErrorActionPreference = 'Stop'

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$migrationRoot = Join-Path $projectRoot 'sql/migrations'
$files = @(Get-ChildItem -LiteralPath $migrationRoot -Filter 'V*.sql' -File | Sort-Object Name)
if ($files.Count -eq 0) { throw "No versioned SQL migrations found in $migrationRoot" }

$versions = @{}
$previous = $null
foreach ($file in $files) {
  if ($file.Name -notmatch '^V(?<version>[0-9]+)__[A-Za-z0-9][A-Za-z0-9_-]*\.sql$') {
    throw "Invalid migration filename: $($file.Name). Use VYYYYMMDDNN__description.sql."
  }
  $version = [int64]$Matches.version
  if ($versions.ContainsKey($version)) {
    throw "Duplicate migration version ${version}: $($versions[$version]), $($file.Name)"
  }
  $versions[$version] = $file.Name
  if ($null -ne $previous -and $version -le $previous) {
    throw "Migration versions are not strictly ascending: $previous -> $version"
  }
  $previous = $version

  $sql = Get-Content -LiteralPath $file.FullName -Raw
  if ($sql -match '(?im)\bDROP\s+(DATABASE|TABLE)\b|\bTRUNCATE\s+TABLE\b') {
    throw "Destructive DDL is not allowed in an ordinary migration: $($file.Name)"
  }
}

Write-Host "Migration check passed: $($files.Count) unique, non-destructive migrations." -ForegroundColor Green
