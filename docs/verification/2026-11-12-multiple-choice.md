# Multiple-choice questions — 2026-11-12 verification

This report records the initial implementation in the original checkout. The
production integration subsequently adds explicit API contract 3 and preserves
older iOS clients; it supersedes the initial forced-update rollout assumption.

## Backend

The complete domain, application, and infrastructure unit suites passed (21,
395, and 210 tests respectively). This includes generation validation, the
persisted format snapshot, production gating, deterministic owner grading,
choice localization, wire serialization, settings inheritance, public secrecy,
and existing free-text behavior.

```sh
cd backend
./gradlew :domain:test :application:test :infra:test --console=plain
```

39 focused MySQL integration cases passed using disposable Testcontainers,
covering owner acceptance, immutable replay, concurrent public participation,
owner inclusion, no participant learning/quota mutations, deletion races and
cascades, public access restrictions, settings updates, generation persistence,
question quota lifecycle, transaction boundaries, feed pagination, and sharing.

```sh
./gradlew :tutor:test -x :tutor:processTestAot \
  --tests '*MultipleChoice*' \
  --tests '*QuestionGenerationPersistenceIntegrationTest' \
  --tests '*PersonalizedPublicFeedIntegrationTest' \
  --tests '*PublicQuestionShareIntegrationTest' \
  --tests '*StudyApiIntegrationTest' \
  --tests '*QuestionQuotaLifecycleIntegrationTest' \
  --tests '*QuestionCreationTransactionIntegrationTest'
```

After the final study-room locale metadata adjustment, the application suite and
the 19 settings/owner/Study API integration cases were rerun successfully.
JVM checks skip unrelated test AOT generation using the documented repository
option; no production runtime checks or deployment were performed.

## iOS

The required unsigned generic iOS build passed using `StudyMateiOS`:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS \
  -configuration Debug -destination 'generic/platform=iOS' \
  -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
```

The iOS 26 QA simulator passed all 131 `QuestionGenerationFlowTests` plus the
Japanese string completeness check (132 tests). Coverage includes legacy record
and typed-draft decoding, root preference requests, owner submission after
navigation, delayed account-change responses, inline public participation,
refreshed counts, preserved reactions, and existing free-text/like regressions.

On the connected **Min iPhone (iPhone 16 Pro)**, the focused feature and regression
suite passed 11 executable tests. Its repository-source Japanese completeness
check is skipped on a physical device and passed on the simulator instead.
Component render attachments were generated on that physical device, with
before/after choice and four-option large-text coverage. These are automated
component rendering and state/transport checks, not a claimed manual tap-through
of a deployed backend.

Inspected snapshots: [before selection](multiple-choice/before.png),
[after selection](multiple-choice/after.png), and
[four options with accessibility text size](multiple-choice/large-text.png).
Final visual checks also passed after replacing disabled result buttons with
readable static rows and correcting English singular counts.
Two final physical-device regression checks passed for stable option IDs across
localized/original labels and participation/reaction state. Original-view detail
refreshes its own original response without replacing localized feed labels.

Device tests use the Xcode device UDID and the existing development team as a
command-line override; the user's shared scheme and signing settings are not
changed by this feature.

## Release boundary

No live OpenAI request, App Store submission, backend deployment, forced-update
activation, or production generation activation was performed. Production
`BUDDYSTUDY_MULTIPLE_CHOICE_ENABLED` remains off by default. Release the capable
iOS build before enabling it. Older versions remain supported through negotiated
API contracts and must not be force-updated for this feature.
