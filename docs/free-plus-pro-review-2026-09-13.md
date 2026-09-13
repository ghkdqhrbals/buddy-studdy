# Free → Plus → Pro and App Review preparation

The user corrected the final public plan order to Free → Plus → Pro.
Product IDs and server tier codes remain stable for transaction continuity:

| Public name | Existing code | Product suffix | First month (Korea) | Renewal | Monthly voice |
| --- | --- | --- | ---: | ---: | ---: |
| Free | TIER1 | — | Free | Free | Unavailable |
| Plus | TIER2 | tier2.monthly | ₩9,900 | ₩19,900 | 60 min |
| Pro | TIER3 | tier3.monthly | ₩19,900 | ₩39,900 | 60 min |

The user explicitly confirmed Free voice access remains unavailable. Existing
server plan eligibility denies Free even if a positive administrative time
override exists. Neither app-side branding nor an admin number grants entitlement.
Question allowances remain 30 / 300 / 1,000. Referral grants continue to grant the
same TIER2 entitlement, now called Plus. Apple group ordering remains level 2 for
Plus and level 1 for Pro; the one-month introductory discount is group-wide.

App changes cover all three supported languages, plan pickers, membership status,
purchase action labels, billing history, referral copy and voice upgrade notices.
App Store localization and reference names are updated to Plus / Pro. Administrator
user, tier, order and advertising displays use the same names while API writes
retain tier codes. The Free plan voice allowance input is fixed at zero and
identifies voice access as unavailable.

Verification: generic iOS Debug build passed; three focused tests passed on the
physical iPhone 16 Pro (localized public names, membership fixtures, and Japanese
billing labels). Admin Vite production build passed. All 143 administrator tests passed. All 89 backend voice service/preview tests
passed, including Free session and preview denial even with an administrative
allowance override. Backend infrastructure compilation also passed.

App Review 1.1.0 has an existing unresolved Guideline 2.1 information request.
Apple explicitly requires a physical-device recording of the core flows and an
accurate list of tested device/OS versions. Repository review-note/video fields
are still placeholders; they must not be replaced with fabricated evidence.
Release remains manual after approval. A TestFlight upload/build selection alone
must not be reported as successful submission to Apple's review queue.

Apple setup readback confirmed Korean, English and Japanese Plus/Pro product
localizations, unchanged product IDs/group levels, Korean first-month offers and
renewal prices. App Info, age rating, and version metadata synchronized. The old
unresolved submission was canceled to unlock its items; the replacement draft
`26e49ef8-1a93-41aa-bc2e-6298f6d1d5f5` contains the app version, both
subscription versions, and the subscription group version. This draft has not
been submitted and does not resolve Apple's outstanding request for evidence.

The new simulator-rendered membership review screenshot shows Free, Plus and Pro,
the Korean regular prices, paid-plan 60-minute allowances, and purchase/legal
controls. Both Apple subscription screenshot resources are COMPLETE and match
MD5 `49afdcf4455c3dc02b80dbc51fdc53aa`. Apple normalizes their fileName to
`SOURCE`; verification checks the content checksum, resource identity and
subscription-scoped relationship. The screenshot fixture now supplies the paid
voice quota. Generic device and simulator builds pass; three review-item recovery
tests (eight assertions) pass. This screenshot is not the physical-device video
requested by App Review.

The media sync helper now detaches READY_FOR_REVIEW items using DELETE and
reattaches the same subscription version even on upload failure. It refuses
locked unresolved submissions before changing media.

Deployment results:
- Administrator/monitoring workflow `34747697534` and personal-deploy
  `34747713158` succeeded at source `535dc5b8`.
- Initial backend image run `34747695697` failed with Kotlin compiler
  `OutOfMemoryError: GC overhead limit exceeded`. The JVM image build now uses
  a 6 GiB in-process compiler heap and one Gradle worker. Local `:tutor:bootJar`
  passed. Retry `34748030099` and backend deploy `34748341545` succeeded at
  source `b316fa48`. Production runtime memory limits are unchanged.

Apple upload `780ae096-b831-45c6-9fa9-b9120ab4f591` for build 117 failed
processing with ITMS-90683 (missing `NSCameraUsageDescription`). The IPA transport
job succeeded, but no valid Build resource was created. Its polling job was
canceled after identifying the failure. The iOS plist now declares that camera
access is not needed for audio-only Buddy conversations; no camera capture or
permission request was introduced. Microphone copy now names Buddy. The release
workflow validates both purpose strings in archive and exported IPA. The
TestFlight waiter inspects Build Upload failures and preserves Apple's error
code/details. All 13 build-note tests (46 assertions) and generic iOS build pass.

The corrected source `cbf5f09f` passed the three scoped physical-device tests
again on iPhone 16 Pro / iOS 26.6.2 (23G90). A transient GitHub HTTP 500 prevented
the first new dispatch; a fresh run lookup found no duplicate, and the retry
created iOS release run `34748629787`, build 118.

Final release result:
- Branch: `codex/voice-call-continuity-20260910`; iOS build source `cbf5f09f`.
- Run `34748629787` initially failed before compilation because GitHub could not
  acquire a runner after five attempts. Attempt 2 succeeded through signing,
  upload, TestFlight notes and exact App Review build selection.
- Apple Build Upload `a7981396-3ec6-4e83-bdb2-413618425d20` is COMPLETE with
  no errors or warnings. iOS 1.1.0 (118) is VALID and selected on App Store
  version `f1fd5f81-f24c-46c9-be83-41f0f2406691`. Release type remains MANUAL.
- The new waiter was additionally checked against actual rejected build 117 and
  immediately returned Apple's 90683 error instead of polling until timeout.
- Server, administrator and iOS deployment workflows have all completed
  successfully. No App Review submission was sent. The replacement review
  package remains a draft pending the physical-device recording and the
  associated Guideline 2.1 evidence requested by Apple.

Workflow evidence:
- iOS: https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/34748629787
- Backend image/deploy watcher: https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/34748030099
- Backend deployment: https://github.com/ghkdqhrbals/personal-deploy/actions/runs/34748341545
- Administrator/monitoring: https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/34747697534
- Administrator/monitoring deployment: https://github.com/ghkdqhrbals/personal-deploy/actions/runs/34747713158
