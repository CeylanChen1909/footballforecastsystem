$ErrorActionPreference = 'Stop'
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $projectRoot

Write-Host '[0/6] Secret hygiene'
$trackedEnv = git ls-files -- .env .env.prod
if ($trackedEnv) {
  throw "Secret-bearing environment file is tracked: $trackedEnv"
}
$historicalEnv = git log --all --format= --name-only -- .env .env.prod | Where-Object { $_ -and $_ -in @('.env', '.env.prod') } | Sort-Object -Unique
if ($historicalEnv) {
  throw "Secret-bearing environment file still exists in Git history: $($historicalEnv -join ', '). Rewrite history and rotate credentials before release."
}
if (Get-Command gitleaks -ErrorAction SilentlyContinue) {
  & gitleaks detect --no-banner --redact --source .
  if ($LASTEXITCODE -ne 0) { throw 'Gitleaks detected a secret' }
} else {
  Write-Warning 'gitleaks is not installed; install it in CI for complete secret scanning.'
}

Write-Host '[1/6] Migration integrity'
& .\scripts\migration-check.ps1

Write-Host '[2/6] Python syntax'
python -m py_compile football-ml-service/train.py football-ml-service/app.py

Write-Host '[3/6] Business and gateway tests'
& .\mvnw.cmd -pl football-business-service,football-gateway -am test
if ($LASTEXITCODE -ne 0) { throw 'Maven tests failed' }

Write-Host '[4/6] Frontend build'
Push-Location frontend
try {
  npm run build
  if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
  Write-Host '[5/6] Frontend smoke checks'
  npm test
  if ($LASTEXITCODE -ne 0) { throw 'Frontend smoke checks failed' }
} finally {
  Pop-Location
}

Write-Host 'Quality gate passed.' -ForegroundColor Green
