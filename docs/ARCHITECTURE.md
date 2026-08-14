# BuddyStudy Architecture

The Apple StoreKit membership payment ledger, transaction boundaries, event
sourcing rules, App Store notification authority, and operations setup are
documented in [BILLING.md](BILLING.md).

The canonical iOS screen routes, compatibility aliases, push landing behavior,
and administrator-message destinations are documented in
[DEEPLINKS.md](DEEPLINKS.md).

## Overview

BuddyStudy is a SwiftUI app with shared domain logic across macOS and iOS. The app keeps lightweight local state for settings, drafts, and recoverability, but production question generation, answer grading, API-key validation, scheduled delivery, settings, records, and statistics are owned by the Spring Boot Kotlin backend. Study records are not persisted in a local SQLite database; they are held as an in-memory view cache and refetched from the backend. The app never calls OpenAI directly; OpenAI requests are made only from the backend through workload-scoped clients and credentials. iCloud/CloudKit state sync is no longer exposed or enabled; backend persistence is the active source of truth. Internal target names, bundle identifiers, background task identifiers, and legacy CloudKit record types retain `StudyMate` to avoid breaking existing installs.

The backend has one source tree and one container contract with two selectable
runtime artifacts: GraalVM Native Image (`native`, the production default) and
a regular Java 25 executable jar (`jvm`). Native builds use GraalVM 25; JVM
builds and execution use Eclipse Temurin 25. `BACKEND_RUNTIME` selects only the
Docker build stage. Both artifacts use the same ports, profiles, environment
variables, migrations, persistence adapters, and deployment workflow, so a
runtime comparison or rollback does not fork application behavior.

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
  - Transient infrastructure failures (`408`, `429`, and `5xx`) and invalid backend responses keep their status, request ID, and diagnostic body in internal logs but resolve to localized, feature-specific retry guidance in user-facing UI. Structured non-transient business errors continue to use their server-provided messages.
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
  - `Community/CommunityUseCase.swift` centralizes public question, sign-in, profile, like, report, abusive-user blocking, and comment backend operations.
  - `AppState` may still orchestrate state application and recovery, but it should not grow new direct backend action logic when a use-case boundary exists.

- `Services/SettingsStore.swift`
  - Local persistence facade.
  - Stores settings, API keys, draft state, backend metadata, and exposes an in-memory record cache for the current session.
  - Does not trim records as a retention policy. The backend owns durable record history, while the app incrementally fills its in-memory cache from paginated responses.
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

- `SentryMonitoring.swift`
  - Initializes the iOS Sentry SDK before app state is created.
  - Accepts only error and fatal events. Product analytics, logs, metrics, automatic session health, and performance tracing are disabled.
  - Keeps ordinary Session Replay sampling at zero and uploads the masked replay buffer only for error events. Text, images, request bodies, and network headers are not recorded in Replay.
  - PostHog is not linked, initialized, or supplied through the iOS release workflow.

- `AppAnalytics.swift`
  - Owns the iOS-only Firebase Analytics integration and exposes typed product events.
  - Uses `FirebaseAnalyticsCore`, disables advertising-network registration and automatic SwiftUI screen reporting, and never sets a Firebase user ID.
  - Rejects missing, placeholder, disabled, or bundle-mismatched Firebase configuration instead of starting partial collection.
  - Keeps local Debug collection disabled unless `BUDDYSTUDY_GA_DEBUG=1` is explicitly supplied.
  - The release workflow replaces the repository placeholder with the `GOOGLE_SERVICE_INFO_PLIST_BASE64` GitHub Secret before building.

