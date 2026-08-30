# BuddyStudy Backend API

The backend is the source of truth for iOS study settings, scheduled question delivery, records, answer drafts, grading results, and Pro Voice Tutor sessions/results. It is a Spring Boot Kotlin service backed by MySQL and Spring Data JPA.

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

### Model Context Protocol

The optional stateless MCP endpoint uses the same authenticated bearer boundary:

```http
POST /api/v1/mcp
Authorization: Bearer <accessToken>
Content-Type: application/json
Accept: application/json, text/event-stream
```

It exposes private profile/resume/interests, owned studies, asynchronous question and grading operations, records, feedback, scores, topic statistics, and the read-only Voice Tutor quota/history/result surface. It never accepts a `userId` argument. Production is disabled unless `MCP_SERVER_ENABLED=true`; connection, tool, privacy, and rollout details are documented in [MCP_SERVER.md](../docs/MCP_SERVER.md).

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
Runtime checks must not run from GitHub Actions. Production alerting is owned
by Grafana, which evaluates Loki and the configured observability data sources.
The readiness endpoint is available for operator diagnostics:

```http
GET /api/v1/health/readiness
```

It returns `200` when required dependencies are reachable and core scheduler
jobs have recent successful runs, otherwise `503` with component-level check
results. Scheduler checks include structured `details` so dashboards and
operator diagnostics can show missing jobs, disabled jobs, stale jobs, and
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
          "event-outbox-dispatch",
          "user-stats-refresh",
          "answer-grading-watchdog"
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

Admin users can inspect scheduler freshness and the latest run for each managed
job in bounded pages:

```http
GET /api/v1/admin/jobs/statuses?limit=10&offset=0
Authorization: Bearer <adminToken>
```

New clients must send both pagination parameters. Omitting `limit` is retained
only for legacy clients and returns the full registry using the same indexed
latest-run lookup.

Response:

```json
{
  "jobs": [
    {
      "jobName": "question-schedule",
      "displayName": "Scheduled question dispatch",
      "description": "Delivers due scheduled study questions.",
      "enabled": true,
      "monitored": true,
      "scheduleType": "FIXED_DELAY",
      "scheduleValue": "30s",
      "latestRun": null,
      "stale": true,
      "staleThresholdMinutes": 15,
      "timeoutSeconds": 300,
      "stuck": false
    }
  ],
  "totalCount": 23,
  "limit": 10,
  "offset": 0
}
```

### Admin Feedback And Targeted Notifications

```http
GET /api/v1/admin/feedback?query=&status=NEW&limit=20&offset=0
Authorization: Bearer <BACKEND_API_TOKEN>

PATCH /api/v1/admin/feedback/{feedbackId}/review
Authorization: Bearer <BACKEND_API_TOKEN>

POST /api/v1/admin/feedback/{feedbackId}/notifications
Authorization: Bearer <BACKEND_API_TOKEN>

POST /api/v1/admin/users/{userId}/notifications
Authorization: Bearer <BACKEND_API_TOKEN>
```

The feedback list supports `NEW`, `REVIEWED`, and `REPLIED` status filters.
Sending through a feedback targets its captured registered user, or its
submitting device when the feedback was anonymous. A direct user notification
targets the selected member.

Both notification endpoints accept:

```json
{
  "title": "피드백을 확인했어요",
  "body": "소중한 피드백 감사합니다. **무료 크레딧**을 확인해 주세요.",
  "deepLink": "buddystudy://home/message"
}
```

`deepLink` defaults to `buddystudy://home/message`. Only validated
`buddystudy://` app destinations are accepted; HTTP and HTTPS destinations are
rejected. `home/message` presents the full Markdown body in a Home popup after
an explicit notification tap. Other supported destinations route to Home, My
Studies, Records, Statistics, Settings/Profile, or Public Questions. APNs uses
a parser-derived plain-text preview while the notification inbox retains the
original Markdown.

### Admin Voice Tutor Limits

The membership-tier default and a user's persistent monthly Voice Tutor cap are
managed independently:

