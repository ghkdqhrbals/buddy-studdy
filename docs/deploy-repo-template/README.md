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

Monitoring deploy:

- `GRAFANA_ADMIN_PASSWORD`

## Runtime Layout

- `buddystuddy-nginx`: public HTTPS proxy on host port `443`.
- `buddystuddy-backend-a`: blue slot for Spring Boot app on Docker network port `8080`.
- `buddystuddy-backend-b`: green slot for Spring Boot app on Docker network port `8080`.
- `rsc-coordinator`: Redis Stream Coordinator on Docker network port `8080`, deployed from a GHCR native-image artifact.
- `buddystuddy-db`: private PostgreSQL container on Docker network port `5432`.
- `buddystuddy-postgres-data`: persistent Docker volume for PostgreSQL data.
- `buddystuddy-backend-data`: legacy SQLite volume, kept for historical safety and not deleted.
- `backups/`: local host directory (`/opt/buddystuddy-backend/backups`) where `pg_dump` files are written before each deploy.
- `buddystuddy-postgres-data` retains live DB data across restarts and redeploys.
- Nginx proxies `/health`, `/api/v1/health`, and `/api/v1/*` to the BuddyStudy Spring Boot app.
- If `COORDINATOR_BACKEND_URL` is configured, Nginx also serves `https://coordinator.ghkdqhrbals.org/*` and proxies it to that backend URL.
- If `COORDINATOR_BACKEND_URL` is configured, `https://api.ghkdqhrbals.org/coord/*` redirects to the coordinator hostname to keep coordinator traffic out of BuddyStudy backend logs.
- Other paths return 404 at Nginx.

## Monitoring Deploy

Monitoring is deployed separately from backend image rollout. Copy
`deploy-monitoring.yml` into the deploy repository's `.github/workflows/`
directory and run **Deploy BuddyStudy Monitoring** manually.

Monitoring is PLG only: Promtail, Loki, and Grafana. Prometheus and Redis
exporter containers are explicitly removed by the workflow so they do not
consume memory or disk I/O on the small EC2 host.

The workflow creates or replaces:

- `rsc-loki`: Loki with persistent `rsc-loki-data` volume.
- `rsc-promtail`: Promtail scraping Docker container logs.
- `rsc-grafana`: Grafana with persistent `rsc-grafana-data` volume.

Grafana dashboard provisioning is file-based, so dashboards are restored on
container recreation:

- `BuddyStudy Log Search`
- `BuddyStudy API Performance`

The workflow downloads dashboard JSON from this repository's
`docs/observability/` directory and mounts them into Grafana provisioning. This
keeps Grafana UI state from being the source of truth for log dashboards.

## Manual Deploy

Run the `Deploy BuddyStudy Backend` workflow and provide the backend image ref,
for example:

```text
ghcr.io/ghkdqhrbals/buddy-studdy-backend:latest
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

1. New image starts on the inactive slot (`buddystuddy-backend-a` or `...-b`).
2. New slot health is validated with `/health`.
3. Certificate checks are refreshed, and both old/new slots can coexist briefly.
4. Traffic is switched to the new slot, then the old slot is drained and removed with graceful stop.

Only one scheduler leader is active during overlap windows. PostgreSQL advisory lock is used so only one running backend instance processes scheduled question dispatch at a time.

## Smoke Test

The workflow uses Let's Encrypt with the `tls-alpn-01` challenge, so only port `443` needs to be public. If certificate issuance fails, a temporary self-signed certificate keeps the service reachable for debugging.

```sh
curl -fsS https://api.ghkdqhrbals.org/health
```

`api.ghkdqhrbals.org` must resolve to the EC2 host for trusted certificate issuance.

## Backup restore (deploy host)

```sh
docker run --rm \
  -e PGPASSWORD="<postgres-password>" \
  --network buddystuddy-net \
  -v "/opt/buddystuddy-backend/backups:/backups:ro" \
  postgres:16-alpine \
  pg_restore -h buddystuddy-db -U buddystuddy -d buddystuddy /backups/buddystuddy-<timestamp>.dump
```