- `backend/`
  - Spring Boot Kotlin APNs push backend.
  - Runs Spring WebFlux on Reactor Netty with Kotlin suspending controllers, use cases, persistence ports, and security lookups.
  - Uses Spring Data R2DBC and the MySQL R2DBC driver for runtime database access. Request-path persistence does not use JPA, Hibernate, Hikari, JDBC, or a blocking executor.
  - Uses reactive transaction context for suspending `@Transactional` methods. Entity mutation must be persisted explicitly because Spring Data Relational does not provide JPA dirty checking, lazy loading, or managed entity sessions.
  - Uses JDBC only during application startup when Flyway migrations are enabled. Runtime details and migration trade-offs are documented in [`R2DBC_MIGRATION.md`](R2DBC_MIGRATION.md) and [`WEBFLUX_MIGRATION.md`](WEBFLUX_MIGRATION.md).
  - The backend is organized as multi-module hexagonal architecture: `domain`, `application`, `infra`, and executable `tutor`.
  - Incoming web/scheduler/stream handlers live in `backend/infra`, persistence/OpenAI/APNs/Redis integrations live in `backend/infra`, and use-case services live in `backend/application` behind inbound port interfaces.
  - Spring Data Relational entities live in `backend/domain`; outbound adapters use coroutine repositories, `R2dbcEntityTemplate`, or `DatabaseClient` for explicit SQL.
  - Closed entity attributes such as provider, lifecycle status, language, platform, source, and notification type use Kotlin enums. Spring Data R2DBC stores enum names as `VARCHAR`; enums with lowercase external database codes use explicit reading and writing converters. Flyway migrations document every entity-backed table and constrained column with MySQL comments, list allowed enum values, and add matching `CHECK` constraints. Free-form text, identifiers, and extensible catalog codes remain strings.
  - Topic-level statistics calculation is separated into `backend/application/src/main/kotlin/com/buddystudy/backend/stats/StatsService.kt` and is consumed by study application services.
  - Public base URL: `https://api.ghkdqhrbals.org`.
  - Runs behind Nginx on host port `443`.
  - API request, exception, and authentication logs share `ApiLoggingPolicy`. The `dev` profile emits compact method/path/status/duration logs without body capture, request IDs, IP addresses, headers, or full stack traces; production keeps detailed structured logs for operations.
  - Uses a private Dockerized MySQL container with a persistent named volume.
  - Separates OpenAI workload ownership. `SystemOpenAIClient` and
    `OPENAI_API_KEY_SYSTEM` serve only post-study child-topic suggestions.
    `OpenAIClient` and `OPENAI_API_KEY_USER` serve question generation,
    embeddings, translation, user-answer feedback, grading, and grading
    previews. Production startup and deployment reject missing or identical
    workload keys.
  - Answer submission and AI grading are separated by a transactional outbox. `POST /api/v1/records/{id}/answer` stores the immutable submitted answer, changes the question projection from `UNGRADED` to `GRADING`, appends the first durable `question_grading_events` event, stores its ID as `grading_last_event_id`, and writes the `ANSWER_GRADING_REQUESTED` outbox row in one transaction. It returns `202 Accepted` with `questionStatus`, `correlationId`, and `gradingLastEventId`, and never waits for OpenAI.
  - `AnswerGradingStreamListener` consumes the typed Redis domain event and runs evidence analysis, criticism, judging, and optional adjudication through `AnswerGradingService`. Each transition is persisted before it is exposed to clients; completion stores the AI decision and statistics dirty key atomically.
  - `question_grading_events` is the append-only grading lifecycle event store. Every row records both the detailed grading stage and the resulting question lifecycle state; `questions.status` and `questions.grading_last_event_id` are the current read projection updated in the same transaction. `GET /api/v1/answer-processes/{correlationId}` exposes request-scoped progress as a one-shot polling response. The iOS app sends the last durable event ID as `after`, receives all newer stages without gaps, and polls at the fixed three-second product interval while `questionStatus=GRADING` and the grading status is non-terminal. Leaving the screen cancels only status polling; an already-sent answer request is allowed to finish and persist its accepted state.
  - Stores generated questions in MySQL before sending APNs notifications.
  - Owns community profiles, public question browsing metadata, question reports, and persistent per-user block relationships. Authenticated public-question lists, detail reads, and comment lists enforce the block relationship server-side so reinstalling the app or using another device cannot restore blocked content.
  - Treats anonymous identities as installation credentials rather than administrator-visible members. Admin user and quota queries exclude `ANONYMOUS` rows.
  - Public question views, likes, unlikes, comment creation, and comment deletion are transactionally written to `redis_event_outbox` and published to dedicated Redis Streams. Reaction tables remain the source of truth. View events update the idempotent `question_stats` read model, while reaction events are durably consumed into Inbox history without applying the already-committed reaction count a second time.
  - Transactional domain writes append `PENDING` typed events to `redis_event_outbox` in the same R2DBC transaction. Notification events carry their own `shouldPush` option, so inbox creation and optional push delivery cannot diverge through separate outboxes. After commit, `OutboxPublicationService` immediately claims, publishes, and completes each row. `OutboxRecoveryScheduler` uses that same flow for failures and abandoned claims, so polling is a recovery mechanism rather than the normal delivery path. Claim failures fail the managed job and retain their stack trace instead of being reported as a successful zero-row run.
  - `RedisStreamTopicManager` is the single Redis client boundary for topic registration, Spring Data Redis consumer-group reads, acknowledgement, retention, administrator inspection, and Lettuce `XAUTOCLAIM`.
  - Typed consumers declare topic, event type, Jackson payload class, group, consumer, batch size, and timing through `@StreamListener`. A handler is acknowledged only after Jackson conversion and handler completion.
  - Database outbox claims use a lease plus UUID fencing token. Immediate request publication and scheduled recovery may race, but only the token owner can mark a row published or retryable. See [`EVENT_OUTBOX_ARCHITECTURE.md`](EVENT_OUTBOX_ARCHITECTURE.md).
  - Redis consumer pending recovery is declared through `@StreamScheduler`; every registered topic has a matching scheduler in the same consumer group because `XAUTOCLAIM` cannot recover another group's pending list. Startup explicitly creates the named `-recovery` consumer so it is visible before the first failure. Entries older than the configured idle time are claimed and passed through the same typed handler contract.
  - A failed Stream handler receives at most three Inbox attempts. The third failure is recorded as terminal and acknowledged out of the pending list, but the Redis Stream entry is retained for operator inspection until normal bounded retention trims it.
  - Domain-event and push-delivery streams are atomically trimmed to independently configured exact maximum lengths. Both default to 1,000 entries. `redis_event_outbox`, `app_notifications` push state, and the consumer Inbox remain the durable diagnosis and recovery surfaces when bounded Redis retention advances.
  - Push delivery uses one consumer group with ten stable concurrent worker identities by default. Blocking Redis reads run on `Dispatchers.IO` instead of Reactor event-loop threads.
  - Redis delivery is at-least-once. `(event_type, event_id)` is the producer idempotency key, and consumers must keep their existing event-id deduplication because a crash can occur after Redis accepts an event but before the outbox row is marked published.
  - Outbox SQL uses jOOQ table and field classes generated from the ordered Flyway migration history, so table and column changes fail at compile/code-generation time instead of relying on untyped row maps. Reads use explicit field projections rather than generated mutable record construction so the same claim path works in GraalVM native images without reflective record creation.
  - Forwards reports by SMTP only when report-email secrets are configured; reports are still stored when email delivery is unavailable.
  - Stores studies as a MySQL adjacency list through `studies.parent_study_id`. Root studies use `NULL`; child depth is not capped by the schema or API.
  - Uses `sort_order` plus `id` for stable sibling ordering. A self-referencing foreign key cascades subtree deletion at the study layer, while question soft deletion resolves the same subtree with a recursive CTE before deleting the studies.
  - Keeps study identity on record responses so the iOS cache can remove only records owned by a deleted subtree, even when two branches use the same topic label.
  - Separates read contracts by purpose: `GET /api/v1/records/{recordId}` is record detail, `GET /api/v1/studies/{studyId}` is one study room with both its latest pending and latest completed question, and paginated `GET /api/v1/studies` is tree/list synchronization. The iOS room prefers the pending question and otherwise keeps the latest completed question, submitted answer, and AI response visible.
  - Root studies own schedule state and question-generation settings. Descendants own topic, difficulty, ordering, and `active_for_questions`; scheduled claims never target descendants directly.
  - Keeps three write boundaries explicit: `POST /api/v1/studies` creates a root, `POST /api/v1/studies/{parentStudyId}/topics` creates a descendant without generating a question or consuming quota, and `POST /api/v1/studies/{topicId}/questions` generates a question.
  - Authorizes study and topic creation with `study:create`, which has no monthly question requirement. Manual question generation uses the separate `question:create` permission and is the only one of these write paths guarded by monthly question quota.
  - Question generation is an asynchronous Choreography Saga. Submission reserves quota and stores the `QUEUED` Saga plus `QUESTION_GENERATION_REQUESTED` outbox in one transaction, then returns `202 Accepted` with a correlation ID. Generation and translation use separate Redis consumer groups and `(event_id, consumer_group)` Inbox leases; the canonical Saga advances through compare-and-set transitions. Every handler commits business state and its durable outbox before finalizing the Inbox lease, so a database rollback cannot acknowledge an uncommitted effect. A terminal generation or translation failure stores `FAILED` plus `QUESTION_GENERATION_ROLLBACK_REQUESTED`; its dedicated consumer removes any ungraded generated question and projections, reverses coverage, refunds quota, and sets `rollback_completed_at` transactionally. A failed process remains non-terminal and retains its topic lock until that compensation commits. The iOS client polls `GET /api/v1/question-processes/{correlationId}` and resumes a persisted process after restart. See [`QUESTION_GENERATION_SAGA.md`](QUESTION_GENERATION_SAGA.md).
  - Korean, English, and Japanese delivery preserves independent source languages for the question, user answer, AI response, and each comment. Question generation (manual or scheduled), answer submission, grading completion, and comment creation atomically append the missing per-language `QUESTION`, `ANSWER`, `AI_RESPONSE`, or `COMMENT` `CONTENT_TRANSLATION_REQUESTED` rows to the shared transactional Outbox. Publication starts after commit; a failed immediate publish is recovered by the normal Outbox worker. Localized reads retain read-repair behavior: a missing or stale translation returns the original immediately and idempotently requeues only the missing part. Translation uses the configurable provider chain while retries and terminal failures remain isolated through the Redis Inbox consumer. A durable request token deduplicates concurrent write-through and read-repair requests, while `PENDING` or `FAILED` work older than five minutes receives a new token. Production LibreTranslate runs as an internal-only deployment module on the backend Docker network; the backend keeps OpenAI as the next provider in the chain. See [`QUESTION_LOCALIZATION.md`](QUESTION_LOCALIZATION.md).
  - Selects the next eligible active node from the complete root subtree by oldest `last_sent_at`, with never-selected nodes first and stable `sort_order`/`id` tie-breaking. Nodes already at their per-topic pending-question limit are excluded before selection, preventing one unanswered branch from starving the rest of the tree; the root backs off only when every active node is blocked.
  - Stores both manual and scheduled questions with the root study ID while copying the selected node's topic and difficulty into the question. Inactive nodes remain available for manual generation.
  - `POST /api/v1/studies/{id}/topic-suggestions` requests unique GPT suggestions for a parent node, and `PATCH /api/v1/studies/{id}/question-activation` changes only rotation participation.
  - `system_topic_catalog` is the shared source for reusable topic suggestions. A lookup is keyed by normalized root plus the hashed ancestor path, language, and child depth. A cache miss invokes the system model, then idempotently stores the generated children.
  - The managed catalog supports five descendant levels and up to ten children per opened branch. User study rows are materialized lazily from selected suggestions instead of eagerly cloning a combinatorial tree; newly created nodes remain active for question rotation by default.
  - Rejects duplicate topics using a trim, case-fold, and repeated-whitespace normalized key across all studies owned by the user.
  - Resolves monthly question allowance from `user_entitlement_projection` and the policy-v5 `user_quota` projection. A verified upgrade immediately raises the base limit without clearing committed usage, active reservations, or the current-period bonus. A downgrade remains pending without changing the current tier or quota until the first verified lower-tier renewal at or after `currentPlanEndsAt`; when effective it lowers only the base limit, preserves the counters, and clamps remaining capacity to zero when the preserved usage already reaches the lower limit. Generation reserves one unit by Saga correlation ID before work, commits it only after a usable question is stored, and releases it exactly once on permanent failure. Every accepted mutation updates `user_quota` and appends `user_quota_history` in one transaction, while `quota_reservations` retains its original period identity across rollover. A managed one-minute rollover resets the natural monthly window, and every quota read/write invokes the same idempotent rollover as a recovery fallback. `GET /api/v1/questions/quota` remains compatible while exposing base, bonus, reserved, period, and policy-version fields; `GET /api/v1/billing/status` is the unified entitlement-plus-quota authority.
  - Provides authenticated admin APIs for paginated user search, current-period bonus ledger adjustments, on-demand RevenueCat reconciliation, and a unified subscription/invoice/payment/quota timeline. Operators cannot assign a user's subscription tier directly.
  - Stores the latest iOS marketing version, build number, and observation time on the exact authenticated `devices` row. Every launch/foreground policy observation also appends an idempotent per-device `app_control_events` fact, so the current device version and the historical version/update funnel are both queryable. The legacy `POST /api/v1/app-updates/check` remains available for older builds.
  - Owns one active update campaign per platform and per-user campaign state in `app_update_campaigns` and `app_update_user_states`. Forced and optional presentation modes share the same localized payload; shown, dismissed, App Store opened, and target-version-returned timestamps remain distinct so conversion uses prompted users as its denominator.
  - Publishes one versioned JSON document to the Firebase Remote Config parameter `ios_app_control_v1`. The backend fetches the current template, merges only its owned parameter, validates it, and publishes with the fetched template version instead of force-overwriting unrelated Firebase parameters. The policy carries independent App Store/TestFlight update channels plus one current or scheduled maintenance window.
  - Exposes authenticated Monitoring APIs under `/api/v1/admin/app-updates` for campaign activation/ending, policy republishing, maintenance activation/ending, campaign metrics, and paginated user-level conversion inspection. Database state is committed before the external Firebase publication; publication status, revision, timestamp, and a bounded error are retained for operator recovery.
  - Provides authenticated feedback review and targeted messaging APIs. Feedback retains the submitting user/device target and progresses through `NEW`, `REVIEWED`, and `REPLIED`; administrative messages reuse the notification outbox/Redis/APNs pipeline instead of sending directly from the controller.
  - Validates administrative destinations as allowlisted `buddystudy://` deep links. The default `buddystudy://home/message` route opens a compact Markdown announcement sheet on Home, while record, statistics, settings, profile, and public-question destinations use the shared `AppRoute` parser. The legacy studies route remains readable but is not offered by the operator composer.
  - Exposes Redis Stream inspection under the unified Monitoring `Manage` section. The standalone analytics admin does not duplicate this operational surface.
  - Provides authenticated topic/key search, cursor-based Redis Stream inspection, exact stream-entry ID lookup, consumer groups, consumer Inbox attempts, and `redis_event_outbox`. Nested credentials are redacted before serialization. See [`REDIS_STREAM_OPERATIONS.md`](REDIS_STREAM_OPERATIONS.md).

