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

## Runtime Layout

- `buddystuddy-nginx`: public HTTPS proxy on host port `443`.
- `buddystuddy-backend`: private FastAPI app on Docker network port `8080`.
- `buddystuddy-db`: private PostgreSQL container on Docker network port `5432`.
- `buddystuddy-postgres-data`: persistent Docker volume for PostgreSQL data.
- `buddystuddy-backend-data`: legacy SQLite volume, kept for migration safety and not deleted.

## Manual Deploy

Run the `Deploy Backend to EC2` workflow and provide the image ref, for example:

```text
ghcr.io/ghkdqhrbals/buddy-studdy-backend:latest
```

## Smoke Test

The workflow uses Let's Encrypt with the `tls-alpn-01` challenge, so only port `443` needs to be public. If certificate issuance fails, a temporary self-signed certificate keeps the service reachable for debugging.

```sh
curl -fsS https://api.ghkdqhrbals.org/health
```

`api.ghkdqhrbals.org` must resolve to the EC2 host for trusted certificate issuance.
