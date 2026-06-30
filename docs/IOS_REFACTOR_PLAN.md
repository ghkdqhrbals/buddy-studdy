# BuddyStudy iOS Refactor Plan

## Context

The iOS app currently relies on a large `AppState` object that owns API calls, routing,
settings editing, study-room state, records, statistics, community, notifications, and
debug state. This makes it easy for the same domain concept to be represented in multiple
places. Recent pending-question bugs came from `currentQuestion`, local `studyRecords`,
and backend `BackendStudyRoom.pendingQuestion` competing as sources of truth.

## Goals

- Make backend study rooms the source of truth for study-room pending questions.
- Keep user drafts keyed by `record.id` so new push/sync data never replaces an active draft.
- Split iOS state by product area so bugs are local to one owner.
- Keep SwiftUI views thin: views should bind to feature state and call feature actions.
- Keep compatibility with current `StudyMateiOS` target and existing backend API.

## Non-Goals

- Do not change macOS behavior.
- Do not reintroduce local record persistence as source of truth.
- Do not redesign the visible UI during structural refactors unless needed for correctness.

## Target Structure

```text
StudyMate/
  Models/
    StudyModels.swift
  Services/
    RemotePushBackendClient.swift
    SettingsStore.swift
    NotificationService.swift
  ViewModels/
    AppState.swift                    # composition/root shell only
    StudyRoomStateStore.swift          # study-room pending source of truth
    RecordsStateStore.swift            # record pages/cache/search
    StatsStateStore.swift              # backend stats request/cache
    CommunityStateStore.swift          # public questions/profile/comments
    AccessStateStore.swift             # pageAccess/auth prompts
    SettingsEditingStateStore.swift    # draft/saved settings and dirty state
  Views/
    StudyView.swift
    HistoryView.swift
    StatisticsView.swift
    MobileRootView.swift
```

## Ownership Rules

- `BackendStudyRoom.pendingQuestion` owns study-room pending status.
- `StudyRecord` lists are view caches for Records/Stats. They must not decide whether a
  study room has a pending question when backend rooms are loaded.
- `currentQuestion` is legacy compatibility state. New study-room logic must not depend on it.
- Draft answers are keyed by `StudyRecord.id`.
- Route state belongs to the root/mobile navigation layer, not feature caches.

## Migration Steps

1. Extract `StudyRoomStateStore`.
   - Move pending count, room lookup, set/clear pending, and graded-record application into it.
   - Keep `AppState.backendStudyRooms` as a read-only compatibility facade.
2. Extract `RecordsStateStore`.
   - Move record page cache, selected record, search results, deletion marker logic, and refresh state.
   - Keep `AppState.studyRecords` as a temporary compatibility facade until views are migrated.
3. Extract `StatsStateStore`.
   - Move period/query/sort request state and backend stats cache.
   - Statistics views should no longer scan all records for source-of-truth stats.
4. Extract `AccessStateStore`.
   - Own `/api/v1/me/access`, 401 cleanup, 403 prompts, and page gate decisions.
5. Reduce `MobileRootView`.
   - Move profile sheet, auth sheets, community detail, notification list, and settings screen into separate files.
6. Remove legacy `currentQuestion` dependencies from iOS study-room paths.

## Test Plan

- Pending indicator appears when `BackendStudyRoom.pendingQuestion` exists.
- Pending indicator disappears after answer grading returns a graded record.
- Pending indicator disappears after skip clears the pending record.
- Local ungraded record cache does not revive a cleared backend pending question.
- Study room shows the backend pending question after entering from Home.
- Draft is preserved by `record.id` across refresh and push sync.

