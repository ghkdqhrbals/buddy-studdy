# Voice answer editing on iPhone

## Change

The answer previously used a 180-point (280 with accessibility text) TextEditor
inside the transcript ScrollView. Focusing it aligned the entire answer card to
the bottom, while transcript drag settlement could still scroll the outer view.
That nested, clipped viewport made multiline correction difficult. This is a
code-level finding; it does not establish that the parent directly intercepted
the keyboard's spacebar trackpad gesture.

Tapping either answer preview now opens a large sheet containing one native
multiline TextEditor that fills the available keyboard-safe space. The editor
keeps iOS cursor, selection, scrolling and marked-text behavior; it has no
custom UITextView synchronization. Opening the sheet cancels queued transcript
scroll tasks and prevents automatic scrolling while it remains open. Focus is
requested only when the editor appears.

The binding still writes through VoiceTutorViewModel and the existing
SettingsStore draft repository. Done only closes the editor; submission stays
an explicit separate action. The sheet captures the answer ID, rejects writes
for a different/non-editable answer, and closes when the answer identity or
editable lifecycle ends. Existing Korean/English/Japanese AppStrings are reused.

## Verification

- Generic iOS Debug build, StudyMateiOS, signing disabled: passed.
  `build/voice-answer-editor-generic-build.log`.
- Simulator iOS 26.0: 19 tests passed (13 answer draft contracts, five native
  editor tests, one rendering test covering ten manual-answer screens).
  `build/voice-answer-editor-tests.xcresult`.
- Paired iPhone 16 Pro, iOS 26.6.1: all five native editor tests passed.
  `build/voice-answer-editor-device-tests.xcresult`.
- Native tests exercise vertical UITextInput caret movement, preservation of
  selection/scroll/view identity across parent updates, Korean composition
  across a parent refresh, exact Korean/newline/emoji/whitespace edits through
  the binding, and scrolling with accessibility text size.
- Regular and accessibility editor snapshots were inspected. Exports:
  `build/voice-answer-editor-screenshots`,
  `build/voice-answer-editor-accessibility-screenshots`, and
  `build/voice-answer-editor-device-screenshots`.
- Signed app installed on Min iPhone and relaunched against
  `https://lowfidev.cloud` after device tests. No backend change was needed.

Programmatic UITextInput operations are not a physical spacebar long-press
trackpad test. iPhone Mirroring supplies a hardware keyboard, and its available
control API does not expose a timed touch-and-hold gesture. A physical keyboard
trackpad check therefore remains outstanding.

The Home profile initially remained in its preparation flow, then opened after
relaunch and waiting. Profile preparation also awaits RevenueCat identification
and invoices; the observed HTTP 200 responses alone did not prove that the
entire preparation had completed. No navigation change was made.

A call started through iPhone Mirroring at 23:56 KST. After Home and another-app
switching, the UI showed a disconnected call on return at 23:58. The backend
recorded PROVIDER_RELAY_COMPLETE / PROVIDER_CLOSED at 23:57:43, following a fresh
heartbeat at 23:57:41, then settled 73 seconds. This was not CLIENT_END or a
server heartbeat timeout; the server does not log the upstream close reason or
the local microphone state.

Apple explicitly states that iPhone Mirroring does not support microphone
access: [iPhone Mirroring](https://support.apple.com/en-euro/120421). The mirrored
call therefore cannot establish that real background microphone/media operation
works or fails. No speculative transport change was added from that observation.
The ended test call was dismissed, the app was left at the Voice Tutor entry,
and Mirroring was closed. Direct physical-device call/return, editor open/close
and spacebar trackpad checks were requested and remain pending user observation.
