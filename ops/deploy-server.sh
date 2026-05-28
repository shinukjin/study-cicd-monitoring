#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${GHCR_IMAGE:-}" ]]; then
  echo "GHCR_IMAGE is required. Example: ghcr.io/<owner>/study-cicd-monitoring"
  exit 1
fi

if [[ -z "${SPRING_DATASOURCE_PASSWORD:-}" ]]; then
  echo "SPRING_DATASOURCE_PASSWORD is required."
  exit 1
fi

if [[ -z "${GRAFANA_ADMIN_PASSWORD:-}" ]]; then
  echo "GRAFANA_ADMIN_PASSWORD is required."
  exit 1
fi

export APP_IMAGE="${GHCR_IMAGE}:${IMAGE_TAG:-latest}"

echo "Deploying image: ${APP_IMAGE}"
docker compose pull app
docker compose up -d
docker compose ps app
curl -fsS http://localhost:18080/actuator/health
echo "Deployment completed."

