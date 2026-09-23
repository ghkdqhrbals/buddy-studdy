# iOS usability verification — 2026-09-23

Status: **39 automated tests passed; updated simulator build passed; simulator and physical-iPhone Offline QA interactions verified within the scope below**.

This report covers the usability changes made after baseline `91d335ed` in the
isolated `ios-usability` worktree. The requirement is at least ten concrete
improvements; the twelve changes below are new changes in this worktree. The
earlier interest-feed, App Store growth and signed 1.2.0 (121) work is not counted
again as an improvement in this report.

The baseline simulator build succeeded and its binary was retained. Earlier
compile failures were corrected: direct `xcresulttool` inspection of
`build/UsabilityRegressionRun3.xcresult` confirms **39 passed, 0 failed, 0 skipped**.
The subsequent UI-only large-text layout refinement is covered by the successful
final simulator build and screenshot 07, rather than by a claim that the earlier
test run executed that later layout. `/tmp/buddystudy-usability-simulator-final-build.log`
contains `BUILD SUCCEEDED`. The separate Offline QA app was subsequently
installed and launched on a physical iPhone; its actual UI results and the
flows not exercised are recorded below.

## Changes and required verification

| # | Before | Implemented change | Source | Verification and remaining checks |
| --- | --- | --- | --- | --- |
| 1 | Closing public-feed search could leave a filtered page behind. Erasing the input before closing also hid the query while an earlier filtered request could still complete. | Clearing search invalidates the filtered page and outstanding request, including when the input was already erased, and reloads the unfiltered feed while retaining the chosen scope and sort. | [Home search actions](../../../StudyMate/Views/MobileRootView.swift), [AppState.clearCommunitySearch](../../../StudyMate/ViewModels/AppState.swift), [feed search request state](../../../StudyMate/ViewModels/CommunityFeedStateStore.swift) | **Policy PASS:** all 3 `HomeSearchUsabilityTests`, including stale-response invalidation after full erase. **Physical Offline QA UI PASS:** clearing a no-match query restores feed rows (08→09); fully erasing a matching query in Following restores both followed rows and preserves scope/sort (12). Delayed real network responses were not exercised. |
| 2 | An empty public-feed search used the general empty-feed or followed-topic empty state. It did not give a search-specific way to recover. | Empty search results explain that the query can be changed and provide a Clear Search action that returns to the selected feed scope. | [communityQuestionSection](../../../StudyMate/Views/MobileRootView.swift), [feedSearchEmptyHelp](../../../StudyMate/Models/StudyModels.swift) | **Physical Offline QA UI PASS:** no-match search shows the specific empty state and Clear Search action (08); using it restores the feed (09). The operator also repeated no-match → Clear Search in Following and observed both followed rows restored. |
| 3 | Scope, sort and Interests shared a single horizontal row. Larger text or longer labels could crowd the controls, and some controls lacked explicit accessibility names or selected values. | `ViewThatFits` offers horizontal and vertical control layouts. Scope, sort, Interests count and the Home scope picker have explicit accessibility labels or values; decorative icons are hidden from accessibility. | [communityFeedControls and control labels](../../../StudyMate/Views/MobileRootView.swift), [localized labels](../../../StudyMate/Models/StudyModels.swift) | **Fixture UI verified:** screenshot 07 shows Japanese maximum accessibility text, vertical controls and multiline labels with interest count 2; the operator confirmed a successful ScrollDown action. **Not exercised:** 30-topic count, other narrow widths and spoken VoiceOver announcements. |
| 4 | Selecting a suggested interest cleared unrelated text already being entered in the input field. | Suggested-topic selection preserves the typed input. Saving can include both the selected suggestion and the pending typed topic. | [MobileTopicSubscriptionsSheet](../../../StudyMate/Views/MobileRootView.swift), [CommunityTopicSubscriptionEditorDraft](../../../StudyMate/ViewModels/CommunityFeedStateStore.swift) | **Policy PASS; simulator and physical Offline QA UI PASS:** 05 and 10 retain `Swift Concurrency` after a suggestion is added, with the count now 3/30, unlike baseline 03. The physical sheet was closed with Cancel; no production save is claimed. |
| 5 | Save remained available without an interest change, causing an unnecessary list replacement and feed refresh. | The sheet keeps its opening snapshot, enables Save only for an edit or pending input, and produces no save payload for an unchanged or reverted draft. | [editor draft and save preparation](../../../StudyMate/ViewModels/CommunityFeedStateStore.swift), [sheet Save action](../../../StudyMate/Views/MobileRootView.swift) | **Policy PASS:** unchanged, whitespace-only and reverted drafts produce no save payload. **Fixture UI verified:** screenshot 04 shows Save disabled. A captured production network trace proving no PUT was not collected. |
| 6 | Suggestions were limited to the first 20 candidates before already-selected topics were removed. Selecting those candidates could hide other available suggestions. | Already-selected topics are excluded before applying the 20-suggestion limit; normalized duplicates and invalid candidates are excluded. | [AppState.suggestedCommunityTopics](../../../StudyMate/ViewModels/AppState.swift), [CommunityTopicSubscriptionPolicy.suggestions](../../../StudyMate/ViewModels/CommunityFeedStateStore.swift) | **Policy PASS:** selected-first-20, duplicate, invalid-name and zero-limit boundaries. The 20-selected-candidate case was not separately rendered in the fixture UI. |
| 7 | Empty or invalid names, excessive length and the 30-topic limit shared one message. A duplicate entered at capacity was reported as a limit error, and old validation messages survived input corrections. | Validation distinguishes invalid names, unsupported characters, length, duplicates and capacity. Duplicate detection precedes the capacity check, and editing the input clears the previous local validation error. Korean, English and Japanese messages identify the corrective action. | [addition validation](../../../StudyMate/ViewModels/CommunityFeedStateStore.swift), [localized interest errors](../../../StudyMate/Models/StudyModels.swift) | **Policy PASS:** duplicate-at-capacity, length/key-expansion and invalid-character boundaries. **Fixture UI verified:** screenshot 06 shows the Korean duplicate message while preserving the input. English/Japanese error rendering remains unchecked. |
| 8 | Adding an interest required scrolling past the existing list, potentially 30 rows. Validation and save errors followed that list. | The input, count, limits and error feedback appear above the selected-topic list; existing remove controls remain available. | [MobileTopicSubscriptionsSheet section order](../../../StudyMate/Views/MobileRootView.swift) | **Simulator and physical Offline QA UI verified:** 04–06 and 10 show the input above 2–3 selected topics; 06 shows the error below it. A 30-topic list, software keyboard and large-text interest sheet were not exercised. |
| 9 | Records could show an empty-history message during the initial load or after a load failure, making an unavailable page look like a genuinely empty account. | Records distinguishes initial loading, a successfully empty page, search with no matches, and a failed request with Retry. | [HistoryView empty-page presentation](../../../StudyMate/Views/HistoryView.swift), [RecordsStateStore page state](../../../StudyMate/ViewModels/RecordsStateStore.swift), [record error strings](../../../StudyMate/Models/AppStrings+CommonRecords.swift) | **Policy PASS. Physical Offline QA UI PASS for failure presentation:** the normal Records tab rendered 18 fixture records; submitting a search without a backend identity showed an explicit load error and Retry (13). Retry retained the error; closing search restored 18 records. This was not a successful network recovery or a successfully empty response. |
| 10 | A Records pagination failure had no explicit recovery, and a spinner could be displayed merely because additional pages existed. | A spinner is shown only while loading. Failure retains visible records and the page position and offers Retry; an idle page with more results offers More. Automatic row-triggered pagination pauses on error. | [HistoryView pagination footer](../../../StudyMate/Views/HistoryView.swift), [record retry methods](../../../StudyMate/ViewModels/AppState.swift), [RecordsStateStore](../../../StudyMate/ViewModels/RecordsStateStore.swift), [SearchStateStore](../../../StudyMate/ViewModels/SearchStateStore.swift) | **Policy PASS:** failed append, preserved rows/offset, same-query refresh and stale-result rejection. Records pagination loading/error footer and successful retry were not exercised in the physical UI. |
| 11 | Pull-to-refresh while searching Records refreshed the unfiltered record list instead of the current query. A same-query reset also removed visible search results before the request finished. | Pull-to-refresh requests the current query. Refreshing the same query keeps visible results until replacement succeeds; failure can be retried without losing that page. | [HistoryView.refreshRecords](../../../StudyMate/Views/HistoryView.swift), [SearchStateStore.beginRecordPage](../../../StudyMate/ViewModels/SearchStateStore.swift), [record-search retry](../../../StudyMate/ViewModels/AppState.swift) | **Policy PASS:** same-query refresh/failure preserves rows and pagination. Physical UI search submission/error/retry/close was observed (13); pull-to-refresh with existing matching search results and successful network recovery were not exercised. |
| 12 | A notification-page failure after existing rows loaded lacked an inline retry, and row-triggered loading could immediately repeat failed pagination. | The notification list retains existing rows, shows a retry footer on failure, remembers whether the failed request was reset or append, and suppresses automatic pagination until an explicit retry. | [MobileNotificationsView](../../../StudyMate/Views/MobileRootView.swift), [NotificationStateStore](../../../StudyMate/ViewModels/NotificationStateStore.swift), [AppState.retryNotifications](../../../StudyMate/ViewModels/AppState.swift) | **Policy PASS:** both `NotificationStateStoreTests`, including preserved rows/retry mode and blocked automatic retry after failure. **Physical Offline QA UI:** empty error and Retry are visible (11); tapping Retry returns the same error because no backend identity exists. This is not successful recovery; loaded-page errors were not exercised. |

