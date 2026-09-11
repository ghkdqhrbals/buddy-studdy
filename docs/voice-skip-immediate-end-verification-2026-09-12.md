# Voice question skip and immediate termination

## Incident evidence

The development backend and actual iPhone logs agree on this sequence on
2026-09-11 UTC. Session and provider identifiers are omitted here; private
incident artifacts stay in ignored `build/` files.

| Time | Observed event |
| --- | --- |
| 20:25:21 | `request_question` returned an existing ungraded saved question. |
| 20:25:22–20:25:42 | Its dedicated question audio played; device drain completed one second later. |
| 20:26:06 | The app sent the exact answer skip control and `skip_question` succeeded. |
| 20:26:12 | The pending-question lookup returned no next question. |
| 20:26:18–20:26:21 | The next request repeatedly failed with `INPUT_PERSISTENCE_PENDING`. No new generation was accepted. |
| 20:27:07 | The user ended the call. Server cleanup completed in approximately 250 ms. |
| 20:27:10–20:27:11 | iOS requested REST settlement, then finally closed local media. |

The observed preparation failure was for the question after the skip. The first
question did arrive and play; this incident does not support a separate initial
question-generation failure. The skip continuation reused the older microphone
turn's persistence gate even though the learner had just authorized continuation
through an authenticated app control. Repeating that gate could not create the
missing newer speech. The resulting tool error left the lesson in question-failed
state.

## Changes

A successful app skip now carries internal answer/study/skipped-record identity
into one server-owned next-question request. It checks for a ready question first
and otherwise starts normal generation with an idempotency key derived from the
successful skip action. Skip persistence and question generation remain separate
operations: skipping costs no question allowance; newly generated questions use
the ordinary allowance. The original microphone-source checks still apply to
model-requested operations. Provider arguments cannot manufacture the internal
authorization. New speech, learning cancellation and focus/revision changes
invalidate a continuation that has not started.

Explicit termination closes local microphone and speaker playback synchronously
and dismisses the conversation. A retained asynchronous task flushes outstanding
work, finalizes consented recordings and settles the captured server session.
A three-second socket deadline closes the captured old transport before REST fallback, including a stalled audio flush or end send. A short iOS background execution lease protects cleanup, releases promptly at expiration, and closes the old control socket. Local recording finalization starts concurrently with network settlement so a slow request does not postpone creation of the protected retry manifest. Repeated end/dismiss events share the same finalization. Local media closes once
per attempt, and delayed microphone permission completion cannot reactivate an
abandoned call. The old call's historical quota is not applied over a new call's
reservation. Existing draft and conversation-history storage remains in use.

The canonical question source/content validation and answer-readiness audio gates
are unchanged. This patch does not claim to solve exact word-boundary interruption.

## Verification

| Check | Result |
| --- | --- |
| Backend question coordinator, native controller and relay | 327 passed; 0 failures/errors/skips. |
| Executable backend JAR | `:tutor:bootJar` passed. SHA-256 `cc7a54ed5cedfa41a6c967080d3aa2f12bfd02dbfd48abf1d40b48cef5de97a3`. |
| Development runtime | Applied with zero active calls. Local health returned UP. Only the backend container was restarted; its JAR hash matches above. |
| Final iOS simulator regression | 287 cases: 283 passed, 4 opt-in skips, 0 failures. Includes answer/source state, draft preservation, duplicate dismissal, retained-owner lifetime, socket deadline and background-lease behavior. |
| Generic iOS build and signed build-for-testing | Both passed with StudyMateiOS and iOS destinations only, after the final code changes. |
| Actual iPhone regression | 49 passed, 0 failures/skips on iPhone 16 Pro, iOS 26.6.1. Includes six finalization behavior tests, question/answer state, and the four light/dark orb/transcript native renders. |
| iPhone application | Updated test host installed with a deliberately unreachable backend override. Afterwards the app was relaunched normally with zero active calls and the override removed. |

Backend artifacts: `build/voice-skip-continuation-tests-retry.log` and
`build/voice-skip-backend-jar.log`. The first test attempt exhausted the default
Kotlin compiler heap before tests ran; the in-process compiler with a 3 GiB heap
completed successfully without repository build-setting changes. The previous
JAR remains at `/app/buddystudy-backend.pre-skip-continuation-20260912.jar`.

No paid provider audio conversation, production deployment, Docker image build
or model download is part of this regression run. The iOS execution lease is
bounded by the operating system; it is not a guarantee against force termination
before a local recording manifest exists.


Final iOS artifacts: `build/voice-skip-immediate-end-verified-simulator.xcresult`,
`build/voice-skip-immediate-end-device.xcresult`,
`build/voice-skip-immediate-end-generic-final.log`, and
`build/voice-skip-immediate-end-signed.log`. Test counts above come from
`xcresulttool` summaries, not the interleaved console log. The real-device orb
renders were visually inspected for readable question content and light/dark
answer contrast. These are isolated state/render and cleanup-orchestration tests;
they do not establish a new paid live-provider question-generation/audio roundtrip.

The retained background work covers normal dismissal and also an explicit
dismissal while server-driven finalization is already in progress. The latter
attaches only an execution lease and never starts a second settlement. The
cancelled startup task cannot take the recorder away from that retained cleanup.
The deadline task is cancelled and joined before the same view model can retry,
so it cannot disconnect a subsequent socket after old cleanup has completed.
