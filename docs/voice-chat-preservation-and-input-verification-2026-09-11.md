# Conversation text, answer submission and selectable requests

## Findings

Intentional interruption removed the current provisional tutor message from the
live UI. The backend stored only completed tutor responses, so the same partial
text was absent from private conversation history. Older completed messages were
not deleted. A separate teardown path saved only manually edited answer drafts,
losing a nonempty spoken draft that the learner had not edited.

The 03:45 Redis screenshot also shows ordinary tutor responses to answer
fragments, followed by an instruction to tap an unavailable Submit control. It
predates the saved-question readback recovery deployed at 04:05 KST (see
`voice-question-readback-recovery-verification-2026-09-11.md`). Only an actual
server-bound saved question opens answer capture; saying a question from memory
does not create that UI. This change additionally makes Finish Answer explicit
inside the answer card and clarifies the real Finish → review/edit → Submit
sequence in the tutor policy.

Selectable `request_user_input` cards were already supported by the native
control handshake and SwiftUI. The model could instead present spoken options.
The full session instructions and tool description now explicitly require the
actual form when the tutor asks for recommendations, preferences or next steps.
They describe single/multiple/text questions, direct alternatives, silence while
pending and resumption after the exact submitted/cancelled result. Already clear
requests need no redundant form. Ordinary responses continue using the complete
session instructions, including language, draft protection and tool authority.

## Changes

- Keep the visible interrupted tutor message once under its original response
  and item identity, with an interrupted label. Ignore duplicate interruption,
  delayed transcript completion and text belonging to a replacement response.
- Freeze server-received tutor text at intentional interruption/user end and
  retain it in owner-private source history. Additive MySQL V118 adds
  `interrupted`, defaulting older rows to false. No existing rows are rewritten.
- Interrupted rows retain their conversation order, but never constitute study
  questions, final answers, feedback, approval or learning-summary evidence.
  Eligibility checks keep them as ordering barriers and hash their provenance.
- Keep the canonical unsubmitted answer draft on teardown, including spoken
  text, without submitting or grading it. Source speech aliases stay hidden in
  favor of the final draft text.
- Add an explicit Finish Answer button inside the listening answer card. The
  existing finalization, review/edit and Submit stages remain separate. Pause,
  structured-input acknowledgement and terminal state prevent invalid actions.

## Verification

Tests use synthetic controller, persistence and iOS fixtures. These establish
protocol/UI transitions, not universal model compliance or a live microphone
replay of the historical conversation. Provider audio lacks word-aligned timing:
retained generated text is not claimed to be the exact audible prefix. Text
already discarded by previous versions cannot be reconstructed by this change.


The final generic iOS build passed (`build/voice-chat-preservation-generic-final.log`).
Backend targeted tests passed: **462 tests, 0 failures/errors/skips** across
application service/response DTO, post-call domain evidence, native controller
and relay, WebSocket handler, transcript persistence, approval, exploration,
summary and canonical record projection. Final affected relay/persistence classes
were rerun after correcting one test that incorrectly expected raw transcript
insertion to fail instead of checking that its learning link was rejected.
The native end-to-end tests exercise single/multiple/text cards, exact
request/session/attempt matching, free text, cancellation, storage and result ACK
holds, and explicit end waiting for the interrupted archive write to finish.

| iOS destination | Passed | Skipped | Failed |
| --- | ---: | ---: | ---: |
| iOS Simulator | 303 | 4 | 0 |
| iPhone 16 Pro, iOS 26.6.1 | 289 | 18 | 0 |

Suites cover the call contract, summary/history, structured input and its actual
production card, answer drafts, native conversation, pause and interruption
boundaries. The iPhone run includes real UIKit Korean multiline entry and
rendered-card checks at normal and accessibility text sizes. Device skips include
source/SwiftUI accessibility-tree tests unavailable in hosted device tests and
explicit opt-in manual microphone/touch tests. Physical card screenshot
attachments were inspected. iPhone Mirroring remained unavailable, and no live
provider microphone replay is claimed.

Local artifacts:

- `build/voice-chat-preservation-backend-summary.json`
- `build/voice-chat-preservation-backend-final.log`
- `build/voice-chat-preservation-simulator-final.xcresult`
- `build/voice-chat-preservation-device.xcresult`
- `build/voice-chat-preservation-device-attachments/manifest.json`

## Development rollout

Only the authorized local Docker development API on port 8080 was updated.
The tested JAR SHA-256 is
`c8fccde9b0f35c8ac7988928126a7ed9ce66e510374e795359c62df44a9bb147`.
The previous JAR is retained as
`/app/buddystudy-backend.pre-chat-preservation-20260911.jar` in the development
app volume. No backend Docker image was built and no production host was used.

This container uses `FLYWAY_LOCATIONS=filesystem:/app/db/migration-mysql`, so
V118 was also copied into that existing mounted directory before the final
restart. Flyway history reports V118 successful and the `interrupted` column
exists. This additive column remains compatible with the previous JAR.
There were zero active/reserved/ending or result-processing conversations before
device testing, installation attempts and each API restart. Manual local and
external development health reads returned `{"ok":true}` after migration on
2026-09-11 around 04:35 KST. No GitHub Actions runtime health checks were added.

The final signed iOS build was installed on the paired iPhone at 04:37 KST and
launched normally at 04:37:34 with
`BUDDYSTUDY_BACKEND_BASE_URL=https://lowfidev.cloud`. Two transient CoreDevice
tunnel failures during the initial post-test installation/launch attempts were
resolved by reconnecting; the final install and launch both succeeded.
