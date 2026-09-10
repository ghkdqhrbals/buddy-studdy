# Voice progress and choice history

## Incident evidence

The affected development conversation accepted and persisted the learner's first
“그럼 이걸로 시작해보자.” at 20:51:10 UTC on September 10. The second start
utterance arrived at 20:53:09. This was not a missing transcript or a required
second confirmation. Historical native response events are not retained, and
the previous generic failed-response path omitted failure diagnostics, so the
exact provider failure for that first turn cannot be established retroactively.

The actual question request began at 20:53:22 UTC. Optional coverage planning
requested an unlimited recursive concept tree. Its first OpenAI request failed
after 120,561 ms with `OpenAIIoException`; that failure retried the entire
generation Saga after the Inbox recovery delay. Coverage retry took another
54,026 ms. The actual question request then took 12,728 ms. The saved question
completed at 20:58:11, 4 minutes 49 seconds after acceptance and after the call
had ended. It was already saved; investigation did not regenerate it or change
the learner's quota or records.

## Changes

- Choice cards use an optional `user_input.request.operationId` and the same
  causal transcript layout as MCP operations. Cancellation and submission never
  move a card to the newest message. Delayed operation/caption events resolve the
  original anchor. Legacy requests capture their placement when received.
- Submitted multiple selections and custom text use the exact acknowledged
  submission snapshot. Cancelled inputs remain visibly unsubmitted drafts;
  empty cancelled cards do not imply that a selection was made.
- An output-buffer clear no longer prematurely asks the learner to repeat before
  the existing bounded automatic response recovery finishes. Generic failed,
  cancelled, incomplete and cleared responses now produce safe diagnostics.
  Completed tool writes are not replayed. The server-only
  `buddystudy.voice.response.recovering` carries an exact interrupted response ID
  and its original client speech sequence, preserving partial text while
  restoring only that turn's response wait. A silent retry can settle it and an
  exhausted retry can request repetition without leaving the old speaker active.
  The iOS retry hint can recover only from the exact replacement audio response
  in the same acoustic epoch.
- Accepted question/grade work has one operation lifecycle. The existing native
  WebSocket carries state updates; there is no repeated model-driven polling or
  additional SSE connection. Redis Pub/Sub sends only the correlation ID after
  commit. Receiver-before-subscribe, subscription/reconnect ACKs and a 30-second
  recovery read close notification gaps. Every hint reauthorizes and rereads the
  canonical saved process; Redis is not the source of truth.
- Generation completion binds the exact saved question and initiates readback
  only while the original learner and lesson boundary remain current. New
  speech, cancellation or focus changes cannot start old question audio. A
  bounded observer timeout leaves the accepted request intact for later recovery
  and returns the presentation to conversation with a delay notice, instead of
  claiming a durable failure or generating again.
- Optional coverage planning has a compact prompt, a 2,000-token completion cap,
  a provider timeout of at most 20 seconds and no SDK retries. Failure uses the
  existing topic/angle guide. The actual question and rubric still use normal
  generation, validation and failure handling. Saved study-tree creation and
  topic recommendations are unaffected.

## Verification

- Generic iOS build passed: `build/voice-progress-generic-ios.log`.
- Simulator regression: 251 passed, 5 skipped, 0 failed;
  `build/VoiceProgressSimulator-20260911.xcresult`.
- Physical iPhone 16 Pro, iOS 26.6.1 regression: 235 passed, 16 skipped, 0 failed;
  `build/VoiceProgressDevice-20260911.xcresult`.
- Skips are the existing opt-in interaction/capture checks and the physical
  hosted-XCTest SwiftUI accessibility limitation. Production native multiline
  editing and card rendering run on the actual iPhone without creating a voice
  session or consuming question quota.
- These are deterministic state, transport and rendered-view checks. They do
  not claim a new live OpenAI conversation or an end-to-end spoken generation
  latency measurement.

- Full production conversation rendering passed on simulator and iPhone in both
  light and dark appearance. Simulator accessibility positions/reading order
  and retained identity checks place each cancelled/submitted card beneath its
  own source conversation and above the next conversation. Four physical and
  four simulator captures were visually inspected;
  `build/VoiceProgressLayoutSimulator-20260911.xcresult` and
  `build/VoiceProgressLayoutDevice-20260911.xcresult`, exported in
  `build/voice-progress-layout-{device,simulator}-ui/`.
- The second device run's unrelated native-editor check failed when the selected
  text `직접` became `ㅁ.` and the cursor collapsed. The device log contains
  direct touches in UIRemoteKeyboardWindow at the same time; the fixture does not
  generate those touches or replacement text. This is consistent with external
  keyboard/IME interference, without identifying its source. No assertion or
  input code was weakened. An isolated repeat passed (1 test, no skips/failures):
  `build/VoiceProgressInputDeviceRepeat-20260911.xcresult`. The broad first run
  had also passed this exact native-editing check.

## Response-recovery verification

- The final scoped backend run passed after the nullable speech-sequence fallback
  and duplicate recovery-event guard were saved:
  `build/voice-event-progress-recovery-final-tests.log`.
  Its retained reports contain 70 application and 274 infrastructure tests,
  with no failures, errors or skips. The earlier broader focused run also passed
  Redis signal delivery, after-commit persistence, canonical question recovery
  and optional coverage-budget checks:
  `build/voice-event-progress-final-tests.log`.