## Baseline screen evidence

Fresh CUA captures were taken from the retained baseline binary on an iPhone
simulator running **iOS 26.0**. These are before-change observations, not evidence
that the updated implementation works.

- [01 — Home before changes](screenshots/01-before-home.png)
- [02 — Interests before changes](screenshots/02-before-interests.png)
- [03 — Typed interest cleared after selecting a suggestion](screenshots/03-before-interest-draft-cleared.png)

Screenshot 03 records the reported reproduction: after typing an interest and
selecting a suggested topic, the input field visibly became empty. This is
direct baseline UI evidence for change 4, not a test inferred solely from code.
The other captures provide layout context; they do not establish failure-state,
network, accessibility or account-isolation behavior.

## Updated fixture UI evidence

The following four updated captures were each opened directly for visual review.
They show simulator fixture behavior, separate from production and physical
candidate evidence.

| Capture | Directly visible result | Limit |
| --- | --- | --- |
| [04 — Interests after changes](screenshots/04-after-interests.png) | Interest count 2/30, input above the selected list, Save visibly disabled before an edit. | No save request or server persistence was tested by this image. |
| [05 — Typed interest preserved](screenshots/05-after-interest-draft-preserved.png) | After the suggested SwiftUI topic was added, `Swift Concurrency` remains in the input, count is 3/30, and Save is enabled. | Demonstrates local interaction and preservation, not a production write. |
| [06 — Duplicate input](screenshots/06-after-interest-duplicate.png) | The entered duplicate remains visible, a specific Korean duplicate message appears directly below the input, and the selected count remains 3/30. | Other validation cases are covered by policy tests; their localized rendering is not established by this capture. |
| [07 — Japanese maximum accessibility text](screenshots/07-after-home-japanese-largest-text.png) | Scope, sort and Interests are arranged vertically, Japanese labels wrap, and count 2 remains visible. | The operator separately confirmed ScrollDown succeeded. The still image does not prove scrolling or spoken VoiceOver output, nor does it show the 30-topic case. |