```http
PATCH /api/v1/admin/membership-tiers/{tierCode}
Authorization: Bearer <BACKEND_API_TOKEN>
Content-Type: application/json

{
  "monthlyVoiceSecondsLimit": 18000
}
```

`monthlyQuestionLimit` and `monthlyVoiceSecondsLimit` are independently
optional, but at least one must be provided. Updating only the voice limit does
not change the question limit.

```http
PATCH /api/v1/admin/users/{userId}/voice-limit
Authorization: Bearer <BACKEND_API_TOKEN>
Content-Type: application/json

{
  "monthlyVoiceSecondsLimitOverride": 900
}
```

The user override is `0..31536000`, takes precedence over the tier default, and
survives monthly rollover. Send `null` to restore the tier default. Lowering a
limit preserves already used/reserved seconds and clamps remaining seconds at
zero. A positive override never grants Voice Tutor entitlement to TIER1; a paid
tier with an effective zero-second cap remains entitled but quota-exhausted.
Admin user responses add the effective, override, and tier-default limits plus
`voiceUsedSeconds`, `voiceReservedSeconds`, `voiceRemainingSeconds`,
`voicePeriodStartedAt`, and `voiceResetAt`.

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
POST /api/v1/auth/apple
Content-Type: application/json
Authorization: Bearer <accessToken>
```

Request:

```json
{
  "idToken": "google-id-token",
  "referralCode": "BS-ABCDEFGH"
}
```

The backend verifies the ID token against `GOOGLE_IOS_CLIENT_ID`, then links the
Google identity to the device. `referralCode` is optional. It is accepted only
for a newly created account that is still completing required terms; signing in
to an existing active account never creates referral attribution.

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
  "verificationCode": "123456",
  "referralCode": "BS-ABCDEFGH"
}
```

If the email already exists, `verificationCode` can be omitted. If it does not
exist, the backend verifies the code and creates a `PENDING_TERMS` `EMAIL` user.
Passwords are stored only as SHA-256 hashes.

Response:

```json
{
  "profile": {
    "id": 1,
    "displayName": "Buddy",
    "bio": "",
    "avatarUrl": null,
    "avatarSymbolName": "pixel-buddy",
    "avatarColorSeed": "avatar-color-mint"
  },
  "accessToken": "jwt",
  "accessTokenExpiresAt": "2026-09-05T00:00:00+00:00",
  "isNewAccount": true,
  "referralAttributed": true
}
```

`referralAttributed` confirms that the server durably retained the attribution;
it does not mean the reward has been issued yet. The backend grants both the
inviter and the new member one non-renewing month of `TIER2` in the transaction
that accepts the final required term and changes the member to `ACTIVE`.

### Referrals

```http
GET /api/v1/referrals/me
Authorization: Bearer <accessToken>

POST /api/v1/referrals/redeem
Authorization: Bearer <accessToken>
Content-Type: application/json
```

The summary response includes the server-owned canonical URL. The iOS client
accepts only the exact BuddyStudy HTTPS host and path; for compatibility with a
staggered backend rollout, it may reconstruct that same canonical URL only from
a strictly validated referral code:

```json
{
  "code": "BS-ABCDEFGH",
  "referralUrl": "https://api.ghkdqhrbals.org/referrals/BS-ABCDEFGH",
  "successfulReferralCount": 1,
  "rewardMonthsEarned": 1,
  "rewardStartsAt": "2026-08-28T00:00:00Z",
  "rewardEndsAt": "2026-09-28T00:00:00Z",
  "hasRedeemedReferral": false
}
```

Manual redemption is a recovery path for missed link attribution, not a general
promotion for existing accounts. By default it is available only during the
first 24 hours after account creation. Repeating a successful redemption with
the same code returns the existing result; changing to another code conflicts.

The public link and Associated Domains metadata require no authentication:

```http
GET /referrals/BS-ABCDEFGH
GET /.well-known/apple-app-site-association
```

An installed iOS app receives a valid referral path as a Universal Link. Without
the app, the HTML landing page offers the App Store and a copyable code. This
landing does not claim fully deferred attribution through an App Store install.

### Profile

