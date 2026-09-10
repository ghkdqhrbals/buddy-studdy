# Conversation choice UI refinement

## Changes

The previous card stacked an individual rounded box for every option, repeated
waiting/selection guidance and a large empty inline editor. The redesign groups
options into one list with subtle separators, a small selection count and the
conversation's existing mint accent. Single and multiple choice use distinct
indicators. A clear Submit action sits beside a secondary Cancel action.

Direct input is a compact row with a two-line preview. It opens a dedicated
native TextEditor with keyboard-aware space and a Done action that only retains
the draft. The request keeps ownership of the text; status/request changes close
the editor and existing state guards reject stale edits. This removes native
cursor gestures from the live transcript's scrolling/dragging gestures.

Submitted and cancelled cards keep the request title, status and entered values,
with no inactive unselected buttons. Selection and terminal-state transitions
respect Reduce Motion, and the system sheet provides the editor transition.
Localized Korean, English and Japanese labels remain in AppStrings.

No backend, API protocol, study creation, grading or quota behavior changed.

## Verification

- Generic iOS Debug build passed: `build/voice-input-clean-ui-generic-verified.log`.
- Simulator: 13 passed, 1 opt-in manual-touch test skipped, 0 failures;
  `build/voice-input-clean-ui-simulator-verified.xcresult`.
- iPhone 16 Pro, iOS 26.6.1: 11 passed, 3 skipped, 0 failures;
  `build/voice-input-clean-ui-device-verified.xcresult`. The two in-process
  SwiftUI accessibility tests run on simulator because that tree is unavailable
  to the physical-device test host; the third skip is the manual-touch test.
- The simulator exercised the actual custom-input button, presented sheet,
  native text editor, Done dismissal, draft preview, changed selections, Submit,
  acknowledgement and compact submitted/cancelled cards. An initial custom
  accessibility wrapper hid the native Button action; removing that redundant
  wrapper fixed both the semantics and actual activation regression.
- Physical-device tests rendered the production card and hosted the same
  production editor directly. Native Korean marked-text composition, multiline
  insertion, vertical caret movement, selection preservation on parent refresh,
  validation retry, acknowledgement gating and stale/double actions passed.
- Visually inspected exported simulator and iPhone renderings: normal text,
  accessibility2 text, dark theme and 375-point light layout. Selected rows,
  custom previews and footer actions remain readable without overlap. The real
  simulator sheet and physical native editor renderings were also inspected.
  Attachments are in `build/voice-input-clean-ui-*-verified-attachments/`.

Hosted UI fixtures do not connect to a voice session or modify account study
data. This pass did not replay a live voice conversation or manually perform
the keyboard spacebar gesture. Native vertical caret support was exercised
through UIKit's text-input API. The editor keeps a four-line question preview
to preserve writing space; the full question remains in the card and its
accessibility label.

The final signed build was installed on the connected iPhone at 04:58 KST on
2026-09-11 and launched successfully at 04:59 KST. The development backend URL
remains `https://lowfidev.cloud`.
Active/processing session count was checked as zero before device tests and
installation/launch. No backend restart or deployment was needed.
