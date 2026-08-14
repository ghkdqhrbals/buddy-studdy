# BuddyStudy 1.1.0 App Review Resubmission

## What must be ready

- A replacement build containing public-author blocking and complete subscription purchase disclosures.
- A backend deployment and migration supporting persisted user blocks and hiding content from authors the signed-in user blocked.
- A permanent review account configured only in App Store Connect. Never delete it during recording or place its password in a tracked file.
- A complete walkthrough recorded on a physical iPhone running the latest publicly released iOS available on the recording date.
- A truthful list of every physical device model, exact OS version, and selected build used for verification. Because the submitted binary supports both iPhone and iPad, verify the selected build on both physical device families and list both tests.

## Guideline 2.1 reply

The canonical copy-ready version is `app-store/metadata/resolution-center-reply.txt`. After recording and physical-device verification, replace the two evidence placeholders in that reply, `app-store/metadata/review-notes.txt`, and the mirrored text block below. The device entry must include the selected build number in explicit `build N` form, and the validator requires identical evidence in all copies. Then paste this complete reply into Resolution Center:

```text
Hello App Review,

Thank you for reviewing BuddyStudy 1.1.0. Here is all requested information.

1. COMPLETE PHYSICAL-DEVICE VIDEO
Attached video: {{ATTACHED_VIDEO_FILENAME}}
This unedited physical-iPhone video uses the latest public iOS available on the recording date. Starting on the Home Screen, it launches BuddyStudy and shows disposable email registration/code/deletion; OTP-free demo login; AI study/grading/results; report/block; subscription disclosures; Apple's purchase sheet then cancellation; and the notification prompt.

2. TESTED DEVICES AND OS VERSIONS
{{ACTUALLY_TESTED_DEVICE_MODELS_AND_IOS_VERSIONS}}
This lists every physical model, exact OS version, and selected build tested. No unlisted device is claimed.

3. FUNCTIONS, AUDIENCE, PROBLEM, AND VALUE
BuddyStudy helps students and self-directed learners age 14+ practice active recall. It creates short questions for a selected topic/difficulty, grades answers, saves records, and shows topic progress to identify weak areas.

4. SETUP AND ACCESS
Active email demo credentials are in App Review Information. Demo login needs no OTP, invitation, membership, sample file, or special hardware; new email registration separately needs its delivered code. Accept Privacy if prompted. Paths: Home > My Studies for AI study; Records/Statistics for results; Home > All Studies for public content, Report, and Block; Profile > Membership & Billing for subscriptions; Settings > Account Settings > Delete Account. Apple, Google, and email login are visible.

5. EXTERNAL SERVICES, TOOLS, AND PLATFORMS
AWS Seoul hosts the API, data, and operator-managed LibreTranslate; Cloudflare provides DNS/TLS/proxy/security; OpenAI provides questions, grading, feedback, suggestions, and translation fallback; Apple provides sign-in, subscriptions, and APNs; RevenueCat provides products, purchase/restore, Customer Center, and subscription sync; Google provides sign-in, registration email verification, Firebase Analytics/Remote Config; Sentry provides masked diagnostics.

6. REGIONAL DIFFERENCES
Features and subscription benefits are consistent in all offered regions. UI/content display is localized in Korean, English, and Japanese. Only storefront price, currency, and tax presentation varies.

7. REGULATED INDUSTRIES OR PROTECTED THIRD-PARTY MATERIAL
Not applicable. This general study aid is not a regulated professional service, and AI output makes no legally significant decisions. It has no licensed catalog or protected third-party access. Questions/comments are UGC; Terms prohibit infringement. Users can report content and block an author, hiding that content.

8. IN-APP PURCHASES AND PURCHASE LOCATION
Tier 2 is a one-month auto-renewable subscription with 300 AI questions per monthly quota period; Tier 3 has 1,000. One generated question uses one allowance; study/topic creation does not. Path: Home > Profile > Membership & Billing > Manage membership > Tier 2/3 > Get Tier/Switch to Tier. Each offer shows title, localized price, one-month duration, allowance, renewal disclosure, Terms, Privacy, Restore, and purchase/change control. It opens Apple's purchase sheet; the video cancels before purchase. Manage Subscription appears only for active subscribers. Apple handles payments, renewals, cancellations, and refunds.

Please let us know if more information would help.
```

Do not add the real review password to this reply. App Review receives it through the dedicated App Review Information fields.

## Physical-device recording checklist

Use the exact replacement build that will be selected for review on a physical iPhone running the latest publicly released iOS available on the recording date. Make one continuous, unedited recording: start on the iOS Home Screen, tap the BuddyStudy app icon as the first interaction, and do not add cuts, splices, or title cards. Keep the status bar, taps, and readable UI visible. Do not expose Apple ID, Google, App Review, or Sandbox credentials, device serial numbers, or UDIDs.

