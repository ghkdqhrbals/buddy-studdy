# Voice question readback: duplicate delivery incident

## Observed incident

The learner heard the saved Saga question twice. The first delivery did not
open the manual answer state. Local provider usage records show two successful
audio responses on 2026-09-11 UTC:

| Time | Response purpose | Output audio tokens |
| --- | --- | ---: |
| 19:12:26.293 | Ordinary response | 437 |
| 19:12:49.389 | Saved-question readback | 390 |

The corresponding private transcript contains separate tutor entries for the
same question. This was actual duplicate generation, not just a display issue.
The ordinary response has no canonical answer-capture identity; only the second,
server-owned readback can open manual answering.

The source paths also exposed two related problems:

- Native tool output instructed the conversational model to read the saved
  question even when a separate typed readback was queued behind newer speech.
- Full-transcript validation rejected a short readback introduction before an
  otherwise complete saved question and could retry the entire utterance.
- iOS rendered the spoken question in a caption and the canonical question again
  in the answer card. Those are distinct copies of one source, not new questions.

## Changes

Native pending-question results now expose saved identity and status instead of
the question body. Only typed server readback metadata carries the source for
speech. If newer learner speech must be handled first, its ordinary response is
explicitly constrained not to read or explain the queued question. The real
question response remains in conversation history for subsequent discussion.

Content validation accepts one enumerated short delivery introduction before a
complete question. Missing requirements, changed numbers, added hints and repeated
introductions still fail. The controller publishes one answer-ready receipt
after the first successful readback drains.

The additive `buddystudy.voice.answer.question_source` receipt binds that answer
to the exact verified response and up to 32 provider item IDs. It precedes the
unchanged answer-ready envelope, preserving older app compatibility. iOS renders
the canonical question at that caption once and keeps the question available on
the orb. Original captions and MCP anchors remain intact. Multipart transcript
completion replaces only its own item, preserving earlier/later items in the
bounded response draft and committed or interrupted caption.

## Verification

| Check | Result |
| --- | --- |
| Backend focused voice suites | 1,207 cases: 1,201 passed, 6 opt-in skips, 0 remaining failures. The initial run found one outdated assertion requiring no ordinary-response instructions; after replacing it with explicit ordinary-purpose/no-question checks, the complete 244-case controller suite passed. |
| Backend executable JAR | `:tutor:bootJar` passed. SHA-256 `6852cfe02eaf9531d177b6bce3c2d17f4af970de4bfadbd076ac79d2853d1a71`. |
| iOS source binding, multipart history and rendering | Final simulator run: 287 cases, 282 passed, 5 opt-in skips, 0 failures. Includes contract, answer-draft, session-state and hosted lesson-content checks. |
| Generic iOS build and signed build-for-testing | Passed using StudyMateiOS and iOS destinations only. |
| Additional source-bound portrait fixture | 1 simulator test passed, including four light/dark transcript/orb screenshots and accessibility checks. |
| Real iPhone verification | 43 tests passed, 0 failures/skips on iPhone 16 Pro, iOS 26.6.1. Includes source/ready state and four native portrait renders. The renders were visually inspected; no paid voice conversation was started. |
| Local backend and iPhone application | Applied after the existing conversation completed naturally. Only the backend container was restarted; the running JAR matches the SHA-256 above and local health returned HTTP 200 / UP. The updated iPhone app was launched normally after testing. |

Backend artifacts: `build/voice-question-readback-backend-tests.log`,
`build/voice-question-readback-backend-first-pass.json`,
`build/voice-question-readback-controller-recheck.log`. iOS results are in
`build/voice-question-source-final.log` and
`build/voice-question-source-final.xcresult`.
The correction after the first backend run changed test expectations only; no
production source changed after the executable JAR was built.

The existing conversation completed at approximately 20:10:44 UTC before any
backend or main-app replacement. Backend startup was 20:11:37 UTC. The previous
JAR is retained at `/app/buddystudy-backend.pre-question-readback-20260912.jar`.
The development URL and externally mapped port 8080 were preserved. No
production deployment or image build was performed.

Real-device results: `build/voice-question-source-device.xcresult` and
`build/voice-question-source-device-attachments/voice-question-once-{transcript|orb}-portrait-{light|dark}.png`.
The screenshot fixture decodes production source/ready receipts while retaining
learner, interrupted tutor and completed readback captions. Device checks cover
state and native rendering; they do not claim a new live-provider audio end-to-end
test. The app's normal launch was restored after the test environment override.

The earlier acoustic-gap interruption policy is still not a word-alignment
implementation. The learner has allowed approximately one second of additional
playback latency. An opt-in, fixed local Korean TTS fixture was fed to the
simulator's installed DictationTranscriber in paced 10 ms PCM chunks. Basic
finalization returned individual word times 2.02–13.30 seconds late;
frequent finalization returned them 1.92–11.12 seconds late. Neither returned
any of its 18 final word times within one second. Most provisional ranges
covered the whole sentence, so their earlier arrival does not prove word timing.
These results do not establish the behavior of every engine or the actual iPhone.
No provider calls, model downloads or microphone input were used by the probes.
The subsequent actual-iPhone measurements and unresolved one-second word-boundary
constraint are documented in [the dedicated probe report](voice-word-alignment-probe-2026-09-12.md).
