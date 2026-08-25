# BuddyStudy AdMob release checklist

The repository deliberately does not publish a placeholder `app-ads.txt` or a production sample App ID. Complete the external steps below with values issued for BuddyStudy. Until then, the Release archive workflow must fail closed.

## 1. AdMob and UMP

1. Register the iOS app in AdMob with BuddyStudy's App Store bundle identity.
2. Create one Native ad unit for `COMMUNITY_FEED`; do not add mediation partner SDKs in this release.
3. Configure the EEA/UK GDPR message and applicable U.S. state privacy messages in Privacy & messaging. Include a privacy-options entry point so UMP can expose it from Settings when required.
4. Confirm that requests are non-personalized, teen-rated, publisher first-party ID is disabled, SDK crash reporting is disabled, and no ATT/IDFA flow exists.
5. Register simulator and physical QA devices as test devices. Use only Google's sample IDs in Debug and only AdMob's test-device mode with issued IDs before production.

## 2. Repository and CI values

Set these GitHub Actions repository variables before producing a Release archive:

- `ADMOB_APP_ID`: issued iOS App ID in `ca-app-pub-<publisher>~<app>` format.
- `ADMOB_NATIVE_AD_UNIT_ID`: issued Native unit ID in `ca-app-pub-<publisher>/<unit>` format.

The iOS release workflow rejects empty, malformed, or Google sample values and checks the archived and exported `Info.plist`. Keep the workflow on Xcode 26.2 or later.

## 3. app-ads.txt

1. Copy the single inventory line from `docs/app-ads.txt.template` into `docs/app-ads.txt`.
2. Replace `pub-REPLACE_WITH_ADMOB_PUBLISHER_ID` with the exact issued AdMob publisher ID. Do not commit or deploy the placeholder.
3. Publish GitHub Pages and verify that `https://ghkdqhrbals.github.io/app-ads.txt` (the App Store developer-website root domain) returns plain text without redirects to HTML or authentication.
4. Set the same developer website in App Store Connect, then wait for AdMob to report the app-ads.txt status as authorized.

The repository's current project site is under `/buddy-studdy`; AdMob requires the file at the developer website's domain root. If GitHub Pages cannot serve that root from this repository, publish the exact file through the repository that owns the root site before enabling ads.

## 4. App Store privacy and review metadata

Use the conservative disclosure reflected in the immutable 2026-08-25 privacy policy:

- Data types: Coarse Location, Device ID, Product Interaction, Advertising Data, Crash Data, Performance Data, and Other Diagnostic Data.
- Treat the Google SDK data as linked to the user or device.
- Purposes: Third-Party Advertising, Analytics, and App Functionality as applicable.
- Declare that the app does not use data for tracking: no personalized advertising, IDFA, ATT prompt, or cross-app tracking.
- Verify the Korean, English, and Japanese privacy URLs, membership ad-free benefit, and Review Notes before submission.

## 5. QA and controlled rollout

1. Publish GitHub Pages with `publish_public=true` and verify that the Korean, English, and Japanese immutable 2026-08-25 privacy URLs all return the expected documents.
2. Deploy backend and admin frontend through their existing module-scoped GitHub Actions. Do not use SSH and do not add workflow health checks. The included Flyway row only stages the 2026-08-25 policy with a future sentinel effective time; the currently effective policy must remain unchanged.
3. Keep `COMMUNITY_FEED` policy OFF after deployment.
4. With a TestFlight-only v2 build, briefly enable the policy for registered test devices and verify Korean/English/Japanese, dark mode, Dynamic Type, offline handling, simulator plus physical iPhone/iPad, UMP regions and consent states, Native Validator, and Ad Inspector privacy signals.
5. Turn the policy OFF again after TestFlight validation.
6. Sync the already-published privacy URLs and updated metadata to App Store Connect, complete review, and verify that the approved build is publicly available. Publish a FORCE app-update campaign for this AdMob-capable version and confirm that older builds are no longer allowed to pass the protected app flow.
7. In a separate post-approval change, add a new Flyway migration that activates the staged 2026-08-25 policy at a deliberate timestamp, deploy that backend-only change through GitHub Actions, and verify that the app displays and submits the server term's exact version and content hash. Never edit the already-applied staging migration.
8. Enable the default policy (`2/day`, `6-hour gap`, minimum `4` questions, zero-based positions `2...7`). The immediate rollback is the admin policy OFF switch.

Record the issued identifiers and external-console verification in the private release record. Do not place console credentials, signing secrets, or raw consent diagnostics in this repository.
