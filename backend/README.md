# BuddyStuddy Push Backend

Python/FastAPI backend for scheduled APNs question delivery.

This backend is intentionally separate from the local-first app. It exists only for the case where the user wants lock-screen question delivery even after the iOS app is suspended or force-quit.

## What It Does

- Stores APNs device tokens.
- Stores a per-device study schedule.
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
- `APNS_ENV`: `production` for App Store/TestFlight, `sandbox` for debug builds.
- `BACKEND_API_TOKEN`: optional shared token required for admin endpoints if set.
- `DATABASE_URL`: PostgreSQL connection string. If omitted, the backend falls back to local SQLite for development.

The schedule API may store the user's OpenAI API key encrypted at rest. This changes the privacy model: the backend operator becomes responsible for protecting that key.

## Local Run

```sh
cd backend
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

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
- `PUT /v1/devices/{device_id}/schedule`
- `DELETE /v1/devices/{device_id}`
- `POST /v1/admin/scheduler/run-once`

Device schedule updates require:

- `X-Device-Id`
- `X-Client-Secret`

FastAPI also serves generated API docs at `/docs`, `/redoc`, and `/openapi.json`.