- The exact recovery wire passed the generic iOS build and simulator regression
  (245 passed, 4 opt-in skips, 0 failed):
  `build/voice-progress-final-generic-ios.log` and
  `build/VoiceProgressRecoverySimulator-20260911.xcresult`.
  The planned matching physical run at 06:27 KST was not started because a live
  development conversation existed; the gate preserved that conversation.

## Native echo-processing path

The previous automatic mode preferred Apple's platform AEC. Its active-state
probe demonstrated a running processing path, not adequate acoustic echo
attenuation. Recurring reported echo motivated selecting WebRTC software AEC3
for the native audio engine instead. Platform processing is disabled before
the peer/media engine is created, and AEC/NS move together to avoid stacking
the platform and software echo cancellers. The local microphone remains
available during tutor playback and user double-talk.

Capture readiness now requires a running engine, recording, active software AEC
and an inactive platform/VPIO path. Tests cover pre-SDP configuration with a
muted local track and verify that the module uses AEC3 rather than legacy AECM.
These configuration and native capture checks do not measure speaker echo
reduction during a connected live conversation.

- Updated generic iOS build passed:
  `build/voice-curriculum-aec3-generic-ios.log`.
- Updated simulator regression: 246 passed, 4 opt-in skips, no failures;
  `build/VoiceCurriculumAEC3Simulator-20260911.xcresult`.
- Updated iPhone regression: 234 passed, 16 opt-in/hosted-accessibility skips,
  no failures; `build/VoiceCurriculumAEC3Device-20260911.xcresult`.
- The explicit native microphone + Silero probe passed on the iPhone, without
  skips: `build/VoiceAEC3NativeCaptureDevice-20260911.xcresult`. It verified real
  processed frames with active AEC3, platform processing off and microphone
  unmuted. No live call or question was created. Fresh zero-active-session gates
  preceded both device runs after the earlier live conversation had ended.

## Selected-topic curriculum

Saved `curriculumTerminal` distinguishes an unexpanded topic from a deliberately
terminal learning unit. The curriculum resolves the original root separately
from the selected node. Missing children are prepared for that selected node,
with new-node difficulty inherited from the root. Existing children retain
their saved levels. Depth-four and legacy deeper nodes proceed to questions;
an ordinary empty child list does not bypass curriculum setup. Explicit manual
refinement below depth four can turn a terminal node back into a branch.

The native tool result can hold a server-owned curriculum form even when the
model did not call `request_user_input`. iOS binds that card to the exact
select/question operation ID as it does for ordinary input requests. The final
anchor change passed the generic build and all 15 user-input tests on both
simulator and physical iPhone (no skips/failures):
`build/voice-curriculum-final-generic-ios.log`,
`build/VoiceCurriculumFinalSimulator-20260911.xcresult`, and
`build/VoiceCurriculumFinalDevice-20260911.xcresult`.

Backend integrated verification passed with 134 application tests and 479
infrastructure tests in the ordinary retained JUnit result files, with no
failures/errors/skips: `build/voice-curriculum-integrated-final-tests.log`.
After the final typed-input ACK budget reset and discovery metadata projection,
the changed paths were rechecked: 13 application and 345 infrastructure tests,
all passing, followed by successful `:tutor:bootJar`:
`build/voice-curriculum-final-verification-and-jar.log`.
The actual repository/after-commit notification class was also run independently
(4 passed, no failures/errors/skips):
`build/voice-progress-persistence-final-tests.log`. Its Gradle XML uses an
`__TEST-...` temporary-style filename, so a `TEST-*.xml` glob alone misses it;
the class report and XML both confirm all four executions.

The initial incremental compile could not resolve existing unchanged extension
functions, and the following default-heap compilation exhausted memory. Final
runs used `--max-workers=1 --no-parallel`, `-Dorg.gradle.jvmargs=-Xmx3g
-XX:MaxMetaspaceSize=768m`, `-Pkotlin.compiler.execution.strategy=in-process`
and `-Pkotlin.incremental=false`. Two old prompt-text assertions were updated
to check the new mandatory curriculum flow; no behavior assertion was disabled.

## Development rollout

At approximately 07:03–07:05 KST on September 11, the existing development JAR
volume was updated using the already-installed Docker image; no image was
built and no production server was accessed. Every restart/install/launch was
preceded by a fresh zero-active-or-finalizing-conversation gate.

- New JAR SHA-256:
  `5f6638df1ce7dab6716d31f97052b579a844f045ad69d02443b9fc56dc8af132`.
- Previous JAR retained as
  `/app/buddystudy-backend.pre-progress-curriculum-20260911.jar`, SHA-256
  `dc038077df143df709b76312dd7e7f205a60807eac04d5dd67de1ee643263eb8`.
- This dev container reads `FLYWAY_LOCATIONS=filesystem:/app/db/migration-mysql`.
  The first JAR-only restart therefore left the schema at V118. V119 was then
  copied into that existing migration directory and the idle container restarted.
  MySQL `flyway_schema_history` confirms V119 success and both new columns with
  default 0. The additive migration preserves old rows and records.
- Both `http://localhost:8080/actuator/health` and
  `https://lowfidev.cloud/actuator/health` returned `status: UP` after migration.
- The signed iOS build was installed on the iPhone and launched with
  `BUDDYSTUDY_BACKEND_BASE_URL=https://lowfidev.cloud`:
  `build/voice-curriculum-device-install.log` and
  `build/voice-curriculum-device-launch.log`.

This rollout confirms startup, migration, native-device behavior and deterministic
conversation contracts. It does not claim a new live spoken curriculum session
or measured acoustic echo attenuation during simultaneous speaker playback.