`PATCH /api/v1/profile` accepts the pixel-avatar fields
`avatarSymbolName`, `avatarColorSeed`, and `avatarMode`. New profile-photo
uploads are not supported. Saving a non-photo avatar removes any legacy
profile photo associated with the account.

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

Liked public questions:

```http
GET /api/v1/public/questions/liked?query=SwiftUI&tl=ko&view=localized&limit=20&offset=0
Authorization: Bearer <accessToken>
```

This endpoint returns only the authenticated user's still-public, graded liked
questions, ordered by the time they were liked. It excludes blocked authors and
never inserts native advertisements. `limit` is clamped to `1...100`, `offset`
is clamped to zero or greater, and `tl` takes precedence over the deprecated
`language` alias.

Patch request:

```json
{
  "displayName": "Buddy",
  "bio": "Backend learner",
  "avatarSymbolName": "pixel-fox",
  "avatarColorSeed": "avatar-color-mint"
}
```

`DELETE /api/v1/profile` immediately withdraws the active member, revokes its sessions, scrubs login/profile secrets, reconnects the current device to an anonymous user, and returns a fresh anonymous `accessToken`. In the same transaction it writes an `ACCOUNT_WITHDRAWN` outbox event. An at-least-once Redis Stream consumer then idempotently removes profile assets, public questions, studies, records, reactions, user-block relationships, notifications, and related data.

### Permission Policy, Terms, And Notifications

```http
GET /api/v1/terms/active?context=SIGNUP
POST /api/v1/terms/agreements
GET /api/v1/notification-preferences
POST /api/v1/notification-preferences
```

The backend owns the active terms list, links, required/optional flags, and mutability. Clients should render the returned terms instead of hard-coding signup requirements.

Terms agreement requests use enum `type`; legacy `code` is accepted only for backward compatibility:

```json
{
  "type": "TERMS_OF_SERVICE",
  "action": "AGREED",
  "source": "PROFILE"
}
```

Supported terms types are `TERMS_OF_SERVICE`, `PRIVACY_POLICY`, and `MARKETING_NOTIFICATION`.

Notification preference requests use enum `type`; legacy `key` is accepted only for backward compatibility:

```json
{
  "type": "QUESTION_NOTIFICATION",
  "enabled": true
}
```

Supported notification preference types are `QUESTION_NOTIFICATION` and `MARKETING_NOTIFICATION`. Responses include both `type` and legacy storage key/code fields during migration.

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

Reports are always stored in MySQL. If `REPORT_EMAIL_TO` and SMTP settings are configured, the backend also forwards the report by email.

### Block A Community User

```http
PUT /api/v1/community/users/{userId}/block
DELETE /api/v1/community/users/{userId}/block
Authorization: Bearer <accessToken>
```

Both operations require the `public-user:block` permission and are idempotent.
The `PUT` response is `{ "userId": 42, "blocked": true }`; `DELETE` returns
the same shape with `blocked: false`. A member cannot block their own account.

For an authenticated requester, blocking an author removes that author's
questions from public-question lists, returns not found for direct public-detail
reads, and removes that author's comments from comment lists. The relationship
is stored by the backend, so it applies on every signed-in device until the user
unblocks the author or either account is deleted.

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
- `customPrompt`: optional tutor-instruction override. When omitted, `null`, or blank, the backend uses its static default question prompt.
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

- `maxHistoryCount`: deprecated compatibility field. It is still accepted from older clients but does not delete or cap backend records.

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

Returns the authenticated user's study tree/list synchronization page. It is not
the detail or record-history endpoint. Each list item can include one
`pendingQuestion` so list badges and pending counts can be synchronized without
loading completed record history.

### Study Detail

```http
GET /api/v1/studies/{studyId}?tl=ko
Authorization: Bearer <accessToken>
```

Returns exactly one authenticated user-owned study room. `pendingQuestion` is
the latest non-skipped, non-deleted question without a score, including a
persisted submitted answer and grading state when present. `latestQuestion` is
the latest completed question and includes its user answer and AI grading
response. Clients display `pendingQuestion` first and fall back to
`latestQuestion` when no pending question exists.

### Study Topic Suggestions

```http
POST /api/v1/studies/{studyId}/topic-suggestions?count=10
Authorization: Bearer <accessToken>
```

