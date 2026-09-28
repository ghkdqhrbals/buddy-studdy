# Answer timestamp display verification — 2026-09-28

## Scope

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
