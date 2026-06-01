# Operations Runbook

## Alert: AppDown
- Symptom: `up{job="spring-app"} == 0`
- Check:
  - `docker ps --filter "name=study-app"`
  - `docker logs --tail 200 study-app`
  - `curl -fsS http://localhost:18080/actuator/health`
- First action:
  - `cd /opt/study-cicd-monitoring && docker compose up -d app`
- Rollback:
  - `docker pull ghcr.io/<owner>/study-cicd-monitoring:<previous_sha>`
  - Set `APP_IMAGE` to previous tag and run `docker compose up -d app`

## Alert: HighHttp5xxRate
- Symptom: 5xx rate > threshold for 5m
- Check:
  - Grafana `HTTP RPS`, `HTTP p95 Latency`
  - app logs for recent stack traces
- First action:
  - Identify failing endpoint and dependent DB/service
  - Scale or restart app if needed: `docker compose up -d --scale app=2 app`
- Rollback:
  - Redeploy previous SHA image

## Alert: HighP95Latency
- Symptom: p95 > 1s for 5m
- Check:
  - DB connectivity and slow query logs
  - JVM memory/GC and CPU saturation
- First action:
  - Restart app if deadlock/leak suspected
  - Mitigate heavy traffic route
- Rollback:
  - Redeploy previous SHA image

## Alert: HighHostCpuUsage / HighHostMemoryUsage / LowDiskSpace
- Check:
  - `docker stats --no-stream`
  - `df -h` and `free -h`
  - `docker system df`
- First action:
  - Stop non-critical containers
  - Cleanup images: `docker image prune -af --filter "until=168h"`
- Rollback:
  - Revert recent deployment if resource pressure started immediately after release

## Contacts
- On-call primary: fill in real owner
- On-call backup: fill in real backup
- Incident channel: fill in real chat channel

