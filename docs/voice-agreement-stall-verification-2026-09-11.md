# Conversation stalled after agreement

## Evidence and limits

The reported development conversation ran from 02:26:44 to 02:32:45 KST on
2026-09-11 and ended through the learner's explicit end action. The level
change succeeded at 02:28:53; the saved transcript contains the subsequent
agreement at 02:29:03. No lesson focus was committed. Later tutor responses
repeatedly described a selection as pending.

The available MCP exchange log contains a failed and successful topic listing,
mutation preparation and mutation confirmation. There is no selection exchange.
That alone cannot prove no function item was attempted: native pre-execution
validation can reject a call before it reaches the logged adapter. Ordinary
responses cannot start while a registered tool or its output ACK is pending,
and selection is synchronous. The tutor's repeated pending-selection claim
therefore did not reflect an outstanding registered selection operation.

Raw provider response frames were not retained, so the exact failure branch in
this historical call cannot be identified. Regression fixtures reproduce the
concrete failure paths below; they do not claim to reproduce the provider's
semantic decision or microphone acoustics in that original call.

## Implementation

- Silent non-audio completion settles its own frozen input sequence. A nonempty
  text-only assistant response to an audio request instead gets one bounded
  response retry for the same learner turn/revision. Empty/noise output never
  forces another response; responses containing executable tool calls are not
  replayed by this recovery.
- Exhaustion carries the exact acoustic sequence as well as a response ID when
  available. iOS can clear a pending turn even if its failed response was never
  announced. Old failures cannot clear new speech or a different active response.
  Native late transcripts preserve the retry hint until fresh speech begins.
- Cancelled/failed responses close their actual, never-executed function items
  with a correlated `executed:false` result. Exact output ACKs gate the next
  response. Cleanup cannot execute tools, overwrite accepted results or create
  an old continuation. Duplicate IDs, partial items, concurrent accepted calls
  and missing/mutated ACKs are fenced.
- Instructions carry contextual agreement into the known topic's actual
  selection and canonical question flow. Final selection errors and discarded
  calls are not background jobs; uncertainty about a write requires a saved-state
  read and never automatic write replay.
- Safe diagnostics add response IDs, input sequences and retry dispositions;
  response text, tool arguments and private results are excluded.

The provider's [Realtime server event reference](https://platform.openai.com/docs/api-reference/realtime-server-events)
documents that function-argument completion events can also occur on interrupted,
incomplete and cancelled responses. Cleanup uses only actual function items in
the matching completed response frame, never guessed IDs from partial text.

## Verification

- Backend: 317 tests passed with zero failures/skips: application service 64,
  native controller 138, native relay 26, event policy 36, MCP coordinator 9,
  existing MCP relay/input 27, sideband diagnostics/failure policy 17. Logs:
  `build/voice-agreement-application-tests.log`,
  `build/voice-agreement-backend-tests-final.log` and
  `build/voice-agreement-diagnostics-tests.log`.
  The relay fixture runs preparation, confirmed level change, contextual start
  agreement, exact selection and saved-question readback through editable answer
  capture, asserting the new learner source/revision and one mutation execution.
- Final generic iOS build passed: `build/voice-agreement-generic-final.log`.
- Simulator: 207 passed, 3 explicit skips, zero failures in
  `build/voice-agreement-simulator-final.xcresult`.
- Physical iPhone 16 Pro: 197 passed, 13 explicit skips, zero failures in
  `build/voice-agreement-device.xcresult`. Repository-source and unavailable
  hosted-fixture checks are skipped on device; state/parser recovery tests ran.
- An initial broad simulator run exposed three outdated source assertions in
  the existing transcript layout test. They now inspect the shared answer/orb
  helper and require the transcript surface and scroll identity to remain
  mounted while allowing the existing pause control transitions. The final
  simulator run above passes those stronger scoped assertions.

## Development installation

- `:tutor:bootJar` passed (`build/voice-agreement-bootjar.log`). SHA-256:
  `fae6a8162e5add91d300cfe2518c5206cc7c7b5bc719c1e9f1f8132a33568f83`.
- A fresh read-only check found zero preparing/active/ending conversations or
  processing results. The existing development application volume received that
  exact JAR; its hash matched. The prior JAR is preserved at
  `/app/buddystudy-backend.pre-agreement-stall-20260911.jar`.
  Only `backend-backend-1` restarted. No Docker image build, production rollout,
  database schema change or unrelated container restart was performed.
- The signed iOS app was installed on the physical iPhone. Logs:
  `build/voice-agreement-signed-build.log` and
  `build/voice-agreement-device-install.log`.
- The development API restarted at 03:16:01 KST. Manual local port 8080 and
  public `https://lowfidev.cloud/health` checks both returned HTTP success with
  `{"ok":true}`. The installed app launched normally with that development URL
  (`build/voice-agreement-device-launch.log`).
- Final `git diff --check` passed. Implementation and verification are committed
  together.
