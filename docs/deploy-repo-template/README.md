# BuddyStudy Deploy

Deployment repository for the BuddyStudy Spring Boot Kotlin push backend.

This repo is triggered by `repository_dispatch` from the app repository after a backend Docker image is published to GHCR.

The public API domain is `https://api.ghkdqhrbals.org`.

## Required Secrets

Backend deploy:

- `GHCR_USERNAME`
- `GHCR_TOKEN`
- `BACKEND_MASTER_KEY`
- `BACKEND_API_TOKEN`
- `REDIS_STREAM_COORDINATOR_PASSWORD`
- `APNS_AUTH_KEY_BASE64`
- `APNS_KEY_ID`
- `APNS_TEAM_ID`
- `APNS_BUNDLE_ID`
- `APNS_ENV`
- `OPENAPI_ACCESS_TOKEN` (optional, only if docs API endpoint is enabled)
- `GOOGLE_IOS_CLIENT_ID`

EC2 log forwarding to MacBook Air Loki:

- `REMOTE_LOKI_BASIC_AUTH_USER` (optional, only if the Loki endpoint is protected with basic auth)
- `REMOTE_LOKI_BASIC_AUTH_PASSWORD` (optional, only if the Loki endpoint is protected with basic auth)

MacBook Air monitoring deploy:

- `GRAFANA_ADMIN_PASSWORD`

Repository variables:

- `REMOTE_LOKI_PUSH_URL`: remote Loki push endpoint consumed by the EC2 promtail sender, for example `http://100.79.59.22:3100/loki/api/v1/push` over Tailscale or `https://loki.lowfidev.cloud/loki/api/v1/push` when protected by a tunnel.
- `MACBOOKAIR_MONITORING_ROOT`: persistent host path for MacBook Air PLG data, defaults to `$HOME/data/buddystudy/monitoring`.
- `GRAFANA_PORT`: MacBook Air Grafana host port, defaults to `3000`.
- `LOKI_PORT`: MacBook Air Loki host port, defaults to `3100`.

## Runtime Layout

- `buddystudy-nginx`: public HTTPS proxy on host port `443`.
- `buddystudy-backend-a`: blue slot for Spring Boot app on Docker network port `8080`.
- `buddystudy-backend-b`: green slot for Spring Boot app on Docker network port `8080`.
- `rsc-coordinator`: Redis Stream Coordinator on Docker network port `8080`, deployed from a GHCR native-image artifact.
- `buddystudy-db`: private PostgreSQL container on Docker network port `5432`.
- `buddystudy-postgres-data`: persistent Docker volume for PostgreSQL data.
- `buddystudy-backend-data`: legacy SQLite volume, kept for historical safety and not deleted.
- `buddystudy-promtail`: lightweight EC2 log sender. It scrapes Docker logs and forwards them to the MacBook Air Loki endpoint when `REMOTE_LOKI_PUSH_URL` is set.
- `backups/`: local host directory (`/opt/buddystudy-backend/backups`) where `pg_dump` files are written before each deploy.
- `buddystudy-postgres-data` retains live DB data across restarts and redeploys.
- Nginx proxies `/health`, `/api/v1/health`, and `/api/v1/*` to the BuddyStudy Spring Boot app.
- If `COORDINATOR_BACKEND_URL` is configured, Nginx also serves `https://coordinator.ghkdqhrbals.org/*` and proxies it to that backend URL.
- If `COORDINATOR_BACKEND_URL` is configured, `https://api.ghkdqhrbals.org/coord/*` redirects to the coordinator hostname to keep coordinator traffic out of BuddyStudy backend logs.
- Other paths return 404 at Nginx.

## EC2 Runner Bootstrap

Use `ec2-user-data-self-hosted-runner.sh` as the EC2 launch template user data.
Replace these placeholders before launching the instance:

```text
__GITHUB_OWNER__
__GITHUB_REPO__
__GITHUB_PAT__
```

The script installs Docker and a GitHub Actions self-hosted runner under
`/opt/actions-runner`, then registers this systemd unit:

```text
buddystudy-github-runner.service
```

The service starts automatically on every EC2 reboot. The EC2 runner is
deploy-only. It must pull GHCR images and run containers, but must not build
backend code or Docker images.

The backend workflow expects the EC2 runner to match:

```yaml
runs-on: [self-hosted, Linux, ARM64, ec2, rsc-deploy]
```

Use an ARM instance such as `t4g.medium` when backend, Postgres, Redis,
coordinator, nginx, and promtail share the host.

## Monitoring Deploy

Monitoring is deployed separately from backend image rollout and runs on the
MacBook Air, not on EC2. Copy `deploy-macbookair-monitoring.yml` into the
deploy repository's `.github/workflows/` directory and run
**Deploy BuddyStudy Monitoring on MacBook Air** manually.

The MacBook Air runner must have labels:

```yaml
runs-on: [self-hosted, macOS, ARM64, macbook-air, monitoring]
```

The MacBook Air workflow creates or replaces:

- `buddystudy-loki`: Loki with persistent host data under
  `$HOME/data/buddystudy/monitoring/loki/data` by default.
- `buddystudy-grafana`: Grafana with persistent host data under
  `$HOME/data/buddystudy/monitoring/grafana/data` by default.

