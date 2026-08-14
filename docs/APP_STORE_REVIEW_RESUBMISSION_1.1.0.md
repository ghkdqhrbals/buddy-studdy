# BuddyStudy 1.1.0 App Review Resubmission

## What must be ready

- A replacement build containing public-author blocking and complete subscription purchase disclosures.
- A backend deployment and migration supporting persisted user blocks and hiding content from authors the signed-in user blocked.
- A permanent review account configured only in App Store Connect. Never delete it during recording or place its password in a tracked file.
- A complete walkthrough recorded on a physical iPhone by the submitter.
- A truthful list of every physical device model and iOS version used to verify the exact replacement build.

## Guideline 2.1 reply

The canonical copy-ready version is `app-store/metadata/resolution-center-reply.txt`. Replace only the two `{{...}}` fields after recording and physical-device verification, then paste the following complete reply into Resolution Center:

```text
Hello App Review,

Thank you for reviewing BuddyStudy 1.1.0. Here is the requested information.

1. COMPLETE PHYSICAL-DEVICE VIDEO
Attached video: {{ATTACHED_VIDEO_FILENAME}}
This one continuous, unedited physical-iPhone video starts by tapping the BuddyStudy app icon and shows disposable-account registration and deletion, review-account sign-in, the AI question/answer/grading flow, Records and Statistics, public-content report/block controls, subscription information, and notification permission.

2. TESTED DEVICES AND OS VERSIONS
{{ACTUALLY_TESTED_DEVICE_MODELS_AND_IOS_VERSIONS}}
The selected build and walkthrough were verified on physical hardware.

3. PURPOSE, AUDIENCE, PROBLEM, AND VALUE
BuddyStudy is an educational AI study companion for students and self-directed learners age 14 or older. It turns a chosen topic and difficulty into short recall questions, grades answers, saves study records, and shows topic-level progress. It replaces passive rereading with active-recall practice and identifies topics needing attention.

4. ACCESS TO FEATURES
The active email demo credentials are in App Review Information; no invitation, OTP, external membership, or special hardware is required. If the updated Privacy Policy acceptance screen appears after sign-in, accept it to continue. Then: Home > My Studies opens AI study; Records and Statistics show results; Home > All Studies opens public questions, comments, Report Question, and Block User; Home > Profile button > Membership & Billing opens subscriptions; Settings > Account Settings > Delete Account opens deletion. Apple, Google, and email sign-in options are visible.

5. EXTERNAL SERVICES
Services used: AWS (Seoul API/data hosting); Cloudflare (DNS, proxy, TLS, security); OpenAI (questions, grading, feedback, suggestions, translation fallback); Apple (sign-in, App Store subscriptions, APNs); RevenueCat (products, purchase, restore, Customer Center, subscription sync); Google (sign-in, verification email, Firebase Analytics/Remote Config); and Sentry (masked error diagnostics). An operator-hosted LibreTranslate instance in AWS Seoul handles other localization without an outside LibreTranslate provider.

6. REGIONAL DIFFERENCES
Features and subscription benefits are identical wherever BuddyStudy is offered. UI/content display is localized in Korean, English, and Japanese. Only App Store price, currency, and tax presentation varies by storefront.

7. REGULATED INDUSTRIES OR PROTECTED THIRD-PARTY CONTENT
Not applicable. BuddyStudy is a general study aid, not a medical, financial, gambling, legal, or other regulated professional service; AI results make no legally significant decisions. It has no licensed media catalog or protected third-party service access. Public questions/comments are user-generated. The Terms prohibit infringing submissions. Reports are stored for operator review, and blocking hides the selected author's content.

8. IN-APP PURCHASE BENEFITS AND ACCESS PATH
Tier 2 is a one-month auto-renewable subscription providing 300 AI-generated study questions per monthly quota period; Tier 3 provides 1,000. One generated question uses one allowance; study/topic creation does not. Path: Home > Profile button (top-left avatar) > Membership & Billing > Manage membership > select Tier 2 or Tier 3 > Get Tier/Switch to Tier. Before purchase, the screen shows localized price, one-month duration, allowance, auto-renewal disclosure, Terms, Privacy Policy, Restore Purchases, and the purchase/change control. Manage Subscription appears only for an active subscription; the free review account instead shows purchase and restore. Apple handles payment, renewal, cancellation, and refund decisions.

Please let us know if any additional information would be helpful.
```

Do not add the real review password to this reply. App Review receives it through the dedicated App Review Information fields.

## Physical-device recording checklist