1. State the exact device model, iOS version, and selected BuddyStudy build aloud while the Home Screen is visible. Confirm immediately before recording that this is the latest public iOS release. Do not open or record any screen that exposes a serial number or UDID.
2. With a disposable account, show email registration and entering the verification code delivered to that address, required Terms and Privacy acceptance, Settings > Account Settings > Delete Account, confirmation, and the returned signed-out state. Do not use the permanent review account for deletion.
3. Sign in with the permanent review account and make clear that this demo login does not require OTP. Show that Apple, Google, and email options are available, keep password entry masked, and accept the updated Privacy Policy if prompted. No sample file or external setup is required.
4. Open Home > My Studies, create or open a study/topic, generate a question, enter and submit an answer, wait for AI grading, then show the saved result in Records and topic-level progress in Statistics.
5. Open Home > All Studies with seeded content from other users. From one question's `...` menu, submit Report Question. From a different author's `...` menu, confirm Block User and show that author's public questions and comments disappear. Also show comment long-press > Block User.
6. From Home, tap the top-left Profile button, then open Membership & Billing > Manage membership. With the free review account, show both monthly products, localized prices, Tier 2 `300` and Tier 3 `1,000` question benefits, one-month duration, auto-renewal disclosure, Terms, Privacy Policy, Restore Purchases, and the purchase/change control. Tap Get Tier/Switch to Tier so Apple's purchase confirmation sheet is visibly presented, then cancel it before purchase. Use TestFlight/Sandbox without exposing credentials. Manage Subscription appears only for an account with an active subscription.
7. From Profile > Notification Settings, enable Question notifications on a fresh install to show the contextual iOS permission prompt. Explain a previously decided permission state on screen instead of resetting or staging a fake prompt.
8. End on the Profile version row or another clear screen that identifies BuddyStudy 1.1.0, and verify the saved recording is readable from start to finish.

Because BuddyStudy supports iPad, physical-iPad verification on the latest public iPadOS is required before resubmission. If suitable hardware is unavailable, wait until that test can be completed or intentionally remove iPad support in a later replacement build; never claim an unperformed iPad test.

## Resubmission checklist

- [ ] Backend block migration and API are deployed through the approved GitHub Actions workflow.
- [ ] The replacement iOS build is uploaded, processed, and selected; build 88 is not reused.
- [ ] The selected build passes the generic iOS build and physical-iPhone verification on the latest public iOS available on the test date.
- [ ] The exact selected build passes physical-iPad verification on the latest public iPadOS; the tested iPad model, iPadOS version, and build are included in the device list.
- [ ] Report Question and Block User work from public-question list/detail; comment-author blocking works; blocked content is hidden after refresh and relaunch.
- [ ] Subscription rows and the pre-purchase screen show price, one-month duration, exact benefit, auto-renewal terms, legal links, restore, and purchase/change controls; Manage Subscription is checked separately only with an active subscription.
- [ ] Each subscription's App Review Screenshot is newly checked against the replacement build's monthly-only membership UI: no annual choice, the correct localized price, Tier 2 `300` or Tier 3 `1,000` benefit, and the purchase disclosure are visible. Replace any stale screenshot instead of assuming a local artifact still matches.
- [ ] Do not reuse or modify `artifacts/app-store-connect/membership-review-1242x2688.png`; retain it only as stale evidence because it shows the retired monthly/annual selector and old membership/legal presentation.
- [ ] For iPad English and Korean uploads, select only the canonical five files named `01-study-tree`, `02-study-list`, `03-public-questions`, `04-statistics`, and `05-records`; those folders also contain older May screenshots and duplicate `-v2` files, so do not upload the directory with a wildcard.
- [ ] The permanent review account can access seeded study, records/statistics, public content from other authors, and billing without OTP, sample files, or forced setup; disposable email registration is separately verified with its delivered email code.
- [ ] The permanent review account has no active subscription, matching the reply's “free review account” wording; its billing screen shows purchase/change and Restore Purchases, not Manage Subscription.
- [ ] A separate disposable account was used for the recorded deletion flow.
- [ ] Privacy Policy and Terms URLs open in English and match current shipped behavior.
- [ ] App Privacy, age rating/UGC, export compliance, content-rights, IDFA/no-tracking, review contact, and demo-account fields are confirmed in App Store Connect.
- [ ] The physical-device video is attached and its filename replaces `{{ATTACHED_VIDEO_FILENAME}}` in the Resolution Center reply, Review Notes, and the mirrored reply block in this guide.
- [ ] Every listed device/OS actually tested the selected build; the exact models, OS versions, and build number in `build N` form replace `{{ACTUALLY_TESTED_DEVICE_MODELS_AND_IOS_VERSIONS}}` in all three copies.
- [ ] The video visibly opens Apple's purchase confirmation sheet from Get Tier/Switch to Tier and then cancels before purchase.
- [ ] The final Resolution Center reply remains at or below 4,000 UTF-8 bytes after placeholder replacement.
- [ ] Final Review Notes remain at or below 4,000 UTF-8 bytes after all four placeholder replacements.
- [ ] The review-notes updater is dry-run first and refuses unresolved evidence placeholders; real credentials remain only in App Store Connect.
- [ ] The final commit includes implementation and verification documentation before resubmission.

## Known legal-text follow-up

The immutable English Terms dated 2026-07-30 names email and Google in its Accounts section but does not name Apple, while the shipped login screen supports Apple, Google, and email. Do not edit that fixed copy. Publish a new dated Terms version and update consent metadata in a later legal-document release. The App Review reply should continue to describe the login options actually visible in the submitted build.
