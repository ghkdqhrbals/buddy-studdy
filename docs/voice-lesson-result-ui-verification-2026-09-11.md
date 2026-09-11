# Voice lesson question and result surface

## Behavior

The compact voice screen now gives the entire canonical question a prominent
heading, larger text and a separate bordered surface above the orb. It keeps
the full question before the finish-answer control and does not switch to chat.
The answer panel uses black/white contrast in light mode and white/black in
dark mode, with text-only direct-input/edit actions and a multiline preview.
The existing native editor and explicit finish, review and submit path remain.

Orb status is localized text throughout, including answer waiting, capture,
pause and grading. Empty current capture says “답변 대기”; captured content uses
the recording status. Decorative microphone, sparkle, checkmark and pause
symbols have been removed from the orb and answer controls. Remaining time is
also text. The actual MCP rows remain beneath their original conversation.

Previously the screen received only a `graded` phase, so it could display
completion without any score, reason or explanation. It now reads the exact
saved record through the existing authenticated record-detail use case. The
result is transient UI state, bound to the owner, call, connection attempt,
lesson revision, study and record. A graded QUESTION, valid 0–100 score and
nonempty feedback/explanation are required. No result is inferred from the
transcript or borrowed from another cached record.

The score appears in the orb, with readable “채점 이유” and “해설” sections
below. A pending selection still owns the orb's action; its accompanying result
panel includes the score. Loading is bounded to 20 seconds and failures expose
an explicit result-only retry. Duplicate snapshots and pause changes do not
poll, resubmit an answer or regenerate a grade. Changing focus, account,
connection or ending the lesson invalidates previous results. Stale active
drafts cannot leave an answer composer or pause control on a different lesson.

## Verification

- Generic iOS Debug build (`StudyMateiOS`, `generic/platform=iOS`, signing
  disabled): passed. Signed iPhone build-for-testing: passed.
- Related iOS simulator regression: 395 passed, 8 opt-in audio/touch/render
  tests skipped, 0 failed (`build/voice-lesson-result-regression.xcresult`).
- After the large-type orb adjustment, targeted question/result, compact
  interaction and answer-pause tests: 8 passed, 3 opt-in render tests skipped,
  0 failed (`build/voice-lesson-result-adaptive.xcresult`).
- Final physical iPhone 16 Pro / iOS 26.6.1 run: 9 passed, 0 skipped/failed
  (`build/voice-lesson-result-device-final.xcresult`). This includes eight
  grading-state tests and the native portrait rendering fixture enabled by
  `BUDDYSTUDY_VOICE_UI_RENDER_SMOKE=1`.

The simulator tests exercise the authenticated record-detail GET through the
existing AppState use case with a local HTTP fixture: exact record path,
authorization and language headers, no repeated polling, account replacement
during the request, wrong-record rejection, explicit result-only retry, and
preservation of drafts and record storage. Reducer tests cover timeout and late
receipts, incomplete or invalid grades, owner/call/attempt/revision/study/record
replacement, duplicate snapshots and pause changes. Hosted UI tests cover the
full question before Finish, a multiline native editor retaining its cursor
through a tool update, explicit finish/review/submit, result loading/retry and
choice/result coexistence without forced chat disclosure. Production ViewModel
event wiring was source-reviewed; it was not exercised by a live provider call.

Eight final native images were exported to
`build/voice-lesson-result-device-final-attachments/` and visually inspected:
question waiting, entered answer, and score/reason/explanation in both light
and dark appearance, plus Accessibility 3 question and grade screens. The first
large-type inspection found state text overflowing the circle. The circle now
grows with Dynamic Type, with its text bounded by its actual diameter; the final
images show no overlap between question, circle and answer/result surfaces.
Large-type content remains in the existing scroll view, with longer answers and
explanation below the initial viewport. The standard-size result fixture shows
the score, reason and explanation together.

The device fixture injects a canonical question and an exact saved grade into
the native screen. It does not start a voice session, capture audio, submit or
grade a real answer, or change account settings. Its explicit portrait viewport
does not change the device scene orientation; the normal test host may still
initialize app services. This verifies native rendering, not real-touch/live
grading end-to-end behavior. A read-only local active-session check preceded
device execution. No backend or audio implementation changed in this patch.
