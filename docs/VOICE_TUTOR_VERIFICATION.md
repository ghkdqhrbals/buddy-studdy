# Pro Voice Tutor verification

Verification date: 2026-08-31. This is implementation verification, not a
production rollout or a measured ChatGPT-equivalent latency guarantee.

Latest implementation: [bundled Silero plus contextual meaningful-input
assessment](#silero-and-contextual-meaningful-input-assessment), commit
`9b1d30f0`, is running in the existing 8080 dev API and installed on the physical
iPhone. It follows the response-continuation fix `c33f0fe4`. The earlier checks below are historical;
they do not all describe the current implementation. The selected native
three-turn test [passed after network
approval](#physical-native-three-turn-verification-after-network-approval),
but that receive/playout test is not a microphone/semantic-gate end-to-end test.
The [earlier OSS probes](#additional-meaningful-input-model-probes) motivated
separating acoustic speech detection from contextual communicative intent;
none of their rejected semantic models or filler regexes was installed.

## Contract

- Direct iPhone/OpenAI WebRTC media, with authenticated backend-owned control;
  the bounded PCM WebSocket path remains a fallback.
- WebRTC verifies explicit `turn_detection: null`. Automatic local microphone
  speech edges are numbered start/stop pairs; the backend alone commits input
  and schedules replies. Both SDP/control requests declare `local-vad-v1`.
- Learner overlap never triggers playback stop, response cancellation, or
  truncation. Each tutor response is one short complete sentence. The next
  answer requires completion of the same provider response, provider audio-buffer
  drain, and a meaningful, persisted learner turn with no unresolved input work.
  A raw microphone edge, commit acknowledgement or partial transcript is not
  permission to reply. The continuous WebRTC track retains its
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

### Local rollout and device-network follow-up

- Commit `c33f0fe4` was installed in the existing `backend` Compose API service
  on `127.0.0.1:8080`. The `dev` profile, AWS dev-secret configuration, original
  environment values, mounts, resource limits, and network were preserved.
  The database, Redis, translation, and backup container identities and start
  times were unchanged. Only the API was refreshed; no Docker image was built
  and no additional infrastructure stack or route was created.
- The JAR SHA-256 is
  `ee156104cfe927fcb8c2930f97f2bcbc2cad8835fad93a202e156f32dba81800`.
  The previous JAR remains available for local rollback. No active voice
  session was present before the refresh. Manual local health and readiness
  requests both returned HTTP 200; no GitHub Actions runtime check was added.
- The normal signed `StudyMateiOS` app rebuilt successfully, was installed on
  the physical iPhone, and launched with the existing public dev base URL.
  The normal build removed the test-only injected frameworks and plug-ins.
  Logs: `build/voiceResponseContinuationSignedBuild.log`,
  `build/voiceResponseContinuationDeviceInstall.log`, and
  `build/voiceResponseContinuationDeviceLaunch.log`.
- After the user enabled iPhone local-network access, the native three-turn
  test no longer reported `Local network prohibited`. Its requests to the
  temporary Mac fixture instead timed out with `NSURLErrorDomain -1001`.
  No provider call or answer was observed, and the temporary fixture exited.
  Log: `build/voiceResponseContinuationNativeConversationAllowed.log`.
  This is still not a passing three-turn/native-audio test.
- Read-only checks confirmed the Mac LAN address and the phone's CoreDevice
  local-network connection. The Mac firewall is enabled; the actual Homebrew
  Python executable is ad-hoc-signed and not listed as an approved application.
  The user was asked to approve Python's incoming connections. This is a
  candidate cause of the LAN timeout, not a proven packet-drop attribution.
  No firewall setting or routing rule was changed to bypass it.

### Additional meaningful-input model probes

- Namo Turn Detector v1 Korean was evaluated using the author's exact
  single-transcript input, quantized ONNX model, and unmodified argmax decision.
  Its documented complete/incomplete examples passed. However, it classified
  short meaningful Korean replies such as readiness confirmations, requests,
  and affirmative/negative answers as incomplete. Some fillers scored higher
  than meaningful replies, so tuning one threshold is not a justified fix.
  It was not installed as a production input gate. Fixed revision:
  `8a7c88d5daab243a0220cf7a6c70060a583ba77b` (Apache-2.0).
  Artifacts: `build/namoTurnModelProbe.py` and
  `build/voiceTurnModelAssets/namo/probe-results.json`.
- A separate contextual probe used official Qwen2.5-0.5B-Instruct Q8_0 with
  `de.kherud:llama:4.2.0`, one frozen prompt, six in-prompt examples, and 36
  held-out synthetic teacher/learner pairs. It preserved all 25 meaningful
  inputs but incorrectly admitted 10 of the 11 hesitation/noise cases.
  Overall accuracy (26/36) was only one case better than always admitting
  input. It was not adopted. CPU inference was about 455 ms median on this
  Mac, with about 943 MiB peak process RSS; these are not server/iPhone latency
  or memory measurements. The isolated probe also required explicit process
  termination after results were saved because a JNI-attached thread prevented
  natural JVM exit. No application process was terminated by that cleanup.
  Artifacts: `build/QwenTurnModelProbe.java` and
  `build/qwenTurnModelProbe/RESULTS.md`.
- Qwen3-0.6B was also checked with a pinned quantized ONNX conversion and the
  same frozen 36 held-out contextual cases. The canonical non-thinking chat
  template and first-token two-label logit selection rejected 12 of the 25
  meaningful inputs and admitted 5 of the 11 noise/hesitation inputs (19/36
  correct). It was not adopted. This is a limited probe of this model,
  conversion, prompt, and decision method, not a claim that all open models
  cannot classify Korean turns. Median inference was approximately 1.21 s,
  with 2.30 GiB peak Python process RSS on this Mac, not on the iPhone or dev
  JVM. ARM64 libraries were found in the ONNX Runtime Java artifact indexes;
  that packaging check is not a native Java load or deployment test.
  Conversion revision: `da1453100cf3ff33ef56d17983fc7a8648706db6`, linked
  upstream Qwen revision `c1899de289a04d12100db370d81485cdf75e47ca`
  (Apache-2.0). Artifacts: `build/qwen3OnnxTurnModelProbe.py` and
  `build/voiceTurnModelAssets/qwen3/summary.json`.
- One bounded follow-up reused those same Qwen3 weights with a single Korean
  zero-shot prompt and 24 new cases frozen before inference. It accepted all
  12 meaningful inputs, but also accepted all 12 hesitation/noise/empty inputs
  (12/24 correct). The original 36 cases were counted only as diagnostics, not
  fresh held-out data; 10 of their 11 negative cases were admitted, and one
  meaningful input was dropped. No further prompt/threshold tuning was done.
  This also failed admission requirements. The run finished in 46.38 seconds,
  with approximately 748 ms median per new case and 2.20 GiB peak process RSS.
  Artifacts: `build/qwen3OnnxKoreanTurnModelProbe.py` and
  `build/voiceTurnModelAssets/qwen3/koreanFollowup/summary.json`.
- No regex, filler word exclusion list, or unvalidated semantic gate has been
  added to production code. Speech detection, turn completion, and meaningful
  input are separate decisions; passing one is not evidence of the others.
- After the probes stopped, only the three downloaded, rejected Namo/Qwen
  weight files were removed (approximately 1.43 GB total). Frozen cases,
  results, scripts, model revisions, licenses, and SHA-256 metadata remain for
  reproducibility and explicit re-download. No application data, existing
  Docker volume, user recording, or unrelated Xcode artifact was removed.

### Physical native three-turn verification after network approval

- Read-only firewall state subsequently showed the actual Homebrew Python
  executable allowed to accept incoming connections. The user-approved network
  path then reached the temporary fixture. The selected physical-iPhone test
  passed in 30.65 seconds with zero failures; the fixture observed one provider
  allocation, completed all three teacher responses, accepted both fixed text
  learner messages, and received HTTP 200 for provider hangup before exiting.
  Log: `build/voiceResponseContinuationNativeConversationFirewallAllowed.log`.
- The metadata-only XCTest attachment recorded new native nonzero buffers for
  every turn (726, 845, 808), with new inbound RTP bytes for every turn
  (62,009, 69,328, 70,699). The receiver, track, SSRC, codec, and transport stayed
  the same. Across 118 inbound statistics samples, RTP timestamps moved forward
  115 times and backward zero times; byte/packet counters remained monotonic.
- The available native counters observed 13 CNG frames and 3 PLC frames, zero
  jitter-buffer flushes after the first turn, and 344 nonzero callbacks after
  response completion. Thus this run actually exercised continuing native
  non-silent output without depending on a client drain acknowledgement.
  Provider clears, truncations, and errors were all zero. No local recording,
  capture buffers, input events, microphone permission, or app-quota request was
  involved. The final state was listening, with native playout still enabled.
- This is evidence for repeated native receive/playout and stream continuity,
  not a microphone/ASR test, full app-control/backend integration call, listening
  judgement of acoustic quality, or a sample-accurate guarantee about the last
  audible syllable. The synthetic fixture uses the production renderer and
  response-state types; backend turn scheduling is verified separately above.
- Result bundle:
  `build/iOSDeviceDerivedData/Logs/Test/Test-StudyMateiOS-2026.08.31_17-34-22-+0900.xcresult`.
  Exported attachment directory:
  `build/voiceResponseContinuationNativeConversationPassedAttachments`.
  A reusable, explicitly opt-in fixture and manual instructions are provided in
  [the native conversation probe guide](../scripts/voice-tutor-native-conversation.md).
  No paid call or runtime check was added to CI.
- After the selected test, a normal signed app build passed, the iPhone install
  completed, and only then was the normal app launched with the unchanged dev
  base URL. This removed test-injected frameworks and plug-ins again. No active
  BuddyStudy voice session existed before reinstalling. Logs:
  `build/voiceResponseContinuationAfterNativeSignedBuild.log`,
  `build/voiceResponseContinuationAfterNativeDeviceInstall.log`, and
  `build/voiceResponseContinuationAfterNativeDeviceLaunch.log`.
- The reusable host was extracted from the local fixture after the passing
  native run and then hardened for canceled/in-flight allocation cleanup. Its
  17 no-network regression tests passed: admission closes immediately on stop,
  one concurrent offer owns allocation, cleanup recovers a delayed call ID
  within the original negotiation deadline, and uncertain allocation/hangup
  cannot report success. HTTP/websocket operations are in-memory fakes and real
  client/listener construction is forbidden in these tests. No additional paid
  provider call was made for this host-only hardening. Root verification log:
  `build/voiceNativeHostOfflineRegression.log`.
  Reproduce using the [offline host checks](../scripts/voice-tutor-native-conversation.md#no-network-host-regression-checks).

## Silero and contextual meaningful-input assessment

### Implementation boundaries

- iOS bundles the pinned MIT Silero v6 Core ML model (905,043 source bytes,
  approximately 0.9 MB). Model provenance, hashes, conversion revision and
  license are in [the bundled model README](../StudyMate/Resources/SileroVAD/README.md).
  Five-second local model loading/warm-up must complete before reserving a call;
  there is no model download, RMS-classifier fallback or additional runtime.
- Native FloatS16 microphone frames remain untouched for WebRTC transmission.
  A separate bounded worker copies/normalizes one channel, continuously converts
  to 16 kHz and runs 512-sample Silero windows with recurrent state and context.
  Probability hysteresis uses an 80 ms onset and 700 ms release. Readiness,
  explicit mute, close and capture-format generations fence stale callbacks;
  tutor speech never gates capture or interrupts tutor playback.
- Acoustic speech includes hesitation. Final ASR therefore goes through the
  existing GPT configuration (default `gpt-5.4`) with the latest completed
  teacher sentence. The strict-schema result must contain exactly one decision
  for each original USER item ID. No regex, filler blacklist, minimum sentence
  length, transcript rewrite or alternate-model fallback decides meaning.
  Short acknowledgements, negations, names, numbers, questions and partial
  meaningful ideas are explicitly valid; uncertainty preserves meaningful input.
- The application service implements its inbound use case and depends on an
  outbound provider port. Process-wide admission is bounded to four requests,
  with a five-second total deadline, no waiting queue and no implicit retries.
  Configurable batch/text bounds are documented in [the backend README](../backend/README.md).
  The authenticated context supplies user identity; callers cannot select a
  different key/account through a provider call ID. Chat Completions uses
  `store: false` and the body `safety_identifier`, not the Realtime-only header.
- Assessment and transcript publication run in an independent ordered worker,
  never inside the provider's ordered receive loop. Meaningful input is
  persisted/forwarded before authorizing a reply. Non-communicative USER items
  are removed by exact ID and matching provider acknowledgement before another
  reply. Late responses, stale assessment tokens, duplicate events and closed
  sessions cannot republish input or schedule work.
- ASR, assessment, publish/delete acknowledgement and response phases have
  separate deadlines. Assessment failure is not a negative semantic verdict:
  a small retry hint keeps the call alive after safe input cleanup. An unresolved
  cleanup or terminal deadline closes pending work immediately through an
  independent failure signal, even when outbound control delivery is blocked.
- A long learner monologue first gets a checkpoint commit without stopping
  capture. Only a meaningful, persisted checkpoint permits a short tutor
  intervention. Immediate stop/tail commits wait up to 250 ms; rapid mute/unmute
  stops in that interval share a commit that settles every reserved speech slot.
  An exact matching empty-commit error settles only its own outstanding commit.
  None of these input decisions clears, cancels, truncates, mutes or resets the
  teacher's audio.

### Backend and real-model checks

- The final focused application run passed 72 tests; the infrastructure report
  contains 240 tests with zero failures and one explicitly opt-in live-model
  test skipped (239 passed). `:tutor:bootJar` also passed. These counts come from
  the current Gradle reports, not unrelated old temporary XML files in the
  reused build directory. Log: `build/voiceMeaningfulInputBackendFinal.log`.
- Coverage includes strict parsing/identity correlation, bounded admission,
  cancellation/deadlines, private diagnostics, ASR-before-commit acknowledgement,
  both teacher completion/audio-stop orders, meaningful publication barriers,
  filler deletion acknowledgement, checkpoint/tail coalescing, stale worker
  callbacks and terminal failure under outbound backpressure.
- A separate, explicitly enabled real-GPT test used the production use case,
  adapter, default model, schema and five-second deadline. Four sequential
  requests classified 24 frozen synthetic cases: all 15 meaningful and all nine
  non-communicative cases matched their expected labels. Batch durations were
  4,555 ms, 1,650 ms, 1,662 ms and 1,709 ms (9,583 ms total). No retries or
  prompt/model tuning followed the results. Log:
  `build/voiceMeaningfulInputLiveAssessment.log`; reusable opt-in test:
  `VoiceTutorInputAssessmentLiveTest`.
- The live check used the existing dev regular user-content API credential
  only in process memory. It created no app session, quota reservation,
  database record or recording, and used no real conversation/audio. It is
  skipped in ordinary tests/CI. Twenty-four synthetic cases are not an accuracy
  benchmark, and the cold request's proximity to the deadline is not evidence
  of a production latency guarantee or load/concurrency performance.

### Physical iPhone checks

- The required generic `StudyMateiOS` Debug build passed using
  `generic/platform=iOS`, the existing `build/iOSDeviceDerivedData`, and
  `CODE_SIGNING_ALLOWED=NO`. Log:
  `build/voiceMeaningfulInputGenericBuildFinal.log`.
- The signed test host built successfully. A selected run on iPhone 16 Pro,
  iOS 26.6, passed 101 tests with no failures or skips: 76 non-mutating call
  contracts and 25 offline Silero/pipeline contracts. Recording/account purge
  tests and microphone/provider probes were excluded from that run. Existing
  DerivedData was reused. Logs:
  `build/voiceMeaningfulInputDeviceTestBuildFinal.log` and
  `build/voiceMeaningfulInputDeviceTestsFinal.log`; result bundle:
  `Test-StudyMateiOS-2026.08.31_19-15-28-+0900.xcresult`.
- The first physical run exposed a genuine large-buffer conversion defect:
  the once-only input provider ignored the converter's requested frame count,
  supplying only a 4,096-frame prefix of larger 44.1/48 kHz input. The fix
  retains an offset and supplies bounded slices until the complete input is
  consumed. Assertions were strengthened, not relaxed: 100 ms inputs must
  produce exactly 1,600 samples, and 250 ms inputs at 8/32/44.1/48/96/192 kHz
  must produce exactly 4,000 samples with the same waveform as 10 ms chunks.
  On-device pull metadata confirmed complete consumption even for 48,000 input
  frames. This finding is not an attribution of the user's earlier incident.
  Attachment: `build/voiceMeaningfulInputResamplerAttachments`.
- A separately selected, explicitly enabled synthetic Korean test passed with
  no skip. The installed, non-personal system voice generated five in-memory
  clips: short affirmative, short negative, voiced hesitation, readiness and
  topic question. Each produced both acoustic start and stop with the bundled
  model. Per-prediction maxima ranged from 0.56 to 1.23 ms in this selected
  device run; these are not production tail-latency or battery measurements.
  There was no microphone, audible playback, provider, recording file or
  semantic judgement. Log: `build/voiceMeaningfulInputSyntheticDeviceProbe.log`;
  attachment: `build/voiceMeaningfulInputSyntheticAttachments`.
- The opt-in native microphone test also passed, without requesting a new
  permission. The actual production WebRTC module factory installed its capture
  delegate; readiness-disabled delivery was observed before enabling the
  acoustic gate. The real 48 kHz callback then reached five bundled Silero
  predictions, with 28 native input buffers observed. No sender, SDP, ICE
  gathering, provider, audio storage or playback was used. Log:
  `build/voiceMeaningfulInputNativeCaptureProbe.log`; attachment:
  `build/voiceMeaningfulInputNativeCaptureAttachments`.
- These tests separately exercise real microphone-to-model delivery, Korean
  acoustic detection, actual GPT classification, controller integration and the
  previously verified native receive/playout path. They do **not** constitute
  a human microphone → live ASR → backend admission → audible reply end-to-end
  pass or a guarantee that all natural Korean calls remain uninterrupted.

### Existing dev API and normal app refresh

- Implementation commit `9b1d30f0` was installed in the existing `backend`
  Compose API on `127.0.0.1:8080` at 19:20 KST. The `dev` profile and AWS
  `buddystudy/dev` secret configuration, all original environment values,
  mounts, network and resource limits were preserved. The database, Redis,
  translation and backup container IDs and start times were unchanged. No
  additional service stack, Docker image build, tunnel or routing change was
  introduced. Log: `build/voiceMeaningfulInputDevRefresh.log`.
- The new JAR SHA-256 is
  `d5a1bfbcd0c61a2dbd82f4725f481a789dfd3476d29d2c0cb78a81e8dc3717bb`.
  The previous `c33f0fe4` JAR remains available for rollback. Active sessions
  were checked before staging and immediately before the API restart; both
  counts were zero. Manual local health/readiness and the existing public dev
  health route returned HTTP 200. These checks are local verification, not
  GitHub Actions runtime health gates or a live voice-call pass.
- After testing, the normal signed iOS build succeeded. Its bundle contained
  the compiled Silero model and MIT notice, with no injected XCTest plug-in or
  test frameworks. It was installed and then launched on the physical iPhone
  with the unchanged `https://lowfidev.cloud` dev base URL. No user recordings,
  settings, app data or unrelated build directory was deleted. Logs:
  `build/voiceMeaningfulInputSignedBuildFinal.log`,
  `build/voiceMeaningfulInputDeviceInstall.log`, and
  `build/voiceMeaningfulInputDeviceLaunch.log`.

### Post-call summary inspection (read-only)

- The reported summary is a server-side operation over stored final transcripts,
  not another microphone session or a reanalysis of an audio recording. Ending
  a call settles usage and queues its separate result as `PENDING`; the existing
  five-second recovery scheduler claims work as `PROCESSING`. A separate GPT
  request receives topic, difficulty, role-tagged text and summary instructions,
  then persists summary/strengths/improvements/next steps as `COMPLETED`.
- The inspected 147-second dev call had a completed `gpt-5.4` result roughly
  8.03 seconds after call end (seven learner and ten tutor transcript items).
  No transcript, recording, credential, user identity or full session ID was
  copied into this report. An earlier 131-second call completed its result in
  approximately 10.31 seconds. These are observations, not an SLA.
- The app can incorrectly leave its pending text visible: its poll exits when
  any non-nil result exists, including the server's `PROCESSING` placeholder,
  and a cached history detail is not refreshed. The backend had already
  completed the inspected result. This turn diagnosed that UI bug but did not
  change result polling/caching, stored summaries or quota data.
- No-transcript calls receive a deterministic empty-conversation result without
  GPT. Provider/parse failure becomes `FAILED`, not an automatic retry; an
  abandoned `PROCESSING` lease can be reclaimed after 300 seconds. No new MCP
  integration, worker container or infrastructure is needed for this flow.

## 2026-08-31 — voice MCP tools and learning-summary repair

This section supersedes the outstanding polling/cache defect identified in the
read-only inspection above. It does not change the continuous WebRTC media path,
the contextual meaningful-input classifier, or the user's 60-minute allowance.

### Confirmed causes and implementation

- The existing MCP catalog exposed study tools, but the voice session registered
  no tools and had no function-call/result execution path. Its instructions
  explicitly forbade app-data creation. An enabled HTTP MCP endpoint alone
  cannot make the voice LLM aware of those capabilities.
- The voice backend now registers seven existing MCP definitions, verifies
  their schemas and tool-choice setting in the provider's session-update ACK,
  and invokes those same permission-checked handlers through a local bridge.
  The model receives neither the app bearer nor an MCP access token. Writes
  are restricted to explicitly requested child topics in the current study's
  subtree; root creation, deletion, question generation and call control remain
  unavailable as voice tools.
- `get_study` is a single-node read, not a child list. The existing
  `list_studies` tool now supports an owner-checked `parent_study_id` filter,
  with matching count/page predicates and exact offsets. Omitting it preserves
  the original catalog behavior. Creation still uses the existing study use
  case and does not consume question quota.
- A bounded, serial worker executes only correlated, completed function calls.
  The exact output item/call acknowledgement releases a follow-up response.
  Proven function-only responses do not wait for an audio-stop event that will
  never arrive; mixed speech/tool responses still wait for playback completion.
  Input classification, accepted-transcript persistence and filler-deletion
  acknowledgement remain independent gates. No cancel, buffer-clear, truncate,
  microphone reset or RTP restart was added.
- Each call allows at most eight tools per response, six tool rounds per learner
  turn and 256 tool-call IDs per session. Arguments/results are valid JSON
  bounded to 16 KiB; execution and output ACK have separate 15-second deadlines.
  Duplicate IDs cannot repeat writes, and an uncertain timed-out mutation is
  not automatically replayed or represented as confirmed success.
- Successful child creation publishes only its numeric study ID to the app.
  An identity/attempt-fenced read refreshes that study's metadata through the
  existing store, preserving pending questions and answer drafts. Private tool
  arguments/results are not forwarded to the UI or diagnostic logs.
- Read-only incident inspection found a normal `CLIENT_END` followed by a late
  provider close. The transport error was copied into a failed result about
  7.7 ms after call end, with no model recorded: this was not evidence that GPT
  summary generation failed. The first terminal reason/error now settles
  atomically, and a late close cannot overwrite a completed call or summary.
- A failed transport with usable persisted transcript can still be summarized.
  The existing scheduler also recovers only the narrow legacy signature where
  the result error is the copied call error and no model was used. Actual model
  failures remain terminal; cancellation or persistence failure retains the
  existing recoverable processing lease. Completed results and settled usage
  are preserved. No manual transcript, summary or quota update is used.
- Summary generation remains a server-side, bounded GPT request over saved
  final transcript text, not an audio upload. Response finish reason, refusal,
  size and JSON shape are validated, with fixed safe failure diagnostics.
  Detail responses project status and content from the same result-row read,
  avoiding stale top-level `FAILED` combined with newly completed content.
- The app now distinguishes pending/processing, completed, empty and failed
  results. Both call and history screens use at most eight identity-fenced
  detail reads with bounded delays. Reopening history refreshes cached data;
  the compact recheck action reads the existing result and does not start a new
  generation request. A processing placeholder is not treated as completion.

### Actual provider protocol check

- A bounded synthetic check used the existing dev regular API credential only
  in process memory and one Realtime WebSocket session per attempt. It supplied
  synthetic study IDs and mocked tool results, with no real MCP business write,
  microphone, app session, recording file or quota reservation.
- The first attempt exposed a real Realtime contract violation: the generated
  function-result item ID was 38 characters, and the provider rejected it with
  `string_above_max_length`. Production and probe IDs were corrected to the
  32-character maximum, and a regression assertion preserves that bound.
- The subsequent bounded attempt passed: one provider session, four responses,
  two actual model-selected tool calls (child lookup and child creation), two
  spoken results, 571,200 generated audio bytes, verified session schemas and
  matching `conversation.item.added` acknowledgements. No output transcript or
  audio was saved. Local diagnostic: `build/probeVoiceMcpProtocol.py`.
- This proves actual model tool selection, output acknowledgement and subsequent
  audio generation for those synthetic exchanges. It is not a physical iPhone
  microphone → live ASR → production MCP write → audible speaker end-to-end
  pass. Real ownership/persistence integration is covered separately by mock
  and in-memory repository tests, not asserted from the synthetic provider run.

### Physical iPhone verification

- The generic iOS Debug build passed using `StudyMateiOS`,
  `generic/platform=iOS`, `CODE_SIGNING_ALLOWED=NO` and the existing
  `build/iOSDeviceDerivedData`. Log: `build/voiceSummaryMcpGenericBuild.log`.
- A signed build-for-testing and explicit selection on the iPhone 16 Pro,
  iOS 26.6, passed 136 tests with zero failures or skips: 76 existing call
  contracts, four new MCP event contracts, 25 offline Silero/pipeline contracts,
  and 31 summary/metadata-refresh tests. The latter include bounded polling,
  stale identity/attempt completion, processing placeholders, final-attempt
  completion, read failure and preservation of active drafts/questions.
  Logs: `build/voiceSummaryMcpDeviceTestBuild.log`,
  `build/voiceSummaryMcpDeviceTests.log`; result bundle:
  `Test-StudyMateiOS-2026.08.31_20-12-06-+0900.xcresult`.
- No microphone, provider request, audio purge, account mutation or recording
  test ran in that selection. Afterward a normal signed build and strict
  signature verification passed. The normal app contained the Silero model and
  MIT notice, with no injected XCTest plug-in/framework. It was installed and
  launched with the unchanged `https://lowfidev.cloud` dev base URL; active call
  count was zero before install, before launch and after launch. Logs:
  `build/voiceSummaryMcpSignedBuildFinal.log`,
  `build/voiceSummaryMcpDeviceInstall.log`,
  `build/voiceSummaryMcpDeviceLaunch.log`.

### Backend verification and test-environment findings

- The first focused run exposed a runtime dependency conflict: Spring AI 2.x
  selected NetworkNT's Jackson 3 ABI, while the MCP SDK selected its Jackson 2
  validator. Nineteen schema-dependent tests failed with `NoSuchMethodError`.
  Both the HTTP server and voice bridge now explicitly use the SDK's Jackson 3
  validator with JSON-compatible map boundaries. Input, output and meta-schema
  checks remain enabled; validator errors are redacted before SDK logging.
  HTTP MCP fixtures subsequently passed all eight tests, and the existing
  OpenAI/Spring AI routing plus structured-output advisor tests passed.
- A preliminary `tutor:test` invocation revealed that Graal's `processTestAot`
  initializes all Spring test contexts before JUnit's `--tests` selection. It
  briefly started an isolated MySQL Testcontainer and its Ryuk helper and
  attempted the optional default-dev secret import. The build was stopped
  before test methods ran. Testcontainers removed both temporary containers;
  Docker metadata confirmed their destruction and unchanged existing database
  and Redis container IDs/start times. No application voice/model test or
  production deployment occurred in that invocation.
- The final focused run explicitly excludes the five test-AOT tasks, with its
  task graph checked first. Pure HTTP/permission fixtures and H2 repository
  tests require no new infrastructure. Newly added MySQL summary persistence
  integration cases compile but are not claimed as executed in this turn.
- The final 37-suite regression report contains 479 unique tests: 478 passed,
  zero failed and one explicitly opt-in paid classifier test skipped.
  Application: 105 passed; infrastructure: 363 passed plus one skipped;
  tutor: ten passed. This includes H2/loopback verification, not only unit
  tests. No provider request or real app-data write occurs in that selected
  regression run. Log: `build/voiceMcpSummaryFinalRegression.log`.
- Coverage includes 13 function-relay tests, two actual input-coordinator/tool
  ordering tests, 33 session-tool configuration cases, 23 MCP voice bridge
  tests, five direct-child repository pagination cases, explicit SDK schema
  validation and Spring AI compatibility, HTTP MCP/permission boundaries,
  first-terminal ordering, summary recovery policy and 35 voice service cases
  including the three detail-snapshot regressions.
- The normal main `processAot` and `bootJar` then passed with the existing
  explicit `aot` profile and build-only configuration; only test AOT was
  excluded. This main build created no Testcontainer and imported no dev
  secret. Log: `build/voiceMcpSummaryFinalMainAotJar.log`. The final artifact
  is 330,667,522 bytes with SHA-256
  `8c73d7b8fc80ef0a5431add049b305458fde9213b4a266c1b5175ddf18379b7a`.
