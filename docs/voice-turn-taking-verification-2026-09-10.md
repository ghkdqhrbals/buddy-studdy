# Preserve the learner's voice turn

## Evidence and behavior

The learner reported that the tutor began replying while they were still
speaking. The previous detector released a speech turn after 480 ms of quiet,
and the native server could dispatch immediately after the input commit ACK.
That configuration allowed a short thinking pause to start a tutor response.
The incident's lifecycle logs show an established manual-turn connection and
an explicit user end, but do not contain acoustic-edge/response timing; they
do not independently reproduce the reported overlap.

The local Silero hold is now 1,280 ms (40 continuous 32 ms windows). Speech
returning in the continuation band resets the whole hold. The backend then
requires 400 ms of quiet before a normal reply, including opening, tool
continuation and error retry. A fresh learner start resets this gate. The
combined configured wait is approximately 1.68 seconds after acoustic speech
ends, plus detection, transport, scheduler and generation latency.

If a response was requested but has neither started audio nor been announced
to the app, renewed speech supersedes it. Cancellation is scoped to the exact
provider response ID, including creation still in flight. Its late text and
tools are discarded, and the newest input owns the next reply. This does not
consume the ordinary error retry budget or clear a newer client input wait.
An opening displaced by learner speech yields to their current input.
On iOS, an exact matching positive silent-input settlement also consumes the
unannounced opening expectation. A stale sequence cannot clear newer input,
and a late ready event cannot revive that settled opening wait.

Already announced/playing output is preserved. No learner-triggered
`output_audio_buffer.clear` is introduced. OpenAI documents
[`response.cancel`](https://developers.openai.com/api/reference/resources/realtime/client-events#response.cancel)
as cancelling generation with an optional exact response ID; playback clearing
is a separate operation. Direct RTP and sideband timing remain separate, so
this conservative pre-audio fence is not proof that media can never race ahead.

The existing realtime model is instructed to stay silent for an unfinished
thought, word search or breath, and still accept clearly complete short
answers. No extra semantic classifier, transcript word filter, minimum answer
length or new user control is introduced. Long pauses can still exceed the
window and model interpretation is not deterministic.

## iOS verification

- Unsigned generic iOS `StudyMateiOS` build passed:
  `build/voice-turn-taking/ios-final-generic.log`.
- The final selected simulator suites executed 209 tests: 205 passed, four opt-in
  tests skipped, zero failures:
  `build/voice-turn-taking/ios-final-simulator.xcresult`.
- The connected iPhone executed the same 209 tests: 195 passed, 14 skipped,
  zero failures. The skips are ten repository-source inspections unavailable
  on the physical device and the four opt-in tests:
  `build/voice-turn-taking/ios-final-device.xcresult`.
- The Korean synthetic-speech probe was then enabled separately on the
  simulator and passed without skips:
  `build/voice-turn-taking/silero-korean-synthetic.xcresult`. It runs the actual
  bundled Core ML model and continuous resampler on five non-personal system
  utterances, including a hesitation and short affirmative/negative answers.
  It does not open a microphone, play or record speech, or contact a provider.
- Tests cover the 40-window release boundary, repeated 480/768/1,024/1,248 ms
  pauses, the lower-probability continuation reset, paired sequences, invalid
  samples, and preservation of ongoing tutor output. An independent code
  review found no actionable client detector defect.
- The final iOS run includes the opening-supersession wait regression: stale
  and unknown settlements leave the newest input intact, its exact settlement
  clears both waits without inventing audio, and delayed readiness stays settled.

This run does not measure a real microphone-to-tutor conversation or certify
that every natural pause is understood correctly. The previous orb UI and its
tap, swipe and staged hold interaction remain unchanged.

## Backend verification and local rollout

- Final backend checks passed: native controller 44, native relay 16, event
  policy 25, application `VoiceTutorServiceTest` 64; total 149 with no failures,
  errors or skips. The relay regression checks real asynchronous clock release
  of the quiet gate after a superseded response, without another inbound event.
  `:tutor:bootJar` also passed. Log:
  `build/voice-turn-taking-backend-final.log`.
- Boundary regressions cover 399/400 ms scheduling, renewed learner speech,
  opening displacement, tool continuations, exact late creation and rejection,
  preservation of announced output, ignored stale text/tools, retry allowance,
  pause/resume and quota. A cancelled response containing only partial audio
  metadata does not wait for a playback buffer that never started. An observed
  late audio start or nonempty audio frame still requires its stop boundary.
- Independent read-only review found no actionable defect in the final native
  controller and matching iOS settlement changes.
- Built JAR SHA-256:
  `4ee80546f981bab26a3a9a9cc33041c5be5014293015d899f8a8d387dae8fa4f`.
  It replaced the existing local Docker volume's JAR without building an image.
  The previous JAR was preserved as
  `/app/buddystudy-backend.pre-turn-taking-20260910.jar`, SHA-256
  `ccdac1ffa392b7a9f677dc753451edab89861b17e19d352d6e7ad02af5bc54f4`.
  The session table reported zero READY/ACTIVE/ENDING calls before restart.

- The local API restarted at 03:23:07 UTC and completed startup at 03:23:14 UTC.
  The first check during startup received an empty reply; after startup, both
  the localhost dependency endpoint and the existing iPhone development route
  `https://lowfidev.cloud/api/v1/health/dependencies` reported `ok: true` for
  database and Redis at 03:23:39 UTC. The running container's JAR hash matched
  the built artifact. These are manual local-development checks; no GitHub
  runtime gate or production rollout was added.
- The final signed iOS test run installed the updated app on the connected
  iPhone. `devicectl` successfully launched `io.github.ghkdqhrbals.StudyMate`
  against the existing development route at 12:23:45 KST (03:23:45 UTC).
