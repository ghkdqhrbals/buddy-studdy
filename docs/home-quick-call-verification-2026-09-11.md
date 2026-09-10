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
- Quick calls default recording consent to false. A missing authenticated
  profile is prepared before the fresh status read. The full Profile page,
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

## Cold-start correction

The user reported the unavailable message at 00:25 KST. Mirroring showed the
Voice Tutor entry with no usage data, and Retry left it unchanged. The development
API was healthy and had received no voice-status or session requests since the
previous installation. A cold launch restores sign-in but leaves the profile
unresolved; the first implementation required that profile ID to construct a
Voice Tutor request context, so it returned before sending the status request.

Entry preparation now loads only a missing authenticated profile through the
existing shared profile request, then fetches a fresh Voice Tutor status. The
initial unresolved owner may become the verified owner within the same account,
backend and language generations. Initial entry, Retry and pull-to-refresh use
the same preparation; the entire preparation shows loading instead of a false
unavailable message. No full Profile page or billing SDK preparation is added.

Generic iOS and signed device builds passed (`quick-call-cold-start-generic-build.log`
and `quick-call-cold-start-device-build.log` under `build`). The corrected app
was installed and launched fresh on Min iPhone. Without visiting Profile or a
study, one Home phone tap showed loading and then the connected listening screen.
The backend confirmed this sequence at 00:33 KST:

- Profile: HTTP 200, 17 ms.
- Voice status: HTTP 200, 21 ms.
- Session creation: HTTP 201, 44 ms.
- WebRTC exchange: HTTP 200, 2.02 seconds.
- Control connection opened at 00:33:24.

This proves that the previously blocked cold-start path reaches call admission
and connection. It does not measure microphone or conversation quality through
Mirroring. The test app was restarted afterwards to release the test connection
and leave Home ready; no backend rollout or setting change was required.

Twelve focused simulator tests passed after the correction
(`build/quick-call-cold-start-tests.xcresult`): six new cold-entry/profile
bootstrap cases, four existing entry-admission cases, stale account response
isolation and shared profile/status request preparation. The new tests begin
with restored authentication and no profile, enforce profile-before-status
ordering, exclude unrelated destination loads, verify recovery after failure,
and reject cancellation, account/language changes and changed generations.

The verification session finished settling at 00:36:12 KST. Both session and
result are COMPLETED and the account has zero active calls, so the next user
call is not blocked by the test connection.
