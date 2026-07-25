# BuddyStudy Backend

Spring Boot Kotlin backend for BuddyStudy study settings, records, grading, statistics source data, and scheduled APNs question delivery.

This backend is the operational source of truth for the iOS app. The app may cache data locally for UI responsiveness, but production reads and writes should go through this PostgreSQL-backed service.

## Module Structure

- `domain`: Spring Data Relational entities, domain root objects, common event/domain DTOs.
- `application`: inbound use cases, outbound ports, application services, application response models.
- `infra`: WebFlux/scheduler/stream adapters, R2DBC persistence adapters, OpenAI/APNs/Redis integrations.
- `tutor`: executable Spring Boot root module, bootstrap resources, AWS Secrets environment post processor, integration tests.

## What It Does

- Stores APNs device tokens.
- Stores per-device study settings and schedule.
- Stores study records, answer drafts, skipped/deleted states, and grading results.
- Stores optional community profiles for Google-signed-in users.
- Stores community question reports and can forward them by email when SMTP is configured.
- Uses database-generated autoincrement `id` primary keys on every backend table.
- Uses Spring Data R2DBC with suspending repository/service transaction boundaries.
- Runs Flyway through a startup-only JDBC connection in the `dev` profile.
- Generates due questions with OpenAI.
- Publishes scheduled push jobs through Redis Streams and consumes them with the backend's lightweight polling consumers.
- Sends APNs remote notifications to iPhone from the stream consumer.
- Runs in Docker with PostgreSQL stored on a mounted volume.

## Runtime Secrets

Set these on the deployment host or deploy workflow. Do not commit them.

- `SPRING_PROFILES_ACTIVE`: runtime profile. Use `dev` or `prod`; the default is `dev`.
- `BACKEND_MASTER_KEY`: base64/random master key used to encrypt stored OpenAI API keys.
- `APNS_AUTH_KEY_P8`: raw or base64 encoded Apple APNs `.p8` key.
- `APNS_KEY_ID`: Apple APNs key ID.
- `APNS_TEAM_ID`: Apple Developer Team ID.
- `APNS_BUNDLE_ID`: app bundle ID, currently `io.github.ghkdqhrbals.StudyMate`.
- `APNS_ENV`: fallback APNs environment. Scheduled delivery uses each registered device's `apnsEnvironment`, so one backend can serve both debug `sandbox` tokens and TestFlight/App Store `production` tokens.
- `BACKEND_API_TOKEN`: optional shared token required for admin endpoints if set.
- `R2DBC_DATABASE_URL`: required runtime PostgreSQL R2DBC connection string, for example `r2dbc:postgresql://db:5432/buddystudy`.
- `DATABASE_URL`: Flyway startup JDBC connection string, for example `jdbc:postgresql://db:5432/buddystudy`.
- `DATABASE_USERNAME`, `DATABASE_PASSWORD`: PostgreSQL credentials.
- `ENABLE_OPENAPI_DOCS`: set `false` in production to hide `/docs`, `/redoc`, and `/openapi.json`.
- `OPENAPI_ACCESS_TOKEN`: required when API docs are enabled on production hosts.
- `GOOGLE_IOS_CLIENT_ID`: Google OAuth iOS client ID. Required for community Google Login.
- `REPORT_EMAIL_TO`: destination Gmail address for community question reports.
- `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`: Google SMTP settings. `SMTP_HOST` defaults to `smtp.gmail.com` and `SMTP_PORT` defaults to `587`; store the Gmail address and Google app password as `SMTP_USERNAME` and `SMTP_PASSWORD` in the active AWS Secrets Manager application secret. When credentials are omitted, reports are stored in the database only and email signup codes cannot be sent.
- `PROFILE_PHOTO_DIRECTORY`, `PROFILE_PHOTO_PUBLIC_BASE_URL`: profile photo storage directory and public backend origin. Production mounts the persistent `buddystudy-profile-photos` volume at `/app/profile-photos`.
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL`: Redis settings used by Redis Streams and email verification sessions.
- `EMAIL_VERIFICATION_TTL_SECONDS`: signup code TTL. Production default is `180`.
- `AWS_SECRET_ID`, `AWS_REGION`: optional AWS Secrets Manager config import. The default secret name is `buddystudy/dev` for the `dev` profile and `buddystudy/prod` for the `prod` profile. Store keys using the same names as environment placeholders, for example `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `BACKEND_MASTER_KEY`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `SMTP_HOST`, `SMTP_USERNAME`, and `SMTP_PASSWORD`.
  Spring property keys are also supported by Spring Cloud AWS, for example `spring.r2dbc.url`, `spring.r2dbc.username`, `spring.r2dbc.password`, and the separate `spring.flyway.*` keys. Keep runtime R2DBC and Flyway JDBC URLs in their respective formats.

