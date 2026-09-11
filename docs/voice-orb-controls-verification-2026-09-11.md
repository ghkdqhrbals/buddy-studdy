# Voice conversation controls

## Changes

The orb screen now remains the primary surface through question arrival,
answer review and server-owned user-input requests. Those events previously
called `setTranscriptExpanded(true)` themselves. Compact selection cards now
reuse the same request-bound selections, multi-selection, free text, native
editor and submit/cancel handlers. A valid choice can also be submitted by
tapping the orb. Canonical questions still precede answer controls; the draft,
editor, skip and learning-cancel actions remain available without opening chat.
Replacing or closing a request cancels a stationary orb press, so releasing a
finger cannot submit a different request or fall through to the pause action.
An ongoing disclosure drag is preserved.

The orb displays grading and grading-complete text from the authenticated lesson
phase. Its chat size no longer drops merely because the draft became submitted.
No extra grading request, score inference or parallel persistence was added.

Actual MCP operations are visible again, using the existing exact response,
caption, answer and input-card layout. A completed or failed operation remains
under its origin as later chat arrives. Rows show only the sanitized function,
phase and concise server-measured duration. Empty operation lists render no
row. The bounded history remains scoped to one connection attempt; this does
not add a new durable history store.

Learner bubbles in light mode are black with white text, including the
learner's retained draft marker. Following the same-day dark-mode request,
dark mode uses white bubbles with black text and matching dark draft markers.
Tutor text remains on the conversation background.

Both surfaces and the single interpolated orb remain mounted. Disclosure now
uses one shared background and one crossfade, with a short content offset.
The breathing scale uses an independent timeline instead of resetting an
animation-disabled transaction whenever the lesson changes. A lesson update
does not snap an ongoing disclosure drag.

## Interruption and cancellation

The old interruption path forced source gain straight to zero after at most
450 ms. The native path now allows up to 1.2 seconds for an observed 80 ms quiet
gap and completes an 80 ms raised-cosine gain ramp before sending the ordered
speech/pause control. Continuous sound at the deadline also uses the ramp.
The wait, fade and local interruption retain the original response ID and
generation. A newer response cannot inherit an old fade, and duplicate events
for the same response cannot restore its gain midway through interruption.
Capture, software AEC and native RTP continue throughout.

There is no separate saved interruption-duration preference in the current app;
this implements the earlier requested natural stopping behavior. Native
Realtime audio has no word-aligned playback timestamps. Acoustic boundaries
and a short fade do not prove exact word completion or a sentence ending in
exactly three words, and those guarantees are not claimed.

Learning cancellation was checking only a retained draft's phase, even when
the authenticated lesson had already moved to another question or grading.
It now requires the current lesson identity and revision, while still allowing
cancellation during a user pause. Old nonterminal answer updates cannot
reacquire input; exact terminal receipts may close the preserved draft. A new
question-ready receipt cannot replace a pending post-cancellation choice.
Cancellation does not submit, grade, skip or delete the saved question/draft,
and does not end the conversation.

## Verification

- Generic iOS Debug build passed after the final production change:
  `build/voice-orb-controls-generic-final-touch.log`.
- Signed `StudyMateiOS` device build-for-testing passed:
  `build/voice-orb-controls-device-final-touch-build.log`.
- Serial hosted simulator regression passed: 346 tests, 338 passed, 8 opt-in
  skips, no failures. This covers draft identity/cancellation, form input and
  acknowledgments, question-before-answer controls, operation origins, pause,
  interruption and the existing voice contracts:
  `build/voice-orb-controls-regression-final.xcresult`.
- After the final stationary-touch guard, the affected compact interaction and
  contract suites passed again: 238 tests, 232 passed, 6 opt-in skips:
  `build/voice-orb-controls-touch-guard.xcresult`.
- Physical iPhone 16 Pro / iOS 26.6.1: 13 passed, no skips or failures. These
  comprise 10 deterministic PCM boundary/fade tests and three native UI render
  tests for compact choices, grading, transcript disclosure and answer pause:
  `build/voice-orb-controls-device-final.xcresult`.
- Final rendering used the phone's portrait 402 × 874 point bounds. Light/dark
  images show the selected options and typed draft, accessible submit/cancel
  controls, grading-complete orb, black/white learner bubble in light mode, and
  the MCP row directly below its originating learner message. The separately
  requested portrait viewport fixture also passed; its metadata identifies the
  imposed viewport and native scene orientation. Earlier device rendering
  covered the landscape bounds. Attachments and metadata are in
  `build/voice-orb-controls-device-final-attachments/manifest.json`.
- Hosted AX action tests select multiple options, edit multiline Korean text in
  the production editor, preserve that draft across unrelated tool updates,
  submit through the same orb, and cancel without submitting or opening chat.
  The initial run's four AX failures were exact-label assumptions: SwiftUI
  exposes combined speaker/caption elements and a button plus its own text.
  Helpers now require exact labels and verify containment and row order; they
  still reject a second form or separately positioned duplicate. The serial
  rerun passed all four.
- `git diff --check` passed. The installed app was launched without the XCTest
  environment at 20:18 KST, after confirming there were no active voice sessions.

The device UI fixtures inject synthetic states and do not open a paid provider
session, record audio, or prove real touch/orientation E2E. The 80 ms disclosure
images are time samples, not frame-by-frame animation verification. Live
provider speech interruption and perceived word endings were not acoustically
tested in this pass; the deterministic tests verify the boundary/fade timing
and stale-response fencing only.

## Dark-mode palette follow-up

The learner bubble now reverses with the theme: white/black in dark mode and
black/white in light mode. Retained draft and interrupted-response markers use
the same theme-aware foreground at reduced opacity. No transcript data or
control behavior changed.

The generic iOS build and signed device build passed
(`build/voice-dark-bubble-generic.log`,
`build/voice-dark-bubble-device-build.log`). The existing native portrait render
test passed on iPhone with no skips (`build/voice-dark-bubble-device.xcresult`).
Both transcript images were visually inspected: the dark learner bubble has a
white fill and black text, and the light learner bubble retains its black fill
and white text. Synthetic fixture limitations above still apply. The installed
app was relaunched without the test environment after the active-session gate
returned zero.
