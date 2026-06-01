# BuddyStuddy PRD

## Purpose

BuddyStuddy is a quiet AI tutor for people who use AI heavily but still want to keep their own knowledge sharp. The product asks short questions on a schedule, lets the user answer when convenient, grades the answer with OpenAI, and turns the accumulated record into topic-level learning statistics.

## Product Principles

- Keep the app useful from the first screen, not as a marketing surface.
- Never steal the user's current answer draft when a new question arrives.
- Prefer compact, predictable controls over decorative UI.
- Treat statistics as the core feedback loop.
- Make cross-device sync understandable and recoverable.
- Keep settings simple enough to scan repeatedly.

## Supported Platforms

- iOS app built with SwiftUI `TabView`.
- macOS menu bar app built with SwiftUI `MenuBarExtra`, currently kept in the repository with public release paused.
- Shared model, storage, OpenAI, notification, and CloudKit sync services.

## Release Scope

- Current public release target: iPhone app through App Store Connect.
- macOS DMG/Sparkle release is on hold until the macOS sync/update experience is revisited.

## Core User Flows

### Onboarding

1. User chooses app language.
2. User optionally enters an OpenAI API key.
3. User sets topic, difficulty, and interval.
4. User can skip setup and finish later in Settings.

### Study

1. User receives or manually creates a study question.
2. User writes an answer draft that is preserved automatically.
3. User can reveal the hint on demand.
4. User submits for grading.
5. Grading result, feedback, and explanation are stored in records.
6. Ungraded pending questions are capped at 3.

### Records

1. Ungraded records appear first.
2. Records are searchable and paginated.
3. Record detail shows question, answer, feedback, explanation, and grading state.
4. Ungraded records can still be answered from detail.
5. Individual records can be deleted.

### Statistics

1. Statistics are filtered by period.
2. Topics are grouped by normalized topic key so case, spacing, hyphen, underscore, and simple camelCase variants are merged.
3. Topic range estimates combine difficulty level and score into a 1-10 ability range.
4. Topic browser supports search, sort, pagination, selected topic detail, and trend chart.
5. Similar topic aliases are visible in the selected topic detail when multiple labels were merged.

### Settings

1. Study settings appear first.
2. OpenAI API key and model are managed separately from study settings.
3. Notification permission opens system settings; no in-app test notification button is shown.
4. iCloud sync is shown as a single compact footer row at the bottom.
5. Developer logs are hidden unless debugging mode is enabled.

### Sync And Push

1. CloudKit sync stores settings, current question, draft answer, records, history, and the regular OpenAI API key.
2. Only the regular OpenAI API key is supported for sync.
3. Question push records are used for iPhone delivery.
4. iPhone subscribes to question push records and receives CloudKit/APNs notifications.
5. Push arrival syncs data without opening a new answer page unless the user taps the notification.
6. On iPhone, lock-screen delivery uses at most one pending scheduled local notification prepared before suspension; exact background network generation is not guaranteed by iOS.
7. Server-scheduled APNs delivery is handled by the Python backend at `https://api.ghkdqhrbals.org`.

## Non-Goals

- Guaranteeing real-time push delivery independent of iCloud/APNs behavior.
- Storing OpenAI billing balance locally as an authoritative source.
- Supporting more app languages than Korean and English in the current version.

## Current UX Backlog

- Add a clearer sync diagnostics panel for iCloud account, quota, schema, and permission failures.
- Add optional topic merge review so users can rename or split automatically grouped topics.
- Add a compact "next best question" recommendation based on topic range uncertainty.
- Add export for records and topic stats.
- Add explicit conflict UI when two devices edit the same answer draft.