- `Views`
  - `StudyView`: active question and pending question workflow.
  - `HistoryView`: 30-row incremental record/search pagination, detail, and deletion.
  - `StatisticsView`: shared-axis root-study growth comparison, calculation help, period filtering, a pinch-zoomable circular score tree without separate zoom buttons that reuses the My Studies layout and saved node positions, trend charts, and a compatibility projection for older servers. Selecting a tree node keeps the growth summary visible and appends an independently paginated 30-row record list for that exact `studyId`; selecting a row reuses the standard record detail.
  - `SettingsView`: macOS settings.
- `MobileRootView`: iOS tabs, onboarding, profile category hub, settings, notification inbox, and study-tree interaction.
  - Firebase Remote Config is the control-plane read model for maintenance and update guidance. iOS fetches and activates on launch/foreground and installs Firebase's real-time config listener while the process is active. A pure resolver enforces `maintenance > forced update > optional update > normal`, validates schema and freshness, compares marketing version then build, and schedules a local boundary task for future maintenance start/end without polling.
  - Forced and optional updates share the same compact centered card and softly dim the underlying screen. Forced mode uses the stronger dim treatment as a full-screen interaction barrier, hides the dismiss action, marks the card as required, and keeps it present until the installed version reaches the target; optional mode uses a lighter non-intercepting dim, leaves underlying content interactive, and persists dismissal by campaign through `SettingsStore`. Consumer App Store apps cannot silently install their own updates; automatic installation timing remains owned by iOS/App Store settings. Maintenance is a full-screen gate and retains the hidden developer bypass. Malformed, expired, unsupported, or unavailable Remote Config fails open for maintenance and may use only the existing backend app-update API as an update-guidance fallback. There is no monitoring-owned status endpoint or maintenance polling path.
  - App Store CI replaces the tracked placeholder `StudyMate/GoogleService-Info.plist` with the release secret. Local signed device builds must pass `BUDDYSTUDY_FIREBASE_PLIST_PATH=/absolute/path/GoogleService-Info.plist`; the iOS target validates that file and installs it into the built app after resources are copied, without modifying or committing the tracked placeholder.
  - The primary tab bar exposes Home, Records, Statistics, and Notifications. Profile is pushed from Home in the existing navigation stack instead of being presented as a modal. Its top quota summary shows the resolved membership tier and opens Membership Management as a sheet; Settings remains a profile-hub destination so account and app preferences share one predictable entry point.
  - Avatar editing is a dedicated `Avatar` destination. Logout is the final destructive action in the profile hub, while irreversible membership deletion is isolated under `Settings > Account Settings`.
  - Public-question visibility is persisted through `PATCH /api/v1/profile` as `allowPublicQuestions`; it is independent of protected-page access policy.
  - Developer controls use a local build-distribution gate. Five taps on the Profile version row within two seconds grant access only when `AppDistributionContext` identifies a Debug or TestFlight build. TestFlight records the version/build that accepted the gesture; a different build clears the stale unlock and saved debugging toggle before startup. Debug builds retain the installation-level unlock, while App Store production always rejects and clears hidden developer access. The gesture reveals developer options but never opens the floating panel automatically. While the global maintenance gate is visible, five taps on the maintenance copy bypass only the current in-memory gate when access was already restored for that build; maintenance never grants developer access or changes the published Firebase policy. The floating APP/API debug panel uses a dedicated drag handle and remains inert until `debugPopupAllowed` is granted.
  - Per-topic question generation exposes its category-scoped in-flight state so only the selected study room renders the inline loading message.
  - The compact My Studies outline swaps branch data in one view, then reveals only the new row contents with a subtle direction-aware stagger. Row frames, dividers, and the card remain outside the animation, and content never becomes fully transparent or overlaps. Root expand/collapse stays immediate. Its card context menu exclusively exposes the View Full Tree destination alongside Edit and Delete, so the list does not duplicate that navigation row. View Full Tree remains available for childless roots as the entry point for adding the first child, while only the empty child-topic section is omitted. A selected topic's study screen exposes New Question plus a separate trailing More menu; that menu reuses the topic editor and resolves View Full Tree to the topic's containing root. Deletion is intentionally available only inside the editor.
  - My Studies derives root cards only from backend rooms whose `parentStudyId` is absent. Before the first hierarchy response it shows loading, and a failed response exposes retry instead of falling back to the persisted flat category cache. An already loaded hierarchy remains visible during ordinary refresh failures.
  - Recommended child topics support multi-selection. iOS filters duplicate normalized names, creates the selected topics in stable order through the existing single-topic use case, and refreshes the study tree once after the batch so topic creation remains separate from question generation.
  - The notification inbox calls `POST /api/v1/notifications/read-all` and updates loaded rows through `NotificationStateStore` only after the backend mutation succeeds.
  - A system notification tap targeting a detail selects the Notifications tab before publishing its route request. `MobileNotificationsTab` consumes that request and pushes one direct destination. Home and the legacy study-list route bypass the notification stack; the latter selects the hierarchy-aware My Studies scope already owned by `MobileHomeView`, with no parallel flat list destination. Home no longer stages an inbox destination and a nested detail destination in the same SwiftUI update. Notification record loading has explicit loading, unavailable, and retryable-failure states, reuses the first fresh record response without a duplicate detail fetch, and exposes Skip for ungraded questions.
  - `ADMIN_MESSAGE` notifications with `buddystudy://home/message` are a distinct route: an explicit APNs or notification-inbox tap selects Home and presents the stored Markdown in a sheet. The payload parser takes title/body from `aps.alert`, preserves a pending route until `AppState` is configured, and marks a linked inbox notification read after presentation. Other administrative destinations continue through `AppRoute`.
  - Answer drafts and submitted answers are different state domains. A draft is stored only in the record-ID-keyed draft repository and may update the current editor session; it never mutates `StudyRecord.answer`, the record cache, or canonical sync state. `StudyRecord.answer` becomes non-null only from an answer explicitly submitted through a notification action or from a backend response that has accepted the answer. UI presentation derives editor-versus-message state from that invariant, so background refreshes and draft debounce saves cannot promote an in-progress draft into a submitted message.

