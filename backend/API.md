# BuddyStudy Backend API

The backend is the source of truth for iOS study settings, scheduled question delivery, records, answer drafts, and grading results. It is a Spring Boot Kotlin service backed by PostgreSQL and Spring Data JPA.

## Base URL

Production EC2 deployment:

```text
https://api.ghkdqhrbals.org
```

The EC2 workflow serves this domain through Nginx on public port `443`.

## Authentication

The admin endpoint uses the backend token when `BACKEND_API_TOKEN` is configured:

```http
Authorization: Bearer <BACKEND_API_TOKEN>
```

Access tokens include both `user_id` and `device_id`. Protected endpoints use the token principal instead of a `device_id` path parameter:

```http
Authorization: Bearer <accessToken>
```

Bootstrap or refresh an access token with the credentials returned during registration:

```http
POST /api/v1/auth/token
X-Device-Id: <deviceId>
X-Client-Secret: <clientSecret>
```

Public question listing is readable without login. Profile editing, reports, records, statistics, study details, and private device data require `Authorization: Bearer <accessToken>`. Google Login links a Google account to that device identity.

## Endpoints

### Health

```http
GET /health
```

Response:

```json
{
  "ok": true
}
```

`/health` is intentionally lightweight for container and load-balancer probes.
Runtime uptime monitoring must not run from GitHub Actions. Use the
Cloudflare Worker in `deploy/cloudflare-health-monitor` for scheduled external
checks and Slack alerts. That Worker should call readiness:

```http
GET /api/v1/health/readiness
```

It returns `200` when required dependencies are reachable and core scheduler
jobs have recent successful runs, otherwise `503` with component-level check
results. Scheduler checks include structured `details` so external monitors
and Slack alerts can show missing jobs, disabled jobs, stale jobs, and
configured thresholds without parsing free-form text. A scheduler run that
remains `RUNNING` past its configured `timeoutSeconds` is reported as a stuck
job.

Kubernetes readiness probes should use dependency readiness instead:

```http
GET /api/v1/health/dependencies
```

That endpoint checks only hard serving dependencies such as database and Redis.
It intentionally excludes scheduler freshness so a stale background job alerts
operators without removing otherwise healthy API pods from service.

Example readiness response:

```json
{
  "ok": false,
  "checkedAt": "2026-07-03T04:30:00Z",
  "service": "BuddyStudy backend",
  "environment": "production",
  "checks": {
    "database": { "ok": true },
    "redis": { "ok": true },
    "scheduler": {
      "ok": false,
      "message": "Stuck scheduler jobs: question-schedule runningFor=600s timeout=300s",
      "details": {
        "monitoredJobs": [
          "question-schedule",
          "question-push-outbox-dispatch",
          "user-stats-refresh",
          "admin-analytics-recent",
          "admin-analytics-correction"
        ],
        "thresholdSeconds": 900,
        "startupGraceSeconds": 900,
        "stuckJobs": [
          {
            "jobName": "question-schedule",
            "latestRunId": 42,
            "latestStartedAt": "2026-07-03T04:20:00Z",
            "latestStatus": "RUNNING",
            "timeoutSeconds": 300,
            "runningForSeconds": 600
          }
        ]
      }
    }
  }
}
```

Admin users can inspect scheduler freshness and the latest run for each
monitored job:

```http
GET /api/v1/admin/jobs/statuses
Authorization: Bearer <adminToken>
```

Response:

```json
{
  "jobs": [
    {
      "jobName": "question-schedule",
      "enabled": true,
      "scheduleType": "FIXED_DELAY",
      "scheduleValue": "30s",
      "latestRun": null,
      "stale": true,
      "staleThresholdMinutes": 15,
      "timeoutSeconds": 300,
      "stuck": false
    }
  ]
}
```

### Register Device

```http
POST /api/v1/devices/register
Content-Type: application/json
```

Request:

```json
{
  "apnsToken": "apns-device-token-or-empty-string",
  "platform": "ios",
  "apnsEnvironment": "production",
  "language": "ko",
  "timezone": "Asia/Seoul"
}
```

