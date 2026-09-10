# Prepared iOS study opening — 2026-09-10

## Cause and behavior

The My Study Tree node action assigned selectedRoomID immediately. Its local
navigation destination constructed StudyView with the default unprepared state,
bypassing the detail/quota gate already used by My Studies. StudyView displayed
the question while hiding the answer until its task resolved the initial state;
inserting that answer after the push moved the learning-record section.

Both entry points now await the same preparation function. It resolves the exact
study detail and quota and loads one authenticated learning-record page (up to
30 entries) through the existing identity-scoped cache. A restored login first
resolves its missing profile, so history cannot appear late when that profile
arrives. The tree keeps its own
navigation stack and displays loading inside the selected node. Navigation is
published only after preparation succeeds. Failure leaves the tree available
for retry. A different node selection, leaving the tree, changing tabs or entering
selection mode cancels the pending navigation; stale account/detail results are
discarded.

The prepared destination initializes answer editing state and the saved draft
before its first layout. The history section adopts the prepared page
synchronously and skips only its initial duplicate refresh. Explicit refresh,
pagination and returning to the history section continue to reload normally.
Grading polling remains owned by the visible StudyView, not the preload task.

No backend, schema, quota mutation, user-answer submission, deletion or publication
change is part of this fix.

## Verification

Verification artifacts are stored under build/study-opening/.

- Generic iOS Debug build, StudyMateiOS / generic/platform=iOS, signing disabled:
  passed (ios-generic-final.log).
- iOS simulator: 127 tests, zero failures or skips
  (ios-simulator-final.xcresult and ios-simulator-final.log).
- iPhone 16 Pro / iOS 26.6.1: the same 127 tests, zero failures or skips
  (ios-device-final.xcresult and ios-device-final.log).
- Suites: QuestionGenerationFlowTests 91, StudyLearningRecordsTests 34,
  StudyPreparedPresentationTests 2. The 17 new regressions cover delayed
  detail/quota/profile/history, failure, cancellation, preserved tree navigation,
  draft retention, identity changes, prepared history and duplicate-read avoidance.
- Hosted iPhone and simulator UI tests mount an isolated synthetic StudyView,
  inspect its actual editable UIKit input before the asynchronous task runs,
  and compare text and frame after the task settles. Both empty and multiline
  drafts remain visible with stable geometry. Initial/settled screenshots are
  attached to the result bundle and exported to device-attachments-final/.
- The first UI-test run exposed two test-harness assumptions: the native text
  control is shorter than its SwiftUI wrapper, and a simulator window scene may
  be foreground-inactive. The harness now checks the actual font line height
  and uses the connected-scene fallback; final reruns passed on both platforms.
- Independent implementation review and git diff --check passed. No live user
  answers or record-management actions were executed. Hosted tests exercise the
  destination layout; source review and delayed-request tests cover its tree
  navigation integration.

The signed final app was installed by the device tests and launched on the
user's iPhone at 17:02:04 KST using https://lowfidev.cloud (iphone-launch.log).
No server restart or deployment was needed for this iOS change.