## Markdown Message Contract

- `question`, `expectedAnswerHint`, grading `feedback`, and grading `explanation` are stored and returned as Markdown source. Existing plain text remains valid, so this contract does not require a schema migration or a parallel HTML field.
- The backend asks the model for a restrained Markdown subset: paragraphs, emphasis, lists, inline code, and fenced code blocks. Generated HTML is not part of the contract.
- The backend stores model Markdown exactly as received; it does not normalize or rewrite generated content before persistence. iOS renders the stored source with MarkdownUI, while the backend uses commonmark-java only to derive notification-safe plain text.
- iOS permits only `http` and `https` links from rendered Markdown. Unsupported link schemes remain visible as text without an active link.
- Dense list rows use a plain-text projection for predictable height. Full question, hint, answer, feedback, and explanation bubbles render Markdown.
- APNs, the notification inbox, and local notification bodies use a parser-derived plain-text projection of the stored Markdown so notification surfaces do not expose formatting markers. The notification event keeps the Markdown source, and the push adapter derives the safe title/body projection from the persisted notification when delivery is claimed.

## Data Flow

```text
Manual action / backend scheduled interval
-> backend request transaction reserves quota and stores Saga(QUEUED) + outbox
-> manual API returns 202 Accepted + correlationId
-> generation consumer claims its Inbox row and advances Saga to GENERATING
-> OpenAI runs outside the database transaction
-> generated question + QUESTION_GENERATED outbox + locale translation outboxes advance Saga to TRANSLATING
-> translation consumer resolves topic/question/hint through the configured provider chain
-> localization snapshot and delivery outboxes are persisted in one transaction
-> Saga advances to COMPLETED
-> iOS polls by correlationId and caches the returned StudyRecord
-> current question updates only when it will not replace an active ungraded draft
-> notification/push outboxes deliver asynchronously
```

