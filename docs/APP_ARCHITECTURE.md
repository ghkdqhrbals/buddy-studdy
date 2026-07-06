# BuddyStudy App Architecture

## Purpose

BuddyStudy's iOS app follows a Clean Architecture + MVVM composition model. The goal is not to add ceremony, but to keep product behavior predictable as the app grows: views render state, view models orchestrate use cases, use cases own app decisions, repositories hide infrastructure, and services handle integration details.

This document is the source of truth for new app code. Existing large files should be migrated toward this shape incrementally.

## Layer Rules

```text
Views -> ViewModels -> UseCases -> Repositories -> Services
                      -> Domain Models
Core policies can be used by ViewModels, UseCases, and Services.
```

- `Views`: SwiftUI rendering only. They may bind to view state and send user intents, but must not decide backend policy, auth recovery, pagination rules, or API payload details.
- `ViewModels`: Feature state and orchestration. They may call use cases or temporary legacy services during migration, but should not contain raw HTTP, persistence format, or UI styling policy.
- `UseCases`: One user/business action per type. Examples: load public questions, delete study, refresh page access, create question, grade answer.
- `Repositories`: Protocol-backed interfaces that expose app concepts and hide backend/local persistence details.
- `Services`: Infrastructure adapters such as HTTP clients, settings persistence, OAuth, APNs, CloudKit compatibility, and logging sinks.
- `CloudSyncProvider`: the service boundary for CloudKit availability and cloud-sync service construction. View models request cloud sync through this provider instead of instantiating CloudKit infrastructure directly.
- `AppPlatformEffectsProvider`: the service boundary for app lifecycle side effects such as background tasks, app badges, external URLs, and platform-owned app removal. View models request the effect instead of calling UIKit/AppKit or process/file APIs directly.
- `AppNotificationEventProvider`: the service boundary for app-wide notification streams such as backend traffic logs and unauthorized backend events. View models consume typed callbacks instead of subscribing to `NotificationCenter` names and payload keys directly.
- `ClipboardProvider`: the service boundary for reading pasteboard contents and turning platform clipboard payloads into app-level values.
- `AppLogRepository`: the repository boundary for persisted, paginated app diagnostics. View models may page and append app logs through this repository, but must not call `SettingsStore` log APIs directly.
- `RemotePushRegistrationRepository`: the local repository boundary for persisted backend device identity and access-token registration. View models must not call `SettingsStore` registration APIs directly.
- `CommunityProfileCacheRepository`: the local repository boundary for cached community profile identity and avatar state. View models must not call `SettingsStore` profile-cache APIs directly.
- `Core`: Cross-cutting, deterministic policies such as backend error presentation, page access decisions, route decisions, and formatting rules.
- `StudyRecordIdentityPolicy`: the shared Core policy for question normalization and study-record identity matching. Views and view models should use this policy instead of reaching into persistence services for comparison rules.
- `OpenAIAPIKeyExtractionPolicy`: the shared Core policy for deterministic OpenAI API key extraction from text.
- `QuestionSchedulePolicy`: the shared Core policy for scheduled question due-date calculation from the current question and study history.

## Dependency Rules

- Views do not import or instantiate services.
- Services do not import SwiftUI or depend on view models.
- App-wide decisions such as "show login page", "suppress popup", or "preserve current draft" live in Core policy or use cases, not inside views.
- `AppState` is the current composition root and compatibility facade. New logic must not make it larger unless it is temporary orchestration for migration.
- Backend client construction and use-case composition must stay behind `AppUseCasesProvider`; `AppState` may hold `AppUseCases`, but must not store backend transport clients or directly construct use cases from a backend client.
- Domain models stay platform-neutral and must not know about network response status, SwiftUI state, or local storage keys.

## Current Migration Map

The app currently has a few oversized compatibility files:

- `StudyMate/ViewModels/AppState.swift`: root composition, routing, feature orchestration, legacy compatibility facades.
- `StudyMate/Views/MobileRootView.swift`: iOS root navigation plus several feature screens.
- `StudyMate/Services/RemotePushBackendClient.swift`: backend HTTP transport plus backend DTOs.

Migration should be done in safe vertical slices:

1. Move cross-cutting policy out of transport and view models into `StudyMate/Core`.
2. Introduce use-case protocols for one workflow at a time.
3. Move feature state from `AppState` into focused stores or feature view models.
4. Split `MobileRootView` by screen only after state ownership is clear.
5. Keep `StudyMateiOS` building after every slice.

## Error Handling Contract