The backend first reuses children from the shared system topic catalog for the
same root, parent path, language, and depth. It calls the topic generator only
for missing candidates and stores those candidates back in the catalog for
later users. A parent can expose at most 10 candidates and descendants can be
created through depth 5. User-owned study nodes are materialized only when the
user selects a candidate, and newly created nodes are active for questions by
default.

Response:

```json
{
  "parentStudyId": 42,
  "suggestions": ["정규화", "인덱스", "트랜잭션"],
  "source": "CATALOG",
  "depth": 2,
  "maxDepth": 5,
  "childLimit": 10
}
```

`source` is `CATALOG`, `GENERATED`, `MIXED`, or `DEPTH_LIMIT`.

### Pro Voice Tutor

Every Voice Tutor REST call and WebSocket handshake requires a registered
user's bearer token. Session reads, finalization, and the stream are scoped to
the authenticated owner; a client cannot supply another `userId`. The default
plan catalog grants voice seconds to TIER2 and TIER3, while the server-owned
quota projection and plan catalog remain authoritative.

Status does not reserve time:

```http
GET /api/v1/voice-tutor/status
Authorization: Bearer <accessToken>
```

```json
{
  "eligible": true,
  "reason": null,
  "tierCode": "TIER2",
  "quota": {
    "tierCode": "TIER2",
    "periodStartedAt": "2026-08-15T09:00:00Z",
    "resetAt": "2026-09-15T09:00:00Z",
    "limitSeconds": 18000,
    "usedSeconds": 900,
    "reservedSeconds": 0,
    "remainingSeconds": 17100
  },
  "maxSessionSeconds": 3600,
  "activeSession": null
}
```

Create one session with a stable caller-generated idempotency key:

```http
POST /api/v1/voice-tutor/sessions
Authorization: Bearer <accessToken>
Idempotency-Key: ios-voice-<uuid>
Content-Type: application/json

{"studyId":42,"language":"ko","voice":"marin"}
```

`Idempotency-Key` must contain 1–191 characters and is scoped to the
authenticated user. Retrying the same key returns the same session and does not
reserve seconds twice. Only one non-terminal session is allowed per user. The
optional `voice` falls back to the server configuration. A successful new
reservation returns `201` with `sessionId`, `state=READY`, `websocketUrl`,
`websocketProtocol=buddystudy.voice.v1`, `createdAt`, `hardEndsAt`, and the
post-reservation quota. The server reserves at most the minimum of remaining
monthly seconds, the configured per-session ceiling, and time until reset.

History uses an opaque newest-first cursor:

```http
GET /api/v1/voice-tutor/sessions?limit=30&cursor=<opaqueCursor>
GET /api/v1/voice-tutor/sessions/{sessionId}
Authorization: Bearer <accessToken>
```

`limit` defaults to 30 and is clamped to 1–100. The page is
`{"sessions":[...],"nextCursor":"..."}`; `nextCursor=null` is terminal.
Clients must return the cursor unchanged rather than parsing or synthesizing
it. Detail includes the session, authoritative quota, bounded ordered
`transcriptTurns`, and the private Tutor Learning Result when available.

Both explicit REST finalization and the WebSocket control event are supported:

```http
POST /api/v1/voice-tutor/sessions/{sessionId}/end
Authorization: Bearer <accessToken>
```

The request has no body. For a reserved session it finalizes immediately. For
an active relay it requests `ENDING`; the owning WebSocket performs the one
terminal settlement after a bounded transcript drain. The transaction charges
the greater of rounded-up server-observed connected seconds and rounded-up
accepted 24 kHz mono PCM duration, releases unused reservation, and is
idempotent, so a repeated request returns the same terminal result without
charging again.

Connect to the fixed stream path on the same authenticated BuddyStudy backend
origin. `websocketUrl` is an informational same-origin URL (relative by
default); clients must derive or validate the final origin and must never send
the bearer token to a server-provided cross-origin URL:

```http
GET /api/v1/voice-tutor/sessions/{sessionId}/stream
Authorization: Bearer <accessToken>
Sec-WebSocket-Protocol: buddystudy.voice.v1
```

