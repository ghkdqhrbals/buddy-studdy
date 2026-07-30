# BuddyStudy Deploy

Deployment repository for BuddyStudy runtime modules.

This repo is triggered by `repository_dispatch` from the app repository after
module Docker images are published to GHCR.

The public API domain is `https://api.ghkdqhrbals.org`.

## Deployment Modules

Deployments are module-scoped. Backend, admin frontend, monitoring, and health
monitor changes must be deployed through separate workflows/jobs. Start with
`deployment-modules.md` before adding or changing deploy workflows.

Current workflow templates:

- `deploy-backend.yml`: backend API runtime on EC2 with one compact,
  emoji-free Slack result attachment.
- `configure-backend-network.yml`: Redis administrator ingress on the backend
  EC2 security group.
- `deploy-admin-frontend.yml`: admin frontend runtime on EC2.
- `notify-deployment-status.yml`: centralized Slack status receiver for
  one compact iOS release summary and concise threaded progress replies. Set
  `DEPLOY_SLACK_BOT_TOKEN` and `DEPLOY_SLACK_CHANNEL_ID`; the incoming webhook
  remains a parent-summary fallback.
- `deploy-macbookair-monitoring.yml`: API Logs dashboard, Grafana, and Loki on
  MacBook Air.
- `deploy-testzone.yml`: TestZone k6 execution service and InfluxDB on MacBook
  Air.
- `deploy-monitoring.yml`: legacy EC2-local monitoring fallback only.

## Required Secrets

Backend deploy:

- `GHCR_USERNAME`
- `GHCR_TOKEN`
- `BACKEND_MASTER_KEY`
- `BACKEND_API_TOKEN`
- `OPENAPI_ACCESS_TOKEN` (optional, only if docs API endpoint is enabled)
- `GOOGLE_IOS_CLIENT_ID`

Backend application values are stored in AWS Secrets Manager secret
`buddystudy/prod`. Required APNs keys are `APNS_AUTH_KEY_BASE64`,
`APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_BUNDLE_ID`, and `APNS_ENV`. The deploy
workflow reads and validates them before writing the backend environment file;
do not duplicate them as GitHub Actions Secrets.

EC2 log forwarding to MacBook Air Loki:

- `REMOTE_LOKI_BASIC_AUTH_USER` (optional, only if the Loki endpoint is protected with basic auth)
- `REMOTE_LOKI_BASIC_AUTH_PASSWORD` (optional, only if the Loki endpoint is protected with basic auth)

MacBook Air monitoring deploy:

- `GRAFANA_ADMIN_PASSWORD`
- `API_DASHBOARD_BASIC_AUTH_HTPASSWD`

MacBook Air TestZone deploy:

- `GHCR_USERNAME`
- `GHCR_TOKEN`

InfluxDB and component credentials are generated once on the MacBook Air with
mode `0600`, under
`MACBOOKAIR_TESTZONE_ROOT`, and are reused by later TestZone and monitoring
deploys.

Repository variables:

- `REMOTE_LOKI_PUSH_URL`: remote Loki push endpoint consumed by the EC2 promtail sender, for example `http://100.79.59.22:3100/loki/api/v1/push` over Tailscale or `https://loki.lowfidev.cloud/loki/api/v1/push` when protected by a tunnel.
- `MACBOOKAIR_MONITORING_ROOT`: persistent host path for MacBook Air PLG data, defaults to `$HOME/data/buddystudy/monitoring`.
- `GRAFANA_PORT`: MacBook Air Grafana host port, defaults to `3000`.
- `LOKI_PORT`: MacBook Air Loki host port, defaults to `3100`.
- `MACBOOKAIR_TESTZONE_ROOT`: persistent TestZone and InfluxDB path.
- `TESTZONE_INFLUX_ORG` and `TESTZONE_INFLUX_BUCKET`: Grafana and runner
  storage coordinates.

## Runtime Layout

- `buddystudy-nginx`: public HTTPS proxy on host port `443`.
- `buddystudy-backend-a`: blue slot for Spring Boot app on Docker network port `8080`.
- `buddystudy-backend-b`: green slot for Spring Boot app on Docker network port `8080`.
- `buddystudy-db`: MySQL on Docker network port `3306`, published to host port
  `3306` for approved administrator CIDRs.
- `buddystudy-redis`: password-protected Redis on Docker network port `6379`,
  published to host port `6379` for the same approved administrator CIDRs.