EC2 does not run Loki or Grafana. It runs only `buddystudy-promtail` when
`REMOTE_LOKI_PUSH_URL` is configured.

Prometheus and Redis exporter containers are not part of this production
monitoring profile.

The legacy EC2-local monitoring workflow `deploy-monitoring.yml` is kept only
as a fallback template. Prefer `deploy-macbookair-monitoring.yml` for the
current cost-saving EC2 layout.

Grafana dashboard provisioning is file-based, so dashboards are restored on
container recreation:

- `BuddyStudy Log Search`
- `BuddyStudy API Performance`

The MacBook Air workflow downloads dashboard JSON from this repository's
`docs/observability/` directory and mounts them into Grafana provisioning. This
keeps Grafana UI state from being the source of truth for log dashboards.

Recommended flow:

1. Run **Deploy BuddyStudy Monitoring on MacBook Air**.
2. Confirm MacBook Air Loki is reachable from EC2.
3. Set `REMOTE_LOKI_PUSH_URL` in the deploy repository variables.
4. Run **Deploy BuddyStudy Backend**. This starts or refreshes EC2 promtail.

## Manual Deploy

Run the `Deploy BuddyStudy Backend` workflow and provide the backend image ref,
for example:

```text
ghcr.io/ghkdqhrbals/buddystudy-backend:latest
```

Optionally override the Redis Stream Coordinator native image ref:

```text
ghcr.io/ghkdqhrbals/redis-stream-coordinator/coordinator:native-latest
```

Backend and coordinator container images must be built on GitHub-hosted
runners and pushed to GHCR before this workflow runs. The self-hosted EC2
runner must only pull those images and run containers; it must not compile
backend code or build Docker images.

`coordinator.ghkdqhrbals.org` must resolve to the same EC2 host before
deployment so Let's Encrypt can issue a certificate that includes both API and
coordinator hostnames.

The deploy process uses a blue/green rolling pattern:

1. New image starts on the inactive slot (`buddystudy-backend-a` or `...-b`).
2. GitHub Actions validates only deploy mechanics: the new container process
   does not immediately exit, and Nginx configuration is valid. It must not
   call backend health or readiness endpoints, inspect Docker `Health.Status`,
   or call the Health Monitor Worker `/check` endpoint.
3. Certificate checks are refreshed, and both old/new slots can coexist briefly.
4. Traffic is switched to the new slot, then the old slot is drained and removed with graceful stop.

Only one scheduler leader is active during overlap windows. PostgreSQL advisory lock is used so only one running backend instance processes scheduled question dispatch at a time.

The workflow uses Let's Encrypt with the `tls-alpn-01` challenge, so only port `443` needs to be public. If certificate issuance fails, a temporary self-signed certificate keeps the service reachable for debugging.

GitHub Actions must not call backend `/health` or readiness endpoints, must not inspect Docker `Health.Status`, must not use indirect container health gates such as `docker compose up --wait` or `docker compose wait`, and must not call the Health Monitor Worker `/check` endpoint. Runtime server-down alerts are handled by the Cloudflare Worker in `deploy/cloudflare-health-monitor`, which checks the public readiness endpoint from Cloudflare Cron and sends Slack alerts.

Backend scheduler failure alerts are separate from server-down alerts. Set the
deploy repository secret `SLACK_WEBHOOK_URL` when the backend should send Slack
messages for failed managed scheduler jobs. Set the deploy repository variable
`MONITORING_ADMIN_BASE_URL` when the admin frontend origin differs from
`https://api.ghkdqhrbals.org/admin`; scheduler alerts use it to link directly
to the matching run list. Repeated failed-run alerts for the same scheduler job
are throttled by `MONITORING_SCHEDULER_FAILURE_ALERT_REPEAT_SECONDS`, which
defaults to `300`. The template also passes `MONITORING_SLACK_TIMEOUT_MS`,
`MONITORING_SCHEDULER_READINESS_ENABLED`,
`MONITORING_SCHEDULER_STALE_THRESHOLD_MINUTES`,
`MONITORING_SCHEDULER_STARTUP_GRACE_MINUTES`, and
`MONITORING_SCHEDULER_MONITORED_JOBS` into the backend so Docker deployments
use the same scheduler readiness policy as Kubernetes. The Cloudflare Worker uses
`HEALTH_MONITOR_SLACK_WEBHOOK_URL` and remains the only runtime server-down
checker.

The Docker backend template also passes the internal Redis Stream Coordinator
URL to the backend as `REACTION_STREAM_COORDINATOR_BASE_URL` and enables
coordinator readiness with `MONITORING_COORDINATOR_READINESS_ENABLED=true`.
By default both backend traffic and readiness use `http://rsc-coordinator:8080`;
override `REACTION_STREAM_COORDINATOR_BASE_URL` or
`MONITORING_COORDINATOR_BASE_URL` only when the coordinator runs under a
different internal name.

`api.ghkdqhrbals.org` must resolve to the EC2 host for trusted certificate issuance.

## Backup restore (deploy host)

```sh
docker run --rm \
  -e PGPASSWORD="<postgres-password>" \
  --network buddystudy-net \
  -v "/opt/buddystudy-backend/backups:/backups:ro" \
  postgres:16-alpine \
  pg_restore -h buddystudy-db -U buddystudy -d buddystudy /backups/buddystudy-<timestamp>.dump
```
