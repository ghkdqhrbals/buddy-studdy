# Conversation input, topic setup and interruption

## Implemented behavior

- Voice-facing labels use conversation. Normal completed conversations keep
  the existing automatic return behavior.
- The learner can answer 1–5 questions per request, using single selection,
  multiple selection and/or native multiline text. Editing never submits;
  Submit/Cancel remains held until the exact server acknowledgment. Pending
  selection, explicit pause and manual-answer capture share a composed
  microphone gate. Recoverable validation/application failures retain the draft;
  expired or changed-parent proposals close and allow a fresh proposal.
- Successful GUI submissions append a new structured USER evidence row through
  the existing transcript store. Only durable storage and the exact provider
  output ACK create a fresh human boundary for subsequent topic selection.
  Cancel/storage failure cannot borrow an old spoken turn's authority; app
  preferences are excluded from spoken answer and grading evidence.
- A topic-creation request freezes its owned parent metadata, candidate topics
  and difficulty. The server supplies the actual selection card; its exact
  submission creates only the selected children in one transaction. Generic
  preference forms cannot approve arbitrary study mutations. Recommendations
  use the existing shared catalog. Root depth is zero, newly created descendants
  stop at depth four, and existing deeper records remain readable.
- Completed/failed MCP metadata remains below the current conversation instead
  of expiring after five seconds. Function names and compact seconds/minutes
  remain visible; the bounded state retains up to 512 finished entries and eight
  concurrent operations. Payloads and provider error text are excluded.
- Learner interruption and pause allow up to 450 ms for a 60 ms acoustic gap
  before the existing exact-response cancel/clear flow, including a response's
  remaining native playout after the provider reports completion. Pause no longer
  waits for the whole teacher response. Input, cancellation, transport replacement
  and actual termination fences remain in place.

Realtime does not supply transcript/audio alignment sufficient to identify the
currently audible word. This change offers a bounded acoustic gap; it does not
guarantee a completed word or detect exactly three remaining words. See
[OpenAI's interruption and truncation documentation](https://developers.openai.com/api/docs/guides/realtime-conversations#interruption-and-truncation).

## Verification

- Generic iOS Debug build passed with `StudyMateiOS`, signing disabled:
  `build/conversation-interruption-tail-generic.log`.
- Topic application tests: 70 passed, covering depth, owner/parent fences,
  batch-wide validation before writes, replay and preservation of old data:
  `/tmp/buddystudy-97ca-study-topic-tests.log`.
- Conversation prompt regression tests: 64 passed. Structured-input source
  checks: ten domain tests and one application DTO test passed. The additive
  API field requires no database migration and old iOS decoders ignore it.
- Final backend verification totals 600 unique passing tests: 455 infra,
  135 application and ten domain. Coverage includes capability negotiation,
  exact request/ACK correlation, long forms, persistence failures, preserved
  Korean/whitespace input, cancellation, sequential GUI focus, an actual H2
  focus ledger, parent changes and exclusion from spoken grading evidence.
  Final infra result: `/tmp/buddystudy-user-input-evidence-final-tests.log`.
- iOS simulator regression suite: 253 checks passed across the broad run and
  focused card rerun; three existing opt-in microphone/provider probes were
  skipped. The two card tests initially failed because their in-process AX
  identifier lookup was invalid. After switching to accessible labels and traits,
  both passed with actual activation, selected state, Korean multiline input
  and submission callbacks:
  `build/conversation-card-actions-tests.xcresult`. The initial broad result is
  `build/conversation-interactions-simulator-tests.xcresult`.
- A physical iPhone 16 Pro passed the native multiline card test at regular
  and accessibility text sizes, including Korean edits, submission hold,
  duplicate-action rejection and correction after an invalid-answer ACK:
  `build/conversation-native-card-device-final.xcresult`.
- Final signed iPhone run: 41 passed, zero failures and three explicitly skipped
  interaction probes (the two simulator AX actions and the opt-in touch test).
  It covers tree depth, interrupted playout tails, acoustic boundaries, durable
  operation state, user-input protocol and the native multiline card:
  `build/conversation-final-device.xcresult`.
- Exported screenshots confirm the selected options, retained multiline draft,
  large-text layout and operation metadata beneath the conversation. These are
  synthetic UI fixtures, not a claim of live microphone or acoustic quality.

The hosted physical-device test environment does not expose SwiftUI AX nodes
for synthetic button activation. Those two action tests remain simulator tests;
the separate physical fixture exercises the native text view and bound state.
An optional real-touch test is available when iPhone Mirroring is connected.
It was not run in this verification: Mirroring repeatedly reported that the
iPhone was in use and timed out. The signed native-device tests above completed
independently. Live microphone interruption quality and exact audible word
boundaries were not verified by these synthetic audio/UI fixtures.

## Development artifacts

- `:tutor:bootJar` completed successfully. Artifact SHA-256:
  `5fa1f73ed1dd36a86d2e067d0eb3e90bcefdc0fe1378a0f1f7bda91751587b57`.
- The signed iOS app was installed on the iPhone 16 Pro. Its bundled backend
  URL is `https://lowfidev.cloud`; audio background mode and code-signature
  verification passed. Install log: `build/conversation-final-device-install.log`.
- The existing development API container was restarted at 02:05:33 KST after
  fresh checks found no reserved/active/ending conversations or processing
  results. Its mounted JAR hash matches the artifact above. Local port 8080 and
  `https://lowfidev.cloud/health` both returned HTTP 200. No Docker image,
  environment, mount, database/Redis container or production system was changed.
- The installed app was launched against the development backend:
  `build/conversation-final-device-launch.log`.
