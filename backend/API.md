# BuddyStuddy Push Backend API

The backend provides scheduled remote-push question delivery for the iOS app. It is a FastAPI service, so the generated OpenAPI documents are also available at runtime:

- `GET /docs`
- `GET /redoc`
- `GET /openapi.json`

## Base URL

Production EC2 deployment:

```text
https://ec2-13-125-226-24.ap-northeast-2.compute.amazonaws.com
```

The current EC2 workflow creates a self-signed HTTPS certificate for deployment verification. iOS production clients should use a real domain with a trusted TLS certificate before this URL is enabled in the app.

## Authentication

Registration and admin endpoints use the optional backend token when `BACKEND_API_TOKEN` is configured:

```http
Authorization: Bearer <BACKEND_API_TOKEN>
```

Device schedule and deletion endpoints use the credentials returned during registration:

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
Authorization: Bearer <BACKEND_API_TOKEN>
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

### Upsert Schedule

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
  "notificationSound": "default"
}
```

Fields:

- `topic`: study topic, 1-120 characters.
- `difficultyLevel`: integer from 1 to 10.
- `intervalMinutes`: integer from 1 to 1440.
- `enabled`: whether scheduled pushes are active.
- `openaiApiKey`: optional per-device OpenAI API key. If provided, it is encrypted at rest using `BACKEND_MASTER_KEY`.
- `notificationSound`: optional APNs sound name.

Response:

```json
{
  "deviceId": "generated-device-id",
  "enabled": true,
  "nextDueAt": "2026-06-01T12:00:00+00:00"
}
```

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

This removes the device, APNs token, schedule, and stored encrypted OpenAI key from the backend.

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

