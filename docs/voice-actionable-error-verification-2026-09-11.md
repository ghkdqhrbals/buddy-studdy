# Actionable Voice Tutor error presentation

## Scope and behavior

The previous provider-credit fix covered only one error. Session-creation REST
errors, network timeouts, microphone setup and control-stream errors could still
collapse to a generic connection failure or an ended-call label. The iOS policy
now classifies the same safe contract consistently across those boundaries.

- Offline access, timeout and temporary service failure show the reason and a
  manual reconnect action. HTTP 429 alone means temporary request limiting;
  only the exact provider-credit code indicates service provider exhaustion.
- Login, account access, terms, membership, monthly voice allowance, session
  conflict, app version and audio preparation have distinct localized guidance.
  They do not offer a fresh connection that cannot resolve the condition.
- Microphone denial offers the application's system Settings destination and
  dismissal. No permission or account setting is changed automatically.
- `AUTH_REVOKED` means account/access needs review, including while paused; it
  does not assert token expiry or network loss. Graceful quota/time endings keep
  their existing lifecycle.
- Finalization failure points to conversation history instead of starting a new
  paid session. A saved grading result reload fetches the same result; it never
  resubmits the answer or requests grading again.
- Partial transcription warns in both the orb and chat answer editor during
  review, before explicit submission. Existing edits and draft ownership remain
  intact. Question/grading phase events lack cause metadata, so they provide
  generic recovery instructions without guessing quota, auth or network causes.
  A terminal grading failure directs the learner to the submitted answer in Records, rather than asking
  the tutor to submit an answer on the learner's behalf.
- Error explanations use the main text color and body size in light and dark
  appearance, without a second tiny generic-error notice. Existing conversation
  captions remain accessible when switching from the error orb to chat.

Korean, English and Japanese strings stay in `AppStrings`. Raw response bodies,
exception descriptions, request identifiers and URLs are never used as visible
error copy. No backend protocol, payment, API key, quota accounting, microphone
transport or mutation retry behavior was changed.

## Verification

- Generic iOS Debug build, including the final source: passed.
- Signed physical-iPhone build-for-testing: passed.
- Simulator contract/state/native error suite: 274 passed, 4 opt-in audio/touch
  tests skipped, no failures (`build/voice-actionable-errors-simulator.xcresult`).
- Final focused simulator checks: 7 passed, 1 opt-in screenshot test skipped,
  no failures (`build/voice-actionable-errors-final-simulator.xcresult`). This
  includes paused authorization precedence, answer-review warning visibility in
  orb/chat, and explicit saved-result retry without a stale grade.
- Physical iPhone 16 Pro (iOS 26.6.1): 5 focused tests passed, none skipped or
  failed (`build/voice-actionable-errors-device.xcresult`). Eight synthetic
  screenshots cover offline, microphone permission, session conflict and
  provider credits in light/dark appearance. Representative native screenshots
  were visually checked for readable reason/action text and correct controls.
  No real microphone capture, paid provider request or settings modification
  was performed by these tests.
- The updated app was installed by the device test run. After final recovery-copy
  refinement it was rebuilt, installed and launched normally. Read-only active-session checks returned zero before device tests
  and the normal launch. The local backend was not restarted or changed.

Native rendering checks use injected error contracts and do not require or
establish a successful live OpenAI connection. The provider credit exhaustion
identified in the previous incident remains an external dependency.
