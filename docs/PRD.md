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
4. User submits for grading. The backend accepts and persists the answer
   immediately, then grades it asynchronously through the event outbox.
5. The app shows persisted grading stages in real time over SSE and reconciles
   the completed AI decision from the record API. Reconnection resumes from
   the last received event without replacing the user's draft.
6. Grading result, feedback, and explanation are stored in records.
7. Ungraded pending questions are capped at 3.
8. My Studies shows root studies first. Selecting one opens an unlimited-depth study tree whose orientation can be switched between vertical and horizontal.
9. A root study owns the question schedule, OpenAI model, prompt, and the single question flow. Descendant nodes own only their topic, difficulty level, ordering, and question-rotation activation.
10. Adding a tree node opens GPT topic recommendations by default. The user can select one or more recommendations and add them together with one shared difficulty, or switch to manual topic entry. Duplicate normalized topic names are rejected across the user's studies.
11. Topic recommendations use a shared system catalog before generating new entries. The catalog manages up to five descendant levels and up to ten children per opened branch; missing children are generated once and become reusable suggestions.
12. The user's study tree materializes selected catalog topics lazily so a five-level catalog never creates an exponential number of unused user nodes. New root and descendant nodes participate in question rotation by default.
12. Any number of tree nodes can participate in scheduled questions. The backend rotates through active nodes by least-recent selection, skips nodes already at their pending-question limit, and backs off only when every active node is blocked; an inactive node can still be opened for explicit manual question generation.
13. Generated questions are stored under the root study and retain the selected node's topic and difficulty. Level is communicated with restrained color instead of decorative icons.
14. Tree nodes are circular. The tree supports vertical/horizontal layout, pinch and button zoom, draggable saved positions, layout reset, and multi-select activation, pause, and deletion.
15. Root creation, descendant topic creation, and question generation are separate API/client methods. Creating a root or descendant never consumes monthly question allowance.
16. The Profile > Usage page shows the current monthly question allowance, remaining count, and exact reset time. When the allowance is exhausted, question creation is blocked with a localized inline explanation.
17. Root study rows omit level metadata. Level is presented only where it affects a specific tree topic.
18. A tree node with an unanswered question shows one red badge at its upper-right corner; the node action menu remains separate at the lower-right corner.
19. Study deletion always requires an explicit destructive confirmation.
20. Manual question generation immediately shows an inline conversation-style loading message for the selected topic until the request completes.
21. Question, hint, grading feedback, and explanation content supports Markdown for emphasis, lists, and code while remaining backward-compatible with existing plain-text records.
22. The compact My Studies outline keeps the card, row geometry, and dividers fixed while newly selected branch contents settle in with a subtle direction-aware stagger; it does not blink or overlay old and new rows. Long-pressing every study card presents Edit, View Full Tree, and Delete actions; View Full Tree is not duplicated as an inline list row and remains available for childless roots so the user can enter the tree and add the first child topic. Roots without children omit only the empty child-topic section.

### Records

1. Ungraded records appear first.
2. Graded records have no user-configurable retention limit. MySQL is the source of truth and records remain until the user deletes an individual record, clears all records, or withdraws the account.
3. Records and record search load in 30-item pages as the user scrolls. The iOS in-memory view cache contains only pages fetched during the current session.
4. Record detail shows question, answer, feedback, explanation, and grading state.
5. Ungraded records can still be answered from detail.
6. Individual records can be deleted.
7. Before sign-in, the tab previews the shape and value of accumulated learning records, then offers a simple bottom-aligned `로그인` action instead of presenting a blocking login wall.

### Statistics

1. Statistics are filtered by period.
2. Topics are grouped by normalized topic key so case, spacing, hyphen, underscore, and simple camelCase variants are merged.
3. Topic range estimates combine difficulty level and score into a 1-10 ability range.
4. Topic browser supports search, sort, pagination, selected topic detail, and trend chart.
5. Similar topic aliases are visible in the selected topic detail when multiple labels were merged.
6. Before sign-in, the tab uses a subdued sample summary to explain topic progress and keeps the login invitation as a consistent bottom action.
7. Growth-topic labels wrap to show their full value instead of being truncated with an ellipsis.
8. Growth is presented root-study first. Each root card summarizes its full subtree with current 1-10 ability, period growth, measured-topic coverage, answer count, and a compact trend.
9. Opening a root shows the same circular-node tree language used by My Studies: curved directional edges, saved node positions, two-axis navigation, and direct pinch-to-zoom without separate zoom controls. The root node is labeled as the combined subtree score, while every descendant node shows its individual 1–10 ability and growth.
10. Growth compares non-overlapping previous and recent answer windows of three to five graded answers. A topic needs at least six answers before a delta is claimed; otherwise it is shown as measuring.
11. Parent growth includes its subtree and caps each measured node's weight so one high-volume topic cannot dominate the result.
12. Statistics do not expose question-activation state. Ability uses the app accent color, decline uses orange, and insufficient or stable data uses secondary gray.
13. Growth supports recent 30-day, 90-day, and one-year periods, with 90 days as the default.
14. The root-study overview uses one shared 1–10 ability axis with previous and current markers so all studies can be compared at a glance.
15. A `?` beside Growth by Study explains ability estimation, non-overlapping answer windows, minimum sample size, subtree aggregation, and capped topic weighting in plain language.
16. Measured-study coverage, generated-question completion, and answer count sit above the tree. Declining and unmeasured nodes are distinguished within the circular nodes themselves; statistics do not duplicate them in a separate priority list.