## Physical iPhone Offline QA evidence

The separate Offline QA build (`er56Bj`) installed and launched on
**iPhone 16 Pro / iOS 26.6.2**. It uses isolated fixture data; these interactions
exercise the actual UI on physical hardware without using the personal
TestFlight account or claiming production server behavior. Captures 08–14 were
each opened directly for visual inspection. Transitions and button actions
below were observed by the operator through CUA; still images establish their
visible end states.

| Evidence | Actual interaction and observed result | Verification limit |
| --- | --- | --- |
| [08 — Search with no matches](screenshots/08-device-search-empty.png) → [09 — Feed restored](screenshots/09-device-search-cleared.png) | A no-match query displayed the search-specific empty state and Clear Search action. Tapping Clear Search restored the public fixture feed. **UI PASS.** | No production search request or ranking assertion. |
| [10 — Interest input preserved](screenshots/10-device-interest-draft-preserved.png) | Typed `Swift Concurrency`, then added the suggested SwiftUI topic. The selected count became 3/30 and the original text stayed in the input. The sheet was closed using Cancel. **UI PASS.** | No interest save or account mutation. |
| Records tab, operator observation | The tab rendered 18 fixture records normally. | No separate capture or Records initial-error, pagination-error or empty-state verification is claimed. |
| [11 — Notification error and Retry](screenshots/11-device-notification-error.png) | With no backend identity, the empty notification page displayed an error and Retry. Tapping Retry returned the same error. | Confirms visible failure feedback and an actionable retry control, **not successful recovery** or backend notification delivery. |
| [12 — Full erase preserves Following scope](screenshots/12-device-search-erased-keeps-scope.png) | In Following/Recommended, searching `async` returned one fixture row. Selecting all and pressing Backspace restored the two followed rows while retaining Following and Recommended. **UI PASS.** | Outstanding real-network response timing was not exercised; the state-level invalidation regression test covers that boundary. |
| Following no-match recovery, operator observation | In Following, a no-match search followed by Clear Search restored both followed rows. **UI PASS.** | No separate screenshot of this second no-match transition. |
| [13 — Records search error](screenshots/13-device-record-search-error.png) | Submitting `zz-usability-no-match` without a backend identity displayed “기록을 불러오지 못했습니다”, retry guidance and Retry. Tapping Retry returned the same error. Closing search with X restored the original 18 fixture records. **UI PASS for error presentation and local recovery on close.** | The query did not establish a successful no-match response; no successful network recovery, pagination retry or search pull-to-refresh is claimed. |
| [14 — Final Offline QA Records](screenshots/14-device-final-records.png) | The final Offline QA build (`OMqzss`) installed and launched successfully. Home and the 18-record list were rechecked; the capture visibly shows 18/18 records, summary and scored rows. The operator returned the app to Korean Home afterward. | Final-build rendering check, not a repeat of every earlier interaction or a production data check. |

