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

### First Launch

1. On a fresh installation, the app enters Home immediately in the first supported iOS preferred language (`ko`, `en`, or `ja`) and falls back to English.
2. First launch starts with an empty study list. The first root study is created only when the user explicitly uses the add-study action; settings or device registration must never materialize a fallback study.
3. The user can change the app language later in Profile settings. An explicit saved choice is never overwritten by later device-language changes.

### Study

1. User receives or manually creates a study question through the backend.
2. User writes an answer draft that is preserved automatically.
3. User can reveal the hint on demand.
4. User submits for grading. The backend accepts and persists the answer
   immediately, changes the question lifecycle from `UNGRADED` to `GRADING`,
   appends the lifecycle transition to the durable question event history, then
   grades it asynchronously through the event outbox. The API also exposes
   the finer `gradingStatus` (`QUEUED`, analysis stages, `COMPLETED`, or
   `FAILED`), the active `correlationId`, and the latest event cursor without
   treating the editable draft as a submitted answer.
5. The app polls grading every three seconds by the persisted correlation ID
   returned with the accepted answer. Each request includes the last received
   event cursor, so intermediate durable stages are not skipped, and the
   completed AI decision is reconciled from the record API without replacing
   the user's draft. Polling belongs to the answer/detail screen that started
   it and stops immediately when that screen disappears, while an accepted
   submission request itself is allowed to finish persisting. Reopening the study
   room calls `GET /api/v1/studies/{studyId}` instead of loading the whole study
   tree. The response contains the pending question, including its persisted
   answer, grading request ID, and current grading status, plus the latest
   completed question. The app resumes polling for a pending submitted answer;
   when no pending question exists it keeps showing the latest completed
   question, user answer, and AI response.
