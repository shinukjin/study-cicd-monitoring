param(
  [string]$PrometheusUrl = "http://localhost:9090"
)

$ErrorActionPreference = "Stop"

$expr = "vector(1)"
$query = [System.Web.HttpUtility]::UrlEncode($expr)
$url = "$PrometheusUrl/api/v1/query?query=$query"

Write-Host "Prometheus connectivity test..."
Invoke-RestMethod $url | Out-Null

Write-Host "Connectivity looks good. To test Alertmanager route, add or enable a temporary always-firing alert rule."

