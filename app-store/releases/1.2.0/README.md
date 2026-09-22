# BuddyStudy 1.2.0 candidate package

Prepared on 2026-09-23 from app source `4cb14b7a`, then archived and deployed
from `ee9f96cf9644acbcc2bab8535942d6697277d6a2`. The production backend is deployed;
the signed iOS **1.2.0 (121)** was uploaded, processed as **VALID** and selected
on the 1.2.0 version, then **submitted for App Review at 04:15 KST**. UI and
API readback confirm **WAITING_FOR_REVIEW**. This is not approval or public
release. Existing 1.1.0 evidence and reusable templates remain unchanged.

The release operator's September 23 App Store Connect UI/API checks confirmed
**1.2.0 waiting for review**, with **build 121 selected**, **manual
release**, and **existing ratings retained**. The published 1.1.0 (119) remains
available. After the owner reported completing the requested account actions,
the authenticated UI no longer showed the updated-agreement banner and Apple
accepted the submission. The agent did not accept legal terms. The Korean,
English (`en-US`) and Japanese version `promotionalText`, `description`,
`whatsNew` and `keywords` were saved to match
[`app-store/metadata/version-localizations.json`](../../metadata/version-localizations.json).
Support and marketing URLs already matched. App Info names/subtitles were also
saved for all three locales from
[`app-store/metadata/app-info-localizations.json`](../../metadata/app-info-localizations.json).
The new English review notes and three-language TestFlight notes are saved and
read back. Forty screenshots across 10 display sets are COMPLETE; see
[the upload record](screenshots/asc-upload-verification.json).
Submitted metadata is not yet published store copy. See the
[review submission](https://appstoreconnect.apple.com/apps/6774108938/distribution/reviewsubmissions/details/7bc80e37-ea6e-4e63-9f39-a504cc216bb0)
and [submission evidence](../../../docs/verification/2026-09-23-app-store-submission.md).

## Contents

- `review-notes.en-US.txt`: proposed English App Review notes.
- `review-notes.ko.txt`: Korean counterpart for review/preparation.
- `review-notes.asc.en-US.txt`: the credential-free text saved on the actual
  1.2.0 draft, replacing copied 1.1.0/118 evidence. It makes no unverified
  device/video claims; secure sign-in/contact fields remain separate.
- `testflight-build-localizations.json`: What to Test in the existing uploader's
  required `en-US`, `ko`, `ja` string schema, each at most 4,000 characters.
- `TESTFLIGHT_GUIDE.md`: manual test cases and evidence to retain.
- `submission-verification.json`: credential-free API receipt for the exact
  submission, single version item and selected build 121.
- `FEATURING_DRAFT.md`: English/Korean editorial pitch with launch/evidence
  fields still to finalize; no nomination has been submitted.

App Review has one notes field. The `asc.en-US` file is the saved text; the other
two files are working templates, not uploaded notes. Credentials are retained
only in the existing secure App Store Connect fields, never in these files.

## Evidence and remaining checks

| Field | Required action; current state |
| --- | --- |
| Source/build | Verified 1.2.0 (121), source `ee9f96cf`, archive run `35754933809`, local upload success, Apple VALID / APP_STORE_ELIGIBLE / non-exempt encryption false. IPA SHA-256 `a93f0f1f9f48d43b7ccd5bc53e21075f601c4fef1eeb263cfaf418c10203ab06`; see [signed-candidate evidence](../../../docs/verification/2026-09-23-ios-release-candidate.md). |
| App Store version ID | `5bb7c17c-3765-4b1b-b03c-00d8da654a2e`, 1.2.0 / WAITING_FOR_REVIEW / MANUAL, selected build 121; submission `7bc80e37-ea6e-4e63-9f39-a504cc216bb0`. |
| TestFlight availability | Read-only API check at 2026-09-23 02:15:38 KST: build 121 is IN_BETA_TESTING and already belongs to the existing one-tester internal group. No new invitation or group change is needed for that tester; verify the device uses the matching existing TestFlight account. |
| Review access | Existing secure account/password fields are present and preserved. Working login and required-term state still need candidate verification. |
| Review contact | Existing first/last name, email and phone fields are present and preserved. No values were invented or copied into this package. |
| Paid-feature access | Confirm how the reviewer can access Plus/Pro Voice Tutor and remaining voice time. A free account alone cannot verify voice; do not fabricate an entitlement or subscription. |
| Device evidence | Final signed-candidate iPhone checks remain unverified. The user explicitly approved proceeding without physical-iPad verification because no iPad is available; existing simulator evidence remains limited to rendering. |
| Video/attachment | No 1.2.0 filename, attachment ID or URL is available. If attaching evidence or answering a current Apple request, use the actual candidate recording and verify delivery/access. Do not relabel the 1.1.0 (118) video as 1.2.0. |
| Public share samples | Public QUESTION `156` returned correct ko/en/ja previews. No VOICE_TUTOR sample was present in the bounded live feed; its production sample remains pending. `{numericId}` in notes is a route pattern. |
| Backend release | Image source `ee9f96cf`, deploy `35755734992`, digest `sha256:636d835f6b1eaaa70ea8b0f465b627b4c9946987e2177c858729234aeb8665c9`; local GET checks passed. Source contains V120, but production Flyway history was not queried. See [production evidence](../../../docs/verification/2026-09-23-personalized-feed-production.md). |
| Advertising state | Record actual production placement switch/provider behavior. Notes describe supported behavior, not a claim that a particular ad will appear. |

The follow-up device inventory found the paired iPhone 16 Pro on iOS 26.6.2
(23G90), with BuddyStudy **1.1.0 (16)** and TestFlight **4.3.1** installed; it
does not yet establish installation of 1.2.0 (121). No physical iPad was listed.
iPhone Mirroring selected this same phone. After owner confirmation it advanced
to "iPhone in use; lock iPhone to connect", then timed out; a retry showed the
same in-use condition. No candidate interaction or reviewer login was completed.

Do not authenticate the permanent review account from a parallel CLI/device
just to inspect it: the current account-session policy revokes its other active
device sessions on successful login. Continue the review checks in the intended
candidate device session; see the [test guide](TESTFLIGHT_GUIDE.md).

The September 13 physical-iPhone recording was completed and submitted for
1.1.0 (118); see [its evidence](../../../docs/app-review-video-2026-09-13.md).
There is no newly established Apple video request for 1.2.0. The evidence
placeholders identify missing candidate information, not a claim of a new
rejection. If no video is needed or attached, remove video claims from the final
notes instead of inventing an artifact; retain truthful device evidence.

## Release checklist

- [x] Owner reported the requested account actions complete; the authenticated
  UI no longer showed the agreement blocker and Apple accepted the submission.
  The agent did not accept legal terms on the owner's behalf.
- [x] Deploy the integrated backend image, including V120 and share/AASA routes,
  through the module-scoped GitHub Actions/personal-deploy path. Preserve the
  production billing/translation fixes included in this source. Do not use SSH
  or add runtime health/smoke gates to Actions; record manual checks separately.
- [x] Produce the signed iOS candidate from the exact pushed ref with explicit
  `version=1.2.0`. Confirm production API configuration and non-demo AdMob mode.
  `.github/workflows/release.yml` uses its run number as build number; leaving
  version empty currently retains project 1.1.0.
  For archive-only work, explicitly set `upload_to_app_store_connect=false`,
  `app_review_candidate=false` and `admob_test_mode=false`. The current workflow
  also offers `publish_status=false` to suppress its release-status/Slack writes.
- [x] Upload and verify Apple VALID processing for 1.2.0 (121), retaining manual
  release. The local uploader reused the exact verified CI IPA. Prefer
  `app_review_candidate=false` for upload before deliberate build selection:
  that flag otherwise writes the default What to Test and selects an editable
  store version. It does not automatically select this directory's drafts.
- [ ] Run the [signed-candidate guide](TESTFLIGHT_GUIDE.md) on physical iPhone,
  including production interest persistence, account changes,
  voice, purchases, draft protection and installed/uninstalled Universal Links.
  Capture candidate-specific results; resolve any failures.
- [x] Record the user's release-specific exception for unavailable physical-iPad
  verification. Do not report this exception as a passed device test.
- [ ] Finish the required fields above. Verify the dedicated account works
  without an unavailable OTP/invitation step and preserve it during deletion
  tests. Confirm actual paid-feature access separately.
- [x] Save the three-language 1.2.0 version promotional text, description,
  What's New and keywords matching the source JSON; support/marketing URLs match.
- [x] Save and read back all three App Info localizations, the custom 1.2.0
  What to Test, and the current English review notes.
- [x] Upload and read back 40 screenshots across 10 exact display groups.
  The additional 1170 × 2532 candidates were rejected for the legacy-named
  slot and replaced with actual 1206 × 2622 captures; failed uploads were removed.
- [x] Select build 121 using the exact 1.2.0 version ID and read back the
  version/build relationship. Manual release and existing ratings are retained.
- [ ] Finish candidate-device and working-review-access checks. These were not
  established by the submission; presence of credential fields is not a login check.
- [x] Submit the prepared package on the user's instruction to proceed and
  record Apple acceptance separately from upload/selection. Exact version 1.2.0
  and build 121 are WAITING_FOR_REVIEW; see `submission-verification.json`.
- [ ] Obtain Apple approval and perform the later manual release. Submission
  does not establish approval, publication or chart improvement.

The integrated Debug verification already recorded 278 physical-iPhone tests,
five simulator source checks and 199 backend tests, including 62 with MySQL.
These results and the unretouched three-language fixture screenshots are useful
source/UI evidence, but not verification of a signed 1.2.0 production candidate.
See [the integration report](../../../docs/verification/2026-09-23-app-store-growth-integration.md)
and [screenshot limitations](../../growth-screenshots/README.md).

## Local validation and later upload routing

The following command validates the new TestFlight JSON locally and exits before
reading credentials or calling App Store Connect. Use Ruby 3.4 with a clean gem
environment if the system Ruby/shared gems conflict.

```sh
TESTFLIGHT_BUILD_LOCALIZATIONS_PATH=app-store/releases/1.2.0/testflight-build-localizations.json \
TESTFLIGHT_BUILD_NOTES_VALIDATE_ONLY=1 \
ruby scripts/update-testflight-build-notes.rb
```

For a later authorized TestFlight-note sync, retain that explicit localization
path and supply `APP_STORE_VERSION_STRING=1.2.0`, the exact processed
`APP_STORE_BUILD_NUMBER`, and credentials through the existing secure mechanism.
Without `APP_STORE_APPLY=1` the script performs a remote read-only dry run;
with it, the script writes and verifies the notes. Initial file preparation was
local; the later verified sync for 1.2.0 (121) is recorded above.

`update-app-store-review-notes.rb` is coupled to the old resolution reply and
resubmission guide, requires its known placeholders/evidence bundle, and does
not become a 1.2.0 package uploader by changing only the notes path. Do not run
it against these drafts with default 1.1.0 companion files. Use the actual
1.2.0 review detail in App Store Connect for the finalized notes, or deliberately
prepare a matching new package if an actual resolution reply is needed.