6. Grading result, feedback, and explanation are stored in records.
7. Ungraded pending questions are capped at 3.
8. Home exposes separate All Studies, My Studies, and My Study Tree scopes. My Studies preserves the compact hierarchical topic outline, while My Study Tree renders the selected root as an interactive node graph whose orientation can be switched between vertical and horizontal.
9. A root study owns the question schedule, OpenAI model, and the single question flow. The backend owns the question prompt; iOS study creation and editing do not expose prompt overrides. Descendant nodes own only their topic, difficulty level, ordering, and question-rotation activation.
10. Adding a tree node opens GPT topic recommendations by default. The user can select one or more recommendations and add them together with one shared difficulty, or switch to manual topic entry. Duplicate normalized topic names are rejected across the user's studies.
11. Topic recommendations use a shared system catalog before generating new entries. The catalog manages up to five descendant levels and up to ten children per opened branch; missing children are generated once and become reusable suggestions. If the external suggestion provider is unavailable, the API still returns localized, parent-scoped fallback topics so child-topic creation is not blocked.
12. The user's study tree materializes selected catalog topics lazily so a five-level catalog never creates an exponential number of unused user nodes. New root and descendant nodes participate in question rotation by default.
12. Any number of tree nodes can participate in scheduled questions. The backend rotates through active nodes by least-recent selection, skips nodes already at their pending-question limit, and backs off only when every active node is blocked; an inactive node can still be opened for explicit manual question generation.
13. Generated questions are stored under the root study and retain the selected node's topic and difficulty. Level is communicated with restrained color instead of decorative icons.
14. Tree nodes are circular. The tree supports vertical/horizontal layout, pinch and button zoom, draggable saved positions, layout reset, and multi-select activation, pause, and deletion.
15. Root creation, descendant topic creation, and question generation are separate API/client methods. Creating a root or descendant never consumes monthly question allowance.
16. The Profile page shows a compact current-tier quota summary at the top, including remaining count, progress, and exact reset time. Selecting the summary opens Membership Management as a sheet. When the allowance is exhausted, question creation is blocked with a localized inline explanation.
17. Membership allowances are TIER1 30, TIER2 300, and TIER3 1,000 questions per monthly window. TIER2 and TIER3 each offer one monthly StoreKit subscription. The membership screen presents the current allowance and both paid tiers as a compact single list with no billing-period selector, plus one primary subscribe/change action only when another tier is selected. Before purchase it clearly shows each tier name, monthly period, localized price, included monthly question allowance, automatic-renewal disclosure, and direct Terms of Use and Privacy Policy links. The active plan and quota are read only from `GET /api/v1/billing/status`; RevenueCat SDK state is limited to products, purchase, restore, and Customer Center. A paid purchase creates a `NORMAL/WAITING` invoice (`PREPARED`) before RevenueCat presents the purchase. iOS then sends RevenueCat's transaction identifier to the invoice confirmation API; server-side RevenueCat verification and the shared payment use case advance it through `VERIFIED` to `FULFILLED`. The same use case handles at-least-once RevenueCat webhooks, with event ID and transaction ID deduplication providing eventual consistency regardless of delivery order. Unpaid checkouts expire as `FAILED`; completed charges never regress because projection fails and never trigger an automatic refund. Refunds use separate linked `REFUND` invoices. Monthly quota policy v5 keeps one `user_quota` current-state row and appends every mutation to `user_quota_history`; `quota_reservations` retains the exactly-once Saga identity and the period in which work was accepted. A verified upgrade applies the higher base limit immediately without clearing committed usage, active reservations, or current-period bonus. A downgrade keeps the higher tier until `currentPlanEndsAt`; when the lower tier becomes effective it changes only the base limit, preserves the same counters, and clamps remaining allowance to zero when usage already meets or exceeds the lower limit. The managed quota rollover runs every minute and resets only at the natural monthly boundary, while quota reads and writes perform the same idempotent rollover as a recovery fallback. Every generated system question is reserved, committed, or released exactly once by Saga correlation ID, and every effective invoice-scoped tier transition is idempotent. A partial application returns an explicit error while preserving verified payment evidence for retry; iOS must not convert this failure into an approval-pending message.
18. Root study rows omit level metadata. Level is presented only where it affects a specific tree topic.
19. A tree node with an unanswered question shows one red badge at its upper-right corner; the node action menu remains separate at the lower-right corner.
20. Study deletion is available only inside the study editor and executes immediately when the destructive row is tapped, without a second confirmation popover.
21. Manual question generation immediately shows an inline conversation-style loading message for the selected topic until the request completes.
22. When asynchronous question generation or localization exhausts its retry budget, the backend must publish a durable rollback event. Its consumer removes any unusable generated question, restores the coverage reservation and monthly question allowance exactly once, and only then exposes the failed process as terminal so the user can request another question for that topic.
23. Question, hint, grading feedback, and explanation content supports Markdown for emphasis, lists, and code while remaining backward-compatible with existing plain-text records.
24. The compact My Studies outline keeps the card, row geometry, and dividers fixed while newly selected branch contents settle in with a subtle direction-aware stagger; it does not blink or overlay old and new rows. Long-pressing every study card presents Edit, View Full Tree, and Delete actions; View Full Tree is not duplicated as an inline list row and remains available for childless roots so the user can enter the tree and add the first child topic. Roots without children omit only the empty child-topic section. After entering any study topic, the trailing toolbar keeps New Question and a separate More menu visible; More provides only Edit Study and View Full Tree for the containing root. Topic deletion remains inside the study editor.
25. Before the backend study hierarchy is available, My Studies shows an initial loading state. A failed hierarchy request shows a retryable error without exposing persisted descendant topics as independent root-study cards; an ordinary refresh keeps an already loaded hierarchy visible.

### Community

