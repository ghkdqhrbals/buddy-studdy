# Voice grading recovery and answer editing — 2026-09-12

## Incident evidence

The local API log captured the following sequence on **2026-09-11, UTC**
(2026-09-12 in Korea). This report intentionally omits answer text, account
identities, record IDs and correlation values.

| Time (UTC) | Observed operation | Result |
| --- | --- | --- |
| 18:21:36 | `submit_answer` | HTTP 200; submission accepted |
| 18:22:27 | `get_grading_process` | HTTP 400, `QUESTION_SCOPE_MISMATCH`, 8.87 ms |
| 18:25:31 | `list_pending_questions` | HTTP 200; entered a pending-question lookup after the grading error |
| 18:27:29 | `submit_answer` | HTTP 200; another submission later in the conversation |

The failed grading request contained exactly `correlation_id` and
`after_event_id`. The native tool catalog inherited a public schema that allowed
the cursor, while `VoiceTutorCanonicalQuestionCoordinator` accepted only
`correlation_id`. The request therefore failed argument validation before a
grading-status read. This evidence establishes a schema mismatch, not a provider
outage or a root-topic ownership failure.

The later pending lookup could bind another unanswered question in place of the
submitted one. Code inspection also found that a progress-endpoint error blocked
reading an already completed canonical record, and that background completion
updated the app without supplying the saved grade to the realtime response.
The log timeline alone does not establish when grading completed or what the
learner heard.

## Backend changes

- Native `get_answer_status(record_id, correlation_id?)` reads the exact
  submitted canonical record. The existing `get_grading_process` is a compatible
  alias; a valid nonnegative `after_event_id` from an already-open session is
  accepted but is not needed for the canonical record read. Newly advertised
  native schemas omit that cursor. Public MCP and REST grading contracts are
  unchanged.
- The active submission retains its exact record, study, correlation, saved
  question and reviewed answer. Before returning progress or feedback, the
  coordinator checks that the owner-authorized record still matches all of
  those values and the current session/focus/revision. A stale or mismatched
  result cannot update the lesson.
- Foreground status requests and background grading observation read the same
  canonical record. A transient read failure keeps the accepted submission
  available for an explicit status retry; recovery never resubmits the answer,
  creates a question or consumes another question allowance.
- While a submission owns the lesson, `list_pending_questions` returns its
  exact status before curriculum preparation or pending-page lookup can run.
  Explicit next-question requests retain their existing authorization and
  generation rules. Late results for an older question cannot replace the new
  exercise.
- `grading_unavailable` means that the saved answer's current status could not
  be confirmed. It retains the scoped study/record and differs from the
  canonical `grading_failed` result. iOS can send an authenticated,
  record-bound `buddystudy.voice.grading.refresh`; the controller schedules
  `get_answer_status` without replaying submission.
- Verified completion carries `VoiceTutorGradingReadback`: exact study, record
  and correlation plus the saved score, feedback and explanation. Each text
  field is bounded to 8,000 characters; an oversized whole field is omitted and
  marked as available in the saved record, without changing stored content.
  The controller issues one result-specific, tool-free response using that
  payload so stale waiting instructions do not become the spoken result.

## iOS and audio behavior

The orb retains the verified score, reasoning and explanation through ordinary
follow-up listening. A different exercise, lesson revision, account, connection
attempt or terminal state invalidates that presentation. Status lookup failure
shows a specific explanation and an explicit exact-answer refresh action rather
than an indefinite grading spinner. If the app reads a completed saved grade
before the server snapshot catches up, it retains that result and sends one
connection-fenced exact-answer refresh so the controller can replace stale speech. Question text uses the existing Markdown
renderer. No fabricated score or alternate question is used as fallback.

When a grade arrives during an old waiting response, the backend requests
`buddystudy.voice.response.finish_word` with exact response/request identities.
iOS applies the same local acoustic boundary and fade as learner interruption
and returns `buddystudy.voice.response.word_finished`. A stale acknowledgement
cannot cancel a newer response. Missing acknowledgement has a bounded server
fallback so the old response cannot hold completion indefinitely.