Fields:

- `apnsToken`: optional APNs device token. Send an empty string when the app needs a backend identity before notification registration completes.
- `platform`: client platform. Current app sends `ios`.
- `apnsEnvironment`: `production` for TestFlight/App Store, `sandbox` for debug builds.
- `language`: `ko` or `en`.
- `timezone`: IANA timezone name used for schedule calculations.

Response:

```json
{
  "deviceId": "generated-device-id",
  "clientSecret": "generated-client-secret",
  "accessToken": "jwt-access-token",
  "accessTokenExpiresAt": "2026-06-01T12:00:00+00:00"
}
```

The app must store the device credentials locally because the backend does not return the client secret again. The app should use `accessToken` for protected API calls and refresh it through `/api/v1/auth/token` when it expires.

### Update Push Token

```http
PUT /api/v1/push-token
Content-Type: application/json
Authorization: Bearer <accessToken>
```

Request:

```json
{
  "apnsToken": "apns-device-token",
  "apnsEnvironment": "production"
}
```

Use this after iOS returns an APNs token for an already registered backend device. This preserves the same backend identity instead of creating a second device.

### Send Test Push

```http
POST /api/v1/test/push
Content-Type: application/json
Authorization: Bearer <accessToken>
```

Request body is optional. When omitted, the backend sends a default BuddyStudy test notification to the authenticated device's saved APNs token.

```json
{
  "title": "BuddyStudy",
  "body": "Test push",
  "topic": "Test",
  "recordId": "test",
  "sound": "default",
  "deepLink": "buddystudy://test-push"
}
```

Response:

```json
{
  "sent": true,
  "provider": "APNS",
  "deviceId": "generated-device-id",
  "topic": "Test",
  "recordId": "test"
}
```

### Login And Profile

```http
POST /api/v1/auth/google
Content-Type: application/json
Authorization: Bearer <accessToken>
```

Request:

```json
{
  "idToken": "google-id-token"
}
```

The backend verifies the ID token against `GOOGLE_IOS_CLIENT_ID`, then links the Google identity to the device.

Tester email/password login is also supported. New email accounts must verify a 6-digit signup code first.

```http
POST /api/v1/auth/email/code
Content-Type: application/json
Authorization: Bearer <accessToken>
```

Request:

```json
{
  "email": "tester@example.com"
}
```

Response:

```json
{
  "email": "tester@example.com",
  "expiresInSeconds": 180
}
```

The code is stored in Redis with a 180-second TTL and is sent through Gmail SMTP when SMTP settings are configured.

```http
POST /api/v1/auth/email
Content-Type: application/json
Authorization: Bearer <accessToken>
```

Request:

```json
{
  "email": "tester@example.com",
  "password": "secret123",
  "verificationCode": "123456"
}
```

If the email already exists, `verificationCode` can be omitted. If it does not exist, the backend verifies the code and creates an active `EMAIL` user. Passwords are stored only as SHA-256 hashes.

Response:

```json
{
  "profile": {
    "id": 1,
    "displayName": "Buddy",
    "bio": "",
    "avatarUrl": null,
    "avatarSymbolName": "pixel-buddy",
    "avatarColorSeed": "avatar-color-mint",
    "pageAccess": {
      "publicQuestions": true,
      "statistics": false,
      "studyDetail": false,
      "records": false
    }
  },
  "accessToken": "jwt",
  "accessTokenExpiresAt": "2026-09-05T00:00:00+00:00"
}
```

Profile endpoints:

```http
GET /api/v1/profile
PATCH /api/v1/profile
DELETE /api/v1/profile
GET /api/v1/public/users/{userId}/profile
```

Public question listing:

```http
GET /api/v1/public/questions
GET /api/v1/public/questions?topic=SwiftUI&limit=20&offset=0
```

This endpoint is public and must not require `Authorization`.

Patch request:

```json
{
  "displayName": "Buddy",
  "pageAccess": {
    "publicQuestions": true
  }
}
```

`DELETE /api/v1/profile` deletes the active Google-linked account for the current device. The backend immediately removes the profile, sign-in mapping, public questions, and related study records for that user, reconnects the current device to an anonymous user, and returns a fresh anonymous `accessToken`.

### Report Public Question

```http
POST /api/v1/public/questions/{questionId}/report
Content-Type: application/json
Authorization: Bearer <accessToken>
```

Request:

```json
{
  "reason": "Inappropriate question",
  "message": "Optional details"
}
```

Reports are always stored in PostgreSQL. If `REPORT_EMAIL_TO` and SMTP settings are configured, the backend also forwards the report by email.

### Upsert Study Settings

```http
PUT /api/v1/settings
Content-Type: application/json
Authorization: Bearer <accessToken>
```

Request:

```json
{
  "topic": "Redis streams",
  "difficultyLevel": 6,
  "intervalMinutes": 60,
  "enabled": true,
  "openaiApiKey": "sk-...",
  "notificationSound": "default",
  "customPrompt": "Ask concise production-oriented questions.",
  "appLanguage": "ko",
  "openaiModel": "gpt-5.4",
  "maxHistoryCount": 100
}
```

Fields:

- `topic`: study topic, 1-120 characters.
- `difficultyLevel`: integer from 1 to 10.
- `intervalMinutes`: integer from 1 to 1440.
- `enabled`: whether scheduled pushes are active.
- `openaiApiKey`: optional per-device OpenAI API key. If provided, it is encrypted at rest using `BACKEND_MASTER_KEY`.
- `notificationSound`: optional APNs sound name.
- `customPrompt`: optional tutor instruction.
- `appLanguage`: user-level app language, `ko` or `en`. It also controls generated question and feedback language.
- `openaiModel`: selected model. Defaults to `gpt-5.4`.

The `/api/v1/openai/models` endpoint returns all supported model IDs and metadata:

```http
GET /api/v1/openai/models
```

Response:

```json
[
  {
    "id": "gpt-5.2",
    "displayName": "GPT-5.2",
    "supportsTextVerbosity": true,
    "supportsReasoning": true,
    "defaultReasoningEffort": "none"
  },
  {
    "id": "gpt-5.2-pro",
    "displayName": "GPT-5.2 pro",
    "supportsTextVerbosity": true,
    "supportsReasoning": true,
    "defaultReasoningEffort": "high"
  },
  {
    "id": "gpt-4.1",
    "displayName": "GPT-4.1",
    "supportsTextVerbosity": false,
    "supportsReasoning": false,
    "defaultReasoningEffort": null
  }
]
```

The catalog is maintained from the OpenAI documentation and intentionally includes
non-exhaustive, commonly usable Responses API models. If your key has access to
additional model IDs, the API will still accept them and route them through directly.
GPT-5 family models receive Responses API `reasoning.effort` and `text.verbosity`
when supported. Non-reasoning models use a minimal Responses payload with
structured JSON output only.

- `maxHistoryCount`: record retention preference from 10 to 10,000.

Response:

```json
{
  "deviceId": "generated-device-id",
  "enabled": true,
  "nextDueAt": "2026-06-01T12:00:00+00:00"
}
```

### Settings

```http
GET /api/v1/settings
Authorization: Bearer <accessToken>
```

Returns the latest saved study settings. For editing one study room, prefer the study-scoped endpoint:

```http
GET /api/v1/studies/{studyId}/settings
PUT /api/v1/studies/{studyId}/settings
Authorization: Bearer <accessToken>
```

`studyId` is the database-generated id returned in each study settings response.

### API Status

```http
GET /api/v1/api
Authorization: Bearer <accessToken>
```

Returns whether the device has an encrypted OpenAI API key configured, the selected model, and OpenAI usage/billing links.

### Validate API Key

```http
POST /api/v1/api/validate
Authorization: Bearer <accessToken>
```

