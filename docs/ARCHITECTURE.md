# BuddyStudy Architecture

## Overview

BuddyStudy is a SwiftUI app with shared domain logic across macOS and iOS. The app keeps lightweight local state for settings, drafts, and recoverability, but production question generation, answer grading, API-key validation, scheduled delivery, settings, records, and statistics are owned by the Spring Boot Kotlin backend. Study records are not persisted in a local SQLite database; they are held as an in-memory view cache and refetched from the backend. The app never calls OpenAI directly; OpenAI requests are made only from the backend using the user's stored API key. iCloud/CloudKit state sync is no longer exposed or enabled; backend persistence is the active source of truth. Internal target names, bundle identifiers, background task identifiers, and legacy CloudKit record types retain `StudyMate` to avoid breaking existing installs.

## Targets

- `StudyMateiOS`: iOS app and current public release target.
- `StudyMate`: macOS menu bar app, currently not shipped publicly while macOS update/sync UX is paused.
- `StudyMateTests`: unit tests for storage, OpenAI prompt/parsing helpers, backend routing, notification routing, and statistics logic.

## Main Modules

- `Models/StudyModels.swift`
  - Domain models: `StudySettings`, `Difficulty`, `QuestionItem`, `StudyRecord`, `GradingResult`.
  - Display strings through `AppStrings`.
  - Shared topic grouping through `TopicGrouping`.

- `ViewModels/AppState.swift`
  - Main `ObservableObject`.
  - Owns runtime state, drafts, selected tab, backend sync state, pending question limits, and user actions.
  - Coordinates backend API calls, local persistence, notifications, and timers.
  - Should delegate reusable decision logic to `Core/*` policies and backend action boundaries to `UseCases/*`.

- `Core/`
  - App-wide pure policies and shared decision rules.
  - `AppRuntime/AppActionRunner.swift` provides the common async action boundary for view-model orchestration: execute a use case, apply success state, route errors through app policy, and clear loading state from one repeatable shape.
  - New or modified async view-model flows should use the common action boundary unless they are pure local state mutations or low-level callback bridges.
  - `ErrorHandling/BackendErrorPresentationPolicy.swift` converts backend error codes into UI presentation, login, page-access, and identity-reset decisions.
  - `ErrorHandling/AppErrorHandlingPolicy.swift` maps backend error presentation into app UI behavior so login/device/token errors do not leak as repeated popups or feature banners.
  - Backend error parsing accepts both string error identifiers and numeric code ranges. Access-token expiry refreshes only the access token; only explicit device credential, device mismatch, or device-not-found codes replace the backend device identity.
  - Client-side decoding and cancellation errors are normalized by the same error policy so raw system messages such as missing-key decoding failures do not appear as repeated popups.
  - Protected tab UI must not pre-fetch page access. The app opens the tab and lets the requested backend API return auth, permission, or terms errors through the common error policy.

- `UseCases/`
  - Thin application action boundaries around backend capabilities.
  - `AppUseCases.swift` is the app composition root for use-case construction. `AppState` should replace this container when the backend client changes instead of constructing individual use cases in multiple places.
  - `Notifications/NotificationsUseCase.swift` centralizes backend notification list, unread count, individual/all read, delete, and clear operations.
  - `Records/RecordsUseCase.swift` centralizes backend record operations such as fetch, grading, draft saving, skipping, deletion, publicity, and full clear.
  - `Settings/SettingsUseCase.swift` centralizes backend settings, model option, API validation, and schedule sync requests.
  - `Stats/StatsUseCase.swift` centralizes backend topic statistics and activity requests.
  - `StudyRoom/StudyRoomUseCase.swift` centralizes study room backend operations such as study fetch/create/update/delete, quota lookup, and backend question creation.
  - `Community/CommunityUseCase.swift` centralizes public question, sign-in, profile, like, report, and comment backend operations.
  - `AppState` may still orchestrate state application and recovery, but it should not grow new direct backend action logic when a use-case boundary exists.

- `Services/SettingsStore.swift`
  - Local persistence facade.
  - Stores settings, API keys, draft state, backend metadata, and exposes an in-memory record cache for the current session.
  - Caps records at the configured history limit.
  - Stores backend device registration and a stable installation identifier in the iOS Keychain. The registration request sends the identifier over TLS, request logs redact it, and the backend persists only its SHA-256 hash so repeated registration is idempotent.

