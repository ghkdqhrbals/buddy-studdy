# BuddyStuddy Architecture

## Overview

BuddyStuddy is a SwiftUI app with shared domain logic across macOS and iOS. The app remains local-first for study records and day-to-day UI state: SQLite/UserDefaults hold the working state, OpenAI is called directly from the client with the user's API key, and CloudKit provides iCloud sync. A separate Python backend exists only for scheduled APNs delivery when the iOS app cannot run in the background. Internal target names, bundle identifiers, background task identifiers, and CloudKit record types retain `StudyMate` to avoid breaking existing installs and iCloud data.

## Targets

- `StudyMateiOS`: iOS app and current public release target.
- `StudyMate`: macOS menu bar app, currently not shipped publicly while macOS update/sync UX is paused.
- `StudyMateTests`: unit tests for storage, OpenAI parsing, sync behavior, notification routing, and statistics logic.

## Main Modules

- `Models/StudyModels.swift`
  - Domain models: `StudySettings`, `Difficulty`, `QuestionItem`, `StudyRecord`, `GradingResult`.
  - Display strings through `AppStrings`.
  - Shared topic grouping through `TopicGrouping`.

- `ViewModels/AppState.swift`
  - Main `ObservableObject`.
  - Owns runtime state, drafts, selected tab, sync state, pending question limits, logs, and user actions.
  - Coordinates OpenAI calls, local persistence, CloudKit sync, notifications, and timers.

- `Services/SettingsStore.swift`
  - Local persistence facade.
  - Stores settings, API keys, draft state, logs, CloudKit sync metadata, and delegates records to SQLite.
  - Caps logs at 1000 and records at the configured history limit.

- `Services/OpenAIClient.swift`
  - Uses OpenAI Responses API for question generation and grading.
  - Uses the configured supported model list from `OpenAIModelOption`.
  - Keeps OpenAI usage, cost, and billing management as external OpenAI Platform links.

- `Services/CloudSyncService.swift`
  - Uses the private iCloud database.
  - Stores one snapshot record for app state sync.
  - Stores `StudyMateQuestionPush` records for CloudKit/APNs push delivery.
  - Manages the iOS question push subscription.

- `Services/NotificationService.swift`
  - Handles local notifications, notification actions, iOS remote notification bridge, and macOS study window foregrounding.

- `backend/`
  - FastAPI APNs push backend.
  - Public base URL: `https://api.ghkdqhrbals.org`.
  - Runs behind Nginx on host port `443`.
  - Uses a private Dockerized PostgreSQL container with a persistent named volume.

- `Views`
  - `StudyView`: active question and pending question workflow.
  - `HistoryView`: record search, pagination, detail, and deletion.
  - `StatisticsView`: topic-level statistics, period filtering, trend charts, and grouped topic stats.
  - `SettingsView`: macOS settings.
  - `MobileRootView`: iOS tabs, onboarding, and settings.

## Data Flow

```text
Timer / manual action / background refresh
-> AppState.generateQuestion
-> OpenAIClient.generateQuestion
-> SettingsStore appends question history and StudyRecord
-> current question updates only when it is safe to activate
-> NotificationService displays local notification
-> CloudSyncService saves sync snapshot / question push record
```

```text
User answer
-> AppState saves answer draft
-> AppState.gradeCurrentAnswer or gradeRecord
-> OpenAIClient.gradeAnswer
-> SettingsStore updates StudyRecord
-> StatisticsView recalculates topic ranges from records
-> CloudKit snapshot sync is scheduled
```

## Sync Model

- Sync is snapshot based, not event sourced.
- The newest CloudKit snapshot usually wins, but local records are merged to avoid losing device-specific history.
- API key sync is supported for the regular OpenAI key.
- Only the regular OpenAI API key is supported by the app.
- If a local ungraded current question has an answer draft, remote current questions do not replace the active answer page.

## Push Model

- CloudKit push is currently iPhone-focused.
- iPhone registers for remote notifications through `UIApplication`.
- iPhone installs a `CKQuerySubscription` for `StudyMateQuestionPush`.
- Mac creates question push records after question generation.
- iPhone receives the CloudKit/APNs notification, fetches the record, syncs, and opens the question only when the user taps or replies.
- iPhone app timers only run while the app process is active. For locked/background delivery, the app opportunistically pre-generates at most one pending question notification when entering background and schedules it for the configured interval. If a question notification is already pending, it does not create another. `BGAppRefresh` is also requested at the next due date, but iOS does not guarantee exact wake-up timing.
- The Python backend is the production path for server-scheduled APNs delivery. It stores APNs tokens and schedules in PostgreSQL, keeps user OpenAI keys encrypted at rest when provided, and sends APNs alerts on the configured interval.

## Topic Statistics

- Topic grouping uses `TopicGrouping.normalizedKey`.
- The key removes case, spacing, hyphen, underscore, punctuation, width, and diacritic differences.
- Simple camelCase boundaries are separated before normalization.
- The displayed topic is the most frequent/recent label in the merged group.
- Topic range is estimated from difficulty level and score, then widened by small sample size and conflicting evidence.

## Build And Verification

Recommended local checks:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
xcodebuild -project StudyMate.xcodeproj -scheme StudyMate -destination 'platform=macOS,arch=arm64' -derivedDataPath build/MacTestDerivedData CODE_SIGNING_ALLOWED=NO test
```

Use real-device builds when changing iCloud, push, entitlements, or background refresh behavior.
