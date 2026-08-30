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

## Local dev call-recovery follow-up

- The reported immediate disconnect was reproduced at the control handshake,
  not by interpreting the learner's greeting as an end command. SDP negotiation
  succeeded, but the local Routingflare origin proxy removed `Upgrade` and
  changed `Connection` to `keep-alive`, producing HTTP 422 on `/control`.
  Identical unauthenticated probes retained both upgrade headers at port 8080
  and lost them through the local proxy and public dev route. Their expected
  HTTP 401 responses were used only to inspect header forwarding; they are not
  evidence of a successful authenticated call.
- Read-only quota inspection showed four seconds charged across the reported
  attempts, no remaining reservation, and 3,596 seconds available. No quota,
  entitlement, or user-data correction was performed. The erroneous zero was
  the app's stale reservation snapshot.
- Failure cleanup now preserves failure/retry state and server failure reasons,
  clears terminal countdowns, and reconciles settled quota. Per-attempt fences
  reject old callbacks, delayed detail results, and delayed playout successes
  or failures after retry; cleanup avoids cancelling its own settlement work.
- iOS rejects empty/non-audio SDP locally, preserves authenticated request
  headers and exact SDP bytes, and waits for combined ICE/DTLS connection before
  opening control. The teacher-first greeting and readiness-confirmation
  instructions use the existing one-sentence response/playout policy.
- Backend follow-up tests passed: 25 application voice-session tests, 34
  realtime adapter/turn-controller tests, 12 WebRTC adapter tests, and 15
  control-handshake/logging tests. Startup regressions cover a fully committed
  learner greeting before readiness, one opening per call, queued learner turns
  behind the opening's full playout, and no premature continuous-speech
  intervention. Existing mid-conversation fixtures now enter through the real
  opening/completion/drain lifecycle rather than a test-only readiness bypass.
  The final combined run passed all 86 tests with zero failures. Log:
  `build/voiceCallRecoveryBackendTests.log`.
- The updated JVM JAR build passed using the existing local JDK/cache, without
  building another Docker image or creating a new database/Redis stack. Log:
  `build/voiceCallRecoveryBackendBuild.log`.
- Generic `StudyMateiOS` Debug build passed. Log:
  `build/iOSVoiceCallRecoveryGenericBuild.log`.
- The signed app was installed and launched on the physical iPhone 16 Pro;
  26 selected non-mutating contract tests passed with zero failures. These
  include the new media-readiness, SDP, failure outcome, released reservation,
  and genuinely suspended old-attempt/cancelled-result regressions, plus the
  existing sentence-overlap and same-response drain contracts. Log:
  `build/iOSVoiceCallRecoveryDeviceTests.log`. Recording purge/file tests were
  excluded to preserve user data, and no additional DerivedData directory was
  created.
- The user repaired Routingflare's WebSocket forwarding separately. Repeating
  the public dev probe then preserved `Upgrade: websocket`,
  `Connection: Upgrade`, and `buddystudy.voice.control.v2`, matching the direct
  API probe. No bypass, tunnel change, or route change was made for this fix.
  An authenticated control upgrade and audible teacher greeting still require
  a live call; the unauthenticated probe, device contracts, and app launch are
  not an acoustic end-to-end pass.

## Mid-call disconnect and silent-audio follow-up

- After the user's forwarding repair, the supplied Routingflare logs show
  authenticated HTTP 101 upgrades for both reported calls. Database metadata
  shows those calls ended after approximately 3 and 9 seconds. The old
  `COMPLETED / PROVIDER_CLOSED` fallback did not retain the winning relay branch
  or WebSocket close code and cannot attribute those incidents to Routingflare
  or OpenAI.
- A later silent-audio call remained connected for approximately 67 seconds;
  its server log records `VoiceTutorProviderPlayoutTimeoutException`. This
  establishes an unmet post-response playout gate, not an upgrade failure.
  Whether the missing condition is provider-buffer stop, device audio render,
  or device-drain acknowledgement must be distinguished rather than inferred
  from the UI's provider-driven speaking label.
- The sideband now treats unexpected receive/send completion as failure,
  including a peer close code of 1000. Explicit user/server termination remains
  successful. Diagnostics snapshot the winning branch before loser cancellation
  can synthesize a local close status; the close-status observer never extends
  connection lifetime. The control bridge records its own first terminal source
  and numeric close status, correlated through a hashed provider call reference.
- Event counters have a fixed allowlist plus one `other` bucket. Logs exclude
  raw event bodies, close reasons, exception messages, provider IDs, SDP, audio,
  transcripts, and credentials. No Routingflare route, authentication, AWS
  secret, infrastructure, quota, or response/playout timeout has been changed.
- Backend verification passed 63 focused tests: 13 lifecycle/loopback cases,
  34 realtime turn-controller tests, 12 WebRTC adapter tests, and 4 control
  handler tests. Real loopback cases include close 1000/1011, raw TCP EOF,
  outbound-only completion, explicit local endings, cancellation, and privacy
  assertions. The JVM JAR build also passed. Log:
  `build/voiceDisconnectDiagnosticsBackend.log`.
- These diagnostics and the failure classification are not proof that the
  live disconnect or acoustic failure is fixed. The actual iPhone media and
  playout path remains an end-to-end verification requirement.

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
