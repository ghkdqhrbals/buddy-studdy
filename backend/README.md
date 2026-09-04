# BuddyStudy Backend

Spring Boot Kotlin backend for BuddyStudy study settings, records, grading, Voice Tutor, statistics source data, and scheduled APNs question delivery.

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
- Negotiates foreground-only Pro Voice Tutor audio over direct iOS/OpenAI WebRTC while retaining server-owned sideband control (with a bounded PCM relay fallback), keeps overlapping learner audio without cancelling the tutor's current one-sentence response, and waits for matching provider response/output completion plus meaningful, persisted learner input before replying. iOS bundles Silero for acoustic speech/noise; existing GPT performs contextual input assessment independently of media/control receive. It accounts for monthly time in seconds and stores bounded transcript turns plus a separate private learning result. A separately default-off, explicit-consent flow may retain one private mixed call recording under the lifecycle below.
- Stores optional community profiles for Google-signed-in users.
- Stores community question reports and can forward them by email when SMTP is configured.
- Exposes an authenticated, stateless MCP server for private learning context, studies, questions, grading, Voice Tutor quota/history/results, and topic statistics when enabled.
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
- `OPENAI_API_KEY_USER`: regular user-content workload key used for question generation, embeddings, translation, answer feedback, grading, Voice Tutor call negotiation/sideband and PCM fallback, and Tutor Learning Result summaries. It remains server-only and is never returned to iOS or MCP clients. It must be a different OpenAI key from `OPENAI_API_KEY_SYSTEM`; a third Voice Tutor-specific key type is not supported. `OPENAI_USER_CONTENT_API_KEY` and `OPENAI_API_KEY` remain compatibility fallbacks for this value only and never supply the system client; `OPENAI_SYSTEM_API_KEY` remains a compatibility fallback for the system value.
- `VOICE_TUTOR_ENABLED`: enables authenticated Pro Voice Tutor REST/WebSocket registration; defaults to `false` in every environment until the Voice Tutor legal release checklist is approved. An approved deployment must opt in explicitly. Disabling it rejects new live sessions without changing stored entitlement or history, stale-session settlement, or pending-result recovery.
- `OPENAI_REALTIME_MODEL`: realtime voice model; defaults to `gpt-realtime-2.1`.
- `OPENAI_REALTIME_VOICE`: realtime output voice; defaults to `marin`.
- `VOICE_TUTOR_MAX_SESSION_SECONDS`: operational maximum reservation for one session; defaults to `3600` and is hard-clamped to 60 minutes to match the provider session limit. The actual reservation is also bounded by the user's server-owned remaining monthly seconds.
- `VOICE_TUTOR_CONNECT_TIMEOUT_SECONDS`: upstream realtime connection timeout; defaults to `15`.
- `VOICE_TUTOR_HEARTBEAT_LEASE_SECONDS`: stale active-relay lease; defaults to `60`. A server-owned two-second control pulse refreshes the lease and revalidates the authenticated device session; client heartbeats are rate-coalesced acknowledgement requests only.
- `VOICE_TUTOR_CONTINUOUS_SPEECH_INTERVENTION_SECONDS`: legacy-compatible name for the internal ongoing-speech transcription checkpoint interval; defaults to `12` seconds and is clamped to 5–30 seconds. It never triggers a tutor interruption: only stopped, acknowledged, meaningfully assessed and persisted learner input may open the normal response gate.
- `VOICE_TUTOR_RESPONSE_TIMEOUT_SECONDS`: maximum wait for one provider response to reach `response.done`; defaults to `60` seconds and is clamped to 10–120 seconds.
- `VOICE_TUTOR_SESSION_RECOVERY_POLL_MS`, `VOICE_TUTOR_SESSION_RECOVERY_INITIAL_DELAY_MS`, `VOICE_TUTOR_SESSION_RECOVERY_BATCH_SIZE`: bounded stale-session recovery controls; defaults to `5000`, `5000`, and `100`.
- `VOICE_TUTOR_SUMMARY_MODEL`: Chat Completions model used for the private Tutor Learning Result; defaults to `OPENAI_MODEL`, currently `gpt-5.4`. It remains the fallback model for semantic assessment.
- `VOICE_TUTOR_INPUT_ASSESSMENT_MODEL`: optional `buddystudy.voice-tutor.input-assessment.model` override for input relevance, mutation/confirmation attestations, and spoken question/feedback assessment only. Unset or blank preserves `VOICE_TUTOR_SUMMARY_MODEL`; summary jobs are unaffected. The model must support strict JSON schemas and uses the existing regular user-content key. Exact `gpt-5.4` and `gpt-5.4-mini` aliases and their documented snapshots explicitly disable reasoning. Compare semantic quality and single-turn latency before enabling an override; no smaller-model default or automatic fallback is enabled.
- `buddystudy.voice-tutor.input-assessment.*`: independent, validated operational limits bound live input assessment: `timeout-milliseconds=10000` (1–15000), `max-concurrent-assessments=4` (1–16), `admission-timeout-milliseconds=1500` (1–5000), `max-queued-assessments=16` (0–64), `max-utterances=8` (1–8), `max-transcript-characters=4000`, `max-batch-transcript-characters=16000`, and `max-teacher-context-characters=4000` (these text bounds may be lowered, not raised). Configure through normal Spring property binding. Admission is process-wide: a bounded FIFO semaphore wait absorbs short turn-boundary bursts, while queue overflow/timeout remains an explicit BUSY failure with no provider retry or unbounded accumulation. The deadline is only an upper bound: ordinary turns return after one assessment response, while a positive root/child/update mutation may use the remaining budget for its sequential independent semantic attestation. A failure requests another utterance without cancelling tutor playback; it is never a negative semantic verdict. The coordinator uses the same limit snapshot, an 8-second ASR deadline and 5-second publish/delete-ACK deadlines. No raw input, context, provider body or credential enters diagnostics.
- `VOICE_TUTOR_SUMMARY_PROMPT_VERSION`: persisted/result audit version for the summary contract; defaults to `voice-tutor-summary-v2` (source-linked topic explorations in addition to the overall summary).
- `VOICE_TUTOR_SUMMARY_RECOVERY_POLL_MS`, `VOICE_TUTOR_SUMMARY_RECOVERY_INITIAL_DELAY_MS`, `VOICE_TUTOR_SUMMARY_RECOVERY_BATCH_SIZE`, `VOICE_TUTOR_SUMMARY_PROCESSING_LEASE_SECONDS`: bounded result-worker recovery controls; defaults to `5000`, `5000`, `10`, and `300`.
- `VOICE_TUTOR_SUMMARY_MAX_ATTEMPTS`, `VOICE_TUTOR_SUMMARY_RETRY_INITIAL_DELAY_MS`, `VOICE_TUTOR_SUMMARY_RETRY_MAX_DELAY_MS`: keep one claimed result in `PROCESSING` while bounded summary-generation failures are retried with capped exponential backoff; defaults to `3`, `400`, and `2000`. Attempts are runtime-clamped to 1–5 and retry delays to at most 10 seconds. Only exhaustion writes terminal `FAILED`; cancellation and result-persistence failures retain lease recovery. V108 is the rolling-safe expand step: token-aware claims receive an opaque UUID that is valid only on `PROCESSING`, while a pre-migration or overlapping legacy `PROCESSING` attempt may remain NULL and finish under its old contract. Completion and claimed failure compare-and-set both the token and the exact persisted `DATETIME(6)` claim timestamp, so an expired token-aware worker cannot overwrite its replacement even if an overlapping older binary renews only `updated_at` or retries outlive the processing lease. After legacy writers are retired, a later contraction migration may backfill any abandoned NULL processing rows and require tokens on all remaining `PROCESSING` results.
- `VOICE_TUTOR_TRANSCRIPT_MAX_CHARS`: maximum bounded transcript text accepted for one session; defaults to `100000`. Realtime audio frames are never written to application logs or MySQL; an original mixed recording is stored only through the separate explicit-consent recording flow below.
- `VOICE_TUTOR_TRANSCRIPT_MAX_TURNS`: maximum persisted transcript turns per session; defaults to `2000`.
- `VOICE_TUTOR_PUBLIC_BASE_URL`: optional public BuddyStudy `wss` base URL used to construct the returned session URL. When empty, the backend returns an owner-authenticated relative WebSocket path and iOS resolves it against its configured backend origin.
- `VOICE_TUTOR_RECORDING_ENABLED`: independently enables new Voice Tutor recording consent and upload grants. It defaults to `false`, even when live Voice Tutor is enabled, and must remain off until the recording-specific legal release checklist is approved.
- `VOICE_TUTOR_RECORDING_BUCKET`, `VOICE_TUTOR_RECORDING_REGION`, `VOICE_TUTOR_RECORDING_KMS_KEY_ID`: private S3 storage. The bucket is required to enable recording, S3 Block Public Access must remain on, and an empty KMS key selects SSE-S3; a configured key selects SSE-KMS. Bucket lifecycle must expire current versions, noncurrent versions, and delete markers no later than `VOICE_TUTOR_RECORDING_RETENTION_DAYS`.
- `VOICE_TUTOR_RECORDING_RETENTION_DAYS`: absolute recording retention from the server-recorded call end; defaults to `30` days and is clamped to 1–365 days. Retrying an identical pending upload never extends that deadline.
- `VOICE_TUTOR_RECORDING_PRESIGN_SECONDS`, `VOICE_TUTOR_RECORDING_MAX_BYTES`: short-lived owner-scoped upload/download grant lifetime and maximum mixed AAC/M4A object size; defaults are `300` seconds and `134217728` bytes.
- `VOICE_TUTOR_RECORDING_UPLOAD_COMPLETION_SAFETY_SECONDS`: bounded post-expiry deadline through which owner deletion and account-withdrawal cleanup retry a recording key or owner prefix every minute; defaults to `300` seconds and is clamped to 60–3,600 seconds. Retries continue every 24 hours forever after this deadline.
- `VOICE_TUTOR_RECORDING_RETENTION_ENABLED`, `VOICE_TUTOR_RECORDING_RETENTION_BATCH_SIZE`, `VOICE_TUTOR_RECORDING_RETENTION_MAX_ROWS_PER_RUN`, `VOICE_TUTOR_RECORDING_RETENTION_CRON`, `VOICE_TUTOR_RECORDING_RETENTION_ZONE`: bounded object-retention and mandatory permanent deletion-retry controls; defaults are `true`, `100`, `1000`, minutely (`0 * * * * *`), and `UTC`. Disabling ordinary age-based retention does not disable owner-deleted key cleanup, withdrawal tombstones, or their minute-to-daily retries.
- `AWS_SECRET_ID`, `AWS_REGION`: optional AWS Secrets Manager config import. Local `dev` imports `buddystudy/dev` with a `local-secret.` prefix and maps `OPENAI_API_KEY_USER`, `OPENAI_API_KEY_SYSTEM`, their compatibility fallbacks, SMTP, APNs, RevenueCat, and Firebase Remote Config values, so database and Redis values in that secret cannot override local services. Explicit non-empty environment variables override the corresponding AWS values. Use the `dev-aws` profile to import the entire development secret; `prod` imports `buddystudy/prod`. Store APNs as `APNS_AUTH_KEY_BASE64`, `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_BUNDLE_ID`, and `APNS_ENV`; store RevenueCat server verification credentials as `REVENUECAT_PROJECT_ID`, `REVENUECAT_APP_ID`, and `REVENUECAT_SERVER_API_KEY`; store Firebase publication credentials as `FIREBASE_PROJECT_ID` and `FIREBASE_SERVICE_ACCOUNT_JSON_BASE64`. Other keys use the same names as environment placeholders, for example `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `BACKEND_MASTER_KEY`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `OPENAI_API_KEY_USER`, `OPENAI_API_KEY_SYSTEM`, `SMTP_HOST`, `SMTP_USERNAME`, and `SMTP_PASSWORD`.
  Spring property keys are also supported by Spring Cloud AWS, for example `spring.r2dbc.url`, `spring.r2dbc.username`, `spring.r2dbc.password`, and the separate `spring.flyway.*` keys. Keep runtime R2DBC and Flyway JDBC URLs in their respective formats.

The settings API may retain a user's OpenAI API key encrypted at rest for backward compatibility. Backend system and user-content workloads do not route through that stored key.

The monthly Voice Tutor entitlement is not an environment-variable counter.
`user_membership_tiers.monthly_voice_seconds_limit` is authoritative and defaults
to zero for TIER1 and 3,600 seconds for TIER2/TIER3. The authenticated
membership-tier admin API can change `monthlyVoiceSecondsLimit`. The dedicated
user admin voice-limit API stores a nullable, persistent personal cap: the
override takes precedence over the tier default, can lower a paid allowance to
zero, and survives monthly rollover; sending `null` restores the tier default.
It never grants Voice Tutor entitlement to TIER1. Environment configuration
controls feature/provider behavior and the per-session ceiling only. The voice
quota uses its own account-created monthly anchor and advances an overdue period
lazily on authenticated Voice Tutor access; it is not handled by the question
quota's managed rollover job.

Voice lessons follow the actual saved learning tree and each topic's configured
1–10 level. Brief 0–100 answer feedback leaves room for the learner's next intent,
without automatically escalating or chaining deeper questions. V102 freezes the
owned topic metadata supplied to each session; V101 adds nullable structured
explorations to the private result. The existing summary-model request extracts
actual exchanges and explicit spoken assessments with source turn IDs; it does
not generate ordinary questions or grade the learner again. Missing/unsupported
scores stay null. Old results decode with no explorations. These changes require
the normal Flyway migrations, not a new service, database, or Redis instance.

V105 makes `questions.id` the canonical record identity for both `QUESTION` and
`VOICE_TUTOR`. Verified voice exchanges use the same Records-tab, public-detail,
like/comment/report, visibility and deletion paths as ordinary records. The old
`voice_study_learning_records.id` is retained only for the typed evidence and
existing translation/legacy-detail contracts; common question/answer/feedback
text is stored once in `questions`. Result completion, record insertion and
translation outboxes are atomic and idempotent. The existing result recovery
also projects already structured completed results without another LLM request.
The existing configured content-translation stream accepts
`VOICE_STUDY_RECORD` and builds ko/en/ja text projections guarded by source hash
and request token. Originals, node identity, level and spoken score never change.
Private content is excluded from provider history and HTTP/client body logs.

Read combined node history at
`GET /api/v1/studies/{id}/learning-records?scope=node&limit=30&tl=ko&view=localized`;
`scope=subtree` explicitly includes descendants and `cursor` continues its stable
owner/node/scope-bound page. Its `record` field uses the same canonical ID as
`GET /api/v1/records/{id}` for both types; the legacy wrapper and cursor remain
compatible. Owner-only voice evidence detail is
`GET /api/v1/voice-tutor/learning-records/{id}` with the same `tl`/`view` options.
These reads do not generate questions, consume question quota or replace drafts.

The MCP catalog and voice Realtime functions also expose
`list_study_learning_records` (default `scope=node`, `limit=5`, `view=original`;
maximum 30) and `get_voice_learning_record` (numeric `voiceRecord.id`). The
tutor reads a small page for the agreed node before its first question, so
previous spoken answers can inform the lesson without regrading them. Voice
history reads require a verified shared tree root and recheck the active call
after fetching; ordinary MCP reads remain owner-scoped. Existing
`list_records`/`get_record` now read both types with canonical IDs and explicit
`recordType`. A voice score is nullable spoken evidence, not `gradingResult`.
The old `get_voice_learning_record` still takes the legacy evidence ID. See the
[MCP contracts](../docs/MCP_SERVER.md) for pagination and permission details.

V105 copies historical voice core text into canonical records without rerunning
the model, allocates IDs from the existing question sequence, and preserves
legacy translation IDs and frozen tree metadata. Historical rows and all
pre-migration sessions (including delayed summaries) are explicitly private;
new sessions snapshot the normal per-record sharing default, still gated by
the owner's live account sharing setting. Full session/turn identities and
recordings never appear in the public voice payload. Common record and public
feed bodies are excluded from API/client logs. Voice translation retains the
existing `VOICE_STUDY_RECORD` stream contract and source-hash/token CAS.

Deploy V105 and this application as one version: it removes the old duplicate
voice text columns after copying them, so an old JAR is not a rollback option
against the migrated schema. Stop the previous API writer before the new API
runs Flyway. Keep a verified database backup for a schema rollback; never undo
this migration by dropping new canonical records or overwriting user visibility.
No additional DB/Redis/container stack is needed. Ordinary question pending,
grading, generation, embedding, quota and statistics paths explicitly remain
`QUESTION`-only. Deletion uses the common record's existing soft-delete path;
voice evidence and translation reads honor that same tombstone.

WebRTC ready may advertise `pauseProtocol: pause-v1`. Compatible iOS clients
offer a small connected break: tutor output finishes and server input-clear ACKs
fence pause/resume, with no output cancellation or truncation. Pause still uses
connected call time and preserves the monthly and single-call deadlines; PCM
fallback and servers without the capability do not advertise this control.

The optional session-create `voice` accepts `alloy`, `ash`, `ballad`, `coral`,
`echo`, `sage`, `shimmer`, `verse`, `marin`, or `cedar`. An omitted/blank field uses
`OPENAI_REALTIME_VOICE`; unsupported explicit names fail before reserving time.
The iOS Settings preference is captured for the next call, never applied to an
active one. See the [Realtime voice contract](https://developers.openai.com/api/docs/guides/realtime-conversations#voice-options).

Voice Tutor recording is a separate, default-off capability. A session can
receive a recording upload only when the registered owner explicitly sends
`recordingConsent=true` together with the fixed `voice-recording-v1` consent
version at reservation time. One mixed AAC-in-M4A (`audio/mp4`) object is
allowed per session. MySQL stores metadata and consent audit fields only, never
the binary audio. Upload completion HEAD-checks the signed content type, exact
byte length, and SHA-256 checksum before the object becomes available. Signed
PUTs carry `If-None-Match: *`, so a completed object cannot be overwritten with
the still-live grant. Upload and playback URLs are short-lived, owner-only S3
signatures. Bucket names and object keys are not returned as standalone response
fields, but the signed URL necessarily contains its bucket host and object-key
path until expiry; the app must not log it, and MCP never receives it. Every
metadata lookup is joined to the authenticated
session owner, deletion is idempotent and immediate, and the retention job
atomically claims an expired metadata snapshot as deleted before removing its object. Object deletion removes
every S3 version and delete marker, not just the current key. Account withdrawal
first commits an FK-free owner-prefix cleanup tombstone in an independent
transaction, attempts an immediate prefix delete, and then completes relational
cleanup even if that first storage call fails. Because S3 may allow a PUT that started
before signature expiry to finish afterward, the managed job repeatedly deletes
owner-deleted keys and withdrawn prefixes minutely through the configured safety
deadline after the applicable latest grant expiry, then daily forever. Withdrawal
tombstones are permanent; storage or outer transaction failures cannot roll back
the independently committed marker. The private bucket must independently expire
current versions, noncurrent versions, and delete markers no later than the configured
recording retention so untracked or unusually late uploads also have a storage-level bound.
Keep the bucket configured while
retained objects exist even if new capture is disabled. MCP intentionally has
no recording upload, URL, download, or original-audio resource.

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

For a local dev runtime with a volume-mounted JVM artifact, refresh the external
`/app/db/migration-mysql` SQL files from the same source revision as the JAR,
preserving already applied migration files. With the existing filesystem Flyway
location, replacing only the JAR does not make new migrations available, even
when that JAR contains them. Restart only the existing API after checking there
are no active voice calls; preserve its dev profile, AWS configuration and
database/Redis infrastructure, and verify the expected migration versions.

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
