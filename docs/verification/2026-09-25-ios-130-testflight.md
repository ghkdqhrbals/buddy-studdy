# iOS 1.3.0 integrated TestFlight verification

## Scope and source

The user requested a 1.3.0 TestFlight build for personal testing. Integration
uses latest remote main `810db163cc5130f3b9178c36ba257ab7ce34df01` on branch
`codex/ios-130-testflight`. The existing 1.2.0 (122) release is preserved, including
Voice Tutor, production AdMob 13.8.0/UMP 3.1.0, billing, discovery and usability
changes. The original working folder and unrelated IDE/scheme/artifacts remain
untouched.

New behavior is documented in [the feature contract](../FOLLOW_UP_QUESTIONS.md).
The old feature branch's test results are explicitly historical and must not be
reported as validation of this integrated source. New MySQL migrations are
V121/V122; H2 equivalents are V69/V70.

## Verification and distribution

The required generic iOS Debug build passed on the integrated source with the
unchanged Google Mobile Ads 13.8.0 / UMP 3.1.0 package pins. The local Xcode is
26.0.1 (17A400); the signed release workflow retains its Xcode 26.2+ requirement.
The exact command was:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug \
  -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData \
  CODE_SIGNING_ALLOWED=NO build
```

Log: `/tmp/buddystudy130-integrated-ios-build-final.log` (`BUILD SUCCEEDED`).
Initial attempts uncovered two integration compile errors (account ID type and
a newly required conversation-view argument), both corrected before this pass.
An initial package-resolution disk-space failure was resolved by removing only
rebuildable local Xcode outputs and reusing an existing package cache via APFS
clones. Source files, test-result logs and user app data were retained.

The isolated Offline QA builder now accepts follow-up/custom fixtures. Its
prepare-only configuration/signing checks and shell syntax check passed. The
three-locale TestFlight notes validator passed; existing distribution/notes
script tests passed 31 tests (109 assertions). Minitest was installed only in
`/tmp/buddystudy130-ruby-gems` because the system Ruby lacked it.

The selected simulator suites passed **425 tests, zero failures** on iPhone 16
Pro / iOS 26.0 (`B4AC92AC-FF55-495C-B4C3-079FAE6A880A`): ArchitecturePolicy 152,
QuestionGenerationFlow 142, StudyLearningRecords 34, VoiceCommonRecord 60 and
VoiceTutorAnswerDraft 37. Log:
`/tmp/buddystudy130-integrated-ios-tests-final.log` (`TEST SUCCEEDED`).

The first run found four pre-existing source-policy assertions that did not
match current 1.2 source (local UUID fences, an adjacent voice policy, exact
access-endpoint matching, and the membership component declaration). They were
scoped to the intended contracts rather than changing 1.2 production behavior.
A new draft-origin test initially used two settings overridden by the runner's
launch environment; the fixture now sets/restores its effective synthetic
origin and asserts the actual origins differ. Its injected HTTP client remains
fail-closed. All five suites then passed.

Post-release investigation found that the hosted simulator's StoreKit discount
test had initialized the live RevenueCat observer and emitted two anonymous
local-test webhook receipts. The test assertions above passed, but SDK isolation
was incomplete. See [the incident and isolation correction](2026-09-25-revenuecat-local-storekit.md).

### Physical iPhone

The separately signed Offline QA app was installed and launched on **Min iPhone,
iPhone 16 Pro, iOS 26.6.2 (23G90)** over the paired wireless connection. Xcode's
Devices screenshot action captured the real display. This is Debug fixture
rendering/smoke evidence, not a live-account API, purchase, advertising delivery,
or TestFlight-binary interaction test.

- Custom question and answer render as completed private content with the
  `사용자 생성 질문` tag and no numeric score or grading action.
- Original and follow-up content render together, with the original grade
  retained and the first follow-up labeled `꼬리질문 1/2`.
- Physical inspection found the new node-history custom row lacked the tag in
  its empty score position. A five-line presentation fix adds it. The final
  generic iOS Offline QA build passed after that fix and a new physical capture
  verifies the tag in both the question section and node-history row. The 425
  tests above precede only this final display-only change.
- The installed production BuddyStudy build **122** remained installed and
  unchanged; the QA app uses the distinct `.OfflineQA` bundle identifier.

Final build log: `/tmp/buddystudy130-integrated-offline-qa-final.log`.
Final isolated app:
`/var/folders/5q/l5nc5zhd1wq4w7m65vdr6wd40000gn/T/buddystudy-offline-qa.mqfmFu/QA/BuddyStudyOfflineQA.app`.

Evidence: [custom tag on the device](assets/ios-1.3.0/device-custom-question-ko.png)
and [follow-up conversation on the device](assets/ios-1.3.0/device-follow-up-ko.png).
Interactive composer/save/network flows are covered by automated regressions;
this session does not claim a physical tap-through of those flows. iPhone
Mirroring could not attach while the device was unlocked/in use; Xcode capture
provided direct rendering evidence without replacing the installed TestFlight app.

### Backend

[Backend verification](2026-09-25-ios-130-backend.md): **221 tests passed**,
including real MySQL V120→V122 upgrade compatibility and existing voice rows.
Backend commit `efe8cf6cf5d33746c747f1f4b74f35f1f9df05b0` was pushed separately and
[Build Backend Image run 36040541276](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/36040541276)
was dispatched with JVM runtime and the module-scoped personal-deploy handoff.
Both the image workflow and
[personal-deploy run 36041292362](https://github.com/ghkdqhrbals/personal-deploy/actions/runs/36041292362)
completed successfully. At **2026-09-25 03:29:07 KST**, the Swarm rollout reported
`completed`, `1/1` replicas and the expected image task running. Source SHA and
image tag match the backend commit; the published and pulled manifest digest is
`sha256:87cad0d372266c718a54d0bb5f8190dce9fd9923dbb649148671c965f8269a0a`.
No SSH or runtime health/smoke endpoint checks were performed. Backend files in
the iOS release commit `dbe1b02e77b94f5bac193a09955d6b077cbb999d` exactly match this
deployed backend commit.

### Signed TestFlight candidate

[Release iOS App run 36041493343](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/36041493343)
completed successfully for **1.3.0 (123)** from
`dbe1b02e77b94f5bac193a09955d6b077cbb999d`. Apple accepted the upload at
**2026-09-25 03:46:16 KST** with `UPLOAD SUCCEEDED with no errors`.
App Store Connect then displayed the exact version/build as processing.

The downloaded artifact digest matches GitHub's artifact digest:
`sha256:32f61d2114d03862df411fd0178768dcedb0c54fbb07fc5e906249d0fc45d8c6`.
IPA SHA256 is `c2d37fdbc383b284f24647e73b92e3c5ea6396ba41b485a84190b1fddec52dfb`.
Independent inspection passed all 30 checks: exact version/bundle/source,
production API, production non-demo AdMob configuration, embedded Google Mobile
Ads 13.8.0 and UMP 3.1.0, matching Firebase configuration, RevenueCat public key,
absence of Offline QA fixtures, and distribution signing/production entitlements.
See the [machine-readable artifact receipt](../../app-store/releases/1.3.0/testflight-123-release-verification.json).

[Internal distribution run 36043671798](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/36043671798)
completed successfully. At **2026-09-25 03:48:48 KST**, it verified exact build
**1.3.0 (123)** as `VALID`, `IN_BETA_TESTING`, audience `APP_STORE_ELIGIBLE`,
already connected to the existing internal group `tester`
(`5f6797bb-38a0-400b-8269-74aa6f5f3bec`). The result was `current`; no new tester
or external group was added. App Store Connect independently displayed the
same build and internal group with its existing one tester.

[The exact build page](https://appstoreconnect.apple.com/teams/889a8253-618e-4e3a-84ff-73ac167fd81e/apps/6774108938/testflight/ios/8f950eb5-5832-4462-b2f4-304fe3ea3e90)
now has the source-controlled English, Korean and Japanese What to Test notes.
Each locale's save completed in the authenticated UI; Korean content was read
back after changing languages. TestFlight availability is confirmed; this is
not a claim that the user installed or interacted with build 123 on the phone.

Distribution is TestFlight only. The release dispatch used
`version=1.3.0`, `upload_to_app_store_connect=true`,
`app_review_candidate=false`, `admob_test_mode=false`, `publish_status=false`.
No App Review submission or public release is authorized by this task.
