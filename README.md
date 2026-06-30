# BuddyStudy

BuddyStudy is a free AI tutor for iPhone. It asks short study questions, preserves answer drafts, grades responses with OpenAI, and turns records into topic-level statistics.

The macOS menu bar target remains in the repository, but public release work is currently paused for macOS. The active release target is the iOS app, distributed through App Store Connect.

## Why

AI is more useful when you know the subject yourself. Better knowledge leads to sharper questions, better judgment, and better output.

BuddyStudy was built to help keep that knowledge fresh through small, repeated questions.

## Features

- Scheduled study questions by topic, difficulty, and interval
- Answer grading with score, feedback, and explanation
- Pending questions, records, and topic-first statistics
- iCloud sync between devices through CloudKit
- Push delivery for new study questions on iPhone
- Korean and English app language support
- OpenAI Responses API integration with the user's own API key
- Optional Python backend scaffold for true APNs remote push delivery

## Requirements

- iOS 17 or later
- Xcode 16 or later
- OpenAI API key

## Run

1. Open `StudyMate.xcodeproj` in Xcode.
2. Select the `StudyMateiOS` scheme.
3. Run on an iPhone or iOS simulator.
4. Open settings and enter your OpenAI API key.

## Test

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
```

Run macOS tests when shared logic changes:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMate -destination 'platform=macOS,arch=arm64' -derivedDataPath build/MacTestDerivedData CODE_SIGNING_ALLOWED=NO test
```

## Release

GitHub Actions uploads the iOS app to App Store Connect when a version tag is pushed.

```sh
git tag v1.0.17
git push origin v1.0.17
```

Required GitHub Actions secrets:

- `APPLE_TEAM_ID`
- `APPSTORE_CONNECT_KEY_ID`
- `APPSTORE_CONNECT_ISSUER_ID`
- `APPSTORE_CONNECT_PRIVATE_KEY_BASE64`

The workflow builds `StudyMateiOS`, verifies production push and CloudKit entitlements, exports an IPA, keeps the IPA as a short-lived Actions artifact, and uploads it to App Store Connect.

## Backend

The optional push backend lives in `backend/`. It is a Dockerized FastAPI service that can generate scheduled questions and send APNs notifications. Deployment wiring is documented in `docs/BACKEND_APNS_DEPLOYMENT.md`.

## Website

GitHub Pages files live in `docs/`.

- Korean: `docs/index.html`
- English: `docs/en/index.html`

## Notes

- The app is free software, but OpenAI API usage is billed through the user's own OpenAI account.
- Only the user's regular OpenAI API key is supported for question generation and grading.
- iCloud sync is state based and uses the user's private iCloud database.