The schedule API may store the user's OpenAI API key encrypted at rest. This changes the privacy model: the backend operator becomes responsible for protecting that key.

## Local Run

```sh
cd backend
docker compose up --build
```

Local runs use PostgreSQL from `docker-compose.yml` and `SPRING_PROFILES_ACTIVE=dev`.

For iPhone testing against a backend running on this Mac, see [Local Backend Tunnel](../docs/LOCAL_BACKEND_TUNNEL.md).

## Runtime Profiles

- `dev`: development defaults, Flyway enabled by default, scheduler/stream enabled, API docs enabled.
- `prod`: production deployment defaults, Flyway disabled unless `FLYWAY_ENABLED=true`, scheduler/stream enabled, API docs disabled unless explicitly enabled.

## Docker

```sh
docker build -t buddystudy-backend ./backend
docker run --rm -p 8080:8080 --env-file .env -v buddystudy-data:/data buddystudy-backend
```

For a local PostgreSQL-backed stack:

```sh
cd backend
docker compose up --build
```

## Local Testing (TDD)

```sh
cd backend
docker run --rm -v "$PWD:/workspace" -w /workspace gradle:8.14.2-jdk24-alpine gradle --no-daemon test :tutor:bootJar
```

The tests cover Spring context startup, coroutine services, R2DBC persistence, PostgreSQL-specific SQL through Testcontainers, and core service behavior.

## API

See [API.md](API.md) for request/response examples.

- `GET /health`
- `POST /api/v1/devices/register`
- `PUT /api/v1/push-token`
- `GET /api/v1/settings`
- `PUT /api/v1/settings`
- `POST /api/v1/test/push`
- `GET /api/v1/studies/{study_id}/settings`
- `PUT /api/v1/studies/{study_id}/settings`
- `GET /api/v1/profile`
- `PATCH /api/v1/profile`
- `DELETE /api/v1/profile`
- `GET /api/v1/api`
- `POST /api/v1/api/validate`
- `GET /api/v1/studies`
- `GET /api/v1/stats`
- `POST /api/v1/questions`
- `GET /api/v1/records`
- `POST /api/v1/records/{record_id}/answer`
- `DELETE /api/v1/records/{record_id}`
- `POST /api/v1/admin/scheduler/run-once`

Protected endpoints require:

- `Authorization: Bearer <accessToken>`

Device credentials are used only to register a device and bootstrap or refresh `/api/v1/auth/token`.

