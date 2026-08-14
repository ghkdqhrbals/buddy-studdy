# BuddyStudy 1.1.0 App Store Review Readiness

## Release status

- App Store version: `1.1.0`
- Distribution target: iOS App Store Connect
- Submission ID: `0d6aa057-848e-4b3a-b064-e55e89665328`
- Apple requested additional information under Guideline 2.1 for submitted build 88 on August 13, 2026.
- The moderation and subscription-disclosure changes require a replacement build. Do not reselect build 88 or an older build.
- Upload, build selection, and review submission require explicit release approval.
- The copy-ready reply, recording plan, and final checks are in [APP_STORE_REVIEW_RESUBMISSION_1.1.0.md](APP_STORE_REVIEW_RESUBMISSION_1.1.0.md).

## Ready in source

- Korean, English, and Japanese App Store descriptions
- Korean, English, and Japanese App Info subtitles and privacy-policy URLs
- iPhone 6.9-inch and iPad 13-inch screenshot sets for all three locales
- App privacy manifest embedded in the iOS target
- In-app account deletion under Settings > Account Settings
- Public-content reporting
- Public-author blocking from public-question and comment actions
- Sign in with Apple alongside Google and email login
- Monthly Tier 2 and Tier 3 benefits, renewal disclosure, legal links, restore, purchase/change controls, and conditional active-subscription management on the membership screen
- Review notes describing the primary study, moderation, subscriptions, external services, regional behavior, and regulated/protected-content status
- Age-rating declaration aligned with public questions, comments, and user-generated content

## Required release order

1. Deploy the backend changes and required database migration through the backend deployment workflow.
2. Create and upload a new signed iOS build containing public-author blocking and complete subscription purchase information.
3. Verify the uploaded build on a physical iPhone:
   - create and delete a disposable account without affecting the permanent review account;
   - sign in with the permanent review account;
   - accept required terms;
   - create a study and answer a question;
   - leave and reopen the answered question;
   - browse, report, and block public content, including a comment author;
   - verify that content by a blocked author is hidden;
   - inspect Tier 2 and Tier 3 benefits, localized prices, one-month duration, auto-renewal disclosure, legal links, restore, and purchase/change controls; verify Manage Subscription separately only with an active subscription;
   - exercise notification permission from Profile > Notification Settings;
   - open a push notification;
   - verify Korean, English, and Japanese UI and content switching;
   - verify recommended update, forced update, and maintenance overlays.
4. Record the requested walkthrough as one continuous, unedited video on that physical device. Start on the iOS Home Screen and tap the BuddyStudy app icon as the first interaction. Do not expose App Review, Apple, Google, or Sandbox credentials, a device serial number, or a UDID.
5. Add the exact tested device model and OS version and attached video filename to `app-store/metadata/resolution-center-reply.txt`.
6. Select only that verified build for version 1.1.0.
7. Confirm App Privacy answers, review contact, permanent demo account, export compliance, content rights, and advertising identifier answers in App Store Connect.
8. Confirm the uploaded archive contains the Sign in with Apple entitlement.
9. Attach the video, paste the eight-item English response, and submit the replacement build for review.

## Manual App Store Connect checks

The privacy labels must match the shipped SDKs and backend behavior. Confirm these categories before submission:

- Contact Info: name and email address
- Identifiers: user ID and device ID
- User Content: questions, answers, AI feedback attached to user records, and comments
- Search History
- Usage Data: product interaction
- Diagnostics: crash, performance, and other diagnostic data collected by Sentry
- Tracking: no

Confirm the review contact, demo-account credentials, export-compliance answers, content-rights answers, and advertising-identifier declaration in the App Store Connect UI. Keep the real demo password only in App Store Connect; the tracked review notes must retain `{{DEMO_ACCOUNT_PASSWORD}}`.

## Sign in with Apple release check

The iOS and backend source support Sign in with Apple, satisfying the login-surface requirement associated with App Review Guideline 4.8.

Sign in with Apple is enabled for App ID `io.github.ghkdqhrbals.StudyMate`. The App Store distribution provisioning profile and the matching GitHub Actions secret were refreshed on 2026-07-30. The release workflow rejects an archive that does not contain the `com.apple.developer.applesignin` entitlement.

## Review notes source

The canonical review instructions are stored in:

- `app-store/metadata/review-notes.txt`
- `app-store/metadata/resolution-center-reply.txt`

App Store metadata scripts are dry-run by default. Set `APP_STORE_APPLY=1` only when intentionally updating App Store Connect.

Version 1.1.0 is the first public release, so App Store Connect does not accept a `What’s New` value for this version.
