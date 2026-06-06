# AGENTS.md

## Project

BuddyStuddy is a SwiftUI iOS app. It generates short study questions with OpenAI, stores records, syncs with the backend, and shows topic-level statistics. Current public release work targets only the iOS app; macOS menu bar, DMG, Sparkle, and macOS test/build work are out of scope unless explicitly requested. Internal Xcode targets and identifiers still use `StudyMate` for release continuity.

Read these first:

- `docs/PRD.md`
- `docs/ARCHITECTURE.md`

## Working Rules

- Preserve user drafts. New scheduled, pushed, or synced questions must not replace the active ungraded answer page.
- Keep settings compact. Study settings should stay first; iCloud sync should stay a one-line bottom control.
- Keep statistics topic-first. Avoid global average score interpretations that ignore topic and difficulty.
- Keep logs paginated and dense. Do not render all persisted logs at once.
- Only the regular OpenAI API key is supported and synced.
- Keep Korean and English strings in `AppStrings` for new UI labels.
- Do not add or verify macOS app/release/update work unless explicitly requested; iOS App Store Connect release is the active distribution path.
- After completing feature work, always create a git commit that includes the completed implementation and verification updates unless the user explicitly says not to commit.

## Storage

- Use `SettingsStore` for app settings, API keys, logs, draft state, and CloudKit metadata.
- Use the existing study record store path through `SettingsStore`; do not add parallel record persistence.
- Records can scale toward 10,000, so UI must paginate or lazily render lists.

## CloudKit And Push

- CloudKit sync is snapshot based.
- Mac currently creates `StudyMateQuestionPush` records.
- iPhone currently receives CloudKit/APNs push via `StudyRemoteNotificationBridge`.
- Push arrival should sync quietly. Only explicit notification taps/replies should navigate to the pushed question.

## Verification

Run iOS generic build after shared UI, CloudKit, notification, or model changes:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
```

Run real-device verification for push, iCloud, background refresh, and entitlement changes.