```text
Terminal generation/translation failure
-> failed-step transaction stores Saga(FAILED) + QUESTION_GENERATION_ROLLBACK_REQUESTED outbox
-> rollback consumer removes ungraded question/projections and reverses coverage
-> same rollback transaction refunds monthly usage and records rollback completion
-> process becomes terminal and the topic is available for another question
```

```text
User answer
-> AppState saves answer draft
-> AppState.gradeCurrentAnswer or gradeRecord
-> RemotePushBackendClient.gradeRecord
-> backend atomically persists the submitted answer, grading correlation,
   questionStatus=GRADING, gradingStatus=QUEUED, and answer translation outboxes before OpenAI work
-> leaving the detail screen cancels only client polling
-> reopening fetches /api/v1/studies/{studyId}, never the full tree page
-> iOS merges pendingQuestion answer/grading state over stale local cache
-> when pendingQuestion is absent, iOS displays latestQuestion with the submitted answer and AI response
-> iOS resumes polling by the persisted grading correlation
-> backend calls OpenAI and persists score, feedback, explanation, and AI-response translation outboxes
-> SettingsStore updates StudyRecord
-> StatisticsView recalculates topic ranges from records
-> backend stats are refreshed from MySQL records
-> GET /api/v1/stats/studies joins graded question study IDs to the current study tree
-> root and parent nodes aggregate capped descendant weights
-> iOS reuses the My Studies circular tree geometry to show the root aggregate and every descendant's individual score
```