- `Services/OpenAIClient.swift`
  - Contains shared prompt/body/parsing helpers only.
  - It has no runtime networking methods; app-side OpenAI network calls are intentionally unavailable.
  - Uses the configured supported model list from `OpenAIModelOption` for settings validation and backend payloads.

- `Services/CloudSyncService.swift`
  - Legacy CloudKit implementation retained for source compatibility.
  - iCloud/CloudKit sync is not exposed or enabled in the current iOS release path.

- `Services/NotificationService.swift`
  - Handles local notifications, notification actions, iOS remote notification bridge, and macOS study window foregrounding.

- `backend/`
  - Spring Boot Kotlin APNs push backend.
  - Runs Spring WebFlux on Reactor Netty with Kotlin suspending controllers, use cases, persistence ports, and security lookups.
  - Uses Spring Data R2DBC and the MySQL R2DBC driver for runtime database access. Request-path persistence does not use JPA, Hibernate, Hikari, JDBC, or a blocking executor.
  - Uses reactive transaction context for suspending `@Transactional` methods. Entity mutation must be persisted explicitly because Spring Data Relational does not provide JPA dirty checking, lazy loading, or managed entity sessions.
  - Uses JDBC only during application startup when Flyway migrations are enabled. Runtime details and migration trade-offs are documented in [`R2DBC_MIGRATION.md`](R2DBC_MIGRATION.md) and [`WEBFLUX_MIGRATION.md`](WEBFLUX_MIGRATION.md).
  - The backend is organized as multi-module hexagonal architecture: `domain`, `application`, `infra`, and executable `tutor`.
  - Incoming web/scheduler/stream handlers live in `backend/infra`, persistence/OpenAI/APNs/Redis integrations live in `backend/infra`, and use-case services live in `backend/application` behind inbound port interfaces.
  - Spring Data Relational entities live in `backend/domain`; outbound adapters use coroutine repositories, `R2dbcEntityTemplate`, or `DatabaseClient` for explicit SQL.
  - Topic-level statistics calculation is separated into `backend/application/src/main/kotlin/com/buddystudy/backend/stats/StatsService.kt` and is consumed by study application services.
  - Public base URL: `https://api.ghkdqhrbals.org`.
  - Runs behind Nginx on host port `443`.
  - API request, exception, and authentication logs share `ApiLoggingPolicy`. The `dev` profile emits compact method/path/status/duration logs without body capture, request IDs, IP addresses, headers, or full stack traces; production keeps detailed structured logs for operations.
  - Uses a private Dockerized MySQL container with a persistent named volume.
  - Calls OpenAI for API-key validation, question generation, and answer grading.
  - Stores generated questions in MySQL before sending APNs notifications.
  - Owns Google-linked community profiles, public question browsing metadata, and question reports.
  - Treats anonymous identities as installation credentials rather than administrator-visible members. Admin user and quota queries exclude `ANONYMOUS` rows.
  - Public question like/comment counts use source-of-truth reaction tables plus a `question_stats` read model; stream hooks are wired through direct Redis Streams.
  - Transactional domain writes append `PENDING` typed events to `redis_event_outbox` or `question_push_outbox` in the same R2DBC transaction. After commit, `OutboxPublicationService` immediately claims, publishes, and completes each row. `OutboxRecoveryScheduler` uses that same flow for failures and abandoned claims, so polling is a recovery mechanism rather than the normal delivery path.
  - `RedisStreamTopicManager` is the single Redis client boundary for topic registration, Spring Data Redis consumer-group reads, acknowledgement, retention, administrator inspection, and Lettuce `XAUTOCLAIM`.
  - Typed consumers declare topic, event type, Jackson payload class, group, consumer, batch size, and timing through `@StreamListener`. A handler is acknowledged only after Jackson conversion and handler completion.
  - Database outbox claims use a lease plus UUID fencing token. Immediate request publication and scheduled recovery may race, but only the token owner can mark a row published or retryable. See [`EVENT_OUTBOX_ARCHITECTURE.md`](EVENT_OUTBOX_ARCHITECTURE.md).
  - Redis consumer pending recovery is declared through `@StreamScheduler`; entries older than the configured idle time are claimed and passed through the same typed handler contract. Push publishing, consuming, and idle recovery are owned together by `PushStreamManager`; notification events use the same annotation-based typed consumption.
  - Domain-event and push streams are atomically trimmed to independently configured exact maximum lengths. Both default to 1,000 entries, while durable event and push outboxes remain the source for diagnosis and recovery when bounded Redis retention advances.
  - Push delivery uses one consumer group with ten stable concurrent worker identities by default. Blocking Redis reads run on `Dispatchers.IO` instead of Reactor event-loop threads.
  - Redis delivery is at-least-once. `(event_type, event_id)` is the producer idempotency key, and consumers must keep their existing event-id deduplication because a crash can occur after Redis accepts an event but before the outbox row is marked published.
  - Outbox SQL uses jOOQ classes generated from the ordered Flyway migration history, so table and column changes fail at compile/code-generation time instead of relying on untyped row maps.
  - Forwards reports by SMTP only when report-email secrets are configured; reports are still stored when email delivery is unavailable.
  - Stores studies as a MySQL adjacency list through `studies.parent_study_id`. Root studies use `NULL`; child depth is not capped by the schema or API.
  - Uses `sort_order` plus `id` for stable sibling ordering. A self-referencing foreign key cascades subtree deletion at the study layer, while question soft deletion resolves the same subtree with a recursive CTE before deleting the studies.
  - Keeps study identity on record responses so the iOS cache can remove only records owned by a deleted subtree, even when two branches use the same topic label.
  - Root studies own schedule state and question-generation settings. Descendants own topic, difficulty, ordering, and `active_for_questions`; scheduled claims never target descendants directly.
  - Keeps three write boundaries explicit: `POST /api/v1/studies` creates a root, `POST /api/v1/studies/{parentStudyId}/topics` creates a descendant without generating a question or consuming quota, and `POST /api/v1/studies/{topicId}/questions` generates a question.
  - Selects the next active node from the complete root subtree by oldest `last_sent_at`, with never-selected nodes first and stable `sort_order`/`id` tie-breaking. This supports deterministic round-robin delivery across any number of active nodes.
  - Stores both manual and scheduled questions with the root study ID while copying the selected node's topic and difficulty into the question. Inactive nodes remain available for manual generation.
  - `POST /api/v1/studies/{id}/topic-suggestions` requests unique GPT suggestions for a parent node, and `PATCH /api/v1/studies/{id}/question-activation` changes only rotation participation.
  - Rejects duplicate topics using a trim, case-fold, and repeated-whitespace normalized key across all studies owned by the user.
  - Resolves monthly question allowance from the active membership tier and an optional per-user override. `GET /api/v1/questions/quota` returns usage, allowance, remaining count, and the next UTC reset instant.
  - Provides authenticated admin APIs for paginated user search, tier allowance updates, and per-user tier/override assignment. Payment-plan metadata is never returned by the consumer quota endpoint.
  - Exposes Redis Stream inspection under the unified Monitoring `Manage` section. The standalone analytics admin does not duplicate this operational surface.
  - Provides authenticated topic/key search, cursor-based Redis Stream inspection, exact stream-entry ID lookup, consumer groups, `redis_event_outbox`, and `question_push_outbox`. Nested credentials are redacted before serialization. See [`REDIS_STREAM_OPERATIONS.md`](REDIS_STREAM_OPERATIONS.md).

