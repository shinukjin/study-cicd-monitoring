param(
  [Parameter(Mandatory = $true)][string]$GhcrImage,
  [string]$ImageTag = "latest",
  [Parameter(Mandatory = $true)][string]$DatasourcePassword,
  [Parameter(Mandatory = $true)][string]$GrafanaAdminPassword
)

$ErrorActionPreference = "Stop"

$env:APP_IMAGE = "$GhcrImage`:$ImageTag"
$env:SPRING_DATASOURCE_PASSWORD = $DatasourcePassword
$env:GRAFANA_ADMIN_PASSWORD = $GrafanaAdminPassword

Write-Host "Deploying image: $env:APP_IMAGE"
docker compose pull app
docker compose up -d
docker compose ps app
Invoke-RestMethod "http://localhost:18080/actuator/health" | Out-Null
Write-Host "Deployment completed."

