# Pro Voice Tutor verification

Verification date: 2026-08-31. This is implementation verification, not a
production rollout or a measured ChatGPT-equivalent latency guarantee.

## Contract

- Direct iPhone/OpenAI WebRTC media, with authenticated backend-owned control;
  the bounded PCM WebSocket path remains a fallback.
- WebRTC verifies explicit `turn_detection: null`. Automatic local microphone
  speech edges are numbered start/stop pairs; the backend alone commits input
  and schedules replies. Both SDP/control requests declare `local-vad-v1`.
- Learner overlap never triggers playback stop, response cancellation, or
  truncation. Each tutor response is one short complete sentence. The next
  answer requires completion of the same provider response, provider audio-buffer
  drain, and a committed learner turn. The continuous WebRTC track retains its
  locally buffered tail; PCM silence is not a response gate or an acoustic EOS
  acknowledgement. The legacy PCM fallback keeps its real playback callback.
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
- The iOS renderer previously moved the drain deadline for every nonempty
  buffer, including digital silence continuously delivered after speech. It
  now ignores only all-zero buffers for that deadline, retaining every nonzero
  sample and the existing same-response/server-stop/device-latency fences.
  There is no loudness threshold that discards quiet speech. Nonzero comfort
  noise remains a conservative limitation to verify during live calls.
- The WebRTC speaking label now requires local audio for an active response.
  The existing bounded app-log path exposes first-frame/nonzero-frame counts,
  audio-device and route-type/zero-volume states, safe receive error metadata,
  and the first stop source. Actual PCM, close reasons, device identities,
  error descriptions, and conversation contents stay out of those diagnostics.
- The dev API was updated to `e277e9dd` using the existing `backend` Compose
  project, port 8080, `dev` profile, and AWS Secret configuration. Both local
  dependency and readiness checks returned HTTP 200. Database, Redis,
  translation, and backup container IDs were unchanged. No Routingflare change,
  new database/Redis stack, or Docker image build was performed.
- The required generic `StudyMateiOS` Debug build passed, followed by 33
  non-mutating contract tests on the physical iPhone 16 Pro with zero failures.
  Seven new cases exercise real renderer callbacks with synthetic buffers:
  continuous digital silence, late one-unit audio, initial silence, out-of-order
  media/control, stereo/float layouts, active-response speaking state, and
  diagnostic privacy. No provider call, recording purge, or quota mutation was
  triggered by these tests. Existing DerivedData was reused. Logs:
  `build/iOSVoicePlayoutGenericBuild.log` and
  `build/iOSVoicePlayoutDeviceTests.log`.
- A subsequent instrumented short call established the first terminal source
  as `PROVIDER_EVENT_ERROR`, immediately after `input_audio_buffer.speech_started`
  and `output_audio_buffer.cleared`. Its outbound relay completed before the
  receive-branch exception escaped, so the old fallback still persisted
  `PROVIDER_CLOSED / COMPLETED`. The handler now records failure before emitting
  the terminal signal; a regression models this exact send-winning race. This
  observed case is a provider-buffer integrity event, not evidence of a
  Routingflare transport close. The uninstrumented earlier calls remain
  unattributed rather than being retroactively assigned the same cause.
- The GA sideband now sends the turn policy through `session.update` and
  requires `session.updated` to confirm `server_vad` and both explicit false
  booleans before ready or the opening response. Missing/mismatched policy does
  not silently enable the microphone; acknowledgement uses the existing bounded
  connection timeout. The old beta header is not sent on this GA path. Effective
  policy diagnostics contain only a hashed call reference and allowlisted
  configuration fields, never the returned session/instructions. This validates
  the applied contract; it does not yet prove the provider never clears a
  buffer after acknowledging that contract during a real call.
- Verification of the policy handshake and failure race passed 93 focused
  backend tests: 29 session-handshake, 13 relay-lifecycle, 5 control-handler,
  12 WebRTC-adapter, and 34 realtime turn-controller cases, with zero failures
  or skipped cases. The JVM JAR build also passed. Confirmation ordering,
  missing or incorrectly typed flags, cancellation, timeout, an early queued
  learner turn, and full sentence drain remain covered without calling the
  provider. Log: `build/voiceTutorConfigurationHandshakeBackend.log`.