```text
My Studies root
-> GET /api/v1/studies
-> app builds parentStudyId adjacency map
-> selecting a room calls GET /api/v1/studies/{studyId} for its question state
-> compact branch navigation swaps data once, then reveals row contents without moving row geometry
-> recursive tree layout renders circular nodes with restrained level colors
-> the user can switch orientation, pinch or button zoom, drag nodes, and reset saved positions
-> selecting a node opens the root-owned question page with that node as the manual topic
-> plus opens GPT recommendations first, supports selecting multiple suggestions with one shared difficulty, and keeps manual entry as an explicit alternative
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
-> the backend-owned prompt and root OpenAI model generate a question for the selected node's topic and level; iOS study editors do not expose prompt overrides
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
-> GET /api/v1/public/questions?tl=ko|en|ja&view=localized|original
-> GET /api/v2/public/questions/search?query=...&tl=ko|en|ja&view=localized|original
-> tl (`ko|en|ja`) takes precedence over the deprecated language alias
-> view defaults to localized; original bypasses translation scheduling
-> missing translations return the original plus PENDING and enqueue content-translation
-> canonical questions and comments keep original text only
-> READY question/answer/AI snapshots are projected into question_search by language
-> comment translations remain isolated in question_comment_localizations
-> only READY translation snapshots are exposed for the requested language
-> app maps server questions into typed MobileHomeFeedItem values
-> locally scheduled placement may insert non-question items such as feedback prompts
-> feedback prompt opens a dedicated form
-> POST /api/v1/feedback accepts only content and stores it in the dedicated feedbacks table
-> authenticated user and registered-device identifiers are captured as server-side metadata
-> Monitoring GET /api/v1/admin/feedback provides paginated operator review
-> PATCH /api/v1/admin/feedback/{id}/review records review state
-> POST /api/v1/admin/feedback/{id}/notifications queues an ADMIN_MESSAGE for its captured target
-> POST /api/v1/admin/users/{id}/notifications queues the same message for a selected member
-> notification outbox and Redis deliver APNs with parser-derived plain text
-> an explicit tap routes to the validated app destination; home/message presents the full Markdown popup
```

## Sync Model

- Backend sync stores settings, records, answer drafts, generated questions, grading results, and topic statistics.
- Backend record rows are retained without a per-user maximum. `GET /api/v1/records` and its search variant are bounded offset pages; iOS requests the next 30 rows only when the last loaded row approaches the viewport.
- `GET /api/v1/records?studyId={nodeId}` applies the node filter in MySQL before counting and paging. Statistics node detail uses this additive filter so deep study trees never require downloading or client-filtering the user's full record history.
- API key backend sync is supported for the regular OpenAI key; admin keys are not supported.
- Backend settings sync uploads the regular OpenAI API key only when it changes or when backend settings need to be initialized.
- A backend device registration can be created without an APNs token so manual question generation, grading, settings, records, and stats can work before notification permission/token delivery.
- When APNs token registration later succeeds, the existing backend device is updated instead of creating a separate backend identity.
- If a local ungraded current question has an answer draft, remote current questions do not replace the active answer page.

## Push Model

- iPhone registers for remote notifications through `UIApplication`.
- iPhone app timers only run while the app process is active. For locked/background delivery, the app opportunistically pre-generates at most one pending question notification when entering background and schedules it for the configured interval. If a question notification is already pending, it does not create another. `BGAppRefresh` is also requested at the next due date, but iOS does not guarantee exact wake-up timing.
- The Spring Boot Kotlin backend is the production path for server-scheduled APNs delivery. It stores APNs tokens and schedules in MySQL, keeps user OpenAI keys encrypted at rest when provided, and stores a generated question with one `NOTIFICATION_REQUESTED` outbox event whose payload includes `shouldPush`. The notification listener persists the inbox row first, then publishes its ID through `notification.question-push.requested.v1` when push is enabled. An `@StreamListener` sends APNs alerts, while `app_notifications` stores claim, sent, and error state. Successful push messages use `StreamOptions.ACK` so bounded stream history remains available for operations; failures remain pending for `@StreamScheduler` auto-claim recovery.
- Scheduled delivery requires an APNs token. If a backend device exists without a token, the scheduler defers the due item instead of generating an undeliverable push.
- Push eligibility is checked twice: before publishing the push Stream event and again immediately before the APNs call. The final check reloads the current device, requires the queued user to remain attached with an active session, and uses the current database APNs token instead of the token captured in the Stream fields. A logout therefore invalidates already-queued delivery as well as later notification targeting.
- iOS also calls `unregisterForRemoteNotifications()` on logout, removes pending and delivered notification-center entries, clears queued notification responses and device-token callbacks, and ignores any remote delivery that races with logout. A successful login calls `registerForRemoteNotifications()` again through the existing authorization flow; it does not require the user to grant permission a second time when authorization is still active.

