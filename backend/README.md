# BuddyStudy Backend

Spring Boot Kotlin backend for BuddyStudy study settings, records, grading, statistics source data, and scheduled APNs question delivery.

This backend is the operational source of truth for the iOS app. The app may cache data locally for UI responsiveness, but production reads and writes should go through this MySQL-backed service.

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
- Exposes an authenticated, stateless MCP server for private learning context, studies, questions, grading, and topic statistics when enabled.
- Uses database-generated autoincrement `id` primary keys for aggregate and event tables; strict one-to-one state tables may use their owner key.
- Uses Spring Data R2DBC with suspending repository/service transaction boundaries.
- Runs Flyway through a startup-only JDBC connection in the `dev` profile.
- Generates due questions with OpenAI.
- Publishes question push jobs from the durable outbox to the dedicated `notification.question-push.requested.v1` Redis Stream and consumes them through `@StreamListener`.
- Sends APNs remote notifications to iPhone from the stream consumer.
- Runs in Docker with MySQL stored on a mounted volume.
- Persists Redis with AOF (`appendfsync everysec`) and compressed, checksummed
  RDB snapshots in the mounted `/data` volume.

## Runtime Secrets

Set these on the deployment host or deploy workflow. Do not commit them.

- `SPRING_PROFILES_ACTIVE`: runtime profile. Use `dev` for local MySQL and Redis plus the OpenAI and SMTP settings from AWS Secrets Manager, `dev-aws` when development must import the entire AWS secret, or `prod`; the default is `dev`.
- `BACKEND_MASTER_KEY`: base64/random master key used to encrypt stored OpenAI API keys.
- `APNS_AUTH_KEY_P8`, `APNS_AUTH_KEY_BASE64`: raw or Base64-encoded Apple APNs `.p8` key.
- `APNS_KEY_ID`: Apple APNs key ID.
- `APNS_TEAM_ID`: Apple Developer Team ID.
- `APNS_BUNDLE_ID`: app bundle ID, currently `io.github.ghkdqhrbals.StudyMate`.
- `APNS_ENV`: fallback APNs environment. Scheduled delivery uses each registered device's `apnsEnvironment`, so one backend can serve both debug `sandbox` tokens and TestFlight/App Store `production` tokens.
- `FIREBASE_PROJECT_ID`: Firebase project whose Remote Config template owns the iOS app-control parameter.
- `FIREBASE_SERVICE_ACCOUNT_JSON_BASE64`: Base64-encoded Firebase service-account JSON. Grant only Remote Config template read/update access; never expose it to the iOS app or browser admin.
- `FIREBASE_REMOTE_CONFIG_PARAMETER_KEY`: backend-owned Remote Config parameter. Defaults to `ios_app_control_v1`.
- `BACKEND_API_TOKEN`: optional shared token required for admin endpoints if set.
- `R2DBC_DATABASE_URL`: required runtime MySQL R2DBC connection string, for example `r2dbc:mysql://db:3306/buddystudy`.
- `DATABASE_URL`: Flyway startup JDBC connection string, for example `jdbc:mysql://db:3306/buddystudy`.
- `DATABASE_USERNAME`, `DATABASE_PASSWORD`: MySQL credentials.
- `ENABLE_OPENAPI_DOCS`: set `false` in production to hide `/docs`, `/redoc`, and `/openapi.json`.
- `MCP_SERVER_ENABLED`: enables `POST /api/v1/mcp`; defaults to `false` in every profile and must be opted into explicitly.
- `MCP_ALLOWED_HOSTS`, `MCP_ALLOWED_ORIGINS`: comma-separated MCP transport Host and browser Origin allowlists. See [MCP server](../docs/MCP_SERVER.md).
- `MCP_REQUEST_TIMEOUT_SECONDS`: MCP tool/resource timeout, clamped to 5–120 seconds; defaults to `30`.
- `OPENAPI_ACCESS_TOKEN`: required when API docs are enabled on production hosts.
- `GOOGLE_IOS_CLIENT_ID`: Google OAuth iOS client ID. Required for community Google Login.
- `REPORT_EMAIL_TO`: destination Gmail address for community question reports.
- `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`: Google SMTP settings. `SMTP_HOST` defaults to `smtp.gmail.com` and `SMTP_PORT` defaults to `587`; store the Gmail address and Google app password as `SMTP_USERNAME` and `SMTP_PASSWORD` in the active AWS Secrets Manager application secret. When credentials are omitted, reports are stored in the database only and email signup codes cannot be sent.
- `PROFILE_PHOTO_DIRECTORY`, `PROFILE_PHOTO_PUBLIC_BASE_URL`: legacy profile-photo storage retained temporarily so existing files can be removed when an account switches to a pixel avatar or is deleted. New uploads are disabled.
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL`: Redis settings used by Redis Streams and email verification sessions.
- `BUDDYSTUDY_*_STREAM_KEY`: physical Redis Stream keys. Active keys follow `<business-domain>.<data-type>.<event-type>.<version>` and each event contract has one stream.
- `BUDDYSTUDY_*_STREAM_MAX_LEN`: independent exact `MAXLEN` limits for each event stream. Active streams default to `1000`.
- `BUDDYSTUDY_STREAMS_ENABLED`: global Redis Stream listener switch. Keep it enabled in normal local and production runtimes; disabling it pauses generation, grading, translation, push, notification, account-withdrawal, and community-event consumers together.
- `EMAIL_VERIFICATION_TTL_SECONDS`: signup code TTL. Production default is `180`.
- `OPENAI_API_KEY_SYSTEM`: system-workload key used only for post-study child-topic suggestions.
- `OPENAI_API_KEY_USER`: user-content workload key used for question generation, embeddings, translation, answer feedback, and grading. It must be a different OpenAI key from `OPENAI_API_KEY_SYSTEM`. `OPENAI_USER_CONTENT_API_KEY` and `OPENAI_API_KEY` remain compatibility fallbacks for this value only and never supply the system client; `OPENAI_SYSTEM_API_KEY` remains a compatibility fallback for the system value.
- `AWS_SECRET_ID`, `AWS_REGION`: optional AWS Secrets Manager config import. Local `dev` imports `buddystudy/dev` with a `local-secret.` prefix and maps `OPENAI_API_KEY_USER`, `OPENAI_API_KEY_SYSTEM`, their compatibility fallbacks, SMTP, APNs, RevenueCat, and Firebase Remote Config values, so database and Redis values in that secret cannot override local services. Explicit non-empty environment variables override the corresponding AWS values. Use the `dev-aws` profile to import the entire development secret; `prod` imports `buddystudy/prod`. Store APNs as `APNS_AUTH_KEY_BASE64`, `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_BUNDLE_ID`, and `APNS_ENV`; store RevenueCat server verification credentials as `REVENUECAT_PROJECT_ID`, `REVENUECAT_APP_ID`, and `REVENUECAT_SERVER_API_KEY`; store Firebase publication credentials as `FIREBASE_PROJECT_ID` and `FIREBASE_SERVICE_ACCOUNT_JSON_BASE64`. Other keys use the same names as environment placeholders, for example `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `BACKEND_MASTER_KEY`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `OPENAI_API_KEY_USER`, `OPENAI_API_KEY_SYSTEM`, `SMTP_HOST`, `SMTP_USERNAME`, and `SMTP_PASSWORD`.
  Spring property keys are also supported by Spring Cloud AWS, for example `spring.r2dbc.url`, `spring.r2dbc.username`, `spring.r2dbc.password`, and the separate `spring.flyway.*` keys. Keep runtime R2DBC and Flyway JDBC URLs in their respective formats.

