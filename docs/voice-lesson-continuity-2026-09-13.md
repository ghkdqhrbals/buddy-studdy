# Voice lesson continuity and result access — 2026-09-13

## Findings and changes

- Permanent session instructions said to start with the opening topic question.
  They are inherited by normal responses, including after interruption and context
  trimming. Removed that repeatable imperative and opening text from session
  instructions. The first-response controller still owns its one-time opening.
- Added explicit post-grading continuity: preserve the topic, answer follow-ups,
  do not treat acknowledgement as hang-up or authorization for another question,
  and do not promise generation without an accepted operation.
- Grading announcements are bounded to two short sentences: saved score and main
  reason. Internal record IDs and persistence commentary are excluded.
- Added a result details sheet accessible from the countdown area in both call
  presentations; the result card always contains its own score, reason and
  explanation. Existing authenticated result state is reused; no extra provider
  call or answer submission.
- At the user's request, connected pause is named conversation mute. It continues
  to consume connection time. Server billing, hard deadline, pause acknowledgments,
  preserved drafts and microphone/output fencing are unchanged.

## Verification

The first application test run exposed one other old assertion that required the
opening in persistent instructions; it was updated to forbid that leakage while
retaining the language, topic-selection and tool-flow checks.

No claims are made that a prompt rule guarantees every Mini response. Model
behavior still requires a fresh real lesson trial; previous sessions retain their
original provider instructions.


## iPhone verification

- Generic iOS Debug build succeeded (`StudyMateiOS`, code signing disabled).
- Real iPhone 16 Pro: 32 pause/mute and retained grading-result state tests passed.
- A separate on-device visual test rendered three synthetic, network-free screens:
  dark graded orb, light graded transcript, dark muted orb with retained result.
  Inspected the exported PNGs for score/reason/explanation layout and result access.
- Artifacts: `build/voice-lesson-continuity-screens/manifest.json`,
  `build/voice-lesson-continuity-device.xcresult`, and
  `build/voice-lesson-continuity-visual.xcresult`.
- These fixtures do not spend voice allowance or verify a new live Mini lesson.


## Final results and local rollout

- Backend: application 75 + native controller 252 = **327 tests passed**, no
  failures/errors/skips; executable JAR build succeeded.
- Final device run after terminology updates: **32 tests passed**. The visual
  fixture test passed separately. Signed updated iPhone app was installed by
  Xcode and launched through devicectl.
- The development backend had 0 active sessions before restarting. Reused its
  existing Docker image and volume, retaining Mini and existing routing.
- JAR SHA-256: `76b0ed57f125aa80bff2ebe9d4330f97432612feba77b1f89a3e9de842f0761b`.
- Backup in app volume: `/app/buddystudy-backend.pre-lesson-continuity-20260913.jar`.
- Final logs: `build/voice-lesson-continuity-backend.log`,
  `build/voice-lesson-continuity-final-device.log`.

Local `/actuator/health` returned `status: UP` after restart.
