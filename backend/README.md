# BuddyStuddy Backend

Python/FastAPI backend for BuddyStuddy study settings, records, grading, statistics source data, and scheduled APNs question delivery.

This backend is the operational source of truth for the iOS app. The app may cache data locally for UI responsiveness, but production reads and writes should go through this PostgreSQL-backed service.

## What It Does

- Stores APNs device tokens.
- Stores per-device study settings and schedule.
- Stores study records, answer drafts, skipped/deleted states, and grading results.
- Stores optional community profiles for Google-signed-in users.
- Stores community question reports and can forward them by email when SMTP is configured.
- Uses database-generated autoincrement `id` primary keys on every backend table.
- Uses SQLAlchemy ORM with repository methods; JPA 감각의 `save`/`insert`/`delete`도 래퍼로 제공됩니다.
- Generates due questions with OpenAI.
- Sends APNs remote notifications to iPhone.
- Runs in Docker with PostgreSQL stored on a mounted volume.

## Runtime Secrets

Set these on the deployment host or deploy workflow. Do not commit them.

- `BACKEND_MASTER_KEY`: base64/random master key used to encrypt stored OpenAI API keys.
- `APNS_AUTH_KEY_BASE64`: base64 encoded Apple APNs `.p8` key.
- `APNS_KEY_ID`: Apple APNs key ID.
- `APNS_TEAM_ID`: Apple Developer Team ID.
- `APNS_BUNDLE_ID`: app bundle ID, currently `io.github.ghkdqhrbals.StudyMate`.
- `APNS_ENV`: fallback APNs environment. Scheduled delivery uses each registered device's `apnsEnvironment`, so one backend can serve both debug `sandbox` tokens and TestFlight/App Store `production` tokens.
- `BACKEND_API_TOKEN`: optional shared token required for admin endpoints if set.
- `DATABASE_URL`: required PostgreSQL connection string.
- `ALLOW_SQLITE_FALLBACK`: optional. Set to `true` only for isolated local tests. Production must not use SQLite.
- `ENABLE_OPENAPI_DOCS`: set `false` in production to hide `/docs`, `/redoc`, and `/openapi.json`.
- `OPENAPI_ACCESS_TOKEN`: required when API docs are enabled on production hosts.
- `GOOGLE_IOS_CLIENT_ID`: Google OAuth iOS client ID. Required for community Google Login.
- `REPORT_EMAIL_TO`: destination Gmail address for community question reports.
- `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`: optional SMTP settings. When omitted, reports are stored in the database only and email signup codes cannot be sent.
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_DB`, `REDIS_SSL`, `REDIS_CLUSTER`: Redis settings for email signup verification-code sessions. Set `REDIS_CLUSTER=true` for Redis Cluster endpoints.
- `EMAIL_VERIFICATION_TTL_SECONDS`: signup code TTL. Production default is `180`.
- `AWS_SECRET_ID`, `AWS_REGION`: optional AWS Secrets Manager source. The backend reads secret keys such as `redisHost`, `redisPort`, `redisPassword`, `smtpHost`, `smtpUsername`, and `smtpPassword`.

The schedule API may store the user's OpenAI API key encrypted at rest. This changes the privacy model: the backend operator becomes responsible for protecting that key.

## Local Run

```sh
cd backend
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

Local runs also need a PostgreSQL `DATABASE_URL`, or explicit `ALLOW_SQLITE_FALLBACK=true` for throwaway tests.

## Docker

```sh
docker build -t buddystuddy-backend ./backend
docker run --rm -p 8080:8080 --env-file .env -v buddystuddy-data:/data buddystuddy-backend
```

For a local PostgreSQL-backed stack:

```sh
cd backend
docker compose up --build
```

## Local Testing (TDD)

```sh
cd backend
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
pytest
```

The tests cover repository behavior and topic-statistic business rules in isolation,
using ephemeral SQLite databases and real ORM models.

`@transactional` decorator is also available for service-style methods. If the wrapped
method accepts a `session` parameter, it gets a SQLAlchemy session injected:

```python
from app.storage import transactional


class RecordService:
    def __init__(self, db: Database):
        self.db = db

    @transactional
    def record_and_answer(self, question_id: str, session):
        session.query(... )...
```

`Database.connect()` is a transaction boundary using an SQLAlchemy session
(commit on normal exit, rollback on exception). For readability in JPA-style code,
you can use `Database.transactional()` as an explicit block.

## API

See [API.md](API.md) for request/response examples.

- `GET /health`
- `POST /api/v1/devices/register`
- `PUT /api/v1/me/push-token`
- `PUT /api/v1/me/schedule`
- `GET /api/v1/me/settings`
- `PUT /api/v1/me/settings`
- `GET /api/v1/me/profile`
- `PATCH /api/v1/me/profile`
- `DELETE /api/v1/me/profile`
- `GET /api/v1/me/api`
- `POST /api/v1/me/api/validate`
- `GET /api/v1/me/snapshot`
- `GET /api/v1/me/stats`
- `POST /api/v1/me/questions`
- `GET /api/v1/me/records`
- `POST /api/v1/me/records/{record_id}/answer`
- `DELETE /api/v1/me/records/{record_id}`
- `POST /api/v1/admin/scheduler/run-once`

Protected endpoints require:

- `Authorization: Bearer <accessToken>`

Device credentials are used only to register a device and bootstrap or refresh `/api/v1/auth/token`.

FastAPI also serves generated API docs at `/docs`, `/redoc`, and `/openapi.json`.

### DB Backups

- Data is persisted with Docker volume `buddystuddy-postgres-data`.
- In deploy workflow, a logical backup is generated on each rollout as:
  `backups/buddystuddy-YYYYMMDDTHHMMSS.dump`.
- Locally, `docker compose` also starts a dedicated backup service (`buddystuddy-db-backups`) that writes
  daily snapshots to that same 14-day retention policy.

Backup artifacts are written to the mounted backup volume:

- `buddystuddy-db-backups` (local compose)
- `backups/` (deploy host)

Backup files older than 14 days are removed automatically.

Example restore command on the deploy host:

```sh
docker run --rm \
  -e PGPASSWORD="<postgres-password>" \
  --network buddystuddy-net \
  -v "<absolute-path-to-backups>:/backups:ro" \
  postgres:16-alpine \
  pg_restore -h buddystuddy-db -U buddystuddy -d buddystuddy /backups/buddystuddy-20260101T000000.dump
```

Client apps should not call OpenAI directly. They should register a backend device, upload settings/API key to this service, and use the question/grading endpoints.
