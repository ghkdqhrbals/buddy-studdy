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
  - `ErrorHandling/BackendErrorPresentationPolicy.swift` converts backend error codes into UI presentation, login, page-access, and identity-reset decisions.
  - `PageAccess/PageAccessPolicy.swift` owns tab/page access mapping and provisional access decisions.

- `UseCases/`
  - Thin application action boundaries around backend capabilities.
  - `PageAccess/RefreshPageAccessUseCase.swift` fetches backend page access state.
  - `Records/RecordsUseCase.swift` centralizes backend record operations such as fetch, grading, draft saving, skipping, deletion, publicity, and full clear.
  - `StudyRoom/StudyRoomUseCase.swift` centralizes study room backend operations such as study fetch/create/delete and backend question creation.
  - `AppState` may still orchestrate state application and recovery, but it should not grow new direct backend action logic when a use-case boundary exists.

- `Services/SettingsStore.swift`
  - Local persistence facade.
  - Stores settings, API keys, draft state, backend metadata, and exposes an in-memory record cache for the current session.
  - Caps records at the configured history limit.

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
  - The backend is organized as multi-module hexagonal architecture: `domain`, `application`, `infra`, and executable `tutor`.
  - Incoming web/scheduler/stream handlers live in `backend/infra`, persistence/OpenAI/APNs/Redis integrations live in `backend/infra`, and use-case services live in `backend/application` behind inbound port interfaces.
  - Spring Data JPA repositories live in outbound persistence adapters. Current JPA entities are centralized under `backend/domain/src/main/kotlin/com/buddystudy/domain` as a migration bridge.
  - Topic-level statistics calculation is separated into `backend/application/src/main/kotlin/com/buddystudy/backend/stats/StatsService.kt` and is consumed by study application services.
  - Public base URL: `https://api.ghkdqhrbals.org`.
  - Runs behind Nginx on host port `443`.
  - Uses a private Dockerized PostgreSQL container with a persistent named volume.
  - Calls OpenAI for API-key validation, question generation, and answer grading.
  - Stores generated questions in PostgreSQL before sending APNs notifications.
  - Owns Google-linked community profiles, public question browsing metadata, and question reports.
  - Public question like/comment counts use source-of-truth reaction tables plus a `question_stats` read model; stream hooks are wired through direct Redis Streams.
  - Forwards reports by SMTP only when report-email secrets are configured; reports are still stored when email delivery is unavailable.

- `Views`
  - `StudyView`: active question and pending question workflow.
  - `HistoryView`: record search, pagination, detail, and deletion.
  - `StatisticsView`: topic-level statistics, period filtering, trend charts, and grouped topic stats.
  - `SettingsView`: macOS settings.
  - `MobileRootView`: iOS tabs, onboarding, and settings.

## Data Flow

```text
Manual action / pull-to-refresh / backend scheduled interval
-> AppState.generateQuestion
-> RemotePushBackendClient.createQuestion
-> backend POST /api/v1/me/questions
-> backend calls OpenAI and stores an ungraded StudyRecord in PostgreSQL
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
-> backend stats are refreshed from PostgreSQL records
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
- The Spring Boot Kotlin backend is the production path for server-scheduled APNs delivery. It stores APNs tokens and schedules in PostgreSQL, keeps user OpenAI keys encrypted at rest when provided, creates due questions with OpenAI, stores them in the `questions`/records tables, publishes push jobs through Redis Streams, and sends APNs alerts from the backend stream consumer.
- Scheduled delivery requires an APNs token. If a backend device exists without a token, the scheduler defers the due item instead of generating an undeliverable push.

## Community Identity

- Device credentials are used to register a backend device and bootstrap or refresh an access token.
- Access tokens carry both `user_id` and `device_id`; protected API calls resolve the current principal from those claims and the stored user-device mapping.
- Google Login links a verified Google subject to the registered device through `users` and `devices.user_id`.
- User status is one of `ANONYMOUS`, `ACTIVE`, or `WITHDRAWN`. Account deletion immediately removes the active profile, sign-in mappings, public questions, and related study records, then reconnects the current device to an anonymous user.
- Public question rows can expose only the author's public profile fields: display name and bio. Profile photo changes are not exposed in the current iOS app.
- Question publicity defaults to private unless the signed-in user enables public sharing.
- Reports are stored in PostgreSQL and can optionally be emailed to the operator Gmail through SMTP settings.

## Topic Statistics

- Topic grouping uses `TopicGrouping.normalizedKey`.
- The key removes case, spacing, hyphen, underscore, punctuation, width, and diacritic differences.
- Simple camelCase boundaries are separated before normalization.
- The displayed topic is the most frequent/recent label in the merged group.
- Topic range is estimated from difficulty level and score, then widened by small sample size and conflicting evidence.
- Backend topic statistics are a materialized read model in `user_stats`.
- `questions` remains the source of truth. Answer grading and record deletion mark only the affected `(user_id, stat_date, topic_key, difficulty_level)` bucket in `user_stats_dirty_keys`.
- The refresh job processes dirty keys in bounded batches and recomputes each bucket with PostgreSQL aggregation instead of loading all questions into application memory.
- Dirty key processing is at-least-once and idempotent: recalculating the same bucket deletes and reinserts only that bucket's `user_stats` rows.
- Refresh claims dirty rows with `FOR UPDATE SKIP LOCKED`; if the process crashes before commit, the transaction rolls back and the dirty rows remain for the next run.
- Refresh deletes a dirty row only when its `updated_at` still matches the claimed value. If a new answer/delete updates the same bucket during refresh, the dirty row is kept and retried in a later batch.
- H2/test environments fall back to a full rebuild path; production PostgreSQL uses incremental dirty-key refresh.

## Build And Verification

Recommended local checks:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
```

Use real-device builds when changing push, entitlements, or background refresh behavior.
