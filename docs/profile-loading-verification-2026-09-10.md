# Stable iOS profile presentation — 2026-09-10

## Cause and implementation

Home previously pushed Profile immediately. Its first quota row rendered an
unavailable state because loading had not started, then a spinner, then the
larger summary. Profile, billing and voice requests started independently after
the push; the voice request could return before the restored user's profile ID
existed. Returning from a nested destination repeated those appearance requests.

Home now awaits the profile page's preparation before navigating and shows
progress in the existing avatar control. Preparation resolves the missing
profile first, then awaits billing and voice state. Existing profile and voice
reads are joined rather than treated as already complete. Request and account
boundaries reject stale completion after cancellation or identity replacement.
Deep-link destinations use one initial loading state if they were not prepared.

The ready page keeps its list while data refreshes and does not fetch again when
returning from a child page. Pull-to-refresh explicitly refreshes the profile as
well as membership/voice state. Transient failures preserve available data.

Avatar editing now receives its initial profile before the first layout. Its
local draft has an explicit baseline, so appearing or receiving a same-user
background refresh cannot reset unsaved name/avatar edits. It no longer loads
unrelated terms and notification preferences. Only an initially missing profile
needs a lifecycle-bound loading task. Account replacement/sign-out invalidates
and dismisses the old editor; temporary missing profile data does not reset it.

## Verification

Artifacts are stored under `build/profile-loading/`.

- Generic iOS Debug build passed using `StudyMateiOS`,
  `generic/platform=iOS`, and `CODE_SIGNING_ALLOWED=NO`.
  Final log: `ios-generic-final.log` (18:15 KST).
- iOS 26 simulator tests passed: **115 tests, 0 failures, 0 skipped**
  (18:16 KST). Result bundle: `ios-simulator-verified.xcresult`.
  This includes 100 question/profile request-flow tests, 8 profile draft tests,
  3 hosted SwiftUI presentation tests, 2 fixed-avatar tests, and 2 profile
  architecture checks. The suite covers slow and overlapping reads, explicit
  refresh, transient failures, cancellation, account replacement, and preserved
  study answer drafts.
- Hosted presentation tests checked the prepared editor's native name field
  and the hub's visible rows in the first synchronous layout and after lifecycle
  tasks settled. Positions and sizes remained within 1 point, and no extra
  request began on presentation. A same-user profile publication preserved
  native text edits. All four first-layout/settled screenshots were visually
  inspected; quota, voice allowance, avatar and name content were already present
  and kept the same layout. These isolate the prepared views, rather than
  claiming an end-to-end physical navigation test.
- Physical iPhone verification was attempted with the `StudyMateiOS` scheme and
  destination `00008140-00120C8E2E60801C`, with a 20-second destination timeout.
  The iPhone 16 Pro remained `unavailable` in CoreDevice and Xcode could not find
  that destination. Log: `ios-device.log`. Consequently this change was **not
  installed, launched, or verified on the physical iPhone**; reconnecting and
  unlocking that phone is required to complete this check.
- `git diff --check` passed.

No backend, deployment, billing entitlement, user account, avatar save, or record
mutation is part of this verification. Test data and draft edits are synthetic.