This was the separate Offline QA installation, not a replacement of signed
TestFlight 1.2.0 (121). Final official installed-app readback, retained at
`/tmp/buddystudy-usability-preserved-testflight.json`, was inspected and confirms
`io.github.ghkdqhrbals.StudyMate` remains **1.2.0 (121)**. No login, logout,
purchase, permission expansion or production data write is recorded as part
of these Offline QA interactions.

## Automated and build evidence

Direct read-only inspection used:

```sh
xcrun xcresulttool get test-results summary --path build/UsabilityRegressionRun3.xcresult
xcrun xcresulttool get test-results tests --path build/UsabilityRegressionRun3.xcresult
```

The result bundle reports `Passed`: **39 total, 39 passed, 0 failed, 0 skipped**,
on **iPhone 11 Pro Max / iOS Simulator 26.0 (23A343), arm64**, simulator name
`BuddyStudy Review 6.5 Korean`. This supersedes the earlier compile-failure status.
These are iOS tests executed on a simulator, not physical-device tests.

| Suite | Passed |
| --- | ---: |
| `TopicSubscriptionEditorPolicyTests` | 7 |
| `CommunityFeedBlockingTests` | 3 |
| `RecordsPaginationTests` | 7 |
| `NotificationStateStoreTests` | 2 |
| `MobileHomeRefreshPresentationPolicyTests` | 2 |
| `HomeSearchUsabilityTests` | 3 |
| `PageAccessPolicyTests` | 15 |

The subsequent final simulator build also succeeded, as directly confirmed in
`/tmp/buddystudy-usability-simulator-final-build.log`. Screenshot 07 covers the
later large-text UI refinement. The 39-test result is not a claim of a rerun
after that UI-only refinement or of an end-to-end network/UI test suite.

## Verification boundaries

- **Source and automated integration:** the 39 simulator tests and final
  simulator build passed as scoped above. The tests are in
  [PageAccessPolicyTests.swift](../../../StudyMateiOSTests/PageAccessPolicyTests.swift).
  Policy tests do not replace rendered SwiftUI or actual network verification.
  Home search clearing and empty-result recovery have the physical Offline QA
  results above. Records search-error presentation, Retry and closing search
  were also observed; successful network recovery, pagination-error UI and
  loaded-notification-page retry remain not exercised.
- **Fixture UI:** retain baseline and updated captures separately. Fixtures can
  establish layout and local interaction, but cannot prove server persistence,
  production ranking, entitlement, analytics delivery or a successful interest
  write. Captures 04–07 establish the specific interest-editor and Japanese
  large-text results above, not all languages, accessibility behaviors or flows.
