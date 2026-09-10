# MCP records attached to their originating message

## Change

- Transcript messages now contain their own small gray operation rows. A new
  message does not move an existing operation, and completion/failure updates
  its original row. The compact conversation surface shows active work only.
- The native backend sends a separate sanitized
  `buddystudy.voice.operation.context` before each unchanged operation-start
  event. The context freezes response, learner/tutor item and canonical answer
  identities when work is requested. Follow-up question/grading polls inherit
  that original context. Old clients ignore the new event and still receive
  their existing six-field status events.
- iOS preserves response/item identities when provisional transcripts commit.
  Operations can therefore attach to a late response, a tool-only response's
  learner message, a submitted choice card, or a canonical answer draft/receipt.
  Typed answers do not require an ASR source item to establish their identity.
  Skipping a question attaches the operation to that saved question instead of
  an answer receipt that will never be created.
- Exact origins that have not arrived yet remain in bounded operation state.
  They do not borrow a newer message; origins trimmed from the visible transcript
  take their operation rows out of that window. Older-server events without
  context retain their initial local message or provisional-response position.
- Function names, status and compact seconds/minutes remain visible after
  completion. Only active rows run a periodic clock. Arguments, results and
  provider errors do not enter this display model.

## Verification

- Final backend coverage passed 189 unique tests with zero failures/skips:
  native controller 129, event policy 35 and native relay 25. The answer-ID run
  covers all three classes; the final skip refinement reruns controller/relay.
  Logs: `build/mcp-operation-context-backend-final.log` and
  `build/mcp-operation-context-backend-skip.log`. Coverage includes source IDs
  frozen before delayed transcript completion, unchanged legacy status payloads,
  provider-forged context rejection, sanitized metadata, exact typed-answer and
  skipped-question ownership, sequential GUI input and inherited polling origin.
- Generic iOS Debug build passed with `StudyMateiOS`, signing disabled:
  `build/mcp-operation-anchor-generic-verified.log`.
- Final simulator run passed all 29 tests:
  `build/mcp-operation-anchor-simulator-verified.xcresult`.
  Tests cover immutable start position, completion after newer messages,
  pending/final tutor transcripts, late learner transcripts, concurrent calls,
  stale/duplicate metadata, bounded context/history, trimmed origins, answer
  source aliases, typed-only answers, exact choice cards and old wire contracts.
- Two simulator UI tests inspect actual native accessibility reading order and
  screen Y coordinates through message insertion and operation completion.
  Captures show the first message, its completed operation, and later messages.
  The initial UI assertion counted both a combined caption parent and selectable
  body leaf. The corrected test requires exactly one body and validates that
  any combined parent precedes and contains it; order and frame assertions remain.
- The rendering fixture uses the production conversation view. Synthetic
  fixtures verify display and state; they do not claim a live microphone call.
- Physical iPhone 16 Pro run passed 26 tests with zero failures and three
  explicit skips: `build/mcp-operation-anchor-device.xcresult`. The two hosted
  accessibility-tree tests remain simulator-only, and the source-text contract
  test needs the local repository. The production rendering test and all context,
  timing and session-state behavior tests ran on the device. Its exported capture
  was visually checked for message/operation/message order.

## Development runtime

- Final `:tutor:bootJar` succeeded. Artifact SHA-256:
  `b0a3b30062e5e051f06b3082e63facec18a2a3c68da9187fb087e53e256383cb`.
  Build log: `build/mcp-operation-context-bootjar-final.log`.
- The existing local development volume received the tested JAR and only
  `backend-backend-1` restarted, finally at 02:53:42 KST. The mounted hash matches.
  The prior artifact remains at
  `/app/buddystudy-backend.pre-operation-anchor-20260911.jar`.
- Fresh read-only checks found zero preparing/active/ending conversations and
  processing results before replacement. Local port 8080 and
  `https://lowfidev.cloud/health` both returned HTTP 200 with `ok: true` afterward.
  No database schema, Docker image, environment, routing or other container changed.
- The signed app was installed on the physical iPhone. Installation and normal
  development launch logs are `build/mcp-operation-anchor-device-install.log`
  and `build/mcp-operation-anchor-device-launch.log`.
- `git diff --check` passed. The implementation and verification are committed
  together; no production deployment was performed.