WebSocket messages are JSON text frames. The relay accepts the OpenAI Realtime
client event types `input_audio_buffer.append` and `input_audio_buffer.commit`.
`input_audio_buffer.clear`, `response.create`, `response.cancel`,
`conversation.item.truncate`, and the legacy `buddystudy.voice.barge-in` event
are never accepted from the client. When the learner speaks over current tutor
audio, iOS keeps the current tutor sentence queued for uninterrupted playback
while continuing to stream microphone input. After OpenAI marks the complete
response done and the final PCM buffer is actually rendered, iOS sends the
local `buddystudy.voice.playback.completed` event with that `responseId`. The
relay validates and consumes this acknowledgement without forwarding it to
OpenAI; a response-audio-duration-based bounded fallback prevents a lost
acknowledgement from wedging the session. Sending `buddystudy.voice.heartbeat`
returns a rate-coalesced acknowledgement; a server-owned two-second control
pulse, not this advisory client event, refreshes the active relay lease and
revalidates the authenticated user/device session. Sending
`buddystudy.voice.session.end` requests idempotent `USER_ENDED` finalization;
local control events are never forwarded upstream. Provider events are
allowlisted and provider errors are mapped to a safe BuddyStudy error rather
than forwarded raw; audio and transcript deltas are size-bounded before relay,
and OpenAI credentials remain server-only.

Server VAD remains active while tutor audio plays, so learner-to-tutor
overlap is full duplex. Automatic provider response creation and automatic
provider interruption are disabled. Trusted tutor instructions constrain every
spoken response to one short, complete sentence. A learner turn committed while
that sentence is active remains queued; the backend creates one normal response
only after the provider response is done, the matching sentence audio has
finished playback on iOS, and the learner is no longer speaking.
Multiple committed segments that arrive during the same overlap are coalesced
into that next response. A configurable, bounded continuous-speech timer
(default 12 seconds) may request one separate, one-sentence tutor intervention
for a long monologue without committing or clearing the learner's live input
buffer. That captured turn commits naturally after the learner stops. The tutor
prompt limits intervention to long monologues or important misconceptions
rather than ordinary pauses. A configurable response watchdog defaults to 60
seconds and terminates a provider response that never reaches `response.done`.
Ending through either REST or WebSocket flushes accepted-audio accounting,
commits any final buffered input, and waits up to two seconds for its transcript
and active response completion before terminal settlement.

BuddyStudy also emits synthetic server events. Every synthetic event includes
`type`, `sessionId`, and `serverTime`:

| Type | Additional fields and meaning |
| --- | --- |
| `buddystudy.voice.session.ready` | `hardEndsAt`, `quotaRemainingSeconds`; the upstream relay is ready |
| `buddystudy.voice.heartbeat.ack` | `state`; the validated advisory heartbeat was acknowledged |
| `buddystudy.voice.quota.updated` | `limitSeconds`, `usedSeconds`, `reservedSeconds`, `remainingSeconds`, `chargedSeconds` |
| `buddystudy.voice.session.ending` | `reason`, `hardEndsAt`; the server deadline is closing the stream |
| `buddystudy.voice.session.ended` | `reason`, `endedAt`, `durationSeconds`, `chargedSeconds`, `resultStatus`, `pollAfterMs` |
| `buddystudy.voice.result.ready` | `resultStatus=COMPLETED`; the private lesson result can be read |
| `buddystudy.voice.error` | Safe `code`, `message`, and `retryable` finalization status |

When `resultStatus=PROCESSING`, clients wait `pollAfterMs` and refresh the
detail endpoint. Session states are `READY`, `ACTIVE`, `ENDING`, `COMPLETED`, or
`FAILED`; result states are `PENDING`, `PROCESSING`, `COMPLETED`, or `FAILED`.

When MCP is enabled, its Voice Tutor contract is owner-scoped and read-only:

- `list_voice_tutor_sessions(limit, cursor?)` returns `sessions` and an opaque
  `nextCursor`;