The settings API may retain a user's OpenAI API key encrypted at rest for backward compatibility. Backend system and user-content workloads do not route through that stored key.

## Local Run

```sh
cd backend
docker compose up --build
```

Local runs use MySQL from `docker-compose.yml` and `SPRING_PROFILES_ACTIVE=dev`.
The default backend runtime is GraalVM Native Image. Select the JVM without
changing the application or infrastructure configuration:

```sh
BACKEND_RUNTIME=jvm docker compose up --build
```

For iPhone testing against a backend running on this Mac, see [Local Backend Tunnel](../docs/LOCAL_BACKEND_TUNNEL.md).

## Runtime Profiles

- `dev`: development defaults, Flyway enabled by default, scheduler/stream enabled, API docs enabled.
- `prod`: production deployment defaults, Flyway disabled unless `FLYWAY_ENABLED=true`, scheduler/stream enabled, API docs disabled unless explicitly enabled.

## Docker

```sh
# GraalVM Native Image, the default
docker build \
  --build-arg BACKEND_RUNTIME=native \
  -t buddystudy-backend:native-local \
  ./backend

# Regular JVM executable jar
docker build \
  --build-arg BACKEND_RUNTIME=jvm \
  -t buddystudy-backend:jvm-local \
  ./backend
```

The helper script provides the same interface and can start the full local
MySQL/Redis stack:

```sh
cd backend
./scripts/backend-runtime.sh native build
./scripts/backend-runtime.sh jvm build
./scripts/backend-runtime.sh jvm up -d
./scripts/backend-runtime.sh jvm down
```

Both images:

- expose port `8080`;
- run as the non-root `app` user;
- use the same Spring profiles, secrets, R2DBC, Redis, and Flyway variables;
- include MySQL migrations at `/app/db/migration-mysql`;
- report the actual runtime through the existing runtime metrics collector;
- carry the `io.buddystudy.backend.runtime=native|jvm` image label.

