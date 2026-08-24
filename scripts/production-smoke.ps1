param(
  [string]$BaseUrl = $(if ($env:APP_BASE_URL) { $env:APP_BASE_URL } else { 'http://127.0.0.1' }),
  [switch]$RequireAuth
)

$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$checks = @(
  @{ Name = 'crawler-public-status'; Path = '/api/crawler/public-status'; Status = 200 },
  @{ Name = 'public-matches'; Path = '/api/crawler/matches/date/' + (Get-Date -Format 'yyyy-MM-dd'); Status = 200 }
)
foreach ($check in $checks) {
  try {
    $response = Invoke-WebRequest -Uri ($base + $check.Path) -Method Get -UseBasicParsing -TimeoutSec 15
    if ([int]$response.StatusCode -ne $check.Status) { throw "status $($response.StatusCode)" }
    Write-Host "PASS $($check.Name)"
  } catch {
    if ($check.Name -eq 'agent-health') { Write-Warning "Agent health is unavailable: $($_.Exception.Message)"; continue }
    throw "FAIL $($check.Name): $($_.Exception.Message)"
  }
}
$agentHeaders = @{}
if ($env:SMOKE_ACCESS_TOKEN) {
  $agentHeaders = @{ Authorization = "Bearer $($env:SMOKE_ACCESS_TOKEN)" }
  try {
    $agent = Invoke-WebRequest -Uri ($base + '/api/agent/health') -Headers $agentHeaders -UseBasicParsing -TimeoutSec 15
    if ([int]$agent.StatusCode -ne 200) { throw "status $($agent.StatusCode)" }
    Write-Host 'PASS agent-health'
  } catch { throw "FAIL agent-health: $($_.Exception.Message)" }
} elseif ($RequireAuth) {
  throw 'SMOKE_ACCESS_TOKEN is required with -RequireAuth.'
} else {
  Write-Warning 'SKIP agent-health: set SMOKE_ACCESS_TOKEN to verify authenticated Agent.'
}
if ($env:SMOKE_ACCESS_TOKEN) {
  $headers = @{ Authorization = "Bearer $($env:SMOKE_ACCESS_TOKEN)" }
  $me = Invoke-WebRequest -Uri ($base + '/api/users/me') -Headers $headers -UseBasicParsing -TimeoutSec 15
  if ([int]$me.StatusCode -ne 200) { throw "Authenticated smoke failed: status $($me.StatusCode)" }
  Write-Host 'PASS authenticated-me'
}
Write-Host 'Production smoke checks passed.' -ForegroundColor Green
