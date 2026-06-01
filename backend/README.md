# BuddyStuddy Backend

Python/FastAPI backend for BuddyStuddy study settings, records, grading, statistics source data, and scheduled APNs question delivery.

This backend is the operational source of truth for the iOS app. The app may cache data locally for UI responsiveness, but production reads and writes should go through this PostgreSQL-backed service.

## What It Does

- Stores APNs device tokens.
- Stores per-device study settings and schedule.
- Stores study records, answer drafts, skipped/deleted states, and grading results.
- Uses database-generated autoincrement `id` primary keys on every backend table.
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

## API

See [API.md](API.md) for request/response examples.

- `GET /health`
- `POST /v1/devices/register`
- `PUT /v1/devices/{device_id}/push-token`
- `PUT /v1/devices/{device_id}/schedule`
- `GET /v1/devices/{device_id}/settings`
- `PUT /v1/devices/{device_id}/settings`
- `GET /v1/devices/{device_id}/api`
- `POST /v1/devices/{device_id}/api/validate`
- `GET /v1/devices/{device_id}/snapshot`
- `GET /v1/devices/{device_id}/stats`
- `POST /v1/devices/{device_id}/questions`
- `GET /v1/devices/{device_id}/records`
- `POST /v1/devices/{device_id}/records/{record_id}/answer`
- `DELETE /v1/devices/{device_id}/records/{record_id}`
- `POST /v1/admin/scheduler/run-once`

Device schedule updates require:

- `X-Device-Id`
- `X-Client-Secret`

FastAPI also serves generated API docs at `/docs`, `/redoc`, and `/openapi.json`.

Client apps should not call OpenAI directly. They should register a backend device, upload settings/API key to this service, and use the question/grading endpoints.