Validates the device's stored regular OpenAI API key through the backend and returns:

```json
{
  "openaiKeyConfigured": true,
  "isValid": true,
  "openaiModel": "gpt-5.4"
}
```

The iOS/macOS apps must not validate keys by calling OpenAI directly.

### My Studies

```http
GET /api/v1/studies?limit=500&offset=0
Authorization: Bearer <accessToken>
```

Returns the authenticated user's study rooms. It does not return record history, but each study can include one `pendingQuestion` for the current unanswered study-room question.

### Records

```http
GET /api/v1/records?limit=100&offset=0
GET /api/v1/records/{recordId}
PATCH /api/v1/records/{recordId}/answer
POST /api/v1/records/{recordId}/answer
POST /api/v1/records/{recordId}/skip
DELETE /api/v1/records/{recordId}
DELETE /api/v1/records
```

Study record `id` values are database-generated autoincrement IDs returned as strings for client compatibility.
`PATCH .../answer` saves an answer draft without grading. `POST .../answer` grades the answer using the device's stored OpenAI API key and persists the score, feedback, and explanation. Delete endpoints immediately remove the target records and related report/public-question references.

### Statistics

```http
GET /api/v1/stats?period=all&sort=level&limit=8&offset=0
GET /api/v1/stats?startAt=2026-06-01T00:00:00Z&endAt=2026-06-02T00:00:00Z
Authorization: Bearer <accessToken>
```

Query fields:

- `period`: `all`, `today`, `last7`, `last30`, or `last90`.
- `startAt` / `endAt`: optional ISO-8601 UTC date bounds. These override `period`.
- `search`: optional topic search.
- `sort`: `level`, `recent`, `name`, or `count`.
- `limit` / `offset`: topic pagination.

The response is topic-first and includes total response/topic counts, topic aliases, level range, correct rate, and the records for each returned topic.

### Manual Question

```http
POST /api/v1/questions
Authorization: Bearer <accessToken>
```

Generates one question using the device settings and stored OpenAI API key, stores it as an ungraded record, and returns that record. The backend enforces a maximum of three ungraded records before creating more.

### Delete Device

```http
DELETE /api/v1/device
Authorization: Bearer <accessToken>
```

Response:

```http
204 No Content
```

This removes the device, APNs token, schedule, stored encrypted OpenAI key, and records from the backend.

### Run Scheduler Once

```http
POST /api/v1/admin/scheduler/run-once
Authorization: Bearer <BACKEND_API_TOKEN>
```

Response:

```json
{
  "sent": 1,
  "client": "127.0.0.1"
}
```

This endpoint is intended for explicit operator-triggered scheduler checks. It must not be called from GitHub Actions health checks. The normal scheduler loop runs automatically when `SCHEDULER_ENABLED=true`.

## Error Format

Validation, auth, and server failures return one unified JSON shape:

```json
{
  "error": {
    "code": "AUTH_INVALID_DEVICE_CREDENTIALS",
    "message": "Invalid device credentials.",
    "requestId": "9f4f2f8c-8ad1-45f4-9390-64d9a1f09ad0",
    "status": 401
  }
}
```

Common statuses:

- `401`: missing or invalid backend/device credentials, or an access token whose `device_id` no longer matches the stored user-device mapping.
- `403`: authenticated principal does not have permission for the requested page or resource.
- `422`: request body failed validation.

Common error codes:

- `AUTH_ACCESS_TOKEN_REQUIRED`
- `AUTH_DEVICE_MISMATCH`
- `AUTH_GOOGLE_REQUIRED`
- `AUTH_INVALID_ACCESS_TOKEN`
- `AUTH_INVALID_DEVICE_CREDENTIALS`
- `DEVICE_NOT_FOUND`
- `OPENAI_API_KEY_INVALID`
- `OPENAI_API_KEY_MISSING`
- `RECORD_NOT_FOUND`
- `STUDY_SETTINGS_MISSING`
- `VALIDATION_ERROR`
- `INTERNAL_SERVER_ERROR`
