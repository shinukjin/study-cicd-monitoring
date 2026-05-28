param(
  [string]$BaseUrl = "http://localhost:18080",
  [string]$PrometheusUrl = "http://localhost:19090"
)

$ErrorActionPreference = "Stop"

function Invoke-WithRetry {
  param(
    [Parameter(Mandatory = $true)][string]$Url,
    [int]$Retry = 20,
    [int]$DelaySeconds = 2
  )

  for ($i = 0; $i -lt $Retry; $i++) {
    try {
      return Invoke-RestMethod $Url
    } catch {
      if ($i -eq ($Retry - 1)) {
        throw
      }
      Start-Sleep -Seconds $DelaySeconds
    }
  }
}

Write-Host "Checking application health..."
Invoke-WithRetry -Url "$BaseUrl/actuator/health" | Out-Null

Write-Host "Checking prometheus metrics endpoint..."
Invoke-WithRetry -Url "$BaseUrl/actuator/prometheus" | Out-Null

Write-Host "Checking Prometheus targets..."
$targets = Invoke-WithRetry -Url "$PrometheusUrl/api/v1/targets"
$requiredJobs = @("spring-app", "otel-collector")
$downRequired = @(
  $targets.data.activeTargets |
  Where-Object { $requiredJobs -contains $_.labels.job -and $_.health -ne "up" }
)
if ($downRequired.Count -gt 0) {
  Write-Error "Required Prometheus targets are down."
}

Write-Host "All verification checks passed."
