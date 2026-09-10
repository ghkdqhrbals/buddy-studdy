# Home quick call and question options

## Behavior

- Home shows a phone button immediately to the left of Search in the upper-right
  toolbar. Search expansion and study multi-selection hide it along with the
  other non-search controls. The iOS 26 item opts out of a shared glass capsule.
- Each signed-in tap creates a new navigation identity and one quick-call
  intent. A fresh successful Voice Tutor status response must pass the existing
  eligibility, quota and active-session policy before opening the call. Guests
  enter the existing sign-in screen. A blocked entry shows the existing Voice
  Tutor usage/error/upgrade controls.
- The status refresh returns its verified result separately from the display
  cache. Failed, cancelled and replaced-account results cannot admit a call.
  Returning after a call or a later refresh cannot repeat the original intent.
- Quick calls default recording consent to false. Profile preparation,
  RevenueCat identification, prior recording uploads and history loading do not
  delay quick admission. Existing manual entry retains its per-call consent and
  history controls.
- Public-question ellipsis and long-press menus no longer repeat View question.
  Row taps still open detail. A row with no available owner/moderation actions
  does not show an empty ellipsis button.

## Verification

- Final generic iOS Debug build passed using StudyMateiOS, signing disabled:
  `build/home-quick-call-generic-build-final.log`.
- Fourteen simulator tests passed: eight question-option ownership policies,
  four quick-call admission tests, stale-account status/history isolation and
  shared in-flight profile/status preparation:
  `build/home-quick-call-tests.xcresult`.
- Tests cover fresh-status waiting, no automatic replay on return/refresh,
  eligibility/quota/active-session guards, cancellation, account replacement,
  failed refresh with a previously eligible cache and guest/owner/other menus.
- Signed physical-device build-for-testing passed:
  `build/home-quick-call-device-build.log`.
- Updated Min iPhone and launched the app against `https://lowfidev.cloud`.
  On-device visual inspection confirmed the phone icon directly left of Search
  without a shared capsule. The owned-question menu showed only Make private
  and Delete; tapping the question row still opened its existing detail and
  saved grading result. No question option was executed.

The shortcut's admission is covered by automated fixtures; this check did not
start another live call through iPhone Mirroring, which does not support the
microphone. Live voice/background checks from the earlier task remain a direct
physical-device check. No backend configuration or deployment changed here.
