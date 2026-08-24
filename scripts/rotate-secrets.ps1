param(
  [string]$OutputPath = '.env.next',
  [switch]$RewriteHistory,
  [switch]$Confirm
)

$ErrorActionPreference = 'Stop'
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $projectRoot

function New-Secret([int]$bytes = 32) {
  $buffer = New-Object byte[] $bytes
  [System.Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
  return [Convert]::ToBase64String($buffer)
}

if ($RewriteHistory) {
  if (-not $Confirm) { throw '历史重写会改写所有提交并强制重新推送；请在确认影响后同时传入 -Confirm。' }
  if (-not (Get-Command git-filter-repo -ErrorAction SilentlyContinue)) { throw '需要先安装 git-filter-repo；脚本不会使用危险的 git filter-branch 兜底。' }
  & git-filter-repo --path .env --path .env.prod --invert-paths
  if ($LASTEXITCODE -ne 0) { throw '历史重写失败。' }
  Write-Warning '历史已重写；请强制推送前让所有协作者重新克隆，并立即轮换第三方 API/SMTP 密钥。'
}

$lines = @(
  '# Generated locally. Do not commit.',
  "JWT_SECRET=$((New-Secret 48))",
  "ML_INTERNAL_TOKEN=$((New-Secret 48))",
  "MYSQL_ROOT_PASSWORD=$((New-Secret 24))",
  "MYSQL_PASSWORD=$((New-Secret 24))",
  "REDIS_PASSWORD=$((New-Secret 24))",
  'APP_RUNTIME_DDL_ENABLED=false',
  'APP_SCHEMA_REQUIRE_MIGRATIONS=true'
)
$target = Join-Path $projectRoot $OutputPath
if (Test-Path -LiteralPath $target) { throw "Refusing to overwrite existing secret file: $target" }
[System.IO.File]::WriteAllLines($target, $lines, [System.Text.UTF8Encoding]::new($false))
Write-Host "Generated new local secret template: $target" -ForegroundColor Green
Write-Warning '这只生成 JWT/内部/数据库密钥；API、SMTP、SCNet/OpenRouter 密钥仍需在各供应商后台手动轮换。'