1. Signed-in users can open Liked Questions from Profile and browse every public question they have liked, not only liked items that happen to be present in the currently loaded public feed.
2. The liked collection is a server-filtered, newest-first 20-item page with search, lazy pagination, retry, and a dedicated empty state. Deleted, private, unanswered, blocked-author, or otherwise non-public questions never appear, and native advertisements are not inserted into this personal collection.
3. Like state is account-owned backend data and is not duplicated into `SettingsStore`. Signing out, withdrawing the account, or resetting backend identity clears the in-memory liked collection so one account's items cannot appear for another account.
4. Unliking from question detail updates the public feed and liked collection together and removes the successful unlike from the collection without skipping the next server page. A question accepts only one in-flight like mutation at a time so delayed responses cannot overwrite a newer intent.

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
16. Total graded learning records, total topic count, and measured topic count sit above the tree. Question-workflow completion is not presented as a learning-growth statistic. Declining and unmeasured nodes are distinguished within the circular nodes themselves; statistics do not duplicate them in a separate priority list.
17. Selecting any node in the statistics tree opens its growth detail and a newest-first record list for that exact topic node. Records load in 30-item pages as the user scrolls and open the shared question-browse detail flow.
18. Re-selecting the Statistics tab within one minute reuses the current snapshot instead of issuing duplicate requests. Growth windows use stable UTC day boundaries, and equal-count topics use a deterministic name tie-break so the `This Month` card cannot change when the underlying records have not changed. Pull-to-refresh always performs an explicit refresh.
19. Learning-activity, root ability, compact trend, and topic trend graphs use a restrained pixel-game language: square activity cells, a shared ten-tile 1–10 ability track, stepped trend lines, square markers, and a lightweight HUD border. Pixel styling never changes the topic-first calculation, growth colors, or the circular study-tree nodes, and the same data remains available through localized labels and a concise VoiceOver summary.

### Settings

1. Profile is a pushed category page under Home, not a modal or one long form. Its compact top summary shows the current membership tier and quota without another navigation step; selecting it opens Membership Management as a sheet. Profile editing, Settings, Notifications, and Terms remain dedicated destinations, while Membership & Billing keeps dedicated Membership Management and Billing History destinations.
2. OpenAI API key and model are managed separately from study settings, but OpenAI requests are performed only by the backend.
3. Notification permission opens system settings; no in-app test notification button is shown.
4. Public-question visibility is stored as an account preference and is changed from Settings, not from the profile editor.
5. Developer options and debugging popups stay hidden by default. In Debug and TestFlight only, tapping the Profile version row five times within two seconds reveals developer options without opening the debug popup. TestFlight scopes the unlock to the current version/build, starts each newly installed build with debugging disabled, and requires the gesture again after a build update. App Store production ignores the gesture and cannot reveal developer controls. The maintenance screen's five-tap gesture can bypass the current gate only when developer access was already unlocked for that build; it never grants access or changes the published Firebase policy. TestFlight retains the hidden last-tab long-press debug popup after the current-build unlock.
6. Decorative setting-row icons are omitted so labels and controls remain the primary scan targets.
7. The primary iOS tab bar contains Home, Records, Statistics, and Notifications. Settings is reached through Profile.
8. On launch and foreground entry, iOS fetches and activates the Firebase Remote Config app-control policy and listens for real-time changes while the process is active. The resolution order is maintenance, forced update, optional update, then normal. Users already on the target marketing version/build or newer see no prompt. Forced and optional campaigns use the same compact centered update card while softly dimming the underlying screen. Forced mode uses a stronger dim, disables and hides the underlying app from interaction and accessibility, removes the dismiss action, and shows a required lock treatment until the installed version reaches the target. Optional mode uses a lighter non-intercepting dim, leaves the app usable, and provides Update and dismiss actions without repeatedly appearing after dismissal. iOS does not permit a consumer App Store app to silently install its own update. Opening the App Store and returning on the target version are recorded separately so operators can distinguish intent from completed conversion. Missing, invalid, unsupported, or expired Remote Config falls back to the compatibility backend update/status APIs.
9. The backend is authoritative for the learning rhythm. On launch, foreground entry, settings entry, and development/production backend changes, iOS reads `/api/v1/settings` before any local schedule upload, persists the returned interval locally, and never substitutes a missing response interval with the 15-minute onboarding default.
10. The notification inbox supports marking every visible account/device notification as read in one action, independently from deletion.
11. Tapping a system push that targets a detail selects the Notifications tab and pushes exactly one destination onto that tab's navigation stack. Home and the legacy My Studies list route bypass that stack and return directly to Home; My Studies selects the existing hierarchy-aware Home scope instead of opening a parallel flat study list. It does not construct a hidden Home > Notification Inbox > Detail stack. Ungraded notification questions expose the same Skip action as the study room, and skipped/deleted records show an explicit unavailable state instead of an empty detail shell.
12. Profile is a compact category hub: avatar editing is labeled `Avatar`, logout sits at the bottom of the hub, and account deletion lives under `Settings > Account Settings`.
13. Record settings provide destructive record management only; record retention is not configurable.
14. Notification loading failures show a short retry action without exposing HTTP status codes, gateway names, request IDs, or backend diagnostics.
15. Signed-out Settings exposes only installation-level preferences such as app language and developer promotion-code entry. Account-backed learning rhythm, notification permission, and notification sound controls appear only after sign-in.
16. The signed-out Profile hub presents Login as its primary account destination instead of Avatar. After authentication succeeds, the same destination becomes Avatar and exposes profile editing.
17. Local Debug builds default to the development API. Every signed App Store Connect Release archive, including TestFlight and App Review candidates, embeds the production API URL; TestFlight receipt detection controls distribution-only behavior and never changes backend routing. An explicit Xcode launch override takes precedence for developer testing, while a newly installed TestFlight build clears stale developer access and debugging state before its first request.

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
10. Logging out immediately unregisters the iOS app from APNs, clears pending and delivered notifications on that device, and removes the current device from the user's active backend push targets. A push already queued before logout must revalidate the active session, current user-device attachment, and current APNs token immediately before APNs delivery and must be discarded when any check fails. A later successful login re-registers with APNs using the existing system notification authorization.
11. One physical installation may sign in and out of multiple BuddyStudy accounts. Activating an account session on a device revokes every still-active session for other account IDs on that device. Inbox notifications remain owned by their intended account, while APNs delivery selects only devices whose current owner matches that account; stale cross-account sessions are a normal non-delivery condition and must not fail the notification consumer.

