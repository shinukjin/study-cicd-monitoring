param(
  [string]$BaseUrl = "http://localhost:8080",
  [string]$PrometheusUrl = "http://localhost:9090"
)

$ErrorActionPreference = "Stop"

Write-Host "Checking application health..."
Invoke-RestMethod "$BaseUrl/actuator/health" | Out-Null

Write-Host "Checking prometheus metrics endpoint..."
Invoke-RestMethod "$BaseUrl/actuator/prometheus" | Out-Null

Write-Host "Checking Prometheus targets..."
$targets = Invoke-RestMethod "$PrometheusUrl/api/v1/targets"
$down = @($targets.data.activeTargets | Where-Object { $_.health -ne "up" })
if ($down.Count -gt 0) {
  Write-Error "Some Prometheus targets are down."
}

Write-Host "All verification checks passed."