The current boundary waits for the first observed **40 ms** quiet gap, for at
most **750 ms**, then applies a **40 ms** output fade. Concurrent interruption,
pause or result-arrival requests share the first deadline. This does not wait
for sentence punctuation or a whole response. RTP provides no word-aligned
timestamps: these values improve the bounded acoustic transition but do not
prove exact word completion or the earlier three-word sentence preference.

Opening the answer editor immediately holds microphone delivery and requests
the existing ordered pause protocol. Before acknowledging pause, the backend
settles accepted answer-transcription parts. iOS consumes late/out-of-order
transcript identities during editing without appending them to the edited
answer. Closing the editor preserves the draft and resumes only a pause owned
by that editor, after the normal clear/resume acknowledgement. An existing
user-requested pause remains paused; closing an editor while that earlier
pause is still pending keeps the ASR fence until its acknowledgement. Done does not submit; manual submission
and the existing record-keyed draft store remain authoritative.

## Verification status

The coordinating agent ran the checks below against this implementation. Device
renders use synthetic saved lesson data; they do not start an OpenAI session.

| Check | Current record |
| --- | --- |
| Canonical coordinator and MCP adapter regression tests | Passed in the focused backend suite below. |
| Native controller, control-event and session-state regression tests | JUnit XML: 1,189 cases across 37 suites, 1,183 passed, 6 opt-in skips, 0 failures/errors. Includes canonical/MCP suites. `:tutor:bootJar` passed. |
| iOS grading, interruption, answer-draft and state regression tests | Real iPhone: 328 executed, 306 passed, 22 skipped, 0 failures. Live/provider probes remain opt-in; hosted AX actions are covered separately on simulator. |
| Hosted iOS simulator editor/lesson/record-loading/cancellation | 16 executed, 15 passed, 1 screenshot opt-in skip, 0 failures. Verified native Korean multiline cursor/selection and IME, exact draft edits with late ASR, retained grade, Markdown content, and retry actions. |
| `StudyMateiOS` generic iOS build | Passed. Signed iPhone build-for-testing also passed using the app’s existing development team as a command-line override for the test target. |
| Real iPhone | Eight native portrait renders captured and visually inspected: question waiting, answer, saved score/reason/explanation in light/dark plus two accessibility-size renders. Model regressions passed. Live speech/acoustic word alignment was not exercised. |
| Local Docker backend | Updated existing app volume with the verified host-built JAR; restarted only `backend-backend-1`. Local `127.0.0.1:8080/actuator/health` returned `UP`; running JAR hash matches the build. No production rollout or image build. |

Final simulator artifact: `build/voice-grading-simulator-final.xcresult` and
`build/voice-grading-simulator-final.log`. The final generic and signed device
builds also passed (`build/voice-grading-ios-generic-final.log`,
`build/voice-grading-device-build-final.log`).

Device artifacts: `build/voice-grading-device-final.xcresult`,
`build/voice-grading-device-final.log`, and `build/voice-grading-review/`.
The updated signed app was installed by the test runner and launched normally
on Min iPhone. General-size renders show complete lesson content and labels
without horizontal overflow. At accessibility size, long answers/explanations
continue below the initial viewport in the existing vertical scroll view; static
screenshots alone do not verify its touch scrolling. The portrait fixture is
plain text; Markdown behavior is checked separately by hosted presentation tests.

Added regression cases cover the observed legacy cursor request, exact-record
recovery after a transient read failure, pending-question replacement prevention,
stale/mismatched record rejection, saved failure/completion and bounded feedback.
Native and iOS changes add correlated completion/retry, interruption and editor
state checks. Test definitions alone are not a passing result, and simulated
boundaries do not establish real-device acoustic quality.

## Local backend artifact

- New JAR SHA-256: `c8bad8cf30b7f1eb01ca3125455c3b1444fa60b94987e2002ebc000ce3633b11`.
- Previous JAR retained in the existing app volume as
  `buddystudy-backend.pre-grading-recovery-20260912.jar`
  (SHA-256 `a030a0d0ea6e7a40e8471e210aaae1a36d043721862b39c7fb4973ad6910c80d`).
- Active voice-session count was zero before restarting the local backend.
- Backend test/build log: `build/voice-grading-recovery-tests.log`.
- Local refresh/health evidence: `build/voice-grading-local-refresh.log` and
  `build/voice-grading-local-health.json`.