Use the exact replacement build that will be selected for review. Make one continuous, unedited recording: start on the iOS Home Screen, tap the BuddyStudy app icon as the first interaction, and do not add cuts, splices, or title cards. Keep the status bar, taps, and readable UI visible. Do not expose Apple ID, Google, App Review, or Sandbox credentials, device serial numbers, or UDIDs.

1. State the exact device model and iOS version aloud while the Home Screen is visible. Do not open or record any screen that exposes a serial number or UDID.
2. With a disposable account, show registration, required Terms and Privacy acceptance, Settings > Account Settings > Delete Account, confirmation, and the returned signed-out state. Do not use the permanent review account for deletion.
3. Sign in with the permanent review account. Show that Apple, Google, and email options are available, keep password entry masked, and accept the updated Privacy Policy if prompted.
4. Open Home > My Studies, create or open a study/topic, generate a question, enter and submit an answer, wait for AI grading, then show the saved result in Records and topic-level progress in Statistics.
5. Open Home > All Studies with seeded content from other users. From one question's `...` menu, submit Report Question. From a different author's `...` menu, confirm Block User and show that author's public questions and comments disappear. Also show comment long-press > Block User.
6. From Home, tap the top-left Profile button, then open Membership & Billing > Manage membership. With the free review account, show both monthly products, localized prices, Tier 2 `300` and Tier 3 `1,000` question benefits, one-month duration, auto-renewal disclosure, Terms, Privacy Policy, Restore Purchases, and the purchase/change control. Manage Subscription appears only for an account with an active subscription. Use TestFlight/Sandbox if demonstrating the Apple sheet; never expose credentials, and cancel before any unintended transaction.
7. From Profile > Notification Settings, enable Question notifications on a fresh install to show the contextual iOS permission prompt. Explain a previously decided permission state on screen instead of resetting or staging a fake prompt.
8. End on the Profile version row or another clear screen that identifies BuddyStudy 1.1.0, and verify the saved recording is readable from start to finish.

## Resubmission checklist

- [ ] Backend block migration and API are deployed through the approved GitHub Actions workflow.
- [ ] The replacement iOS build is uploaded, processed, and selected; build 88 is not reused.
- [ ] The selected build passes the generic iOS build and real-device verification.
- [ ] Report Question and Block User work from public-question list/detail; comment-author blocking works; blocked content is hidden after refresh and relaunch.
- [ ] Subscription rows and the pre-purchase screen show price, one-month duration, exact benefit, auto-renewal terms, legal links, restore, and purchase/change controls; Manage Subscription is checked separately only with an active subscription.
- [ ] Each subscription's App Review Screenshot is newly checked against the replacement build's monthly-only membership UI: no annual choice, the correct localized price, Tier 2 `300` or Tier 3 `1,000` benefit, and the purchase disclosure are visible. Replace any stale screenshot instead of assuming a local artifact still matches.
- [ ] Do not reuse or modify `artifacts/app-store-connect/membership-review-1242x2688.png`; retain it only as stale evidence because it shows the retired monthly/annual selector and old membership/legal presentation.
- [ ] For iPad English and Korean uploads, select only the canonical five files named `01-study-tree`, `02-study-list`, `03-public-questions`, `04-statistics`, and `05-records`; those folders also contain older May screenshots and duplicate `-v2` files, so do not upload the directory with a wildcard.
- [ ] The permanent review account can access seeded study, records/statistics, public content from other authors, and billing without OTP or forced setup.
- [ ] The permanent review account has no active subscription, matching the reply's “free review account” wording; its billing screen shows purchase/change and Restore Purchases, not Manage Subscription.
- [ ] A separate disposable account was used for the recorded deletion flow.
- [ ] Privacy Policy and Terms URLs open in English and match current shipped behavior.
- [ ] App Privacy, age rating/UGC, export compliance, content-rights, IDFA/no-tracking, review contact, and demo-account fields are confirmed in App Store Connect.
- [ ] The physical-device video is attached and its filename exactly replaces the first placeholder.
- [ ] Every listed device/OS actually tested the selected build and replaces the second placeholder.
- [ ] The final Resolution Center reply remains at or below 4,000 characters after placeholder replacement.
- [ ] The review-notes updater is dry-run first; real credentials remain only in App Store Connect.
- [ ] The final commit includes implementation and verification documentation before resubmission.

## Known legal-text follow-up

The immutable English Terms dated 2026-07-30 names email and Google in its Accounts section but does not name Apple, while the shipped login screen supports Apple, Google, and email. Do not edit that fixed copy. Publish a new dated Terms version and update consent metadata in a later legal-document release. The App Review reply should continue to describe the login options actually visible in the submitted build.