- **Physical iPhone:** the separate Offline QA app was installed and used on
  iPhone 16 Pro / iOS 26.6.2. Physical UI passes are limited to the recorded
  fixture interactions; they are not production authentication, persistence,
  entitlement or ranking tests. Earlier signed-candidate checks are not reused
  as passes for these changes. The personal session and drafts were not used
  for this QA; final installed-app readback confirms the original TestFlight
  installation remains 1.2.0 (121).
- **Not exercised:** the 30-topic rendered list, other narrow widths, spoken
  VoiceOver output, English/Japanese validation errors, software-keyboard and
  large-text interest-sheet combinations, real-network delayed search responses,
  Records pagination-error/search-pull-refresh UI, successful Records network
  recovery and successful notification recovery.
  These delimit the evidence; they are not additional claims of failure or a
  requirement to run unrelated account, paid Voice or purchase flows.
- **Distribution:** these changes are not claimed to be in the existing signed
  TestFlight/App Store candidate. This report records neither a new upload nor
  a release or publication result.

The implementation is based on `91d335ed`. The git commit containing this report
is the implementation snapshot; [verification-summary.json](verification-summary.json)
records exact source hashes, test runs and the final physical QA executable. The
not-exercised checks above must not be inferred to pass from automated results.

## Follow-up: empty state after deleting all records

Final source review found a regression in the new initial-loading state: deleting
all records cleared `hasLoadedPage`, but the successful delete path refreshed
studies and the public feed rather than loading a Records page. A mounted Records
screen could therefore keep showing a spinner after deletion with no record
request running. `clearStudyRecords()` now clears the record state with
`loaded: true`; identity/cache invalidation still uses the default initial state.
The existing failed-delete rollback still restores saved rows and refreshes the
backend Records page.

The new
`testDestructiveClearShowsLoadedEmptyStateWhileIdentityClearReturnsToInitialState`
checks the loaded empty state, cleared error/pagination state, and separate
identity-reset behavior. The complete `RecordsPaginationTests` suite was rerun
after this fix: **8 passed, 0 failed, 0 skipped**, verified directly with
`xcresulttool` in `build/UsabilityRecordClearRegression.xcresult`. The runtime was
the same iPhone 11 Pro Max / iOS 26.0 simulator listed above. This is a separate
follow-up run, not a claim that the earlier 39-test run included this later fix.

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug \
  -destination 'platform=iOS Simulator,id=8DC0FFFA-22CD-4926-9DD9-FCE9F6B32AF7' \
  -derivedDataPath build/UsabilitySimulatorDerivedData \
  -resultBundlePath build/UsabilityRecordClearRegression.xcresult \
  -only-testing:StudyMateiOSTests/RecordsPaginationTests \
  CODE_SIGNING_ALLOWED=NO test
xcrun xcresulttool get test-results summary \
  --path build/UsabilityRecordClearRegression.xcresult
```

The local execution log is `/tmp/buddystudy-record-clear-regression.log` and
contains `TEST SUCCEEDED`; `git diff --check` also passed. No production records
were deleted for this verification. Physical-device deletion and rollback UI
were not exercised by this state test.


## Final build and isolated device verification

After the deletion-empty-state fix, the complete offline QA builder exited 0.
Its `StudyMateiOS` Debug unsigned build used `generic/platform=iOS`,
`CODE_SIGNING_ALLOWED=NO`, and `build/iOSDeviceDerivedData`; the log
`/tmp/buddystudy-usability-qa-records-final.log` contains `BUILD SUCCEEDED`.
The copied QA app passed deep/strict code-signature checks, exact separate bundle
identifier and Keychain entitlement checks, and signing-certificate/profile match.
`bash -n scripts/build-ios-offline-qa.sh` and `git diff --check` also passed.

The final `OMqzss` artifact includes the deletion-empty-state fix. Its executable
SHA-256 is `ec94e4169f9ddd809096b6f640950c4693d7fa38095de037eb51d66e0f2df849`.
The source hashes captured by that build were checked against the final working
tree. Installation and launch succeeded for only
`io.github.ghkdqhrbals.StudyMate.OfflineQA`; Home and the 18-record fixture were
visually rechecked (capture 14). The app was left on its Korean Home screen.

The two regression runs cover 40 distinct tests: the follow-up run repeats the
seven existing Records tests and adds one new deletion/identity-reset test.
No production record deletion, backend recovery, App Store upload or TestFlight
upload was performed. The original `io.github.ghkdqhrbals.StudyMate` was read back
as **1.2.0 (121)** after final QA installation.