- `Views`
  - `StudyView`: active question and pending question workflow.
  - `HistoryView`: record search, pagination, detail, and deletion.
  - `StatisticsView`: topic-level statistics, period filtering, trend charts, and grouped topic stats.
  - `SettingsView`: macOS settings.
- `MobileRootView`: iOS tabs, onboarding, profile category hub, settings, notification inbox, and study-tree interaction.
  - The primary tab bar exposes Home, Records, Statistics, and Notifications. Settings is a profile-hub destination so account and app preferences share one predictable entry point.
  - Public-question visibility is persisted through `PATCH /api/v1/profile` as `allowPublicQuestions`; it is independent of protected-page access policy.
  - Per-topic question generation exposes its category-scoped in-flight state so only the selected study room renders the inline loading message.
  - The compact My Studies outline animates child drill-down toward the leading edge and parent navigation toward the trailing edge. Root expand/collapse stays immediate so opening a top-level study does not create an extra vertical sliding effect.
  - The notification inbox calls `POST /api/v1/notifications/read-all` and updates loaded rows through `NotificationStateStore` only after the backend mutation succeeds.

## Markdown Message Contract

- `question`, `expectedAnswerHint`, grading `feedback`, and grading `explanation` are stored and returned as Markdown source. Existing plain text remains valid, so this contract does not require a schema migration or a parallel HTML field.
- The backend asks the model for a restrained Markdown subset: paragraphs, emphasis, lists, inline code, and fenced code blocks. Generated HTML is not part of the contract.
- The backend stores model Markdown exactly as received; it does not normalize or rewrite generated content before persistence. iOS renders the stored source with MarkdownUI, while the backend uses commonmark-java only to derive notification-safe plain text.
- iOS permits only `http` and `https` links from rendered Markdown. Unsupported link schemes remain visible as text without an active link.
- Dense list rows use a plain-text projection for predictable height. Full question, hint, answer, feedback, and explanation bubbles render Markdown.
- APNs and local notification bodies use a plain-text projection of the stored Markdown so notification surfaces do not expose formatting markers.

