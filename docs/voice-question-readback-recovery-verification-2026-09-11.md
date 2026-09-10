# Saved question playback recovery

## Observed conversation

The 03:40 KST screenshot showed Redis selected, a persistent “질문 읽는 중”
header and a failed `list_studies` row. The corresponding development session
started at 03:38:53 KST on the preceding verified backend build.

The recorded tool sequence was:

1. `list_studies(limit=10)` returned `RESULT_TOO_LARGE` in 189 ms. Its full study
   rows included unrelated question and prompt content.
2. `list_studies(limit=3)` succeeded in 20 ms.
3. `select_voice_study(study_id=74)` succeeded in 58 ms, committing Redis focus
   revision 1 and requesting a separate pending-question lookup.
4. `list_pending_questions(study_id=74)` succeeded in 55 ms, returning saved
   unanswered record 122 at its original difficulty.

Thus the visible failed discovery row was the earlier, recovered request;
question retrieval itself succeeded. No further tool failure was recorded in
that sequence. The tutor's preparatory sentence preceded these calls. The saved
question appeared in the transcript only after the learner asked again roughly
a minute later; subsequent fragments received ordinary tutor responses instead
of entering the manual answer workflow.

Provider response bodies are not retained in those runtime diagnostics, so the
exact empty response shape cannot be established retrospectively. Code review
and deterministic event fixtures reproduced a matching defect: a mandatory
readback with no audible output could be accepted as a silent turn, losing the
typed question binding while leaving `question_reading` visible. A later normal
response could mention the question without arming manual answer capture.

## Changes

- Native `list_studies` returns complete discovery rows containing only IDs,
  parent IDs, topic, level, order and enabled flags plus pagination. Embedded
  questions, answer hints and long prompts are omitted before the existing
  output-size bound. Candidate ownership/tree checks still inspect the original
  result. Legacy/general MCP and canonical question retrieval are unchanged.
- Mandatory question readback cannot silently complete. Empty/blank output and
  unexpected tool calls use the existing single response retry with the same
  saved question. Forbidden function items are closed without execution and
  their output acknowledgements are required before continuing.
  `question_reading` begins with observed audio; scheduling alone retains the
  question-ready state.
- A declared audio item with no observed playback or nonempty audio frame gets
  a five-second grace period after response completion. The controller then
  clears the old provider output and waits for its exact acknowledgement before
  retrying; delayed audio cannot open answer capture during that clear. A missing
  clear acknowledgement remains a bounded connection failure, not a false
  successful recovery. Normal delayed audio within the grace period is retained.
- Retry exhaustion leaves `question_reading`, reports `question_failed` and
  publishes the learner's scoped retry event. A later authenticated refresh of
  exactly that saved question can restore readback and manual answer capture.
  Ordinary refreshes do not repeat already delivered questions, and new speech
  alone does not force a failed question over a changed learner request.
- Recovery metadata is server-only. The controller checks the failed question's
  study, record, original text, lesson revision and current learner boundary.
  A changed focus, active answer capture or ended session cannot revive it.
- iOS exposes its local retry hint and listening orb over a stale reading
  snapshot while preserving server state, pause, drafts, grading and terminal
  presentation priorities.

## Verification

The regression fixtures exercise actual controller/relay event boundaries,
canonical question identity and the UI presentation state. They do not claim a
live provider replay or microphone reproduction of the historical response.

The generic iOS build passed (`build/voice-question-readback-generic.log`).
Application policy tests passed 64 cases with no failures or skips
(`build/voice-question-readback-application.log`).
The final backend run passed another 371 tests: native controller 176, native
relay 28, MCP adapter 114, canonical question coordinator 36 and sideband
diagnostics 17. Combined backend validation passed 435 tests with zero failures,
errors or skips (`build/voice-question-readback-backend-verified.log`).
Simulator tests passed 210 cases, with 3 platform-specific skips and no failures
(`build/voice-question-readback-simulator.xcresult`).
Physical iPhone tests passed 200 cases, with 13 platform-specific skips and no
failures (`build/voice-question-readback-device.xcresult`). Both runs include the
new retry presentation tests and the native conversation contract tests.

The learner ended the existing development conversation at 03:49:33 KST.
Physical-device testing started only after a fresh query found no active,
preparing, ending or result-processing conversation.
The iPhone Mirroring app reported that connection timed out because the iPhone
was in use, so this run does not claim a new mirrored visual or spoken test.

An initial backend test compilation exposed a public parameterized test method
using an internal diagnostic enum. The test method visibility was corrected to
match that enum; no production API visibility was expanded.
The first executable run also corrected new fixture expectations: discovery
attestation intentionally leaves difficulty untrusted, although the displayed
row retains it; a query result without typed progress does not establish a new
conversation state. The authority and no-readback assertions remain intact.

## Development runtime

- `:tutor:bootJar` passed (`build/voice-question-readback-bootjar.log`). SHA-256:
  `791017b7d0f94b62d4e7ffb9ef1df65d3d98707e481c8aef9974c3e1127df6ba`.
- Fresh session checks immediately before replacement and restart found zero
  preparing, active, ending or result-processing conversations. The previous
  artifact is preserved at
  `/app/buddystudy-backend.pre-question-readback-20260911.jar`.
- Only the existing development API container restarted, at 04:04:21 KST. Its
  running artifact hash matches the tested JAR. Manual checks of local port 8080
  and `https://lowfidev.cloud/health` both returned `{"ok":true}`. No database
  schema, Docker image, deployment workflow or unrelated container was changed.
- After another zero-session check, the signed iOS app was installed and
  launched normally on the connected iPhone at 04:05:04 KST using
  `https://lowfidev.cloud`. Test runs did not end an active conversation.
- Final `git diff --check` passed. The implementation and this verification
  record are committed together.