- `buddystudy-mysql-data`: persistent Docker volume for MySQL data.
- `buddystudy-redis-data`: persistent Docker volume for Redis AOF/RDB data.
- `buddystudy-backend-data`: legacy SQLite volume, kept for historical safety and not deleted.
- `buddystudy-promtail`: lightweight EC2 log sender. It scrapes Docker logs and forwards them to the MacBook Air Loki endpoint when `REMOTE_LOKI_PUSH_URL` is set.
- `buddystudy-mysql-data` retains live DB data across restarts and redeploys.
- Nginx proxies `/health`, `/api/v1/health`, and `/api/v1/*` to the BuddyStudy Spring Boot app.
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
runs-on: [self-hosted, Linux, ARM64, ec2]
```

Use an ARM instance such as `t4g.medium` when backend, MySQL, Redis, Nginx, and
Promtail share the host.

## Admin Frontend Deploy

The admin frontend is deployed separately from the backend. Copy
`deploy-admin-frontend.yml` into the deploy repository's `.github/workflows/`
directory. The app repository's `Build Admin Frontend Image` workflow dispatches
`admin-frontend-image-published` and waits for **Deploy BuddyStudy Admin
Frontend**.

The admin deploy workflow owns only the `buddystudy-admin-frontend` container.
It must not rebuild backend, recreate MySQL, recreate Loki/Grafana, or run
runtime health checks.

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

- `buddystudy-api-dashboard`: API Logs dashboard reverse proxy with Basic Auth.
- `buddystudy-loki`: Loki with persistent host data under
  `$HOME/data/buddystudy/monitoring/loki/data` by default.
- `buddystudy-grafana`: Grafana with persistent host data under
  `$HOME/data/buddystudy/monitoring/grafana/data` by default.

The separate TestZone workflow creates or replaces:

- `buddystudy-testzone-service`: bounded k6 runner and JavaScript workspace API.
- `buddystudy-testzone-influxdb`: 30-day TestZone time-series storage.
- approved disposable MySQL, Redis, or Kafka containers only when a user
  deploys them from TestZone.

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

The backend image must be built on a GitHub-hosted runner and pushed to GHCR
before this workflow runs. The self-hosted EC2 runner only pulls the image and
runs containers; it must not compile backend code or build Docker images.

The deploy process uses a blue/green rolling pattern:

1. New image starts on the inactive slot (`buddystudy-backend-a` or `...-b`).
2. GitHub Actions validates only deploy mechanics: the new container process
   does not immediately exit, and Nginx configuration is valid. It must not
   call backend health or readiness endpoints, inspect Docker `Health.Status`,
   or call the Health Monitor Worker `/check` endpoint.
3. Certificate checks are refreshed, and both old/new slots can coexist briefly.
4. Traffic is switched to the new slot, then the old slot is drained and removed with graceful stop.

Only one scheduler leader is active during overlap windows. MySQL advisory lock is used so only one running backend instance processes scheduled question dispatch at a time.

The workflow uses Let's Encrypt with the `tls-alpn-01` challenge, so only port `443` needs to be public. If certificate issuance fails, a temporary self-signed certificate keeps the service reachable for debugging.

GitHub Actions must not call backend `/health` or readiness endpoints, must not inspect Docker `Health.Status`, must not use indirect container health gates such as `docker compose up --wait` or `docker compose wait`, and must not call the Health Monitor Worker `/check` endpoint. Runtime server-down alerts are handled by Grafana alerting. The Cloudflare Worker remains available for explicit diagnostics, but its production Cron check is disabled.

Backend scheduler failures are emitted as `ERROR` logs with the throwable and
run identifiers. Promtail stores the complete stack as one Loki event, and
Grafana alone sends the Slack notification. The backend application does not
receive `SLACK_WEBHOOK_URL`. The template passes
`MONITORING_SCHEDULER_READINESS_ENABLED`,
`MONITORING_SCHEDULER_STALE_THRESHOLD_MINUTES`,
`MONITORING_SCHEDULER_STARTUP_GRACE_MINUTES`, and
`MONITORING_SCHEDULER_MONITORED_JOBS` into the backend so Docker deployments
use the same scheduler readiness policy as Kubernetes. Only frequent jobs belong
in this 15-minute readiness list. Daily correction jobs remain visible through
their ERROR logs and must not make readiness stale between scheduled runs.
Grafana alerting owns continuous server-down detection. The Cloudflare Worker
scheduled check is disabled in production to avoid periodic KV writes.

Slack uses separate app webhooks for separate sender identities:

- `GRAFANA_SLACK_WEBHOOK_URL` belongs to the Grafana Slack app, whose app name
  and icon are configured as Grafana.
- `DEPLOY_SLACK_WEBHOOK_URL` belongs to the BuddyStudy Deploy Slack app, whose
  app name and icon are configured for deployments.
- `SLACK_WEBHOOK_URL` remains a temporary fallback for both workflows while the
  dedicated app webhooks are being provisioned. Slack app name and icon are
  properties of the app behind an Incoming Webhook, so a single webhook cannot
  reliably present two different sender identities.

`api.ghkdqhrbals.org` must resolve to the EC2 host for trusted certificate issuance.

## Backup restore

```sh
gunzip -c /absolute/path/buddystudy-<timestamp>.sql.gz | docker run --rm -i \
  -e MYSQL_PWD="<mysql-password>" \
  --network buddystudy-net \
  mysql:8.4 \
  mysql -h buddystudy-db -u buddystudy buddystudy
```

The PostgreSQL-to-MySQL cutover is not an in-place container replacement.
Follow [`MYSQL_MIGRATION.md`](../MYSQL_MIGRATION.md), retain the old
PostgreSQL volume for rollback, and update `buddystudy/prod/mysql` only after
row-count and API validation.