- The existing dev API was then refreshed to `781c9ae3`; both local dependency
  and readiness checks returned HTTP 200. All original environment values
  were preserved exactly, including the dev AWS Secret setup. The same four
  infrastructure container IDs and the Routingflare route were retained. The
  installed iPhone build includes the silence/drain fixes at `19a1ef1a`.
  A new live call is still required to confirm audible output and the actual
  provider-policy acknowledgement together.

## Compact iOS call surface

- Replaced the oversized call/status/quota cards with a small topic identity,
  one connection-state line, a minutes:seconds countdown, and mute,
  conversation, and end-call controls. Conversation and completed learning
  details expand in the same destination; the app tab bar is hidden during
  that destination. Recording remains explicit, and permission/quota errors
  retain their actionable text and full details.
- The display-only presentation distinguishes the authoritative live
  countdown from monthly quota after settlement. A zero free quota caused by
  the current reservation cannot become a zero call timer. Failure stays a
  failure even when a learning result completes, including the period before
  failure settlement finishes. Empty/unknown results do not invent a pending
  summary. The entry destination survives removal of the quota-dependent
  start button, and captures the selected study and per-call consent.
- The required generic iOS Debug build passed with `StudyMateiOS`,
  `generic/platform=iOS`, and `CODE_SIGNING_ALLOWED=NO`.
  Log: `build/iOSCompactVoiceCallGenericBuild.log`.
- The signed app/test host passed 45 selected non-mutating contract tests on
  the physical iPhone 16 Pro with zero failures. Twelve new cases cover call
  time and reservation boundaries, phase/actions/mute, actual-speaking state,
  failure settlement, actionable errors, result content/status combinations,
  Korean/English/Japanese clocks, and synthetic native UI rendering.
  Log: `build/iOSCompactVoiceCallDeviceTests.log`.
- The native rendering test was rerun successfully after its capture-helper
  adjustments and exports seven synthetic states without creating an
  `AppState`, microphone, or provider session. The normal listening and
  expanded-conversation layouts and the narrow, large Dynamic Type English
  control layout were visually inspected. Large accessibility text uses
  horizontal icon/label rows instead of cramped three-column labels. Some
  offscreen captures omit shared navigation/control text, so these artifacts
  are not a complete seven-state visual acceptance or an interactive
  accessibility pass. Result: `build/iOSCompactVoiceCallScreensVerified.xcresult`;
  log: `build/iOSCompactVoiceCallScreens.log`.
- Existing `build/iOSDeviceDerivedData` was reused. Recording purge tests,
  real provider calls, and quota mutations were excluded. Backend, Docker
  infrastructure, AWS Secret configuration, Routingflare, and the full-sentence
  audio/turn policy were not changed for this UI follow-up. This UI verification
  does not establish an acoustic end-to-end fix.
- After confirming there were no active dev call sessions, the updated signed
  app was explicitly launched on the iPhone with `devicectl`. No call was
  started automatically.

## Mid-sentence audio cut: verified provider reproduction and local turns

This follow-up supersedes the earlier WebRTC `server_vad`/false-flags policy;
the previous checks above remain a historical record, not the current policy.

- A subsequent dev call still acknowledged both false flags, then received
  provider `speech_started` and `output_audio_buffer.cleared` during its first
  response. Its first terminal source was the provider event; the control
  disconnect followed it. That is not evidence that Routingflare initiated
  this cut, nor proof that the learner personally interrupted (echo/noise can
  also be detected as speech).
- Isolated, bounded real-provider probes used the same `gpt-realtime-2.1`
  model/`marin` voice, direct WebRTC, and existing dev AWS-secret credential.
  The learner input was a fixed synthetic greeting, never a user recording or
  live microphone. No BuddyStudy session or monthly user quota was used.
  Each provider call was explicitly hung up with HTTP 200.
- With `server_vad` and both flags false, silence input allowed a complete
  greeting. Overlapping the synthetic learner greeting instead caused a
  matching provider clear/truncation, leaving about 0.7 seconds of audible RTP
  although `response.done` reported `completed` and text finished. The same
  overlap with `semantic_vad` and both false flags also cut audio. Simply
  changing VAD type, trusting completed text, or ignoring the clear is not a
  verified fix. Logs: `build/voiceProviderProbeOverlap.log` and
  `build/voiceProviderProbeSemanticOverlap.log`.
- With explicit null detection and server-owned input commit, the overlapping
  input was acknowledged/transcribed while the first tutor sentence finished.
  The next reply was requested only after the provider buffer stopped. Both
  replies completed and stopped normally, with 602 nonzero received PCM frames,
  no clear/truncation, and no provider error. The earlier one-turn probe's
  completion timer could end during reply two; the corrected two-turn probe
  waited for both drains. Log: `build/voiceProviderProbeManualTwoTurns.log`.