- `get_voice_tutor_session(session_id)` returns bounded transcript/result detail;
- `get_voice_tutor_quota()` returns the server-owned seconds projection;
- `buddystudy://voice-tutor/sessions/recent` and
  `buddystudy://voice-tutor/quota` expose snapshot resources.

These MCP operations use the dedicated `voice-tutor:read` permission and cannot
create, extend, explicitly end, or stream a live session. Before returning an
authoritative snapshot, the shared read use case may settle an expired session
or lazily advance an overdue monthly period; this server housekeeping does not
grant MCP a live-session mutation tool.

BuddyStudy does not persist original microphone or model audio. Voice REST
bodies and WebSocket frames are excluded from API body logs, provider-history
bodies, analytics, and error attachments. Only bounded transcript text,
session/accounting metadata, and the derived private result are stored.

Common Voice Tutor failures are `VOICE_TUTOR_PRO_REQUIRED` (`403`),
`VOICE_TUTOR_QUOTA_EXCEEDED` (`403`), `VOICE_TUTOR_SESSION_CONFLICT` (`409`),
and `VOICE_TUTOR_PROVIDER_UNAVAILABLE` (`503`). Invalid request fields, session
IDs, or cursors use the standard validation error shape.

### Records

```http
GET /api/v1/records?limit=30&offset=0
GET /api/v1/records/{recordId}
PATCH /api/v1/records/{recordId}/answer
POST /api/v1/records/{recordId}/answer
POST /api/v1/records/{recordId}/skip
DELETE /api/v1/records/{recordId}
DELETE /api/v1/records
```

Study record `id` values are database-generated autoincrement IDs returned as strings for client compatibility.
`PATCH .../answer` saves an answer draft without grading. `POST .../answer` grades the answer using the device's stored OpenAI API key and persists the score, feedback, and explanation. Delete endpoints immediately remove the target records and related report/public-question references.
`GET /api/v1/studies/{studyId}` includes the latest ungraded `pendingQuestion`
for that study and the latest completed `latestQuestion`. When an answer has
already been submitted, the pending nested record includes
`answer`, `questionStatus`, `correlationId` (with `gradingRequestId` retained as
a compatibility alias), `gradingLastEventId`, `gradingStatus`, and
`gradingError` so a reopened
client can restore the submitted state and resume grading-status polling.
`GET /api/v1/records/{recordId}` remains the canonical record-detail endpoint;
`GET /api/v1/studies` is reserved for tree/list synchronization.
Records have no per-user retention cap and remain until the user deletes them or
withdraws the account. Clients should request subsequent `offset` pages while
scrolling instead of loading the complete history at once.

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

Study-tree growth uses a separate endpoint:

```http
GET /api/v1/stats/studies?startAt=2026-06-01T00:00:00Z&endAt=2026-09-01T00:00:00Z
Authorization: Bearer <accessToken>
```

It returns root summaries and a flat study-node list. Each root contains a
`profile` with normalized `achievement`, `challenge`, `completion`, `breadth`,
and `depth` values. Achievement is mean score, challenge is mean answered
difficulty, completion is completed/generated questions, breadth is
answered/all subtree studies, and depth is deepest answered/all available tree
levels for the selected period. Ungraded generated questions affect only
completion, not ability, growth, or trend calculations.

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

### External API History

```http
GET /api/v1/admin/external-api-history?limit=20&provider=openai&status=FAILED&query=translate
Authorization: Bearer <adminToken>
```

Returns a descending cursor page of outbound provider call summaries. Request and response bodies are omitted from the page payload.

```http
GET /api/v1/admin/external-api-history/{id}
Authorization: Bearer <adminToken>
```

Returns the selected call's complete request and response as observed by the backend. Authentication headers, cookies, API keys, tokens, verification codes, and APNs device tokens are redacted before persistence.

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
- `AUTH_INVALID_EMAIL_CREDENTIALS`
- `DEVICE_NOT_FOUND`
- `OPENAI_API_KEY_INVALID`
- `OPENAI_API_KEY_MISSING`
- `RECORD_NOT_FOUND`
- `STUDY_SETTINGS_MISSING`
- `VALIDATION_ERROR`
- `INTERNAL_SERVER_ERROR`