The JVM artifact is built with Temurin JDK 25, starts
`buddystudy-backend.jar` on Temurin JRE 25, and accepts normal JVM tuning
through `JAVA_TOOL_OPTIONS`. Its container default caps the heap at 50% of
available memory and exits on an out-of-memory error; deployment configuration
may replace those options. The native artifact is built with GraalVM 25 and
starts the compiled `buddystudy-backend` executable. Its build heap is capped
at 12 GiB; allocate at least 14 GiB to the native build environment. Runtime
selection is a build concern; API and deployment configuration must not branch
on it.

The `Build Backend Image` GitHub workflow exposes the same `backend_runtime`
choice. Tag-triggered deployments remain `native` by default. A manually
selected JVM build is pushed and deployed through a runtime-qualified immutable
reference such as `<commit>-jvm`; native uses `<commit>-native`. The unqualified
tag remains a compatibility alias for the most recently built variant. The
deploy workflow verifies the runtime label before rollout.

## Local Testing (TDD)

```sh
cd backend
docker run --rm -v "$PWD:/workspace" -w /workspace gradle:8.14.2-jdk24-alpine gradle --no-daemon test :tutor:bootJar
```

The tests cover Spring context startup, coroutine services, R2DBC persistence, MySQL-specific SQL through Testcontainers, and core service behavior.

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
Grafana owns production alert delivery. The readiness endpoint checks required backend dependencies and
core scheduler freshness, and returns `503` when the backend process is alive
but not ready to serve traffic. The readiness response includes `checkedAt`,
`service`, `environment`, and component-level `checks` so Grafana alerts can
show the failing component. Scheduler readiness is based on the most recent
successful run for each monitored job, so repeated failed runs do not mask a
stale scheduler. It also includes structured `details` such as `missingJobs`,
`disabledJobs`, `failedJobs`, `stuckJobs`, `staleJobs`, `thresholdSeconds`,
and `startupGraceSeconds` for external monitors and runbooks. Failed and stuck
job details include `latestRunId` so Grafana incidents can be traced to the
matching admin scheduler run. Scheduler freshness is controlled by
`MONITORING_SCHEDULER_READINESS_ENABLED`,
`MONITORING_SCHEDULER_STALE_THRESHOLD_MINUTES`,
`MONITORING_SCHEDULER_STARTUP_GRACE_MINUTES`, and
`MONITORING_SCHEDULER_MONITORED_JOBS`. A failed scheduler run emits one
`scheduled_job_failed` ERROR with the full throwable and safe run identifiers.
Promtail joins that stack into one Loki event, and Grafana detects the ERROR
and sends the Slack notification. The backend application never calls Slack.
Production startup also verifies that every registered `ManagedJob` is listed
in `MONITORING_SCHEDULER_MONITORED_JOBS` and that the list does not contain
unknown job names. `MONITORING_SCHEDULER_STALE_THRESHOLD_MINUTES` must stay
within `1..60`, and `MONITORING_SCHEDULER_STARTUP_GRACE_MINUTES` within
`0..60` so scheduler freshness evaluation cannot be distorted by a bad
production setting.
When adding a new scheduled job, add its job name to that variable and the
Kubernetes backend config in the same change, or production startup will fail
before the job can run unmonitored.

Kubernetes readiness probes should use `/api/v1/health/dependencies`.
Dependency readiness checks database and Redis only, so a stale scheduler sends
external alerts without removing otherwise healthy API pods from service.

### DB Backups

- Data is persisted with Docker volume `buddystudy-mysql-data`.
- Local `docker compose` starts a dedicated backup service that writes
  `buddystudy-YYYYMMDDTHHMMSS.sql.gz` snapshots with a 14-day retention policy.
- Production must use a scheduled `mysqldump --single-transaction` job or a
  provider-managed snapshot policy before rollout.

Backup artifacts are written to the mounted backup volume:

- `buddystudy-db-backups` (local compose)
- `backups/` (deploy host)

Local backup files older than 14 days are removed automatically.

Example restore command:

```sh
gunzip -c /absolute/path/buddystudy-20260101T000000Z.sql.gz | docker run --rm -i \
  -e MYSQL_PWD="<mysql-password>" \
  --network buddystudy-net \
  mysql:8.4 \
  mysql -h buddystudy-db -u buddystudy buddystudy
```

Client apps should not call OpenAI directly. They should register a backend device, upload settings/API key to this service, and use the question/grading endpoints.

## Database Migrations

The `dev` profile starts with Flyway enabled by default. The `prod` profile keeps Flyway disabled unless `FLYWAY_ENABLED=true`. Runtime access uses `spring.r2dbc.*`; Flyway uses its separate startup JDBC configuration.
MySQL migration files live under `tutor/src/main/resources/db/migration-mysql`.

If a running database was deployed before user-level OpenAI settings, apply the equivalent patch manually:

```sql
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS openai_api_key_cipher text;
```
