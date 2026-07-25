# BuddyStudy PRD

## Purpose

BuddyStudy is a quiet AI tutor for people who use AI heavily but still want to keep their own knowledge sharp. The product asks short questions on a schedule, lets the user answer when convenient, grades the answer with OpenAI, and turns the accumulated record into topic-level learning statistics.

## Product Principles

- Keep the app useful from the first screen, not as a marketing surface.
- Never steal the user's current answer draft when a new question arrives.
- Prefer compact, predictable controls over decorative UI.
- Treat statistics as the core feedback loop.
- Make backend sync understandable and recoverable.
- Keep settings simple enough to scan repeatedly.

## Supported Platforms

- iOS app built with SwiftUI `TabView`.
- macOS menu bar app built with SwiftUI `MenuBarExtra`, currently kept in the repository with public release paused.
- Shared model, storage, backend API, and notification services.

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

1. User receives or manually creates a study question through the backend.
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
6. Before sign-in, the tab previews the shape and value of accumulated learning records, then offers a simple bottom-aligned `로그인` action instead of presenting a blocking login wall.

### Statistics

1. Statistics are filtered by period.
2. Topics are grouped by normalized topic key so case, spacing, hyphen, underscore, and simple camelCase variants are merged.
3. Topic range estimates combine difficulty level and score into a 1-10 ability range.
4. Topic browser supports search, sort, pagination, selected topic detail, and trend chart.
5. Similar topic aliases are visible in the selected topic detail when multiple labels were merged.
6. Before sign-in, the tab uses a subdued sample summary to explain topic progress and keeps the login invitation as a consistent bottom action.

### Settings

1. Study settings appear first.
2. OpenAI API key and model are managed separately from study settings, but OpenAI requests are performed only by the backend.
3. Notification permission opens system settings; no in-app test notification button is shown.
4. Question visibility is explicit and defaults to private.
5. User-facing debugging logs are not provided.

### Sync And Push

1. Backend sync stores settings, records, answer drafts, generated questions, grading results, and topic statistics in MySQL.
2. The app keeps records only as an in-memory view cache during a running session. It must not persist study records in a local SQLite database.
3. iCloud/CloudKit sync is no longer exposed or enabled; backend persistence is the active sync path.
4. Only the regular OpenAI API key is supported; admin keys are not supported.
5. The app does not call OpenAI directly. API-key validation, question generation, and grading go through `https://api.ghkdqhrbals.org`.
6. Server-scheduled APNs delivery is handled by the Spring Boot Kotlin backend. It generates each due question, stores it before push delivery, publishes a Redis stream push job, then sends the APNs alert from an `@StreamListener` consumer.
7. Push arrival syncs data without opening a new answer page unless the user taps the notification.
8. If APNs registration is not available yet, the app can still register a backend device and use backend questions/grading manually. Scheduled push delivery starts after the APNs token is attached to that backend device.

### Community

1. Community questions are available only after Google Login.
2. A signed-in user can maintain a public profile with display name and a short bio.
3. Public community questions include the author's display name and a simple in-app pixel avatar. User-uploaded profile photos are not supported.
4. Question-publicity defaults to private for non-signed-in users.
5. Users can report public questions. Reports are persisted by the backend and may be forwarded to the operator email when SMTP is configured.

## Non-Goals

- Guaranteeing real-time push delivery independent of APNs behavior.
- Storing OpenAI billing balance locally as an authoritative source.
- Supporting more app languages than Korean and English in the current version.
- Calling OpenAI directly from the iOS or macOS app.

## Current UX Backlog

- Add optional topic merge review so users can rename or split automatically grouped topics.
- Add a compact "next best question" recommendation based on topic range uncertainty.
- Add export for records and topic stats.
- Add explicit conflict UI when two devices edit the same answer draft.
- Add service-error compensation that does not invent scores: missed scheduled question catch-up, streak freeze for backend/API outages, and automatic retry priority after failed grading.