Spring Boot Actuator serves lightweight health checks at `/health` and
`/api/v1/health`. Runtime uptime monitoring must not run from GitHub Actions;
GitHub Actions is only for build, deploy dispatch, and deploy-result watching.
External uptime monitoring should use the Cloudflare Worker in
`deploy/cloudflare-health-monitor`, which checks `/api/v1/health/readiness`
and sends Slack alerts. The readiness endpoint checks required backend dependencies and
core scheduler freshness, and returns `503` when the backend process is alive
but not ready to serve traffic. The readiness response includes `checkedAt`,
`service`, `environment`, and component-level `checks` so Slack alerts can
show the failing component. Scheduler readiness is based on the most recent
successful run for each monitored job, so repeated failed runs do not mask a
stale scheduler. It also includes structured `details` such as `missingJobs`,
`disabledJobs`, `failedJobs`, `stuckJobs`, `staleJobs`, `thresholdSeconds`,
and `startupGraceSeconds` for external monitors and runbooks. Failed and stuck
job details include `latestRunId` so Slack alerts can be traced to the matching
admin scheduler run. Scheduler freshness is controlled by
`MONITORING_SCHEDULER_READINESS_ENABLED`,
`MONITORING_SCHEDULER_STALE_THRESHOLD_MINUTES`,
`MONITORING_SCHEDULER_STARTUP_GRACE_MINUTES`, and
`MONITORING_SCHEDULER_MONITORED_JOBS`. Backend scheduler failure Slack
delivery is bounded by `MONITORING_SLACK_TIMEOUT_MS` so a slow webhook does
not hold scheduler failure handling indefinitely. Repeated failure alerts for
the same job are throttled by `MONITORING_SCHEDULER_FAILURE_ALERT_REPEAT_SECONDS`
and default to five minutes. Set
`MONITORING_ADMIN_BASE_URL` to the HTTPS admin frontend origin so scheduler
Slack alerts include a direct link to the matching scheduler run list. In the
`prod` profile, `SLACK_WEBHOOK_URL` and a valid HTTPS
`MONITORING_ADMIN_BASE_URL` are required when
`buddystudy.scheduler.enabled=true`; the application fails fast instead of
silently disabling scheduler failure alerts or sending alerts without useful
run links.
Production startup also verifies that every registered `ManagedJob` is listed
in `MONITORING_SCHEDULER_MONITORED_JOBS` and that the list does not contain
unknown job names. `MONITORING_SLACK_TIMEOUT_MS` must stay within `1000..25000`,
`MONITORING_SCHEDULER_STALE_THRESHOLD_MINUTES` within `1..60`, and
`MONITORING_SCHEDULER_STARTUP_GRACE_MINUTES` within `0..60` so alert delivery
and scheduler freshness checks cannot be delayed by a bad production setting.
When adding a new scheduled job, add its job name to that variable and the
Kubernetes backend config in the same change, or production startup will fail
before the job can run unmonitored.

Kubernetes readiness probes should use `/api/v1/health/dependencies`.
Dependency readiness checks database and Redis only, so a stale scheduler sends
external alerts without removing otherwise healthy API pods from service.

### DB Backups

- Data is persisted with Docker volume `buddystudy-postgres-data`.
- In deploy workflow, a logical backup is generated on each rollout as:
  `backups/buddystudy-YYYYMMDDTHHMMSS.dump`.
- Locally, `docker compose` also starts a dedicated backup service (`buddystudy-db-backups`) that writes
  daily states to that same 14-day retention policy.

Backup artifacts are written to the mounted backup volume:

- `buddystudy-db-backups` (local compose)
- `backups/` (deploy host)

Backup files older than 14 days are removed automatically.

Example restore command on the deploy host:

```sh
docker run --rm \
  -e PGPASSWORD="<postgres-password>" \
  --network buddystudy-net \
  -v "<absolute-path-to-backups>:/backups:ro" \
  postgres:16-alpine \
  pg_restore -h buddystudy-db -U buddystudy -d buddystudy /backups/buddystudy-20260101T000000.dump
```

Client apps should not call OpenAI directly. They should register a backend device, upload settings/API key to this service, and use the question/grading endpoints.

## Database Migrations

The `dev` profile starts with Flyway enabled by default. The `prod` profile keeps Flyway disabled unless `FLYWAY_ENABLED=true`. Runtime access uses `spring.r2dbc.*`; Flyway uses its separate startup JDBC configuration.
Migration files live under `tutor/src/main/resources/db/migration`.

If a running database was deployed before user-level OpenAI settings, apply the equivalent patch manually:

```sql
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS openai_api_key_cipher text;
```
