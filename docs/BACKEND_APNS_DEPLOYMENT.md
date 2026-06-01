# BuddyStuddy Backend APNs Deployment

## Context

The iOS app cannot reliably create new questions while force-quit or suspended. A backend is required for true APNs remote push delivery on a schedule.

## Architecture

```mermaid
sequenceDiagram
    participant App as iPhone App
    participant API as Python Backend
    participant OpenAI as OpenAI API
    participant APNs as Apple APNs
    participant Phone as iPhone Lock Screen

    App->>API: Register APNs token
    API-->>App: deviceId + clientSecret
    App->>API: Save schedule + OpenAI key
    loop Scheduler
        API->>API: Find due schedules
        API->>OpenAI: Generate question
        OpenAI-->>API: Question JSON
        API->>APNs: Push alert
        APNs-->>Phone: Notification
    end
```

## Source of Truth

- Local app remains the source of truth for local records unless backend sync is added later.
- Backend is the source of truth for scheduled remote-push registrations.
- Backend stores user OpenAI API keys encrypted at rest if the app sends them.

## Required Secrets

App repository:

- `DEPLOY_REPO_DISPATCH_TOKEN`: GitHub token that can dispatch workflows in `ghkdqhrbals/personal-deploy`.

Deploy repository:

- `EC2_HOST`: current EC2 public DNS, for example `ec2-3-39-42-28.ap-northeast-2.compute.amazonaws.com`
- `EC2_USER`: usually `ubuntu` or `ec2-user`, depending on the AMI.
- `EC2_SSH_PRIVATE_KEY`: private SSH key for the EC2 instance.
- `GHCR_USERNAME`: GitHub username with image pull access.
- `GHCR_TOKEN`: GitHub token with `read:packages`.
- `BACKEND_MASTER_KEY`: random base64 key for encrypting stored OpenAI keys.
- `BACKEND_API_TOKEN`: optional token required by registration/admin endpoints.
- `APNS_AUTH_KEY_BASE64`: base64 encoded Apple APNs `.p8` key.
- `APNS_KEY_ID`: APNs key ID.
- `APNS_TEAM_ID`: Apple Developer Team ID.
- `APNS_BUNDLE_ID`: `io.github.ghkdqhrbals.StudyMate`.
- `APNS_ENV`: `production` for TestFlight/App Store.

The AWS access key is not required for the SSH-based deployment workflow. If an AWS API based deployment is preferred, use a narrow IAM role and rotate any credentials that were pasted into chat.

## Deployment Flow

1. Manually run `Build Backend Image` in this app repository.
2. The workflow builds `backend/Dockerfile`.
3. The image is pushed to GHCR.
4. The workflow sends `repository_dispatch` to `ghkdqhrbals/personal-deploy`.
5. The deploy repository SSHes into EC2, writes `/opt/buddystuddy-backend/.env`, pulls the image, and runs Docker.
6. PostgreSQL runs as a separate Docker container with a persistent named volume.
7. The backend container stays on a private Docker network.
8. Nginx is the only public backend entrypoint and publishes HTTPS on host port `443`.

## Public Network Shape

- Public HTTPS: `https://api.ghkdqhrbals.org -> nginx:443 -> buddystuddy-backend:8080`
- Backend app port `8080` is not published on the EC2 host.
- PostgreSQL port `5432` is published on the EC2 host for production database access.
- The workflow requests/renews a Let's Encrypt certificate with the `tls-alpn-01` challenge, so public port `80` is not required.
- If certificate issuance fails, the workflow can still keep the service reachable with a temporary self-signed certificate, but iOS production traffic should use the trusted certificate path.

Use these connection basics for database administration:

```text
host: api.ghkdqhrbals.org
port: 5432
database: buddystuddy
user: buddystuddy
```

The password is stored on EC2 at `/opt/buddystuddy-backend/.postgres_password`. Keep the generated password private and restrict the EC2 security group if public access is no longer required.

## Data Durability

- PostgreSQL data is stored in the `buddystuddy-postgres-data` Docker volume.
- The previous SQLite volume `buddystuddy-backend-data` is never deleted by the workflow.
- During deployment, the backend image runs `python -m app.migrate_sqlite_to_postgres /legacy/buddystuddy.db` once against the old SQLite volume. Inserts are idempotent, so retrying the workflow does not duplicate rows.
- Containers use `--restart unless-stopped` so backend, Nginx, and PostgreSQL restart after daemon or instance reboot.

Smoke-test the current EC2 deployment with:

```sh
curl -fsS https://api.ghkdqhrbals.org/health
```

## Open Questions

- Whether the app should send each user's OpenAI API key to the backend, or whether the backend should use one server-owned OpenAI API key.
- DNS for `api.ghkdqhrbals.org` must point to the EC2 host for trusted certificate issuance.
- Whether backend records should sync back into the local app history after a notification is tapped.
