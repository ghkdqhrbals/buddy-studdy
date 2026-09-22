# App Store growth integration — 2026-09-23

## Source boundary

The discovery change from `bf373244` is being integrated onto the newer
`origin/main` base `1d57ac12` in an isolated worktree. The original checkout and
its user artifacts are preserved. The September 22 build, device tests and
screenshots describe the earlier branch; they are not validation of this merge.
Record the integrated build/test results separately below before release.

The merge preserves Voice Tutor, canonical QUESTION/VOICE_TUTOR records,
owner-aware public actions and the current Free → Plus → Pro membership model.
Both paid plans include a separate Voice Tutor allowance. Interest following and
first-study suggestions neither grant membership nor consume question/voice quota.
Localized store names, keywords and discovery/share copy remain next-update
candidates. The descriptions include voice learning and paid-plan voice time;
they do not promise unverified introductory eligibility or fixed storefront prices.

The subscription migration is **V120** on this base. The earlier branch's V99
identifier is already occupied on mainline; September 22 reports retain their
historical V99 references with a follow-up note rather than rewritten results.
The retained screenshot fixtures must be compared with the integrated UI.

## Store observation

Read-only browser inspection on September 23 still showed the updated agreement
banner. Apple Developer asks the owner to agree by October 2 to retain resource
access; App Store Connect explicitly says owner acceptance is required to update
existing apps or submit new apps. No agreement was accepted by this work.
BuddyStudy 1.1.0 remained ready for distribution, with English name `BuddyStudy`
and subtitle `Daily Study with Buddy`. The September 22 build-119 and analytics
observations remain recorded in [the growth plan](../APP_STORE_GROWTH.md).

## Minimum release path

1. Complete and verify the integrated source, then dispatch the iOS release
   workflow from its exact pushed ref with an explicit new marketing version.
   The archive overrides project version/build with the input version and
   `GITHUB_RUN_NUMBER`; leaving version empty retains project version 1.1.0.
   Archive-only requires both upload and review-candidate flags false. For a
   TestFlight upload before review-build selection, enable upload and keep
   `app_review_candidate=false`. That flag otherwise writes What to Test and
   selects a build in App Store Connect; it is not just a label.
2. Create the new editable App Store version separately. Existing scripts find
   versions but do not create them. Pin `APP_STORE_VERSION_ID` when selecting
   the verified build: `select-app-store-build.rb` does not filter by
   `APP_STORE_VERSION_STRING`. Keep archive and store version strings equal.
3. Use the individual metadata, App Info and screenshot scripts with a read-only
   dry run first, then explicit apply. The review-content workflow is still
   pinned to 1.1.0 and an old submission ID and uploads the existing iPad sets;
   it does not select the new four-image iPhone discovery set. Choose the correct
   screenshot display set for the 1242 × 2688 candidates instead of relying on
   the uploader's `APP_IPHONE_67` default.
4. Validate the final signed candidate against the deployed V120/API/share/AASA
   backend on physical iPhone and iPad (both families remain supported). Include
   cold/warm and installed/uninstalled Universal Links, account switching,
   starter save/cancel, active-draft preservation and review timing. Retain the
   production RevenueCat integration evidence for both Production and Sandbox
   events without a duplicate development integration, as required by
   [BILLING.md](../BILLING.md#production-setup).

The completed September 13 build-118 recording and submission evidence remain in
[the recording report](../app-review-video-2026-09-13.md) and the submitted review
notes snapshot. The reusable 1.1.0 templates still contain placeholders; this does
not invalidate the completed submission or establish a new Apple request for a
video. New release notes and device evidence must describe the actual new build.

## Integrated verification

Pending completion of conflict resolution and checks. No release, metadata
publication, submission or performance improvement is claimed by this document.
