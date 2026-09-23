# BuddyStudy 1.2.0 App Review submission — September 23, 2026

App Store Connect accepted **1.2.0 (121)** for review at **04:15 KST**.
The visible receipt reported one submitted item; the submission detail showed
that exact version/build in **Waiting for Review**. Independent API readback
at **04:18:18 KST** confirmed both submission and version as
`WAITING_FOR_REVIEW`, with release mode `MANUAL`.

The UI flow was Add for Review, Continue for the localized name/subtitle
changes, then **Submit Draft (1 item)**. That last control performed the final
submission and showed the one-item receipt; it was not a preview-only action.

| Resource | Verified identity/state |
| --- | --- |
| App | 6774108938 / io.github.ghkdqhrbals.StudyMate |
| Submission | 7bc80e37-ea6e-4e63-9f39-a504cc216bb0 / IOS / WAITING_FOR_REVIEW |
| Version | 5bb7c17c-3765-4b1b-b03c-00d8da654a2e / 1.2.0 / WAITING_FOR_REVIEW / MANUAL |
| Build | d7e5c8a1-a45a-4147-aca6-d2c912193d50 / 121 / VALID / expired=false |
| Submitted items | Exactly one app-version item referencing the version above |

The [App Store Connect submission](https://appstoreconnect.apple.com/apps/6774108938/distribution/reviewsubmissions/details/7bc80e37-ea6e-4e63-9f39-a504cc216bb0)
is the operational record. The committed
[API receipt](../../app-store/releases/1.2.0/submission-verification.json)
contains only allowlisted release fields and resource identifiers, with no
credentials or reviewer/contact values.

The user explicitly instructed proceeding without a physical iPad because none
was available, and reported the requested owner actions complete. The refreshed
App Store Connect UI was authenticated, the previous agreement banner was absent,
and the submission succeeded. No legal terms were accepted by the agent, and no
agreement text or acceptance timestamp is claimed from those observations.

The submission includes the Korean, English and Japanese names/subtitles and
the previously verified version metadata. Before the final readback, the
independent API check confirmed all 40 screenshots COMPLETE across 10 sets,
with IDs, order and checksums matching the committed upload manifest. Required
review account, password, contact and notes fields were present; their values
were preserved. Existing ratings and manual release were retained.

## Verification boundaries

- The paired iPhone 16 Pro still reported installed BuddyStudy 1.1.0 (16) when
  the resumed work began. iPhone Mirroring advanced past the owner-confirmation
  prompt but then reported the phone in use and required it to be locked; the
  connection timed out, and a retry returned the same condition. No installation
  or manual interaction with candidate 121 was established.
- Physical-iPad verification is omitted under the user's explicit exception.
  Existing iPad simulator captures remain rendering evidence, not a device pass.
- Working reviewer login, required in-app term state, paid Voice access and
  candidate-specific account/draft/Universal Link behavior remain unverified.
  The presence of secure review fields does not establish successful login.
  No parallel CLI login was used because it would revoke the review account's
  other active device sessions.
- Earlier source validation remains 278 physical-iPhone tests, five iOS
  simulator source checks and 199 backend tests. Production anonymous checks
  and the signed artifact retain their separate reports; submission does not
  expand the scope of any of those results.
- No review approval or manual App Store release occurred. The currently
  distributed version remains 1.1.0 (119); no ranking or acquisition result is
  claimed. Any Apple request, rejection or approval requires its own evidence.

No app/backend implementation or binary changed during this submission step.