Backend errors are normalized once, then consumed by the app:

- Server-provided `message` is the user-facing text when an inline message is appropriate.
- Auth/device/token errors are identified from string error codes, HTTP 401, or numeric auth-range codes. They are not shown as repeated popups or inline banners. They drive login or re-registration flows.
- Email verification errors stay in the verification-code flow and must not be collapsed into the generic login-required/page-access-denied flow.
- Validation and resource errors may be shown inline using server text.
- Client decoding failures must never show raw system strings such as missing coding-key messages. They are normalized into one friendly inline retry message and logged for diagnostics.
- Debug descriptions remain for logs, not end-user UI.

The policy is split into two deterministic steps:

- `StudyMate/Core/ErrorHandling/BackendErrorPresentationPolicy.swift` extracts backend code, server message, auth requirements, and reset decisions.
- `StudyMate/Core/ErrorHandling/AppErrorHandlingPolicy.swift` converts that presentation into app UI behavior. Auth, device, token, and page-access errors clear feature messages and drive login/access flows instead of repeated popups or inline banners.
- ViewModels must consume `AppErrorHandlingPolicy`; `RemotePushBackendError` must not expose UI presentation convenience properties.
- `AppState` must not write raw `error.localizedDescription` into primary user-visible error state. Use the common policy so backend, decoding, cancellation, and auth errors behave consistently.
- Refresh flows must complete their visible refresh task and only show loading indicators inside the content region being refreshed. Do not leave duplicated global and inline spinners for the same request.
- Login-required flows should route to the simple login page for the current protected page, preserve the selected tab/screen, and return by dismissing the login page after successful sign-in.
- Backend identity transport calls such as device registration, access-token bootstrap, and APNs token updates must go through `BackendIdentityUseCase`, not direct `AppState` calls to `RemotePushBackendClientProtocol`.
- Backend identity operations must go through `IdentityRepository`. `BackendIdentityUseCase` owns device registration, access-token bootstrap, and APNs token update workflows and must not depend directly on backend transport protocols.
- Google sign-in provider operations must go through `GoogleSignInRepository`. `GoogleSignInUseCase` owns the app sign-in action and must not construct or depend directly on OAuth provider services.
- Page-access refresh must go through `PageAccessRepository`. `RefreshPageAccessUseCase` owns the page-access backend action and must not depend directly on backend transport protocols.
- OAuth provider services such as Google sign-in must be owned by auth use cases. ViewModels should request a sign-in result from `GoogleSignInUseCase` instead of constructing provider services directly.
- Community backend operations must go through `CommunityRepository`. `CommunityUseCase` owns the app workflow contract and must not depend directly on backend transport protocols.
- Study room backend operations must go through `StudyRoomRepository`. `StudyRoomUseCase` owns study list/create/delete/question workflows and must not depend directly on backend transport protocols.
- Records backend operations must go through `RecordsRepository`. `RecordsUseCase` owns record list/detail/answer/grade/delete/publicity workflows and must not depend directly on backend transport protocols.
- Statistics backend operations must go through `StatsRepository`. `StatsUseCase` owns topic stats and activity workflows and must not depend directly on backend transport protocols.
- Notification backend operations must go through `NotificationsRepository`. `NotificationsUseCase` owns notification list/read/delete workflows and must not depend directly on backend transport protocols.
- Settings backend operations must go through `SettingsRepository`. `SettingsUseCase` owns backend settings, model options, API-key validation, and schedule sync workflows and must not depend directly on backend transport protocols.
- Persisted app diagnostics must go through `AppLogRepository`. `AppState` can orchestrate debug-log paging, but local persistence details stay behind a repository adapter.
- Persisted backend device identity must go through `RemotePushRegistrationRepository`. `AppState` may request or update registration state, but local storage details stay behind a repository adapter.
- Cached community profile state must go through `CommunityProfileCacheRepository`. `AppState` may reconcile profile state with backend responses, but user-default keys and trimming behavior stay behind a repository adapter.

## Testing Rules

- Any new policy or use case must have a focused test before production code changes.
- iOS app architecture tests live in the `StudyMateiOSTests` target and are attached to the `StudyMateiOS` scheme. Put new Core policy and iOS-safe use-case tests there instead of relying on the legacy macOS-hosted `StudyMateTests` target.
- iOS feature work must at least pass:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
```

- If the work affects visible iOS behavior, also run an iOS simulator build.
- Existing macOS test-target issues should not be used as proof that iOS verification passed or failed; report them separately.
