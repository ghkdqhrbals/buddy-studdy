# Answer timestamp display verification — 2026-09-28

## Initial display change (247fb66a)

Community question cards and history rows now display `answeredAt` when
available, falling back to question creation time for unanswered or legacy
records. The existing backend timestamp is set when an answer is submitted,
before asynchronous grading. No API or persistence changes are needed.

History rows use the existing localized relative-time/short-date formatter so
the timestamp fits alongside topic, difficulty, and score. Detail formatting,
feed ranking, record sorting, and pagination remain unchanged.

## Verification

The final source passed the required generic iOS build:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS \
  -configuration Debug -destination 'generic/platform=iOS' \
  -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
```

The separate device QA artifact also built and passed its bundle, signature,
capability, and fixture-isolation checks:

```sh
bash scripts/build-ios-offline-qa.sh --fixture records --language ko \
  --derived-data-path build/iOSDeviceDerivedData
```

Installed only `io.github.ghkdqhrbals.StudyMate.OfflineQA` on the connected
iPhone 16 Pro running iOS 26.6.2. The installed production/TestFlight app and
its drafts were not replaced. Inspected the actual device through iPhone
Mirroring using synthetic fixtures; no production request was made.

- Records, Korean: the first two timestamps were fully visible as `11시간 전`
  and `23시간 전`. These fixtures were created 12 and 24 hours before launch
  and answered seven minutes later, so the labels reflect answer time.
- Feed, Korean: the second and fourth cards showed `2시간 전` and `5시간 전`,
  matching answers 170 and 340 minutes before launch. Their question creation
  times were 180 and 360 minutes before launch.
- Independent source review confirmed the fallback for missing answer dates,
  preserved server-provided answer timestamps, and unchanged ordering.
- `git diff --check` passed. No macOS app build or tests ran. No new automated
  test suite was added for this presentation-only change.

Machine-local logs: `/tmp/buddystudy-answer-timestamps-ios-build.log` and
`/tmp/buddystudy-answer-timestamps-device-qa.log`. The final physical artifact
was built in the temporary `buddystudy-offline-qa.X45shR` directory.

This verifies source compilation and physical fixture rendering, not an App
Store release or a live backend integration test.

## Follow-up: server-owned v2 time display

The user's follow-up moves relative wording into the backend. The final client
uses the [v2 read contract](../API_V2_TIME_DISPLAY.md) and displays matching server
text verbatim. An absolute localized date is the compatibility fallback. The
earlier local-formatter verification above records the initial change only.

The required generic iOS build passed again with the v2 models, routes, and views.
The separate offline QA app also built, passed signature/isolation validation,
and was installed and launched on the iPhone 16 Pro without replacing TestFlight.
Final QA artifact: temporary `buddystudy-offline-qa.z7M8Pv/QA/BuddyStudyOfflineQA.app`.

Machine-local logs for this follow-up:

- `/tmp/buddystudy-time-v2-ios-build.log`
- `/tmp/buddystudy-time-v2-device-qa.log`
- `/tmp/buddystudy-time-v2-ios-tests.log`

Backend focused verification passed 61 tests with zero failures/skips: 21
formatter cases, four study-route cases, three community display cases, five
existing community adapter cases, 16 request-logging cases, 11 authentication
boundary cases, and one bearer-filter case. These cover answer-first selection,
missing/future dates, duration boundaries, locales, one clock snapshot, unchanged
v1 JSON/cached DTOs, pagination, ownership failures, and anonymous versus owned
routes. Command (from `backend`):

```sh
./gradlew -Pkotlin.daemon.jvmargs=-Xmx3g \
  :application:test --tests '*RecordTimeDisplayPolicyTest' \
  :infra:test --tests '*StudyV2ControllerTest' --tests '*CommunityTimeDisplayTest' \
  --tests '*CommunityWebAdapterTest' --tests '*RequestLoggingFilterTest' \
  :tutor:test --tests '*RecordV2SecurityTest' --tests '*BearerTokenFilterTest' \
  -x :tutor:processTestAot --console=plain
```

The local Kotlin daemon needed a command-only heap increase. Tutor test AOT
discovery was excluded because it tries to initialize unrelated Docker-backed
integration suites; the selected database-free real-security-chain tests ran.
No Gradle configuration or production runtime setting changed for these local
constraints. Independent reviews of the final iOS and backend changes found no
blocking issues. Release metadata and TestFlight note validators also passed.

Final iOS verification passed **298 tests, zero failures/skips** using
`StudyMateiOS` on the disposable iPhone 17 Pro iOS 26.0 simulator, with
`-parallel-testing-enabled NO`: QuestionGenerationFlow (145),
StudyLearningRecords (35), VoiceCommonRecord (66), VoiceTutorDiscovery (38),
and VoiceTutorGradingResult (14). This includes seven new display/routing cases
and existing draft, ownership, pagination, and stale-response protections.
An initial parallel run exposed a mismatched unlike fixture ID and two
wall-clock ordering assumptions, which were corrected with test-owned response
gates. It also exhausted local disk and was interrupted; generated device build
cache was removed before the successful serial run. No source or user data was
removed.

Final log: `/tmp/buddystudy-time-v2-ios-tests-final.log`. Result bundle:
`build/iOSSimulatorDerivedData/Logs/Test/Test-StudyMateiOS-2026.09.28_20-13-45-+0900.xcresult`.

The follow-up's physical app installation and launch succeeded. After the
iPhone Mirroring "iPhone in use" condition cleared, the final separate QA app
was visually checked on the iPhone 16 Pro at 20:43 KST: Korean Records in dark
mode showed the response-fixture labels `11시간 전`, `23시간 전`, and `1일 전`
fully visible alongside topic, difficulty, and score. Production/TestFlight and
its drafts were not replaced. This physical check covers Records; feed and
other locales were checked on the simulator below. Backend deployment and App
Store submission receipts are recorded separately after release completion.

Final v2 fixture rendering was also inspected on the disposable iPhone 17 Pro
simulator: Korean Records showed `11시간 전` and `23시간 전`, English Home showed
`1 hour ago`, `2 hours ago`, `4 hours ago`, and `5 hours ago`, and Japanese
Records showed `11時間前` and `23時間前`. Each label was fully visible alongside
the existing row metadata. These are offline synthetic response fixtures and do
not replace physical-device or live-backend integration evidence.


## Deployment and replacement review submission

Implementation PR #15 was merged as `d012df1683785e0d76b3f1a7ed84c9a134893b47`.
Backend source run `36414738795` and deploy receiver `36415355234` succeeded;
receiver source SHA/run and image digest were checked independently. iOS release
run `36414741343` successfully uploaded **1.3.0 (127)** from that same source.
After Apple returned VALID/APP_STORE_ELIGIBLE, the previous waiting build 126
submission was canceled, build 127 selected, localized notes updated, and the
new submission verified as WAITING_FOR_REVIEW. Manual release, all screenshots,
and reviewer access were preserved. See the [release receipts](../../app-store/releases/1.3.0/README.md#answer-time-replacement-build-127)
for immutable IDs, hashes, timestamps and verification scope.
