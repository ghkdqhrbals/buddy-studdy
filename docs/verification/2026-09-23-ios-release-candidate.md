# iOS 1.2.0 (121) signed artifact — 2026-09-23

The archive-only [Release iOS App run 35754933809](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/35754933809)
completed **successfully** on its first attempt. It ran from
`codex/app-store-growth-integration` at the expected source
`ee9f96cf9644acbcc2bab8535942d6697277d6a2`. The run started at
2026-09-23 01:33:47 KST and reached its terminal state at 01:48:53 KST
(2026-09-22 16:33:47–16:48:53 UTC).

This report establishes a signed artifact, its checks and the separate local
transport result recorded below. This workflow did
**not** upload a build to App Store Connect, distribute it to testers, select an
App Review build, submit for review or publish release-status notifications.
The later operator upload of this same IPA is a separate event; transport and
Apple-processing evidence remain distinct.

## Inputs and completed jobs

The completed plan-job log confirms the actual dispatch values:

| Input | Value |
| --- | --- |
| `version` | `1.2.0` |
| `upload_to_app_store_connect` | `false` |
| `app_review_candidate` | `false` |
| `admob_test_mode` | `false` |
| `publish_status` | `false` |

The run number supplies build **121**. GitHub's terminal job results are:

| Job | Result |
| --- | --- |
| Plan iOS Release | Success |
| Build Signed iOS IPA | Success |
| Notify iOS Release Planned | Skipped |
| Upload iOS IPA to TestFlight | Skipped |
| Distribute AdMob Test Build Internally | Skipped |
| Label TestFlight App Review Candidate | Skipped |

The plan's TestFlight-review-note validation step was also skipped because
`app_review_candidate=false`. The successful build job includes the generic
iOS Debug build, archive, archive configuration/entitlement verification, IPA
export, exported-IPA verification, metadata creation and artifact upload.
Uploading a GitHub artifact is distinct from uploading to App Store Connect.

## Artifact identity and local verification

- [GitHub artifact 10708026985](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/35754933809/artifacts/10708026985):
  `BuddyStudy-iOS-1.2.0-121`.
- GitHub-reported artifact size: **30,714,746 bytes**.
- GitHub-reported artifact digest:
  `sha256:e09463c237ccc9957aeb874a95b11baaac1c69b1ab7e44f13c3477afe65b25bb`.
- Artifact expiration reported by GitHub: `2026-10-06T16:48:40Z`.
- Downloaded with `gh run download` to
  `/tmp/buddystudy-ios-candidate-121/`.
- IPA: `/tmp/buddystudy-ios-candidate-121/StudyMate.ipa`, **30,853,406 bytes**.
- Independently computed IPA SHA-256:
  `a93f0f1f9f48d43b7ccd5bc53e21075f601c4fef1eeb263cfaf418c10203ab06`.
- Companion file: `release-metadata.json`, **236 bytes**.

The GitHub artifact digest describes its container; the independently computed
IPA hash identifies the exact binary for a later upload. They are different
objects and are not expected to match.

The downloaded metadata was parsed and asserted to contain:

```json
{
  "version": "1.2.0",
  "build": "121",
  "sourceSha": "ee9f96cf9644acbcc2bab8535942d6697277d6a2",
  "runId": "35754933809",
  "backendBaseURL": "https://api.ghkdqhrbals.org",
  "appReviewCandidate": false,
  "admobTestMode": false
}
```

The actual `Payload/StudyMate.app/Info.plist` independently confirms the same
version/build and production API, bundle ID
`io.github.ghkdqhrbals.StudyMate`, minimum iOS **17.0**, and supported device
families **1 and 2** (iPhone and iPad). Microphone and camera purpose strings
are present and nonempty; this does not assert that the audio-only app requests
camera permission.

CI's archive and exported-IPA verification steps both passed. They check the
production API, expected AdMob identifiers, strict code signing and required
entitlements. The downloaded IPA was additionally extracted locally and checked
with `codesign --verify --deep --strict --verbose=2`; it was valid on disk and
satisfied its designated requirement. Allowlisted signed entitlement checks
also passed:

