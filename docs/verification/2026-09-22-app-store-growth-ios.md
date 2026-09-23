# iOS discovery and App Store growth verification — 2026-09-22

This records local implementation evidence. It does not certify production
deployment, App Review readiness, improved conversion, or chart placement.

> Follow-up (2026-09-23): this report records the pre-integration `bf373244`
> branch. Integration onto newer mainline `1d57ac12` uses topic migration `V120`
> instead of the historical `V99` referenced below. Its build/device results must
> be recorded separately in the [integration report](2026-09-23-app-store-growth-integration.md);
> the counts and observations below are unchanged historical evidence.

## Implemented scope

- Account-owned interest management and direct topic follow/unfollow; recommended,
  latest, most-viewed and most-liked discovery with an optional following scope.
- A refreshed interest list before editing/toggling, failed-refresh write barriers,
  session/request guards and feed invalidation, preserving the active answer draft.
- Public-question ShareLink and strict HTTPS routing with production-source checks;
  a delayed feed fetch cannot reopen a question after navigation changes.
- Explicit-save starter topics for an empty My Studies screen, without generating
  questions or consuming question quota.
- StoreKit review attempts after sustained learning, deferred until idle Home,
  with completion identity, cooldown, logout and expiration protections.
- Bounded analytics events, localized next-release metadata and actual UI screenshot
  candidates emphasizing learning feedback, topic progress and interest discovery.

## Build and physical iPhone checks

Required generic build, using only the iOS scheme:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
```

Result: **BUILD SUCCEEDED**. The only reported warning was App Intents metadata
extraction being skipped because the app does not depend on AppIntents.framework.

Physical test device: **Min iPhone, iPhone 16 Pro, iOS 26.6.2 (23G90)**. The local
Debug app reports **1.1.0 (16)**; this is not an uploaded App Store archive. The
selected `StudyMateiOSTests` suites were built, installed and executed on this
connected device, not a simulator or macOS test target:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug \
  -destination 'platform=iOS,id=<connected-iPhone-UDID>' \
  -derivedDataPath build/iOSDeviceDerivedData -allowProvisioningUpdates \
  DEVELOPMENT_TEAM=4CL25TC734 \
  -only-testing:StudyMateiOSTests/StudyReviewPromptTests \
  -only-testing:StudyMateiOSTests/QuestionGenerationFlowTests test
```

Result: **96 tests passed, zero failures**: 92 question-generation/session/feed
regressions and four review-policy tests. Coverage includes subscription wire
contracts and normalization, fresh-list preservation, failed-refresh barriers,
concurrent writes, delayed account-switch/logout responses, draft preservation,
strict public-link parsing and production provenance, and review completion,
cooldown, persistence and exactly-once consumption. Tests isolate URLProtocol
handlers per client so delayed bootstrap traffic cannot contaminate later tests.
Remote service responses are mocked in these iPhone tests; they do not prove
production API availability or the operating system's Universal Link association.

Local command logs: `/tmp/buddystudy-goal-ios-build.log` and
`/tmp/buddystudy-goal-device-tests.log`. These machine-local logs are not checked in.
Backend SQL/HTTP evidence is recorded separately in
[the backend verification report](2026-09-22-personalized-feed-backend.md): 40
focused feed/subscription tests and 20 actual MySQL/HTTP tests. Eight additional
focused share/referral tests passed, as recorded in the growth plan.

## Visual and metadata checks

The iOS 26.0 6.5-inch simulator build succeeded. Twelve actual 1242 × 2688 PNGs
were captured in Korean, English and Japanese and visually inspected. The result
screen shows question, answer and feedback; statistics show topic/study-specific
progress; Home exposes sort/scope/interests; the interest sheet shows suggestions,
selected topics and explicit save/cancel actions. All records and counts are
synthetic screenshot fixtures. Initial inspection found and corrected a reset of
the result fixture's navigation, system-language relative timestamps in localized
feeds, and a truncated English statistics summary label.

See [the candidate images and capture instructions](../../app-store/growth-screenshots/README.md).
No existing screenshot assets were overwritten. Sort choices and local interest
editing/cancel were also inspected in the running simulator. The share landing's
actual renderer was inspected at a 390 × 844 browser viewport in all three locales,
plus Korean desktop; no overlapping content was observed.

The two offline metadata validators passed. Keywords use 95/91/92 UTF-8 bytes for
Korean/English/Japanese; names and subtitles satisfy their 30-character limits.
`bash scripts/verify-app-store-review-readiness.sh` passed **source checks only**:
it still reports `PRE-RECORDING TEMPLATE`, missing review-video/device evidence
in the existing release template and a manual RevenueCat webhook verification
gate. It is not a release-ready result. `git diff --check` passed.

## External release work still required

Read-only App Store Connect inspection found BuddyStudy app **6774108938** at
**1.1.0 (119), READY_FOR_DISTRIBUTION**. The updated Apple Developer agreement
awaits the account owner's acceptance. No agreement was accepted, production
backend deployed, new App Store version created, screenshot/metadata uploaded,
or app submitted by this change.

1. The account owner accepts the pending Apple Developer agreement.
2. Deploy V99 and the subscription/feed/share/AASA backend through the existing
   module-specific GitHub Actions release path. Do not add runtime health gates
   to Actions or connect directly to production with SSH.
3. Prepare the next iOS archive with a new release version/build; validate the
   deployed API, cold/warm and installed/uninstalled share-link journeys on a
   physical iPhone, logout/account switches, starter save/cancel, draft retention,
   and review timing around sheets/backgrounding. StoreKit controls whether a
   review prompt appears, so an attempted request is not a submitted rating.
4. Complete the remaining App Review evidence, including physical iPad checks
   when supported, then attach the matching metadata/screenshots to the next
   editable version and submit through App Store Connect.
5. Measure acquisition and returning use after publication. The inspected
   baseline is too small to establish improvement; see
   [the growth plan](../APP_STORE_GROWTH.md) for source links and measurement rules.
