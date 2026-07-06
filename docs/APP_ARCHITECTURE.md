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
- `Core`: Cross-cutting, deterministic policies such as backend error presentation, page access decisions, route decisions, and formatting rules.

## Dependency Rules

- Views do not import or instantiate services.
- Services do not import SwiftUI or depend on view models.
- App-wide decisions such as "show login page", "suppress popup", or "preserve current draft" live in Core policy or use cases, not inside views.
- `AppState` is the current composition root and compatibility facade. New logic must not make it larger unless it is temporary orchestration for migration.
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
- Validation and resource errors may be shown inline using server text.
- Debug descriptions remain for logs, not end-user UI.

The policy is split into two deterministic steps:

- `StudyMate/Core/ErrorHandling/BackendErrorPresentationPolicy.swift` extracts backend code, server message, auth requirements, and reset decisions.
- `StudyMate/Core/ErrorHandling/AppErrorHandlingPolicy.swift` converts that presentation into app UI behavior. Auth, device, token, and page-access errors clear feature messages and drive login/access flows instead of repeated popups or inline banners.

## Testing Rules

- Any new policy or use case must have a focused test before production code changes.
- iOS feature work must at least pass:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
```

- If the work affects visible iOS behavior, also run an iOS simulator build.
- Existing macOS test-target issues should not be used as proof that iOS verification passed or failed; report them separately.
