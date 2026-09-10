# Server-owned call and lesson presentation — 2026-09-10

## Existing behavior and the missing boundary

Manual canonical-question capture was already implemented. After the saved
question's actual audio playout, the controller enters listening and iOS shows
the amber orb, microphone cue and Finish answer action. Acoustic silence and
long-speech checkpoints only collect recognition. They cannot request a normal
tutor response or submit an answer while capture is active. Finish stops input,
waits for final recognition, and opens an editable review; explicit Submit sends
the complete reviewed answer. Existing limits, draft persistence, pause fences,
and staged hold-to-end gestures remain in force.

The missing piece was a coherent server-owned display of question generation,
manual answer progress and canonical grading. Provider audio activity alone
cannot tell iOS whether the backend is still creating or grading a question.

## Contract

The authenticated native sideband now publishes the additive
`buddystudy.voice.session.state` snapshot:

```json
{
  "type": "buddystudy.voice.session.state",
  "sequence": 12,
  "phase": "answering",
  "paused": false,
  "revision": 3,
  "studyId": 42,
  "recordId": "101",
  "answerId": "11111111-2222-3333-4444-555555555555"
}
```

`sequence` increases within one call; `revision` identifies its lesson epoch.
Optional identities are present only for a known study/question/answer.
Answer phases require all three identities. Question-ready, question-reading,
grading, graded and grading-failed phases require an exact study and record.
The server sanitizer and iOS parser validate types, bounds and known phases.
Provider-authored payloads cannot originate this trusted event. iOS rejects
older sequences, obsolete lesson revisions and snapshots after local teardown.

| Phase | Meaning |
| --- | --- |
| `conversation` | Ordinary dialogue; actual audio still determines listening/speaking |
| `question_loading` | Looking up a saved pending question |
| `question_generating` | Canonical generation is running |
| `question_ready` | A generated question is saved, awaiting readback |
| `question_reading` | The saved question is being read |
| `answering` | Keep recording until the learner taps Finish answer |
| `answer_finalizing` | Finish was requested; wait for the last recognition parts |
| `answer_review` | Input is held; the learner can edit and submit |
| `answer_submitting` | Submit was explicitly requested |
| `grading` | Canonical grading is running |
| `graded` | The exact saved answer has completed grading and saved feedback |
| `question_failed`, `answer_failed`, `grading_failed` | Explicit, operation-specific failure |
| `ending`, `ended`, `failed` | Call termination progression |

Pause is orthogonal: it preserves the current lesson phase and draft. The
existing correlated pause ACK and answer events retain microphone and submission
authority. Display snapshots cannot unmute, submit, advance a question, stop
metering, or change the owner. Local connection/termination and pending pause or
manual-answer commands take display priority while their ACKs are in flight.

The app uses one orb, short localized text, restrained colors and a central
symbol. Question preparation/grading no longer masquerade as an ordinary tutor
reply. The amber persistent answer cue remains visible through thinking pauses;
review and pause retain their existing manual controls.

Canonical generation and grading have a separate read-only observer, bounded by
the live call's hard deadline. It polls every three seconds with a ten-second
per-read timeout. It reauthorizes the same call, study revision, exact process,
record and submitted answer; completion does not depend on the model issuing
another tool call. Transient read failures retain the current state rather than
inventing a failed grade. The observer publishes only typed progress and an
exact record-change notification, never speech, submission or question selection.
New learner speech does not suppress a verified same-operation result. Ending,
focus replacement and a new answer cancel an obsolete observer. Completed
process identities prevent a late pending response/error from undoing a saved
completion. A separate operation identity also lets initial request failures
settle the display after newer learner speech without granting readback rights.

## Verification and development rollout

Artifacts are under `build/voice-session-state/`.

- Generic iOS Debug build passed with `StudyMateiOS`, `generic/platform=iOS`,
  signing disabled (`ios-generic-final.log`).
- iOS 26 simulator: **203 passed, 3 skipped, 0 failures**
  (`ios-simulator-final.xcresult`).
- Physical iPhone 16 Pro / iOS 26.6.1: **193 passed, 13 skipped, 0 failures**
  (`ios-device-final.xcresult`). The extra device skips require local repository
  source. Three opt-in external/computer-use scenarios are skipped on both.
- Suites: 171 call contracts/presentation cases, 13 manual-answer draft tests,
  16 pause tests, 5 session-state parser/reducer tests and one opt-in native
  conversation scenario. The new cases cover every localized phase, malformed
  identities, reordered events, old revisions, terminal fences, pending local
  control priority and ordinary conversation compatibility.
- Eight synthetic lesson-progress screens and the existing manual-answer
  snapshots passed on both destinations. Visual inspection covered creating,
  saved-ready, grading, graded, accessibility transcript, light-theme answer
  capture, paused capture and corrected review. Snapshots are in
  `simulator-snapshots/` and `device-snapshots/`.

- Backend: **274 passed, 0 failures/errors/skips**. Native controller 85,
  canonical coordinator 35, native relay 21, event policy 31, snapshot publisher
  2, MCP adapter 100 (`backend-tests.log`). Coverage includes long manual-answer
  pauses, read-only completion without a model poll, newer speech, scope/revision
  replacement, cancellation, transient lookup failures and late pending/error
  results after both successful and failed canonical operations.
- `:tutor:bootJar` passed (`backend-bootjar.log`). Final JAR SHA-256:
  `f6e65f3d3436979ae1e22caf6885439449abab46f071c43f1c6d3cd6bda3c393`.
- Independent implementation review and `git diff --check` passed. The final
  regression run also corrected a test-harness race: observing provider output
  did not imply the independent client answer-state queue had already drained;
  the assertion now awaits the actual bounded answer-state event.

- Updated the existing local development API container, `backend-backend-1`,
  using its mounted JAR; no Docker image was built and no production deployment
  was performed. Both restart checks found zero active calls. The final mounted
  JAR matches the SHA-256 above (`dev-rollout-final.json`). The original JAR is
  preserved at
  `/app/buddystudy-backend.pre-session-state-20260910-7ae2228e1c89.jar`.
- After final startup, local `http://127.0.0.1:8080/health` and the development
  route `https://lowfidev.cloud/health` both returned HTTP 200 with `{"ok":true}`
  at 19:07 KST (`dev-health-final.json`).
- The signed final app was installed by the physical-device test run and
  launched on the iPhone 16 Pro at 19:07 KST, using the development backend and
  profile deep link (`iphone-launch.log`).

Automated voice fixtures use synthetic provider/transcript events; they do not
establish microphone recognition accuracy or a successful live OpenAI
conversation. No real learning answer was submitted by these tests.
