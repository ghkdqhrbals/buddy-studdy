# iOS usability release — 2026-09-24

## Source integration

The user authorized main integration and iOS distribution after the usability
verification review. [PR 11](https://github.com/ghkdqhrbals/buddy-studdy/pull/11)
merged as `f9e6389dc5d71581fb01a29d902b9b0870e44fe5`. Its source tree exactly matches
tested implementation `525178ed08ca2fd630e2719319c5ae34b18f6fd1`.

The branch already contained current origin/main and the previously verified
and deployed discovery/billing changes. Integration did not dispatch backend,
monitoring, admin, or Cloudflare deployments. The original local main checkout's
pre-existing IDE, scheme and screenshot/artifact changes were left untouched.

The [usability verification report](2026-09-23-usability/README.md) records the
39-test regression run and eight-test Records follow-up (40 distinct tests),
generic iOS build, simulator rendering and scoped physical Offline QA evidence.
Those results are not a new signed-build or production-network pass.

## Signed release

[Release iOS App run 35907365247](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/35907365247)
was dispatched from main with these exact inputs:

- Version `1.2.0`; the run assigned build **122**.
- App Store Connect upload enabled.
- `app_review_candidate=false`: exact build selection is handled separately,
  because existing 1.2.0 (121) was already waiting for review.
- Production AdMob configuration (`admob_test_mode=false`).
- `publish_status=false`: no release-status Slack dispatch.

The workflow completed successfully. App Store Connect accepted the upload at
04:29:29 KST, and the exact build subsequently processed as `VALID` and
`APP_STORE_ELIGIBLE`. Its existing internal TestFlight group already included
the build with `IN_BETA_TESTING`; no tester invitation or group change was needed.

## Release notes

Only `whatsNew` in the existing three version localizations changes; the existing
discovery announcement is retained and the usability improvements are appended.
The exact TestFlight instructions are stored in
[usability-122-testflight-localizations.json](../../app-store/releases/1.2.0/usability-122-testflight-localizations.json).
Korean, English and Japanese content was independently reviewed against the
implementation, and both repository metadata validators passed.

The App Store operation is scoped to the exact app `6774108938`, iOS version
`5bb7c17c-3765-4b1b-b03c-00d8da654a2e`, and the verified 1.2.0 (122) build. It checks
non-whatsNew version/localization metadata before and after each write, preserves
review account/contact/notes values without logging them, and retains manual
release. Uncertain writes are reconciled by GET before any further action.


## Completed rollout

The IPA is 30,907,313 bytes, SHA-256
`10c7c825889ef66e8109b6135c4edd0fc65e9c7abd9f0e2fba96fe08d8a4d84a`.
The downloaded GitHub artifact digest matched GitHub's published digest. The
IPA's actual version/build, production bundle identifier and backend URL match
the workflow metadata. Distribution signing, production APNs/iCloud, CloudKit,
Apple sign-in and Associated Domains were checked. Firebase matches the app;
production RevenueCat configuration is present; AdMob demo IDs and Offline QA
markers are absent. CI performed whole-bundle deep/strict verification; the local
check read IPA metadata and the main executable's signing and entitlements.

The original 121 submission was canceled only after 122 was verified valid.
Cancellation briefly returned `CANCELING`; the persisted operation was reconciled
by GET without sending cancellation again. After it became `COMPLETE`, the exact
1.2.0 version was linked to build 122. Only three localized `whatsNew` fields
were patched; the existing version/localization fields and review details were
verified unchanged during each step. Exact TestFlight notes were separately
updated and read back in all three locales.

New submission **1f254c53-7364-4536-9b7a-ed51b84881aa** contains the single app-version
item for **1.2.0 (122)**. Both the submission and version read back as
`WAITING_FOR_REVIEW`, with `MANUAL` release preserved.

[App Review submission](https://appstoreconnect.apple.com/apps/6774108938/distribution/reviewsubmissions/details/1f254c53-7364-4536-9b7a-ed51b84881aa)

Apple review approval and subsequent manual public release remain outstanding.
No approval or public release is claimed. The connected iPhone was in use during
this rollout, so this report does not claim installation or physical interaction
with signed build 122. The earlier physical Offline QA remains scoped source/UI
evidence. No additional live-account recovery or spoken VoiceOver pass is claimed.


## Independent final receipt

A separate GET-only readback at **2026-09-24 04:40:24 KST** confirmed the exact
version/build and submission states, a single active iOS submission with one
version item, manual release, matching three-locale version and TestFlight notes,
and unchanged non-whatsNew metadata hashes. The existing internal group includes
both 122 and 121. The independent verification made no API writes and did not
read review-account or contact fields. Its public-field receipt and signed-build
evidence are committed in
[usability-122-release-verification.json](../../app-store/releases/1.2.0/usability-122-release-verification.json).