## Data Flow

```text
Manual action / pull-to-refresh / backend scheduled interval
-> AppState.generateQuestion
-> RemotePushBackendClient.createQuestion
-> backend POST /api/v1/me/questions
-> backend calls OpenAI and stores an ungraded StudyRecord in MySQL
-> SettingsStore caches the returned StudyRecord
-> current question updates only when it is safe to activate
-> APNs notification is sent by the backend for scheduled questions
```

```text
User answer
-> AppState saves answer draft
-> AppState.gradeCurrentAnswer or gradeRecord
-> RemotePushBackendClient.gradeRecord
-> backend calls OpenAI and persists score, feedback, and explanation
-> SettingsStore updates StudyRecord
-> StatisticsView recalculates topic ranges from records
-> backend stats are refreshed from MySQL records
```

```text
My Studies root
-> GET /api/v1/studies
-> app builds parentStudyId adjacency map
-> compact branch navigation uses opposite horizontal transitions for child and parent movement
-> recursive tree layout renders circular nodes with restrained level colors
-> the user can switch orientation, pinch or button zoom, drag nodes, and reset saved positions
-> selecting a node opens the root-owned question page with that node as the manual topic
-> plus opens GPT recommendations first, with manual entry as an explicit alternative
-> adding a root calls POST /api/v1/studies
-> adding a child calls POST /api/v1/studies/{parentStudyId}/topics with sortOrder, topic, level, and initial question activation
-> child creation cannot call OpenAI and cannot reserve monthly question quota
-> node activation controls scheduled round-robin participation without disabling manual generation
-> tree options support multi-select activation, pause, and subtree deletion
-> deleting a node soft-deletes questions for the resolved subtree, then cascades the study subtree
```

```text
Root schedule becomes due
-> backend claims the root only
-> complete subtree is resolved
-> active node with the oldest lastSentAt is selected
-> root OpenAI model and prompt generate a question for the selected node's topic and level
-> question is stored under the root study ID
-> selected node lastSentAt and root nextDueAt are advanced atomically
-> Redis outbox publishes the APNs notification
```

```text
Profile > Usage appears or a quota-related request fails
-> GET /api/v1/questions/quota
-> active membership tier + optional user override determine monthlyLimit
-> monthly usage determines remainingCount
-> app renders remaining/monthly/reset only in the Usage category without exposing the internal plan
-> QUOTA_EXCEEDED routes through the shared app error policy and shows reset time inline
```

```text
Public community feed
-> GET /api/v1/public/questions
-> app maps server questions into typed MobileHomeFeedItem values
-> locally scheduled placement may insert non-question items such as feedback prompts
-> feedback prompt opens a dedicated form
-> POST /api/v1/feedback stores an APP_FEEDBACK report without requiring a question row
```

## Sync Model

- Backend sync stores settings, records, answer drafts, generated questions, grading results, and topic statistics.
- API key backend sync is supported for the regular OpenAI key; admin keys are not supported.
- Backend settings sync uploads the regular OpenAI API key only when it changes or when backend settings need to be initialized.
- A backend device registration can be created without an APNs token so manual question generation, grading, settings, records, and stats can work before notification permission/token delivery.
- When APNs token registration later succeeds, the existing backend device is updated instead of creating a separate backend identity.
- If a local ungraded current question has an answer draft, remote current questions do not replace the active answer page.

## Push Model

