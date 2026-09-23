# App Store Connect 1.2.0 draft — September 23, 2026

The next iOS version was created and its localized copy saved in App Store
Connect. These are draft edits, not a release, approval or chart result.

This report preserves the preparation checkpoint. The later 04:15 KST
submission is now **WAITING_FOR_REVIEW**; see the
[submission record](2026-09-23-app-store-submission.md) for current state and
the user's physical-iPad exception.

| Resource | Verified value |
| --- | --- |
| App | 6774108938 / io.github.ghkdqhrbals.StudyMate |
| Draft version | 1.2.0 / PREPARE_FOR_SUBMISSION |
| Version ID | 5bb7c17c-3765-4b1b-b03c-00d8da654a2e |
| Editable App Info ID | 898c70a3-7824-4d19-9262-6d94d37a5fab |
| Release mode | MANUAL |
| Existing ratings | Retained in the version UI |
| Currently published app | 1.1.0 (119) |

The UI saved `promotionalText`, `description`, `whatsNew` and `keywords` for
Korean, English (US) and Japanese from
`app-store/metadata/version-localizations.json`. Support and marketing URLs
already matched. The metadata script's subsequent authenticated read-only dry
run reported **zero pending localizations** for explicit version 1.2.0.

Names and subtitles were saved from
`app-store/metadata/app-info-localizations.json`. Each locale reported Save
Complete. An independent API comparison against the explicit editable App Info
ID returned **zero differing fields** for all three locales, including privacy
policy and privacy choices URLs. The existing App Info helper reports planned
updates even when fields already match, so its update count is not a diff.
Its age-rating comparison reported zero pending fields. Education/Productivity
categories and the existing age ratings were preserved.

The existing local App Store API key authenticated successfully. No API key,
certificate, permission, contact information or review credential was created
or changed. No credentials are stored in this report.

The initial copy checkpoint had no selected build and retained old screenshots
and review notes. Subsequent operations completed the following:

- Build **1.2.0 (121)** was uploaded, processed as **VALID** and selected on the
  exact version ID above. It is `APP_STORE_ELIGIBLE`, not expired, with
  `usesNonExemptEncryption=false`; see [signed-candidate evidence](2026-09-23-ios-release-candidate.md).
- The custom three-language What to Test was saved and verified against
  `app-store/releases/1.2.0/testflight-build-localizations.json`.
- The draft's copied 1.1.0/118 review notes were replaced with
  `app-store/releases/1.2.0/review-notes.asc.en-US.txt` and read back exactly.
  Existing secure sign-in and contact fields were preserved. Their presence
  was verified as booleans; successful reviewer login is a separate pending check.
- Forty screenshot assets reached COMPLETE across 10 exact display sets:
  6.9-inch, 6.5-inch and 13-inch iPad in three languages, plus the existing
  English smaller-iPhone slot. Each set's order/count was verified after its
  new assets processed and before reporting completion. No live 1.1.0 set changed.

The API enum `APP_IPHONE_61` proved misleading: its existing images were
1206 × 2622. A 1170 × 2532 attempt returned `IMAGE_INCORRECT_DIMENSIONS`;
the helper removed the failed asset and retained the old set. New native
iPhone 16 Pro captures at the observed 1206 × 2622 size were accepted and
replaced it. The unused 1170-pixel candidates remain explicitly labelled as
rendering evidence only. The [complete API read-back](../../app-store/releases/1.2.0/screenshots/asc-upload-verification.json)
records accepted files, dimensions, checksums and the draft/build state.

At the preparation checkpoint, the Apple agreement banner remained and no
agreement was accepted by the agent. App Store
Connect states that account-owner acceptance is required to update/submit apps;
successful draft editing/upload did not establish that restriction was cleared.
No review submission, approval or manual release had occurred at that checkpoint.

The [candidate package](../../app-store/releases/1.2.0/README.md) separates the
completed deployment from remaining signed-candidate device checks, review
access, owner agreement and submission steps.
