# BuddyStudy 1.1.0 App Store Review Readiness

## Release status

- App Store version: `1.1.0`
- Distribution target: iOS App Store Connect
- Current local source requires a new build. Do not select build 73 or an older build.
- Upload, build selection, and review submission require explicit release approval.

## Ready in source

- Korean, English, and Japanese App Store descriptions
- Korean, English, and Japanese App Info subtitles and privacy-policy URLs
- iPhone 6.9-inch and iPad 13-inch screenshot sets for all three locales
- App privacy manifest embedded in the iOS target
- In-app account deletion under Settings > Account Settings
- Public-content reporting and user blocking
- Localized review notes describing the primary study and moderation flows
- Age-rating declaration aligned with public questions, comments, and user-generated content

## Required release order

1. Deploy the backend user-block API and database migration.
2. Create and upload a new signed iOS build from the reviewed commit.
3. Verify the uploaded build on a physical iPhone:
   - sign in;
   - accept required terms;
   - create a study and answer a question;
   - leave and reopen the answered question;
   - browse, report, and block public content;
   - delete the account;
   - open a push notification;
   - verify Korean, English, and Japanese UI and content switching;
   - verify recommended update, forced update, and maintenance overlays.
4. Select only that verified build for version 1.1.0.
5. Confirm App Privacy answers in App Store Connect.
6. Resolve the Sign in with Apple decision below.
7. Submit for review.

## Manual App Store Connect checks

The privacy labels must match the shipped SDKs and backend behavior. Confirm these categories before submission:

- Contact Info: name and email address
- Identifiers: user ID and device ID
- User Content: questions, answers, AI feedback attached to user records, and comments
- Search History
- Usage Data: product interaction
- Diagnostics: crash, performance, and other diagnostic data collected by Sentry
- Tracking: no

Confirm the review contact, demo-account credentials, export-compliance answers, content-rights answers, and advertising-identifier declaration in the App Store Connect UI.

## Submission blocker: third-party login

The app currently offers Google login for the primary account but does not offer Sign in with Apple. App Review Guideline 4.8 normally requires an equivalent privacy-preserving login option when a third-party login is used.

Before submission, choose one:

- Add Sign in with Apple to iOS and the backend, and enable the capability for the App ID and provisioning profile. This is the recommended path.
- Remove Google login from the review build and use email login only. This can prevent existing Google-only users from signing in and therefore needs a migration plan.

Do not submit the current login surface without accepting this review risk.

## Review notes source

The canonical review instructions are stored in:

- `app-store/metadata/review-notes.txt`

App Store metadata scripts are dry-run by default. Set `APP_STORE_APPLY=1` only when intentionally updating App Store Connect.

Version 1.1.0 is the first public release, so App Store Connect does not accept a `What’s New` value for this version.
