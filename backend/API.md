# BuddyStuddy Backend API

The backend is the source of truth for iOS study settings, scheduled question delivery, records, answer drafts, and grading results. It is a FastAPI service, so the generated OpenAPI documents are also available at runtime:

- `GET /docs`
- `GET /redoc`
- `GET /openapi.json`

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

Device endpoints use the credentials returned during registration. This is the login-free identity model:

```http
X-Device-Id: <deviceId>
X-Client-Secret: <clientSecret>
```

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

### Register Device

```http
POST /v1/devices/register
Content-Type: application/json
```

Request:

```json
{
  "apnsToken": "apns-device-token",
  "platform": "ios",
  "apnsEnvironment": "production",
  "language": "ko",
  "timezone": "Asia/Seoul"
}
```

Fields:

- `apnsToken`: APNs device token. Minimum length is 32 characters.
- `platform`: client platform. Current app sends `ios`.
- `apnsEnvironment`: `production` for TestFlight/App Store, `sandbox` for debug builds.
- `language`: `ko` or `en`.
- `timezone`: IANA timezone name used for schedule calculations.

Response:

```json
{
  "deviceId": "generated-device-id",
  "clientSecret": "generated-client-secret"
}
```

The app must store both values locally. The backend does not return the client secret again.

### Upsert Study Settings And Schedule

```http
PUT /v1/devices/{deviceId}/schedule
Content-Type: application/json
X-Device-Id: <deviceId>
X-Client-Secret: <clientSecret>
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
- `appLanguage`: `ko` or `en`. This also controls question/feedback language.
- `openaiModel`: currently `gpt-5.4`.
- `maxHistoryCount`: record retention preference from 10 to 10,000.

Response:

```json
{
  "deviceId": "generated-device-id",
  "enabled": true,
  "nextDueAt": "2026-06-01T12:00:00+00:00"
}
```

### Snapshot

```http
GET /v1/devices/{deviceId}/snapshot?limit=500&offset=0
X-Device-Id: <deviceId>
X-Client-Secret: <clientSecret>
```

Returns backend settings plus a paged record cache for app startup and pull-to-refresh.

### Records

```http
GET /v1/devices/{deviceId}/records?limit=100&offset=0
GET /v1/devices/{deviceId}/records/{recordId}
PATCH /v1/devices/{deviceId}/records/{recordId}/answer
POST /v1/devices/{deviceId}/records/{recordId}/answer
POST /v1/devices/{deviceId}/records/{recordId}/skip
DELETE /v1/devices/{deviceId}/records/{recordId}
DELETE /v1/devices/{deviceId}/records
```

Study record `id` values are database-generated autoincrement IDs returned as strings for client compatibility.
`PATCH .../answer` saves an answer draft without grading. `POST .../answer` grades the answer using the device's stored OpenAI API key and persists the score, feedback, and explanation. Delete endpoints are soft-delete operations.

### Manual Question

```http
POST /v1/devices/{deviceId}/questions
X-Device-Id: <deviceId>
X-Client-Secret: <clientSecret>
```

Generates one question using the device settings and stored OpenAI API key, stores it as an ungraded record, and returns that record. The backend enforces a maximum of three ungraded records before creating more.

### Delete Device

```http
DELETE /v1/devices/{deviceId}
X-Device-Id: <deviceId>
X-Client-Secret: <clientSecret>
```

Response:

```http
204 No Content
```

This removes the device, APNs token, schedule, stored encrypted OpenAI key, and records from the backend.

### Run Scheduler Once

```http
POST /v1/admin/scheduler/run-once
Authorization: Bearer <BACKEND_API_TOKEN>
```

Response:

```json
{
  "sent": 1,
  "client": "127.0.0.1"
}
```

This endpoint is intended for deployment smoke tests and manual operations. The normal scheduler loop runs automatically when `SCHEDULER_ENABLED=true`.

## Error Format

FastAPI validation and auth failures return the standard JSON error shape:

```json
{
  "detail": "Invalid device credentials."
}
```

Common statuses:

- `401`: missing or invalid backend/device credentials.
- `403`: authenticated device does not match the path `deviceId`.
- `422`: request body failed validation.
