# App Store growth integration — 2026-09-23

## Source boundary

The discovery change from `bf373244` was integrated as `18eb97df` onto the newer
`origin/main` base `1d57ac12` in an isolated worktree. The original checkout and
its user artifacts are preserved. The September 22 build, device tests and
screenshots describe the earlier branch; they are not validation of this merge.
Record the integrated build/test results separately below before release.

Production includes fixes beyond that mainline base. Read-only inspection found
[backend deployment 35414148040](https://github.com/ghkdqhrbals/personal-deploy/actions/runs/35414148040)
to be the latest successful backend deployment, completed September 19. Its
logs report runtime `jvm` and the actual Docker pull digest below. The deployment
workflow commit `9f49905e6ea3a1b7a788d4d3287874d51b592d26` belongs to
`personal-deploy`; it is not the application source SHA.

- Application source: `bfd1c9a81b7e209dafdc030c8e3df1f5571a18f2`.
- Image: `ghcr.io/ghkdqhrbals/buddystudy-backend:bfd1c9a81b7e209dafdc030c8e3df1f5571a18f2-jvm`.
- Digest: `sha256:b52bdc96caa59342429ff6a7176ec92cbcbd43e42dcdb5e6d8389bf86ced1df7`.
- [Successful image build 35336264572](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/35336264572)
  identifies that source SHA on `fix/billing-alert-repeat-20260918` and pushes
  the same digest. The [billing rollout record](../observability/billing-alert-repeat-2026-09-18.md#deployment)
  independently records the matching build, deployment and digest.

That branch contains six commits beyond `1d57ac12`: `e9082e5c`, `8a645a54`,
`9ed5158b`, `ad9abee5`, `bfd1c9a8`, and `0d67c419`. The first five are included
in the deployed image's source; the sixth adds only rollout/metrics documentation.
Merge `24fb3b68` therefore preserves the production branch at `0d67c419` so the
next deployment does not drop its translation-provider checks, Apple purchase
ownership validation, or billing-alert fixes. This establishes the last deployed
backend baseline, not proof that this branch's Swift changes are already in the
distributed App Store binary. No production SSH access was used.

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

The integrated `StudyMateiOS` generic iOS build and iOS simulator build passed.
Xcode project object identifiers for the new review files were reassigned to
avoid a collision with existing Voice Tutor files. The only build warning was
App Intents metadata extraction being skipped because no AppIntents framework
is linked. Physical-device, simulator source-check and screenshot results are recorded
below; these local checks do not establish production API or store availability.

The imported purchase refresh now checks the captured account, backend and
request identity before token bootstrap and before identity recovery, as well as
before applying the final response. Delayed-401/account-switch and superseded
request regressions were added. Existing test fixtures also needed the current
authorized-request identity parameter and an explicit Sendable reference for
Swift 6 actor crossing; these changes do not relax production account guards.

Metadata validation passed for Korean, English and Japanese. Keywords use
95/91/92 UTF-8 bytes respectively. The local readiness script passed with Ruby
3.4.2 and a clean gem environment:

```sh
env -u GEM_HOME -u GEM_PATH -u RUBYOPT -u RUBYLIB \
  PATH="/opt/homebrew/Cellar/ruby/3.4.2/bin:$PATH" \
  bash scripts/verify-app-store-review-readiness.sh
```

The default Ruby 2.6 cannot run the existing Array#tally check. The reusable
recording-template notice and RevenueCat/AdMob operational evidence reminders
are not proof of a new rejection or an outstanding September 13 recording.
Six public metadata links returned HTTP 200; each privacy document includes its
advertising anchor. Current AdMob behavior is retained; the original checkout's
separate removal commit was not silently folded into this integration.

No release, metadata publication, submission or performance improvement is
claimed by this document.


## Final iOS checks

`StudyMateiOS` generic iOS Debug build passed after the final date-localization
change. The physical **Min iPhone (iPhone 16 Pro), iOS 26.6.2 (23G90)** then ran
**278 tests with zero failures**. The local Debug bundle remains **1.1.0 (16)**;
this is not a signed release archive or an App Store upload.

| Physical iPhone suite | Passed |
| --- | ---: |
| QuestionGenerationFlowTests | 117 |
| StudyReviewPromptTests | 4 |
| VoiceCommonRecordTests | 56 |
| StoreKitPurchaseAccountPolicyTests | 15 |
| BillingLocalizationTests | 13 |
| AppControlPolicyTests (runtime checks) | 14 |
| NativeAdvertisementPolicyTests | 14 |
| CommunityQuestionActionPolicyTests | 8 |
| CommunityFeedBlockingTests | 3 |
| StudyLearningRecordsTests | 34 |

Five existing AppControlPolicyTests inspect Swift source using `#filePath`.
An initial combined run demonstrated that an iPhone cannot read the development
computer's source paths. Those five were run separately on the **iOS 26.0
simulator**, all passing; they were not disabled or replaced by macOS tests:

- testRevenueCatPurchaseConfirmsInvoiceOrWaitsForWebhook
- testSubscriptionCancellationAndRefundUseNativeStoreKitFlows
- testMembershipScreenUsesCachedProductsBeforeProviderReconciliation
- testNativeSubscriptionManagementIsNotBlockedByRetiredProducts
- testPurchaseActionIsResolvedBeforeEntitlementReplayAndCheckoutCreation

The final iPhone run selected the ten suites above and used `-skip-testing` for
only those five source readers. The simulator selected each of those five with
`-only-testing`. Both final commands returned `TEST SUCCEEDED`. Device selection
was `platform=iOS,id=<connected-iPhone-UDID>`, with
`-allowProvisioningUpdates DEVELOPMENT_TEAM=4CL25TC734`. No macOS app scheme or
macOS tests were run. Backend responses in these tests are mocked; production
Universal Links, deployed subscriptions, and signed-candidate device verification
remain release checks.

Final machine-local logs (not checked in):

- `/tmp/buddystudy-integrated-ios-final-build.log`
- `/tmp/buddystudy-integrated-device-tests-pass.log`
- `/tmp/buddystudy-integrated-source-tests.log`

The backend separately passed **199 tests**, including **62 on actual MySQL**;
see [the backend integration report](2026-09-23-personalized-feed-backend-integration.md).

## Screenshot integration fixes

The integrated DEBUG screenshot fixture now supplies an in-memory synthetic
profile and identity-scoped learning-history loader. It does not store real
credentials or call authentication/history endpoints. This corrects the login
placeholder that appeared when the older fixture met newer record ownership
checks. Production identity guards remain intact. The real learning-history date
formatter now uses the selected app language, fixing Korean dates in English
and Japanese UI independently of the simulator system language.

The screenshot candidates are actual, unretouched 1242 × 2688 iOS captures with
illustrative data. The four views in all three locales were recaptured, and the
learning-result images were captured again after date localization. The earlier
September 22 images remain available in commit `18eb97df`. See the
[screenshot README](../../app-store/growth-screenshots/README.md) for visual review
and release limitations. They have not been uploaded to App Store Connect.
