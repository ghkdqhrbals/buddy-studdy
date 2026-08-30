# Pro Voice Tutor verification

Verification date: 2026-08-31. This is implementation verification, not a
production rollout or a measured ChatGPT-equivalent latency guarantee.

## Contract

- Direct iPhone/OpenAI WebRTC media, with authenticated backend-owned control;
  the bounded PCM WebSocket path remains a fallback.
- Learner overlap never triggers playback stop, response cancellation, or
  truncation. Each tutor response is one short complete sentence. The next
  answer requires completion of the same provider response, provider audio-buffer
  drain, and device playout drain, plus a committed learner turn.
- TIER2/TIER3 default to 60 minutes per user's monthly window. Tier defaults and
  personal overrides are independently adjustable. One call has a separate
  configurable ceiling, capped at 60 minutes. V100 preserves non-seed operator
  tier values, personal overrides, usage, reservations, and period boundaries.
- Recording is per-call opt-in. Pre-ready audio and connection setup silence are
  excluded. Only a positively metered terminal call can receive a recording
  upload grant; duration and bytes are bounded by server-metered time.
- Private recordings use fixed call-end retention, integrity-checked upload,
  owner-only playback/deletion, and durable deletion retries. Account changes
  invalidate in-flight requests, local exports, playback, and cached results.

## Backend checks

- Application: 114 focused tests passed across voice sessions, WebRTC lifecycle,
  recording, account withdrawal, administration, and billing.
- Infrastructure: 87 voice-focused tests passed, including turn control,
  provider negotiation/cleanup, recording storage, retention, and settlement.
- `:tutor:compileTestKotlin` passed for the MySQL integration test sources.
- An isolated, network-disabled native MySQL 8.0.44 instance applied V1–V100 and
  the repeatable migration. Queries confirmed the 3,600-second paid-tier defaults
  and FK-free recording/provider cleanup tables. A separate seed-upgrade check
  preserved an operator's 7,200-second tier, a 900-second personal override,
  23 used seconds, 12 reserved seconds, and quota revision 7. The generated
  temporary database was removed after shutdown; no existing database was used.
- MySQL 8.4 Testcontainers execution remains unverified because the local Docker
  socket did not respond. The native SQL check is not a replacement for the
  Spring/R2DBC integration suite on the production database version.

Focused backend command, from `backend/`:

```sh
./gradlew :application:test :infra:test --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=512m' \
  --tests '*VoiceTutor*' --tests '*AccountWithdrawalCleanupServiceTest' \
  --tests '*AdminManagementServiceTest' --tests '*BillingServiceTest'
```

## iOS checks

- Simulator: 121 tests passed (37 Voice Tutor contracts and 84 existing question
  flow tests), including delayed account-A responses after account-B replacement,
  request-scoped 401 handling, recording retry, durable purge, ready/end recording
  cutoffs, and same-response playout drain.
- The HTTP test fixture now isolates response handlers per client so delayed
  logout/registration cleanup cannot execute another test's assertions.
- Simulator log: `build/iOSFinalVoiceFocusedTestsStable.log`.
- The required generic iOS Debug build passed using `StudyMateiOS`,
  `generic/platform=iOS`, and `CODE_SIGNING_ALLOWED=NO`.
  Log: `build/iOSVoiceTutorFinalGenericBuild.log`.
- Physical iPhone 16 Pro: the signed app/test host built, installed, launched,
  and passed 12 selected non-mutating contract tests. These covered learner
  overlap, tutor intervention, response-generation matching, stale/early/late
  PCM drain, SDP request authentication/media types, event parsing, and the
  account identity fence. The existing app development team was supplied to
  the test target with `DEVELOPMENT_TEAM=4CL25TC734`.
  Log: `build/iOSVoiceTutorFinalDeviceTestsSigned.log`.
- Device verification deliberately excluded recording purge/file tests to
  preserve any existing user recordings. No live microphone/provider call was
  started by the device tests; acoustic and S3 end-to-end checks remain below.

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS \
  -configuration Debug -destination 'id=8DC0FFFA-22CD-4926-9DD9-FCE9F6B32AF7' \
  -derivedDataPath build/iOSPurgeTestsDerivedData CODE_SIGNING_ALLOWED=NO \
  -parallel-testing-enabled NO \
  -only-testing:StudyMateiOSTests/VoiceTutorContractTests \
  -only-testing:StudyMateiOSTests/QuestionGenerationFlowTests test
```

## Release gates

`VOICE_TUTOR_ENABLED` and `VOICE_TUTOR_RECORDING_ENABLED` remain default-off.
No production deployment, production database migration, or production S3 change
was performed. Before enabling them, complete the recording-specific privacy
approval and staging validation in [the data inventory](LEGAL_DATA_INVENTORY.md).

Real OpenAI/S3 end-to-end calls still require staging verification: listen to
learner/tutor overlap on an iPhone; measure speech-end-to-audio latency and the
sentence handoff; exercise speaker/headphone route changes, network loss,
background/lock termination, the full 60-minute boundary, recording export,
upload recovery, playback, deletion, and cross-account isolation. Automated
contracts and device launch alone do not establish those acoustic results.