### Settings

1. Profile is a category hub, not one long form. Profile, Settings, Usage, Notifications, and Terms each open a dedicated page.
2. OpenAI API key and model are managed separately from study settings, but OpenAI requests are performed only by the backend.
3. Notification permission opens system settings; no in-app test notification button is shown.
4. Public-question visibility is stored as an account preference and is changed from Settings, not from the profile editor.
5. User-facing debugging logs are not provided.
6. Decorative setting-row icons are omitted so labels and controls remain the primary scan targets.
7. The primary iOS tab bar contains Home, Records, Statistics, and Notifications. Settings is reached through Profile.
8. The notification inbox supports marking every visible account/device notification as read in one action, independently from deletion.
9. Profile is a compact category hub: avatar editing is labeled `Avatar`, logout sits at the bottom of the hub, and account deletion lives under `Settings > Account Settings`.
10. Record settings provide destructive record management only; record retention is not configurable.
11. Notification loading failures show a short retry action without exposing HTTP status codes, gateway names, request IDs, or backend diagnostics.

### Identity

1. One app installation owns one stable backend device identity.
2. Repeated registration for the same installation rotates credentials without creating another anonymous user.
3. Access-token expiry refreshes the token without signing the user out or replacing the device identity.
4. Administrator user and quota lists contain registered members only; anonymous installation identities are operational device records, not members.
5. A newly registered email or Google account receives a Reddit-style `Adjective-Noun-####` display name. Registered display names are case-insensitively unique and remain editable subject to the same uniqueness rule.
6. Account deletion immediately disables the member identity, revokes its sessions, reconnects the current device anonymously, and durably emits `ACCOUNT_WITHDRAWN`. Idempotent asynchronous cleanup removes profile assets, public questions, studies, records, reactions, notifications, and related account data.

### Sync And Push

1. Backend sync stores settings, records, answer drafts, generated questions, grading results, and topic statistics in MySQL.
2. The app keeps records only as an in-memory view cache during a running session. It must not persist study records in a local SQLite database.
3. iCloud/CloudKit sync is no longer exposed or enabled; backend persistence is the active sync path.
4. Only the regular OpenAI API key is supported; admin keys are not supported.
5. The app does not call OpenAI directly. API-key validation, question generation, and grading go through `https://api.ghkdqhrbals.org`.
6. Server-scheduled APNs delivery is handled by the Spring Boot Kotlin backend. It generates each due question, stores it before push delivery, publishes a Redis stream push job, then sends the APNs alert from an `@StreamListener` consumer.
7. Push arrival syncs data without opening a new answer page unless the user taps the notification.
8. If APNs registration is not available yet, the app can still register a backend device and use backend questions/grading manually. Scheduled push delivery starts after the APNs token is attached to that backend device.
9. Persisted message content keeps Markdown source, while notification previews use a parser-derived plain-text projection so formatting markers are not exposed in APNs alerts. Rehydrating a queued push must rebuild this projection instead of falling back to the Markdown source.

### Internal Operations

1. The monitoring workspace includes an authenticated Users & Quotas page for operators only.
2. Operators can search users by ID, email, or display name. User lists are paginated by default.
3. Membership tiers define the default monthly question allowance. An operator can assign a tier and optionally set a per-user allowance override.
4. Payment-plan names and controls remain internal. The consumer app exposes only allowance, remaining count, and reset time.
5. Monitoring `Manage > Redis Streams` provides searchable Redis Stream topics, cursor-paginated entries, exact entry-ID lookup, consumer-group lag, and redacted message detail.

### Community

1. Community questions are available only after Google Login.
2. A signed-in user can maintain a public profile with display name and a short bio.
3. Public community questions include the author's display name and a simple in-app pixel avatar. User-uploaded profile photos are not supported.
4. Question-publicity defaults to private for non-signed-in users.
5. Users can report public questions. Reports are persisted by the backend and may be forwarded to the operator email when SMTP is configured.
6. The feed is modeled as typed content rather than question-only rows, so feedback prompts and future operational content can be inserted without pretending to be questions.
7. Feedback opens a dedicated compact form and is stored independently from question reports.

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