- Implementation retains continuous WebRTC media and the sentence completion,
  provider-buffer, and actual device-playout gates. It changes only how input
  turn boundaries reach the server: an always-installed processed-capture
  detector, 80 ms onset/700 ms release, explicit FloatS16 normalization, paired
  sequence, bounded ordered delivery, and attempt/account/socket fences. The
  server validates ready/capability/rate/sequence, emits one commit per matching
  stop, deduplicates ACKs, and keeps the oldest pending commit timeout bounded.
  Teacher overlap never mutes input. Genuine provider integrity errors still
  fail; timeouts were not extended and failures were not hidden.
- The full focused voice backend run passed 246 tests: 61 application and
  185 infrastructure cases, with no failures or skips. This includes all 24
  orderings of commit ACK/response done/provider stop/device drain, overlapping
  utterances before an older ACK, replay/overflow/timeout/terminal fences,
  long-monologue intervention, explicit-null configuration, and incompatible
  client rejection before provider allocation. The JVM JAR build also passed.
  Logs: `build/voiceManualTurnAllBackendTests.log` and
  `build/voiceManualTurnBackendTests.log`.
- The required generic iOS build passed. The signed physical iPhone 16 Pro
  passed 66 selected non-mutating contracts with zero failures, including 21
  new local-speech tests. Existing `build/iOSDeviceDerivedData` was reused;
  recording/account purge tests and real device microphone/provider sessions
  were excluded. Logs: `build/voiceManualTurnGenericBuild.log` and
  `build/voiceManualTurnDeviceTests.log`.
- These provider/contract tests do not prove acoustic end-to-end behavior of
  the new detector on the user's microphone, noisy-room/echo performance,
  route changes, or a complete 60-minute call. Those real-device release gates
  remain below. The model, monthly/per-user limits, optional-recording consent,
  provider key source, Docker infrastructure, and Routingflare were not changed.
- Local runtime rollout applied implementation `b762d163` to the existing
  `backend-backend-1` on `127.0.0.1:8080`, retaining the `dev` profile and
  `buddystudy/dev` AWS Secret. The refreshed JAR SHA-256 is
  `78bccbbc1824b8a9237aa2b6ed060b86269d874442ffacce8e9df5de7820b830`.
  Every original environment value was compared and preserved exactly; the
  four existing infrastructure container IDs and network were unchanged.
  Both local readiness and dependency health requests returned HTTP 200.
  No new persistent infrastructure container or routing change was made.
  Log: `build/voiceManualTurnDevRefresh.log`.
- With zero active dev calls confirmed, the normal signed iOS app was built,
  installed on the paired iPhone, and explicitly launched successfully. No
  microphone/provider call was started automatically. Logs:
  `build/voiceManualTurnSignedBuild.log`, `build/voiceManualTurnDeviceInstall.log`,
  and `build/voiceManualTurnDeviceLaunch.log`.

