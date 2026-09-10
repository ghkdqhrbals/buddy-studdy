# Cancelling a study direction and choosing another topic

## Problem

The 02:32 screenshot belongs to the same historical conversation investigated
in `voice-agreement-stall-verification-2026-09-11.md`. The tutor's claim that a
selection must finish before any topic switch was unsupported by the recorded
execution state. The prior fix closes unexecuted function items and prevents
silent response completion from leaving the app waiting indefinitely.

This follow-up addresses how the conversation handles cancellation and a
replacement topic, including real late selection results. It also fixes a
concrete selection boundary: native focus was committed before awaiting pending
question retrieval. Failure/cancellation of that additional I/O could prevent
the committed revision from reaching the controller and obstruct later choices.

## Changes

- Native select/advance returns the committed focus/revision immediately with
  `voiceQuestion.lookupRequired=true`. Pending questions are queried through the
  existing `list_pending_questions` tool only if the direction is still wanted.
  Legacy selection remains unchanged. Selection neither generates a question
  nor spends question allowance.
- A late successful select/advance/pending-question lookup result after newer
  speech reports its real saved data plus `followupCancelled=true`. The server
  notice tells the model to address the latest request, including cancellation
  or a different topic. If the replacement topic is unspecified, it asks which
  topic the learner wants rather than claiming a selection is still running.
- Saved focus/revision remains synchronized. The abandoned follow-up cannot
  restore an old question-ready state, question notification, learning watch or
  automatic readback. Successful work is not mislabeled as failed or rolled back.
- The existing exact tool-result ACK remains the gate to the next response.
  A running database transaction is not unsafely killed, accepted changes are
  not replayed, and canonical submitted answers/grading jobs are preserved.
  Cancellation stops the old lesson continuation; it is not an instantaneous
  rollback of an operation that already committed.
- This is a backend conversation change. iOS models, wire event shapes,
  entitlements and app binaries are unchanged from the preceding verified build.

## Verification

The application policy tests and native controller, relay, MCP adapter and
canonical-question tests cover:

- selection completing before any question lookup I/O;
- lookup cancellation after a successful selection, followed by another choice;
- direct-child advancement preserving the committed revision;
- late select/advance/query success preserving saved state while suppressing
  obsolete UI state and readback;
- normal selection and failed-result behavior remaining unchanged;
- an in-memory native relay sequence: old choice starts, the learner cancels and
  chooses another topic, the old result is reconciled and ACKed, the latest
  learner source selects the new topic, and only its separately queried saved
  question is read before answer capture;
- the earlier level-change/agreement flow using the separate question lookup.

Final validation passed 380 tests with zero failures or skips: application policy
64, native controller 144, native relay 27, MCP adapter 110 and canonical question
coordinator 35. Logs: `build/voice-selection-cancel-application.log` and
`build/voice-selection-cancel-backend-final.log`.

An initial adapter assertion expected the exact thrown cancellation object.
Coroutine stacktrace recovery copies that exception, so the assertion now checks
its type and message; the subsequent new-topic selection/revision assertions are
retained and pass.

These are deterministic service/event fixtures. They do not claim a new live
microphone conversation or a provider semantic replay of the historical call.
The unchanged iOS client remains the physical-device-verified build documented
in the preceding agreement-stall verification.

## Development runtime

- `:tutor:bootJar` passed (`build/voice-selection-cancel-bootjar.log`). SHA-256:
  `5d23273b4b5c604a3d9259122e681f8420d90c976c7c6c3926b74817e4c530f1`.
- Immediately before replacement, the read-only session check found zero
  preparing/active/ending conversations or processing results. The existing
  development application volume received the tested JAR and its hash matched.
  The prior artifact remains at
  `/app/buddystudy-backend.pre-selection-cancel-20260911.jar`.
- Only `backend-backend-1` restarted. No database schema, Docker image, iOS
  binary, deployment workflow or unrelated container was changed.
- The development API restarted at 03:32:14 KST. Manual local port 8080 and
  `https://lowfidev.cloud/health` both returned HTTP success and `{"ok":true}`.
  Final `git diff --check` passed; implementation and verification are committed
  together. No production deployment was performed.