| Signed entitlement | Verified value |
| --- | --- |
| `aps-environment` | `production` |
| `com.apple.developer.associated-domains` | Includes exact `applinks:api.ghkdqhrbals.org` |
| iCloud container identifier | Includes `iCloud.io.github.ghkdqhrbals.StudyMate` |
| iCloud container environment | `Production` |
| iCloud services | Includes `CloudKit` |
| Sign in with Apple | Includes `Default` |
| `get-task-allow` | `false` |

No credential, private key or signing secret is reproduced here. The GitHub
artifact contains the IPA and metadata, not the original `.xcarchive`; the
archive-specific result is the successful CI verification step.

## Separate local upload

After the archive-only run, the release operator uploaded the **same** verified
`/tmp/buddystudy-ios-candidate-121/StudyMate.ipa` using local `altool`. The upload
log independently confirms **UPLOAD SUCCEEDED with no errors** at
**2026-09-23 01:52:22 KST**:

- Delivery UUID: `d7e5c8a1-a45a-4147-aca6-d2c912193d50`.
- Transferred bytes: **30,853,406**, matching the verified IPA above.
- Upload source: the same IPA path and previously verified SHA-256; no second
  archive or substitute binary was used.
- Machine-local transport log: `/tmp/buddystudy-ios-121-upload.log`.

This is a successful local upload transport, not a change to the workflow's
skipped TestFlight job. Transport alone does not establish Apple processing;
the subsequent processing checks are recorded below.

## Apple processing and exact build readback

The separate read-only processing wait recorded `UPLOAD_PROCESSING` followed by
`VALID` for the exact **1.2.0 (121)** build. Its custom-What-to-Test run was a dry
run at that checkpoint; the log explicitly did not apply notes.
Machine-local processing log: `/tmp/buddystudy-ios-121-processing.log`.

An independent read-only App Store Connect API query at **2026-09-23 01:58:43
KST** selected exactly one iOS build matching app, marketing version and build
number, then read that specific Build resource:

| Field | Verified value |
| --- | --- |
| App ID | `6774108938` |
| Build ID | `d7e5c8a1-a45a-4147-aca6-d2c912193d50` |
| Marketing version / build | `1.2.0` / `121` |
| `processingState` | `VALID` |
| `usesNonExemptEncryption` | `false` |
| `expired` | `false` |
| `buildAudienceType` | `APP_STORE_ELIGIBLE` |
| `uploadedDate` | `2026-09-22T09:53:15-07:00` |
| `expirationDate` | `2026-12-21T08:53:15-08:00` |

The allowlisted response is retained locally at
`/tmp/buddystudy-ios-121-build-readback.json`. No API write was made by this
independent query. An initial request used an unevaluated shell-default
expression as the issuer and returned 401; parsing the configured UUID correctly
resolved that local setup error without changing or generating credentials.

After the processing dry run, the release operator separately completed and
verified the custom TestFlight What to Test update for `en-US`, `ko` and `ja`
from `app-store/releases/1.2.0/testflight-build-localizations.json`. This later
operator action is distinct from the archive workflow's skipped default-notes
job.

The operator then selected build **121** using the explicit 1.2.0 App Store
version ID `5bb7c17c-3765-4b1b-b03c-00d8da654a2e`. The completed selection
command confirmed target version `1.2.0`, target state `VALID` and
`Selected build: 121` after reading back the version/build relationship.
This is a separately verified build selection, not review submission. The
operator subsequently saved and read back the current App Review notes and
40 COMPLETE screenshots; the [App Store Connect draft report](2026-09-23-app-store-connect-draft.md)
records those separate draft-content checks.

## Scope of completion

Archive, local transport, the exact `VALID` Build resource, custom TestFlight
notes and selection on the explicit 1.2.0 App Store version are verified.
`APP_STORE_ELIGIBLE` is a build-audience classification, not an App Review
approval. No review submission, approval, live backend compatibility or
physical-device behavior of the signed candidate is established here. Keep
subsequent review and release results separate from these archive, transport,
processing and build-selection checks.

The [1.2.0 release package](../../app-store/releases/1.2.0/README.md) tracks
backend rollout, owner agreement acceptance, candidate-specific iPhone/iPad
manual checks, working review access and final review evidence. The
[integrated source checks](2026-09-23-app-store-growth-integration.md) and
simulator screenshot fixtures remain distinct evidence; neither substitutes
for those release gates.
