# Manual voice answer review — 2026-09-10

## Result

Canonical study questions now remain in a held answer-capture state until the
learner taps **답변 종료 / Finish answer**. Acoustic silence and long-speech
checkpoints commit transcription without requesting a tutor response. The learner
can edit the answer during capture or after finishing, and only explicit
**제출 / Submit** sends the reviewed text for canonical grading. An explicit skip
control skips the bound question and preserves its draft.

This supersedes the previous native path's automatic submission of original ASR.
The prior implementation let the realtime model decide that an answer was complete
and request submission. That permitted a pause such as “어 이유는” to be treated as
a finished answer. The new controller blocks this transition independently of the
model's interpretation of the pause.

## Implementation

- Trusted saved-question playout opens an answer ID bound to the active owned call,
  study, canonical record and lesson revision. Provider/model-authored answer
  control cannot create or submit this state.
- Finish synchronously quiesces the microphone and enters the same serial control
  FIFO after the last local speech-stop event. The server waits for commits, final
  ASR and persistence before opening review. Empty, unfinished, stale and duplicate
  submissions cannot trigger canonical grading.
- The existing record-ID draft repository is used through AppState and SettingsStore.
  Explicit edits survive later transcription, retry, skip and call termination.
  Recognition alone does not overwrite a pre-existing stored keyboard draft.
- Incomplete recognition allows manual correction or a fully typed answer. Answers
  above 8,000 UTF-16 code units remain editable and must be shortened before submit.
  The original microphone transcript is retained unchanged in private history;
  late original ASR cannot appear as another learner answer after review/submission.
- Typed reviewed submission and skip travel through the authenticated control handler,
  private controller binding, serial MCP coordinator and canonical tool adapter.
  Exact provider function-call/output acknowledgements retain existing ordering.
  The model's `submit_answer` is denied with `USER_CONFIRMATION_REQUIRED`.
- Exact canonical record preflight and answer readback recover an accepted write
  whose response was lost. Grading uses the saved result for the reviewed answer.
  Original source rows are excluded before submission to avoid duplicate post-call
  learning records; incomplete source evidence is marked incomplete instead of
  falsely claiming a complete source window.
- The orb, swipe-up conversation and existing staged hold-to-end interaction remain.
  Capture shows **답변 종료**; review exposes an editable answer and **제출**.
  General conversation outside a canonical-question capture retains its previous
  automatic turn policy.

## Verification

- Generic iOS Debug build, `StudyMateiOS`, `generic/platform=iOS`, signing disabled:
  **passed**. Log: `build/voice-manual-answer/ios-generic.log`.
- Actual iPhone 16 Pro, iOS 26.6.1, signed development build: **233 tests,
  12 expected skips, 0 failures**. The skips are source checks unavailable on
  physical devices and opt-in external scenarios. Answer draft tests: 13;
  conversation contracts: 166; topic discovery/draft persistence: 38; pause: 16.
  Result: `build/voice-manual-answer/ios-device.xcresult`.
- Simulator: 233 tests, 2 expected skips. One old source assertion expected the
  pre-editing 48-point transcript orb; it was updated to the production 64-point
  manual-answer control. All behavioral and visual tests passed in that run.
  The corrected source assertion is verified separately in
  `build/voice-manual-answer/ios-simulator-contract-retry.xcresult`: **1 test,
  0 failures**.
- Six new manual-answer visual states passed on simulator and iPhone: compact
  capture, capture editor, corrected review, empty review, submitting and
  accessibility retry. The existing 12-state call snapshot test also passed on
  both. Physical compact and review PNGs were inspected: the orb labels, single
  editable answer, submit and skip actions render correctly. Fixtures are synthetic
  (their default header says Redis while answer content illustrates Spring).
  Attachments: `build/voice-manual-answer/device-answer-snapshots/`.
- Backend: **362 tests, 0 failures, 0 skips** (64 application + 298 infrastructure),
  followed by successful `:tutor:bootJar`. Infrastructure suites: canonical question
  coordinator 31; MCP adapter 100; native conversation controller 71; native relay
  19; event policy 30; control WebSocket handler 21; isolated H2 persistence regression 26 (not a
  MySQL locking or migration equivalence check).
  Log: `build/voice-manual-answer/backend-tests.log`.
- Coverage includes long held silence/checkpoints, finish-before-ASR, ordered and
  duplicate source parts, direct typing, edits differing from original ASR,
  incomplete/late transcript recovery, draft preservation, overflow correction,
  stale identity/revision, provider forgery, exact ACK ordering, repeat taps,
  accepted-write recovery, skip, pause, quota and termination.

No live microphone call or live OpenAI grading request was initiated by the tests.
The checks establish the app/server control and editing behavior, not recognition
accuracy in the user's acoustic environment.

## Local rollout

The local backend had zero READY/ACTIVE/ENDING sessions before replacement.
The existing dev JDK container and application volume were reused; no Docker image
was built, no production server was accessed, and no database migration was added.

- New JAR SHA-256:
  `0a9fd4b61b80a6007f28b348a06fcfd7d0fc4a55a17ce03ca5c7f9b67ef9c93c`
- Previous JAR retained in the volume as
  `/app/buddystudy-backend.pre-manual-answer-20260910.jar`, SHA-256:
  `6fb5a9fc6c0fa93cecdb71da5462860d7d20839f9ea4b02613401df905ce35ce`.
- Container restarted at 2026-09-10 14:22:46 KST. Local dependency health
  passed at 14:23:43 and the existing `https://lowfidev.cloud` dev route passed
  at 14:23:49; both database and Redis reported healthy (`environment=dev`).
- The updated signed app was launched on the physical iPhone at 14:23:57 KST
  against that dev route. Log: `build/voice-manual-answer/iphone-launch.log`.