## Community Identity

- Device credentials are used to register a backend device and bootstrap or refresh an access token.
- Access tokens carry both `user_id` and `device_id`; protected API calls resolve the current principal from those claims and the stored user-device mapping.
- Google Login links a verified Google subject to the registered device through `users` and `devices.user_id`.
- New Google and email users receive a cryptographically randomized `Adjective-Noun-####` display name. MySQL stores a generated, normalized key for non-anonymous users and enforces it with `uq_users_display_name_key`; creation retries a new candidate when a concurrent collision is detected.
- Anonymous installation rows are excluded from that display-name key so their internal `Buddy` placeholder can repeat. Profile edits use the same database constraint and return `DISPLAY_NAME_TAKEN` on conflict.
- User status is one of `ANONYMOUS`, `ACTIVE`, or `WITHDRAWN`. Account deletion transactionally changes the member to `WITHDRAWN`, scrubs login/secret/profile fields, revokes active sessions, detaches its devices, reconnects the current device to an anonymous user, and appends an `ACCOUNT_WITHDRAWN` row to `redis_event_outbox`.
- The outbox recovery job publishes `ACCOUNT_WITHDRAWN` to the domain Redis Stream. `AccountWithdrawalStreamListener` processes it at least once and acknowledges only after idempotent SQL cleanup and profile-photo deletion complete. Failures remain pending and are reclaimed after the idle lease; replays are safe because deletes are idempotent and device-scoped rows are bounded by the event's `withdrawnAt` cutoff.
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
- Tree growth does not rewrite `user_stats`. `StudyGrowthStatsPort` reads questions created or answered in the requested period by stable `study_id`, and `StudyGrowthService` joins them to the current `StudyPort` tree. Only graded answers feed ability and trend calculations; ungraded questions remain available for the completion denominator.
- A direct node needs six graded answers for growth. The previous and recent windows never overlap and contain three to five samples each.
- Parent and root estimates include descendant nodes, cap each node's weight at five answers, and report measured-node coverage separately from total subtree size.
- `GET /api/v1/stats/studies` returns root summaries plus a flat node list containing `studyId`, `parentStudyId`, and `rootStudyId`. Each root also carries a normalized five-axis profile: mean score, mean answered difficulty, completed/generated ratio, answered-topic coverage, and deepest answered tree coverage.
- Statistics tab re-selection is freshness-gated for one minute while pull-to-refresh bypasses that gate. Rolling growth periods are expressed as exclusive UTC day bounds, and backend plus iOS topic selection use explicit deterministic tie-breaks so identical read-model data produces identical cards.
- iOS reconstructs a stable pre-order list for the selected root. It lazily renders that full depth-indented list below the radar instead of requiring repeated branch navigation.
- The iOS root overview maps every study's previous and current estimates onto the same 1–10 axis. The help sheet documents the exact window and weighting rules instead of asking users to infer them from the chart.

## Subscription, Entitlement, And Quota