The manual-turn wire protocol follows [OpenAI's conversation guide](https://developers.openai.com/api/docs/guides/realtime-conversations);
the observed interruption is distinguished from response completion using the
[WebRTC output-buffer event contract](https://developers.openai.com/api/reference/resources/realtime/server-events#output_audio_buffer.cleared).

## Missing learner replies: native capture-delegate registration

- Three subsequent physical-iPhone calls completed the tutor opening and then
  followed the local stop/end path, but emitted no local speech start/stop events.
  The phone's allowlisted diagnostics showed ready/listening before tutor audio,
  so neither a missing ready gate nor the later control close explained the
  absent learner turns. No transcript or preference secrets were inspected.
- The pinned LiveKitWebRTC 144.7559.14 iOS arm64 binary confirms that
  `LKRTCAudioCustomProcessingAdapter.initWithDelegate:` ignores the supplied
  delegate and initializes its weak reference to nil. The default processing
  module initializer only calls that initializer. The delegate setter actually
  stores the reference and replays initialization with the native sample rate.
  This also matches the [upstream adapter implementation](https://github.com/webrtc-sdk/webrtc/blob/m144_release/sdk/objc/components/audio/RTCAudioCustomProcessingAdapter.mm).
  The previous pure energy/stream contracts did not exercise this native boundary.
- `VoiceTutorAudioProcessingModuleFactory` now installs both delegates explicitly
  and verifies their native identity before any peer connection is made. A
  missing or wrong delegate is a startup error, not a silently deaf call. The
  existing capture tap remains strongly retained, independent of recording
  consent. Provider detection stays null; speech thresholds, full-duplex input,
  sentence completion, server-owned commits, quota, and routing are unchanged.
- Restoring the optional recording callbacks also requires normalizing their
  copied FloatS16 samples to `[-1, 1]` before the existing AAC recorder. This
  conversion never writes into the native buffer or enables unconsented audio
  persistence. Initialization/first-input diagnostics are each bounded to one
  event and expose only counters, sample rate, and gate/closed flags; no audio,
  energy measurements, user speech, or credentials are retained.
- The generic `StudyMateiOS` build passed with the existing device DerivedData.
  Log: `build/voiceCaptureDelegateGenericBuild.log`.
- The signed physical iPhone passed all 74 selected non-purge contracts,
  including eight new native-boundary cases and the existing 21 local-speech
  cases. The real pinned SDK reproduced nil constructor delegates; the
  production factory installed the exact capture/render objects and rejected
  missing, substituted, or unexpected delegates. Metadata bounds, closed-tap
  fences, no-recording capture, and copied recording normalization also passed.
- A separate, explicit opt-in microphone probe requires a physical iPhone and
  already-granted permission. It keeps only initialization/frame counters and
  stops within a three-second observation window. Its first bare-ADM attempt
  correctly failed with one initialization but zero processed buffers: without
  a peer, WebRTC has not registered ADM's audio-transport callback. The fixture
  now creates and retains an unnegotiated peer solely to initialize the media
  engine, with ICE disabled and no sender, SDP, provider session, recording, or
  playout. It keeps the original frame-receipt assertions and checks that no
  negotiation or candidate gathering occurred. The failed attempt is retained
  in `build/voiceCaptureDelegateNativeInitialAttachments`; it is not counted as
  a successful microphone test.
- Another fixture attempt reported native start `-3010` with an unavailable
  0 Hz output format. The test now waits for an active foreground test host and
  preserves bounded preflight metadata even when startup fails; it does not
  retry failed starts or turn zero frames into a pass. The earlier attempt had
  no foreground snapshot, so its exact OS activation cause is not claimed.
- The final physical-iPhone native probe passed with no failures or skips in
  0.543 seconds: four native initializations, six processed/valid input buffers,
  2,880 frames at 48 kHz, and exactly one diagnostic of each kind. All seven
  preflight snapshots showed a foreground-active app. After native startup,
  recording and the engine were running, the microphone was unmuted, and output
  remained disabled/stopped. The closed session-ready gate produced no speech
  events, while native input delivery remained observable. No sample contents
  were retained. Logs: `build/voiceCaptureDelegateNativeMicrophonePreflight.log`
  and `build/voiceCaptureDelegateNativeVerifiedAttachments`.
- This verifies actual microphone-to-native-capture delivery, not recognition
  of a user's spoken reply, an acoustic end-to-end lesson, noise robustness,
  or route changes. The ordinary iOS contract log is
  `build/voiceCaptureDelegateDeviceTests.log`; the release gates below still
  apply. Backend/Routingflare, the existing 8080 dev container, and its AWS
  secret and infrastructure were not changed by this iOS-only fix.
- The normal signed `StudyMateiOS` app was rebuilt, installed, and launched
  on the paired iPhone with the existing `https://lowfidev.cloud` dev route.
  Zero active dev calls were checked before installation and again before
  replacing the test-host process. No lesson or provider call was started by
  the launch. The same API and four infrastructure container IDs remained
  running. Logs: `build/voiceCaptureDelegateSignedBuild.log`,
  `build/voiceCaptureDelegateDeviceInstall.log`, and
  `build/voiceCaptureDelegateDeviceLaunch.log`.

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

## Replies blocked after the opening: response boundary is not PCM silence

This follow-up supersedes the earlier WebRTC client-playout-drain prerequisite.
The earlier test results remain historical evidence, not the current turn gate.

- A later 46-second call, explicitly ended by the user, had one tutor opening,
  one completed response, and one matching provider output-buffer stop. Four
  learner commits and four learner transcriptions were accepted, but no client
  playout-drain acknowledgement was sent. Thus learner capture/commit was
  working; the missing next reply was not explained by a missing user turn or
  by Routingflare closing this call.
- The phone reported 4,972 native render callbacks, 4,104 with nonzero PCM:
  roughly 41 seconds of nonzero callbacks despite an approximately six-second
  opening. The previous client timer moved its deadline on every nonzero
  render, so continuing output could starve its acknowledgement indefinitely.
  WebRTC's NetEq path can generate nonzero comfort/concealment noise after
  speech. The observed counters identify this completion-gate failure; they
  do not identify the speech type of each frame or prove acoustic end-of-speech.
- The backend now waits for generation completion and the same response's
  provider output-buffer stop, together with the existing committed learner
  turn conditions, without requiring a client PCM-silence/drain message.
  Explicit null provider turn detection and full-duplex learner capture remain
  in effect. Starting the next response neither clears/truncates the current
  output nor recreates the continuous native RTP stream; queued local audio
  remains in that stream's existing playback order.
- iOS uses the production `VoiceTutorWebRTCResponseState` to match the two
  server completion signals in either order and complete that response once.
  Native renders affect only the small speaking indication while that
  response's server output is active. Continuing nonzero output cannot move
  a completion deadline, withhold the next response, or restart the indication
  after the provider stop. This is server-streaming/UI state, not a claim that
  the final sample has already left the speaker. Acoustic tail integrity and
  no-gap sentence handoff still require the physical verification below.

### Verification at this checkpoint

- The required generic `StudyMateiOS` build passed using the existing
  `build/iOSDeviceDerivedData`. Log:
  `build/voiceResponseContinuationGenericBuild.log`.
- The physical iPhone passed all 74 explicitly selected non-purge contracts
  with zero failures. They cover the response-state change and existing
  native-boundary/local-speech contracts, not a three-turn provider call.
  Log: `build/voiceResponseContinuationDeviceTests.log`.
- The focused backend verification passed. Counting only the `VoiceTutor`
  test classes gives 61 application and 168 infrastructure tests, 229 total;
  unrelated or stale XML reports are not included in that count. Log:
  `build/voiceResponseContinuationBackendVerified.log`.
- The local JVM JAR build passed without building a Docker image. Log:
  `build/voiceResponseContinuationBackendBuild.log`.
- A separate opt-in native conversation test compiled. It is designed to
  receive three real provider responses on a receive-only iPhone peer after
  two fixed synthetic learner inputs, reuse the production renderer/response
  state, and inspect stream continuity and available native CNG/flush counters.
  It never creates a BuddyStudy session, consumes user voice quota, captures
  the user's microphone, or writes a recording. Synthetic text input is not
  microphone/ASR validation, and the fixture is not a sample-accurate acoustic
  end-of-sentence test.
- Its first device run failed before signaling reached the temporary host:
  `NSURLErrorDomain -1009`, with the network path reporting
  `Local network prohibited`. No provider call was created. The user was asked
  to enable local-network access, and confirmation was still pending at this
  checkpoint. This blocked attempt is not a passed native three-turn test,
  device-CNG proof, or acoustic acceptance. Log:
  `build/voiceResponseContinuationNativeConversation.log`.
- The test's native completion closure was explicitly annotated `@Sendable`
  after the compiler warning, and the signed iPhone test build passed again.
  Log: `build/voiceResponseContinuationFinalTestBuild.log`. This compile-only
  check did not repeat the permission-blocked provider test.
  Normal-app reinstallation/launch and the existing 8080 dev
  backend refresh for this response-continuation change were also still
  pending at this checkpoint; build success is not deployment confirmation.

### Separate OSS speech/turn-model investigation

- A bounded offline experiment used pinned Silero VAD and Smart Turn v3.2 CPU
  models with fixed Korean Yuna TTS plus seeded noise and digital silence.
  Silero distinguished that noise/silence from speech, including hesitation
  sounds. Speech presence is not a useful-utterance/filler classifier.
- Smart Turn predicts turn completion, not whether an utterance is meaningful.
  At the upstream example threshold, these synthetic fixtures included
  completed predictions for hesitation-only input and incomplete predictions
  for meaningful short replies/requests. Extra trailing silence changed some
  decisions; it did not establish a reliable filler filter.
- These are limited synthetic observations, not an accuracy benchmark for real
  Korean callers or iPhone latency measurements. Neither model was installed
  as a production turn-admission or response-completion gate by this research.
  Artifacts: `build/voiceTurnModelProbe.py` and
  `build/voiceTurnModelAssets/probe-results.json`; pinned hashes, revisions,
  example thresholds, timing environment, and model licenses are recorded
  with those local research artifacts. No user audio or provider call was used
  in that experiment.