- iPhone registers for remote notifications through `UIApplication`.
- iPhone app timers only run while the app process is active. For locked/background delivery, the app opportunistically pre-generates at most one pending question notification when entering background and schedules it for the configured interval. If a question notification is already pending, it does not create another. `BGAppRefresh` is also requested at the next due date, but iOS does not guarantee exact wake-up timing.
- The Spring Boot Kotlin backend is the production path for server-scheduled APNs delivery. It stores APNs tokens and schedules in MySQL, keeps user OpenAI keys encrypted at rest when provided, creates due questions with OpenAI, stores the question and `question_push_outbox` entry in one transaction, publishes the outbox through the dedicated `buddystudy-push-v1` Redis Stream, and sends APNs alerts from an `@StreamListener` consumer. Successful push messages use `StreamOptions.ACK_DEL`; failures remain pending for `@StreamScheduler` auto-claim recovery.
- Scheduled delivery requires an APNs token. If a backend device exists without a token, the scheduler defers the due item instead of generating an undeliverable push.

## Community Identity

- Device credentials are used to register a backend device and bootstrap or refresh an access token.
- Access tokens carry both `user_id` and `device_id`; protected API calls resolve the current principal from those claims and the stored user-device mapping.
- Google Login links a verified Google subject to the registered device through `users` and `devices.user_id`.
- New Google and email users receive a cryptographically randomized `Adjective-Noun-####` display name. MySQL stores a generated, normalized key for non-anonymous users and enforces it with `uq_users_display_name_key`; creation retries a new candidate when a concurrent collision is detected.
- Anonymous installation rows are excluded from that display-name key so their internal `Buddy` placeholder can repeat. Profile edits use the same database constraint and return `DISPLAY_NAME_TAKEN` on conflict.
- User status is one of `ANONYMOUS`, `ACTIVE`, or `WITHDRAWN`. Account deletion immediately removes the active profile, sign-in mappings, public questions, and related study records, then reconnects the current device to an anonymous user.
- Public question rows expose only the author's public profile fields: display name, bio, pixel-avatar symbol, and color seed. The iOS app renders the avatar locally and does not upload or display user photos.
- Question publicity defaults to private unless the signed-in user enables public sharing.
- Reports are stored in MySQL and can optionally be emailed to the operator Gmail through SMTP settings.

## Topic Statistics

- Topic grouping uses `TopicGrouping.normalizedKey`.
- The key removes case, spacing, hyphen, underscore, punctuation, width, and diacritic differences.
- Simple camelCase boundaries are separated before normalization.
- The displayed topic is the most frequent/recent label in the merged group.
- Topic range is estimated from difficulty level and score, then widened by small sample size and conflicting evidence.
- Backend topic statistics are a materialized read model in `user_stats`.
- `questions` remains the source of truth. Answer grading and record deletion mark only the affected `(user_id, stat_date, topic_key, difficulty_level)` bucket in `user_stats_dirty_keys`.
- The refresh job processes dirty keys in bounded batches and recomputes each bucket with MySQL aggregation instead of loading all questions into application memory.
- Dirty key processing is at-least-once and idempotent: recalculating the same bucket deletes and reinserts only that bucket's `user_stats` rows.
- Refresh claims dirty rows with `FOR UPDATE SKIP LOCKED`; if the process crashes before commit, the transaction rolls back and the dirty rows remain for the next run.
- Refresh deletes a dirty row only when its `updated_at` still matches the claimed value. If a new answer/delete updates the same bucket during refresh, the dirty row is kept and retried in a later batch.
- H2/test environments fall back to a full rebuild path; production MySQL uses incremental dirty-key refresh.

## Internal Membership Administration

- `user_membership_tiers` is the operator-managed plan catalog and owns the default monthly limit.
- `user_memberships.monthly_question_limit_override` is nullable. `NULL` inherits the tier value; a non-negative value overrides it for that user.
- Monthly quota periods are anchored to each user's `users.created_at` timestamp rather than calendar-month boundaries. A user created on the 7th resets on the 7th at the same UTC instant each month.
- Month-end anchors use the target month's last valid day without drift. For example, a January 31 anchor resets on February 28 (or 29) and then March 31.
- `user_monthly_question_usage.period_start` is the exact period identity used by consumption, permission evaluation, quota responses, analytics, and admin views. `usage_month` remains only as a legacy reporting label.
- The monitoring Users & Quotas page proxies admin APIs through the authenticated monitoring origin. It does not persist backend admin tokens outside the browser session.
- User search is bounded to 100 rows per API call and the UI uses 20-row pages.

## Build And Verification

Recommended local checks:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
```

Use real-device builds when changing push, entitlements, or background refresh behavior.