- `invoices`/`invoice_events` and `payments`/`payments_history` are the financial ledger. A verified charge completes its normal invoice; later entitlement projection failure does not rewrite financial truth and never automatically requests a refund.
- `billing_accounts` retains one `appAccountToken` owner. Account deletion anonymizes the record and prevents that purchase chain being restored to another BuddyStudy account.
- `subscription_events` is append-only and idempotent. `subscriptions` projects access and renewal independently per original transaction, and `user_entitlement_projection` chooses the highest valid tier without adding duplicate subscription limits.
- `user_membership_tiers` is the plan catalog and owns the base monthly limit.
- The default monthly allowance is 30 questions for TIER1, 300 for TIER2, and 1,000 for TIER3. TIER2 and TIER3 each expose one monthly Apple auto-renewable subscription. Disabled legacy product mappings remain readable only for billing reconciliation.
- The iOS membership screen renders the StoreKit-localized title/price context together with the server-owned monthly allowance, monthly duration, automatic-renewal disclosure, and localized Terms of Use and Privacy Policy links before the purchase action.
- App Store Connect subscription metadata is tracked in `app-store/billing/subscriptions.json`. Korean prices are the base prices and Apple equalized prices are configured for every available storefront.
- A user-initiated purchase creates an `INVOICE_CREATED` event and `NORMAL/WAITING` invoice (`phase=PREPARED`) before the StoreKit sheet opens. RevenueCat returns the completed StoreKit transaction identifier; iOS confirms it against that exact invoice through `POST /api/v1/billing/invoices/{invoiceNumber}/confirm`.
- The backend verifies the transaction through RevenueCat API v2 and advances the derived phase `PREPARED -> VERIFIED -> FULFILLED`. The confirmation request returns 2xx only after payment settlement, `fulfilled_at`, entitlement, and quota are readable. A failure preserves verified financial evidence for idempotent recovery.
- The iOS Debug launch action attaches `StudyMateDev.storekit`, so local Xcode purchases exercise the same pending-invoice, transaction-sync, fulfillment, and event-ledger path as App Store purchases. The dev backend accepts `XCODE` and `SANDBOX`; production accepts Apple-signed environments and explicitly rejects Xcode-local transactions.
- RevenueCat owns StoreKit transaction completion with the App Store `appl_` public SDK key in every iOS build. Apple determines Sandbox versus Production from the transaction environment. The stable BuddyStudy `appAccountToken` is its App User ID. Direct transaction confirmation and authenticated RevenueCat webhooks share one verified-payment use case and deduplicate by provider transaction ID, making arrival order and retries harmless. Direct Apple JWS remains a backward-compatible recovery input.
- Invoice projection status is only `WAITING`, `COMPLETED`, or `FAILED`; granular verification, cancellation, and refund steps are event/payment/action states. `COMPLETED` means authenticated payment evidence committed.
- Refunds create linked `REFUND` invoices instead of mutating the completed `NORMAL` invoice. Apple confirmation completes the refund invoice; decline or reversal fails it.
- `user_quota` is the single current-state quota projection per user. It owns the anchor, current period boundaries, effective tier and base limit, current-period bonus, committed and reserved counters, derived remaining capacity, policy version, and optimistic version.
- `user_quota_history` is append-only. The current-row update and its history entry commit in one transaction for period rollover, reserve, commit, release, bonus, migration, and effective plan changes. A unique event ID and resulting-version chain make replays harmless and provide an auditable counter and plan-change trail; `user_quota` remains the authoritative full current snapshot.
- `quota_reservations` remains the exactly-once identity for question-generation Sagas. It snapshots the period in which the reservation was accepted, so a commit or release delivered after a natural rollover completes the old-period history without corrupting the new current row.
- Month-end anchors use the target month's last valid day without drift. For example, a January 31 anchor resets on February 28 (or 29) and then March 31.
- The first verified paid purchase may replace the account-created anchor with the immutable earliest `purchasedAt`, but it carries the existing counters into the recalculated window. Later upgrades, downgrades, renewals, cancellations, expirations, refunds, and resubscriptions do not move that anchor.
- Remaining capacity is `max(0, base + current-period bonus - committed - reserved)`. A paid upgrade immediately raises the base limit and preserves committed usage, bonus, and active reservations. A requested downgrade keeps the current higher tier unchanged until its renewal boundary; the effective lower-tier renewal preserves those counters, lowers the base limit, and therefore returns zero remaining when the preserved total already reaches the lower limit. A unique invoice-scoped plan-change history event makes confirmation, webhook, reconciliation, and replay converge idempotently; a deferred downgrade emits no quota mutation.
- The managed `user-quota-rollover` job selects due `user_quota` rows every minute with bounded locking and advances each directly to the window containing the current UTC instant. It resets committed, reserved, and bonus counters only for that natural monthly rollover and appends one idempotent `PERIOD_RESET` history entry. Quota status reads, permission checks, reservations, and bonus writes run the same rollover transaction when they encounter an overdue row, so scheduler delay or downtime cannot expose the prior period's allowance. Legacy membership/usage and policy-v4 quota tables are migration inputs only and are not a read authority.
- The monitoring Users & Quotas page proxies admin APIs through the authenticated monitoring origin. It does not persist backend admin tokens outside the browser session.
- User search is bounded to 100 rows per API call and the UI uses 20-row pages.

## Managed Batch Operations

- Business schedulers implement `ManagedJob` and execute through `ManagedJobExecutionUseCase`. The job contract owns an operator-facing name and description in addition to the stable machine name.
- `scheduled_jobs` is the enablement, schedule-display, timeout, and lock-policy registry. Spring scheduling annotations remain the execution trigger, so migrations must keep the displayed schedule value aligned with the configured trigger.
- Every managed execution writes `RUNNING` and then `SUCCESS`, `FAILED`, or `SKIPPED` to `scheduled_job_runs`, including trigger type, start/finish time, duration, bounded result summary or error, retry source, and initiator.
- A MySQL advisory lock prevents concurrent execution of the same managed job across overlapping backend instances. Job work remains idempotent because a lock does not by itself guarantee exactly-once side effects.
- `user-quota-rollover` runs once per minute to materialize natural monthly resets even when a user is idle. Its deterministic per-user period event and row lock make overlapping workers harmless; request-time fallback remains mandatory rather than relying on scheduler timeliness for correctness.
- The administrator status API returns every registered job, while readiness freshness applies only to the configured frequent critical subset. Daily correction jobs remain visible without being incorrectly marked stale by the global 15-minute readiness threshold.
- Monitoring `Manage > Batch Jobs` uses the authenticated admin APIs for job status, paginated run history, run details, and explicit retry. Redis Stream consumer polling and `@StreamScheduler` pending-entry recovery remain pipeline operations and are inspected under `Manage > Redis Streams`, not recorded as high-frequency batch runs.

## Production Incident Auto-Fix

- Grafana remains the only producer of backend ERROR notifications. Its provisioned contact point fans out to Slack and an HMAC-signed, private Monitoring webhook; the backend application has no Slack or GitHub credential.
- `buddystudy-incident-receiver` has no host port. It deduplicates each `(Grafana fingerprint, startsAt)` on persistent disk, reads a bounded redacted ERROR context from Loki, joins the latest successful backend deployment SHA, and sends `codex-incident-autofix` to the source repository.
- The GitHub workflow separates trust boundaries: the Codex job has source read access plus its dedicated OpenAI key, the verification job applies only the serialized patch and runs the full backend test suite, and only the final job receives branch/PR write permission.
- Generated changes are restricted to backend code and directly relevant documentation. Successful verification creates a labeled Draft PR; automatic merge, release, tagging, deployment, and production access are prohibited. The full contract and recovery behavior are documented in `docs/observability/codex-incident-autofix.md`.

## Build And Verification

Recommended local checks:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
```

Use real-device builds when changing push, entitlements, or background refresh behavior.
