# Voice Tutor interruption verification

## Scope

The active `realtime-native-v1` iOS/WebRTC path supports natural learner
interruption of an ordinary tutor response. A speech-start edge immediately
silences local tutor output while microphone capture and the peer connection
remain active. The backend cancels generation, clears queued provider audio and
waits for the interruption acknowledgements before replying to the latest
stopped, committed learner turn. Interrupted responses cannot resume or retry,
execute pending tools, or count as completed tutor transcript evidence.

The existing numbered speech protocol is retained. The additive
`buddystudy.voice.response.interrupted` control event carries the exact
`responseId` so delayed events cannot revive an interrupted response. Explicit
pause, terminal quota notices, call settlement and foreground lifecycle retain
their existing contracts. The legacy PCM fallback is outside this fix.

## Initial verification at `22adb284`

- Backend focused interruption and regression tests: **199 passed, 0 failed,
  0 skipped**. Suites: native controller (30), native session relay (15), event
  policy (23), control handler (19), MCP relay (24), meaningful-input relay (88).
  Report: `backend/infra/build/reports/tests/test/index.html`; XML:
  `backend/infra/build/test-results/test/`. Tests cover cancellation before
  creation/audio, generated-but-buffered output, clear/done ordering, stale
  input acknowledgements, interrupted tools and continuations, and timeout.
  The initial default-heap compiler run exhausted memory; the final successful
  run used Java 25, a 3 GiB Gradle heap, in-process Kotlin compilation and two
  workers. These results cover the initial interruption implementation before
  the later `feature/2.0` integration below.
- iOS selected `VoiceTutorContractTests`, `VoiceTutorPauseTests` and
  `VoiceTutorNativeConversationTests`: **168 passed, 0 failed, 2 opt-in native
  probes skipped** on the iOS 26 iPhone simulator. Artifact:
  `build/voice-interruption-ios-tests.xcresult`; log:
  `build/voice-interruption-ios-tests.log`. Coverage includes generation already
  completed with buffered audio, ongoing render callbacks, repeated corrections,
  interruption before response creation, stale completion and captions, fresh
  response resumption, and existing pause/terminal playout boundaries.
- Required unsigned `StudyMateiOS` Debug build for `generic/platform=iOS`:
  **passed on the initial interruption commit**, with `CODE_SIGNING_ALLOWED=NO` and
  `build/iOSDeviceDerivedData`. Log:
  `build/voice-interruption-ios-generic-final.log`. Existing resolved package
  checkouts were reused with `-clonedSourcePackagesDirPath` and
  `-disableAutomaticPackageResolution`.
- Real-device test run: **attempted, unavailable**. Min iPhone was initially
  connected, then disappeared before Xcode resolved destination
  `00008140-00120C8E2E60801C`. `devicectl` subsequently reported it unavailable.
  Log: `build/voice-interruption-iphone-tests.log`. This run did not install or
  launch the changed app on the physical iPhone.
- Live iPhone microphone-to-speaker check: **not measured**. Exercise interruption
  during generation and buffered playback, consecutive corrections, and the
  next fresh response. Confirm the microphone stays live, the old answer does
  not replay, and pause/end-call behavior remains usable.

## `feature/2.0` merge verification

The integration starts at `c1ff740b` and merges `22adb284`, preserving the newer
1.28-second local quiet hold, 400 ms server quiet gate, manual answer review and
submission, exact input-settled sequence handling, saved call language, session
progress, canonical-question reuse, read recovery and processed-input noise
configuration. Spoken tutor output can now be interrupted while those newer
input and draft rules stay in effect.

- Merged backend focused suites: **371 passed, 0 failed, 0 skipped**. Coverage:
  canonical-question coordinator (35), native controller (98), native relay (22),
  WebRTC handshake (49), session-state publisher (2), event policy (32), control
  handler (21), MCP relay (24), meaningful-input relay (88). The final run used
  the same Java 25 / 3 GiB heap / in-process Kotlin / two-worker settings as the
  initial command below, with the additional canonical-question, handshake and
  session-state suites. No production source changed after the passing run.
  Report: `backend/infra/build/reports/tests/test/index.html`;
  log: `/tmp/buddystudy-backend-merge-tests-final.log`.
  The first integrated run exposed two stale expectations from the old
  no-interruption and pre-session-state contracts; those assertions were updated
  and the final suite also verifies delayed ASR cannot restore a superseded
  question-readback authorization.
- Merged iOS focused suites on the physical Min iPhone: **231 passed,
  0 failed, 14 skipped** (245 selected tests). Suites include Contract, Pause,
  NativeConversation, AnswerDraft, SessionState and Silero. Skips are opt-in
  native/computer-use probes and source-contract checks requiring repository
  access unavailable on the phone. This signed test run installed and launched
  the merged app on the iPhone. Artifact:
  `build/voice-interruption-merge-iphone-tests.xcresult`; log:
  `build/voice-interruption-merge-iphone-tests.log`.
- Merged unsigned `StudyMateiOS` Debug build for `generic/platform=iOS`:
  **passed**, using `CODE_SIGNING_ALLOWED=NO` and the existing package cache.
  Log: `build/voice-interruption-merge-ios-generic.log`.
- Native microphone-to-speaker interruption latency remains unmeasured; ordinary
  unit/contract tests do not establish physical acoustic timing.

## Rollout

This merge targets `feature/2.0` only. Ordinary branch pushes do not trigger the
repository's tag/manual backend image or iOS release workflows. No backend,
monitoring or App Store release is dispatched by this merge/push operation.
No macOS target was built or tested; the legacy PCM path was not changed.

## Provider contract

Generation cancellation and buffered playback clearing are distinct operations.
A cancellation that produces no audio completes without sending an empty-buffer
clear; when audio exists, every issued clear must be acknowledged before a fresh
response. The interruption handshake has a five-second failure bound. This uses
OpenAI's documented [Realtime client events](https://developers.openai.com/api/reference/resources/realtime/client-events)
and [WebRTC interruption flow](https://developers.openai.com/api/docs/guides/realtime-conversations#webRTC-and-sip).
The deterministic tests do not measure native speaker latency or verify a live
provider call.

## Backend verification command

From `backend/`, with Java 25 selected:

```sh
./gradlew :infra:test \
  --tests '*VoiceTutorNativeConversationControllerTest' \
  --tests '*VoiceTutorNativeSessionRelayTest' \
  --tests '*VoiceTutorRealtimeEventPolicyTest' \
  --tests '*VoiceTutorMcpRelayTest' \
  --tests '*VoiceTutorMeaningfulInputRelayTest' \
  --tests '*VoiceTutorControlWebSocketHandlerTest' \
  --console=plain '-Dorg.gradle.jvmargs=-Xmx3g' \
  -Pkotlin.compiler.execution.strategy=in-process --max-workers=2
```