### Internal Operations

1. The monitoring workspace includes an authenticated Users & Quotas page for operators only.
2. Operators can search users by ID, email, or display name. User lists are paginated by default.
3. Membership tiers define the default monthly question allowance. An operator can assign a tier and optionally set a per-user allowance override.
4. Payment-plan names and controls remain internal. The consumer app exposes only allowance, remaining count, and reset time.
5. Monitoring `Manage > Redis Streams` provides searchable Redis Stream topics, cursor-paginated entries, exact entry-ID lookup, consumer-group lag, redacted message detail, and durable Inbox attempt history with retry, expired-lease, success, and terminal-failure states.
6. Sentry is reserved for backend and iOS error/fatal diagnostics. The iOS app uses Firebase Analytics only for coarse product events such as screen visits, authentication outcomes, study creation, question generation, answer grading, and notification opens. It must not send study content, answers, email addresses, tokens, device identifiers, request identifiers, or a Firebase user ID. Performance traces, standalone session replay, advertising identifiers, and PostHog collection are not part of the app.
7. iOS Session Replay uploads only when an error occurs. Ordinary sessions have a zero upload sample rate, error replays use a 100% sample rate, and all text and images remain masked.
8. Monitoring `Manage > Batch Jobs` lists registered managed jobs in bounded server-paginated status pages with each job's operator-facing purpose, schedule, monitoring state, last result, last success, and duration. Execution history is independently paginated newest-first; opening a run shows its result, error, trigger, initiator, and retry lineage and permits an authenticated manual retry. The standalone Admin `Batch Jobs` navigation links directly to this detailed workspace.
9. The home feedback prompt tells users that reviewed feedback may receive complimentary credits. Monitoring `Manage > User Feedback` provides paginated search and `NEW`, `REVIEWED`, and `REPLIED` states so operators can review submissions without exposing them in the consumer app.
10. An operator can select a registered user under `Users & Quotas` and queue a direct `ADMIN_MESSAGE` notification independently of feedback. `User Feedback` has a separate feedback-linked reply action for the user/device captured with that submission. Both flows accept a title, Markdown message, and validated `buddystudy://` destination. Supported presets include a Home message popup, Home, Records, Statistics, Settings, and Public Questions; arbitrary web URLs are rejected. The legacy My Studies deep link remains readable for already-delivered notifications but is not offered as a new destination.
11. An administrative Home-message push stores the original Markdown in the notification inbox but projects parser-derived plain text into the APNs preview. An explicit tap opens Home and presents the full Markdown message in a popup. Other validated deep links route directly to their app destination, and passive push arrival never navigates.
12. Monitoring `Manage > App Control` provides `App updates` and `Maintenance` tabs. `App updates` defaults new campaigns to `FORCE`, while retaining an explicit optional mode; it activates or ends one iOS campaign at a time, localizes update copy in Korean, English, and Japanese, publishes the resulting policy to Firebase Remote Config, exposes publication status/retry, and reports checked, prompted, App-Store-opened, and converted users. `Maintenance` publishes the newly created immediate or scheduled window directly into the same backend-owned policy before relying on a follow-up database read, confirms successful Remote Config publication in the operator UI, and shows the persisted audit history. Firebase Remote Config is the only maintenance delivery channel; no legacy public status endpoint is retained. `Users & Quotas` shows the latest reported version/build per user, while the backend retains the authoritative value and last-seen time per device.
12. Every active Redis Stream exposes a same-group recovery consumer before failures occur. Handler delivery is attempted at most three times; terminal deliveries leave the pending list but retain their Stream event for bounded operational history.

