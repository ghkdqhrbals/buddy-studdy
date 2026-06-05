# BuddyStuddy Deploy

Deployment repository for the BuddyStuddy Python push backend.

This repo is triggered by `repository_dispatch` from the app repository after a backend Docker image is published to GHCR.

The public API domain is `https://api.ghkdqhrbals.org`.

## Required Secrets

- `EC2_HOST`
- `EC2_USER`
- `EC2_SSH_PRIVATE_KEY`
- `GHCR_USERNAME`
- `GHCR_TOKEN`
- `BACKEND_MASTER_KEY`
- `BACKEND_API_TOKEN`
- `APNS_AUTH_KEY_BASE64`
- `APNS_KEY_ID`
- `APNS_TEAM_ID`
- `APNS_BUNDLE_ID`
- `APNS_ENV`
- `OPENAPI_ACCESS_TOKEN` (optional, only if docs API endpoint is enabled)
- `GOOGLE_IOS_CLIENT_ID`

## Runtime Layout

- `buddystuddy-nginx`: public HTTPS proxy on host port `443`.
- `buddystuddy-backend-a`: blue slot for FastAPI app on Docker network port `8080`.
- `buddystuddy-backend-b`: green slot for FastAPI app on Docker network port `8080`.
- `buddystuddy-db`: private PostgreSQL container on Docker network port `5432`.
- `buddystuddy-postgres-data`: persistent Docker volume for PostgreSQL data.
- `buddystuddy-backend-data`: legacy SQLite volume, kept for migration safety and not deleted.
- `backups/`: local host directory (`/opt/buddystuddy-backend/backups`) where `pg_dump` files are written before each deploy.
- `buddystuddy-postgres-data` retains live DB data across restarts and redeploys.
- Nginx proxies `/health`, `/api/v1/health`, and `/api/v1/*` to the BuddyStuddy FastAPI app.
- If `COORDINATOR_BACKEND_URL` is configured, Nginx also serves `https://coordinator.ghkdqhrbals.org/*` and proxies it to that backend URL.
- If `COORDINATOR_BACKEND_URL` is configured, `https://api.ghkdqhrbals.org/coord/*` redirects to the coordinator hostname to keep coordinator traffic out of BuddyStuddy backend logs.
- Other paths return 404 at Nginx.

## Manual Deploy

Run the `Deploy Backend to EC2` workflow and provide the image ref, for example:

```text
ghcr.io/ghkdqhrbals/buddy-studdy-backend:latest
```

Optional coordinator route:

```text
COORDINATOR_BACKEND_URL=http://redis-coordinator:8080
```

`coordinator.ghkdqhrbals.org` must resolve to the same EC2 host before deployment so Let's Encrypt can issue a certificate that includes both API and coordinator hostnames.

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
