# BuddyStudy 1.2.0 candidate package

Prepared on 2026-09-23 from source `4cb14b7a`. This directory contains local
release-preparation drafts; it does not establish an upload, deployment,
submission or approval. The existing 1.1.0 evidence and reusable templates are
unchanged.

The release operator's September 23 App Store Connect UI check confirmed a
**1.2.0 draft in Prepare for Submission**, with **no build selected**, **manual
release**, and **existing ratings retained**. The published 1.1.0 (119) remains
available. The updated Apple agreement banner remains; draft creation did not
establish permission to submit. The operator subsequently saved the Korean,
English (`en-US`) and Japanese version `promotionalText`, `description`,
`whatsNew` and `keywords` to match
[`app-store/metadata/version-localizations.json`](../../metadata/version-localizations.json).
Support and marketing URLs already matched. App Info names/subtitles were also
saved for all three locales from
[`app-store/metadata/app-info-localizations.json`](../../metadata/app-info-localizations.json).
Screenshots and review notes remain unchanged as of this update. Saved draft
metadata is not published store copy. See the
[1.2.0 draft](https://appstoreconnect.apple.com/apps/6774108938/distribution/ios/version/inflight).

## Contents

- `review-notes.en-US.txt`: proposed English App Review notes.
- `review-notes.ko.txt`: Korean counterpart for review/preparation.
- `testflight-build-localizations.json`: What to Test in the existing uploader's
  required `en-US`, `ko`, `ja` string schema, each at most 4,000 characters.
- `TESTFLIGHT_GUIDE.md`: manual test cases and evidence to retain.

App Review has one notes field, not separate locale resources. Choose one final
language; do not concatenate the two drafts. Each draft is kept within the
repository's 4,000 UTF-8-byte limit before real evidence/credentials are inserted.
Recheck the rendered size afterward. Remove the draft instruction only when its
prerequisites are satisfied. Do not commit credentials into either file.

## Required fields still to be supplied

| Field | Required action; current state |
| --- | --- |
| Source/build | Record final pushed source SHA, release run, 1.2.0 build number, IPA/archive checksum and Apple VALID processing evidence. Signed candidate pending. |
| App Store version ID | Read and record the exact 1.2.0 resource ID; the UI's `inflight` URL is not an API version ID. No build selected yet. |
| Review access | Confirm a durable working account and required-term state. Put account name/password only in secure App Store Connect sign-in fields; the draft placeholders are not credentials. |
| Review contact | Confirm the real review contact name, email and phone in App Store Connect; no values invented here. |
| Paid-feature access | Confirm how the reviewer can access Plus/Pro Voice Tutor and remaining voice time. A free account alone cannot verify voice; do not fabricate an entitlement or subscription. |
| Device evidence | Record exact physical iPhone/iPad models, OS versions, candidate version/build and actual manual results. Final signed-candidate and physical-iPad checks are pending. |
| Video/attachment | No 1.2.0 filename, attachment ID or URL is available. If attaching evidence or answering a current Apple request, use the actual candidate recording and verify delivery/access. Do not relabel the 1.1.0 (118) video as 1.2.0. |
| Public share samples | Record actual authorized production public QUESTION and VOICE_TUTOR IDs/URLs after deployment; `{numericId}` in notes is a route pattern, not a working sample. |
| Backend release | Record V120 migration and compatible topic/feed/share/AASA deployment run, source SHA and digest, then manual production results. Pending. |
| Advertising state | Record actual production placement switch/provider behavior. Notes describe supported behavior, not a claim that a particular ad will appear. |

The September 13 physical-iPhone recording was completed and submitted for
1.1.0 (118); see [its evidence](../../../docs/app-review-video-2026-09-13.md).
There is no newly established Apple video request for 1.2.0. The evidence
placeholders identify missing candidate information, not a claim of a new
rejection. If no video is needed or attached, remove video claims from the final
notes instead of inventing an artifact; retain truthful device evidence.

## Release checklist

- [ ] Account owner accepts the updated Apple agreement and confirms the
  App Store Connect submission restriction is cleared. The agent has not
  accepted legal terms on the owner's behalf.
- [ ] Deploy the integrated backend, including V120 and share/AASA routes,
  through the module-scoped GitHub Actions/personal-deploy path. Preserve the
  production billing/translation fixes included in this source. Do not use SSH
  or add runtime health/smoke gates to Actions; record manual checks separately.
- [ ] Produce the signed iOS candidate from the exact pushed ref with explicit
  `version=1.2.0`. Confirm production API configuration and non-demo AdMob mode.
  `.github/workflows/release.yml` uses its run number as build number; leaving
  version empty currently retains project 1.1.0.
  For archive-only work, explicitly set `upload_to_app_store_connect=false`,
  `app_review_candidate=false` and `admob_test_mode=false`. The current workflow
  also offers `publish_status=false` to suppress its release-status/Slack writes.
- [ ] Upload/verify the intended build and keep manual release. Prefer
  `app_review_candidate=false` for upload before deliberate build selection:
  that flag otherwise writes the default What to Test and selects an editable
  store version. It does not automatically select this directory's drafts.
- [ ] Run the [signed-candidate guide](TESTFLIGHT_GUIDE.md) on physical iPhone
  **and iPad**, including production interest persistence, account changes,
  voice, purchases, draft protection and installed/uninstalled Universal Links.
  Capture candidate-specific results; resolve any failures.
- [ ] Finish the required fields above. Verify the dedicated account works
  without an unavailable OTP/invitation step and preserve it during deletion
  tests. Confirm actual paid-feature access separately.
- [x] Save the three-language 1.2.0 version promotional text, description,
  What's New and keywords matching the source JSON; support/marketing URLs match.
- [ ] Complete the intended App Info names/subtitles, this candidate's What to
  Test, finalized review notes and verified screenshot display sets. The new
  1242 × 2688 images require their matching 6.5-inch set; default uploader
  `APP_IPHONE_67` and old iPad assets are not automatic substitutes.
- [ ] Pin the verified 1.2.0 `APP_STORE_VERSION_ID` when selecting the exact
  build. Read back version string, build number, manual release mode, ratings
  retention, locale copy, screenshot sets and review access before submission.
- [ ] Submit only the complete candidate package; record Apple confirmation
  separately from build upload/selection. Approval and later manual release
  remain distinct operations.

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
with it, the script writes and verifies the notes. No external sync was run
while preparing this directory.

`update-app-store-review-notes.rb` is coupled to the old resolution reply and
resubmission guide, requires its known placeholders/evidence bundle, and does
not become a 1.2.0 package uploader by changing only the notes path. Do not run
it against these drafts with default 1.1.0 companion files. Use the actual
1.2.0 review detail in App Store Connect for the finalized notes, or deliberately
prepare a matching new package if an actual resolution reply is needed.