### Community

1. Community questions are available only after Google Login.
2. A signed-in user can maintain a public profile with display name and a short bio.
3. Public community questions include the author's display name and a simple in-app pixel avatar. User-uploaded profile photos are not supported.
4. Question-publicity defaults to private for non-signed-in users.
5. Signed-in users can report public questions and block abusive users. Reports are persisted by the backend and may be forwarded to the operator email when SMTP is configured. Blocking immediately hides the blocked user's public questions and comments for the blocker, rejects direct access to that user's public-question detail, and remains enforced by the backend across devices.
6. The legacy `/api/v1/public/questions` contract returns one ordered `items` list whose discriminated item type is either `PUBLIC_QUESTION` or `ADVERTISEMENT`; it remains supported for older apps and as the first-party fallback inventory. iOS renders that order without selecting, ranking, or repositioning campaigns. Every Coupang advertisement contains its provider name, optional product image, localized advertising label, title, optional body, full affiliate disclosure, server-generated `selectionId`, and HTTPS Coupang advertising URL. The iOS advertisement row follows the public-question card hierarchy and neutral primary/secondary text colors without inheriting the blue link tint, places `쿠팡`/`Coupang` in the topic position, and places the full affiliate disclosure immediately below that topic/advertising meta and before the promoted content in a compact neutral notice band. The disclosure remains visible without truncation, collapses operator-entered whitespace to natural word spacing, and wraps at the available card width. A trailing product thumbnail appears only after a valid HTTPS image loads; otherwise the content uses the full row width without an empty image placeholder. The server requires enough surrounding questions, keeps ads away from the first two rows and the end of the page, and ranks eligible campaigns from business priority, authenticated/anonymous relevance, Bayesian-smoothed 30-day destination-open rate, freshness, exploration, and Bayesian-smoothed not-interested rate. Ranking reads per-user and per-campaign signals in bounded batch queries rather than once per campaign. Daily selection caps, minimum selection intervals, and post-open cooldowns prevent fatigue; 85% of eligible selections exploit the top result and 15% explore the remaining top three. The durable selection history is the ranking source of truth. When at least 50% of an ad row remains on screen for one second, iOS idempotently records `impression_at`; this is distinct from server delivery. Opening the destination publishes one idempotent `NATIVE_AD_VIEWED` event through the transactional Outbox and `community.native-ad.view.v1`; iOS does not maintain ad-ranking history. `관심 없음` immediately removes the card and persists a user-scoped permanent campaign exclusion before future ranking, including on the user's other devices; failures restore the optimistically removed card, while successful suppressions also lower campaign-wide ranking quality. Monitoring `Advertising` is list-first and lets an authenticated operator search campaigns by key or localized title, filter by active/paused/scheduled/ended status and audience, create, schedule, pause, and edit campaigns, enter localized copy, an optional Coupang CDN image, full disclosures, and a validated Coupang URL, tune the ranking/fatigue/position controls, and inspect the exact live ranking formula. The campaign list reports 30-day feed deliveries, verified viewport impressions, destination opens, viewable open rate, not-interested count, and not-interested rate. A paginated per-campaign audience view supports user search and opened/not-opened filters, aggregates delivery/impression/open counts and timestamps per user, redacts anonymous or withdrawn identities, and never exposes raw device identifiers.
7. The current iOS client uses `GET /api/v2/public/questions`. Only the unfiltered first page may contain at most one server-positioned `NATIVE_AD_SLOT`; search, liked questions, and later offset pages never contain a slot. Anonymous users and active TIER1 users are eligible, while TIER2 and TIER3—including a scheduled downgrade before the paid entitlement expires—receive an ad-free public feed. If entitlement resolution is uncertain, the server omits the slot. The `COMMUNITY_FEED` policy is OFF by default and controls its active period, per-user UTC daily cap, minimum interval, minimum question count, and placement range; transactional reservation prevents concurrent requests from exceeding the cap. Regardless of operator values, the server keeps the slot after at least two questions and before the final question and validates a minimum 60-second interval. iOS requests a non-personalized, teen-rated AdMob native ad only after the current-launch UMP refresh and consent gathering succeed and `canRequestAds` is true. When `IABTCF_gdprApplies` says European regulations apply, the IAB TCF Purpose 1 consent string must also permit storing or accessing device information; US-state decisions instead use the GPP signal written by UMP. No ATT prompt, IDFA, BuddyStudy user/device identifier, search term, topic, content URL, or keyword is supplied. BuddyStudy does not request Limited Ads after European Purpose 1 denial/unknown state or a UMP update/form failure. AdMob success owns the slot; those privacy-gate failures, SDK error, no-fill, or the five-second timeout trigger one lazy first-party fallback request, and a late or pre-choice AdMob callback cannot replace it. There is no automatic refresh or immediate retry. AdMob delegate impressions and clicks are sent idempotently with provider `ADMOB`; first-party selections continue using their existing impression, open, not-interested, explanation, and inappropriate/age-inappropriate report paths. The deployable `Advertising` admin owns the common policy and reports 30-day slot delivery, AdMob impression/click, and fallback selection/impression/open totals.
8. Feedback opens a dedicated compact form and is stored independently from question reports.
9. Record, pending-question, public-question, and comment requests use `tl=ko|en|ja` with `view=localized|original`. Question, answer, AI response, and comment source languages are tracked independently. Manual and scheduled question creation, answer submission, grading completion, and comment creation enqueue missing supported-locale translations through the transactional Outbox; localized reads also repair missing or stale work while returning the original immediately. The legacy `language` query remains a temporary compatibility alias.
10. Canonical question and comment rows contain original text only. Translations are stored exclusively in per-content localization tables, and localized search uses the `(content ID, display language)` search read model rather than language-specific columns on the canonical row.
11. Answers use the selected display-language translation for every viewer, including the authenticated author; `view=original` is the explicit way to see the submitted text. Authenticated comment authors continue to see their own comments in the original language.

## Non-Goals

- Guaranteeing real-time push delivery independent of APNs behavior.
- Storing OpenAI billing balance locally as an authoritative source.
- Supporting app and content languages other than Korean, English, and Japanese in the current version.
- Calling OpenAI directly from the iOS or macOS app.

## Current UX Backlog

- Add optional topic merge review so users can rename or split automatically grouped topics.
- Add a compact "next best question" recommendation based on topic range uncertainty.
- Add export for records and topic stats.
- Add explicit conflict UI when two devices edit the same answer draft.
- Add service-error compensation that does not invent scores: missed scheduled question catch-up, streak freeze for backend/API outages, and automatic retry priority after failed grading.
