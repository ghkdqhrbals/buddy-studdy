# AGENTS.md

## Project

BuddyStudy is a SwiftUI iOS app. It generates short study questions with OpenAI, stores records, syncs with the backend, and shows topic-level statistics. The product target is iOS only. macOS menu bar, DMG, Sparkle, macOS schemes, macOS tests, and macOS build verification are out of scope unless the user explicitly asks for macOS in that turn. Internal Xcode targets and identifiers still use `StudyMate` for release continuity.

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
- Do not add, modify, test, or verify macOS app/release/update work unless explicitly requested; iOS App Store Connect release is the active distribution path.
- For app work, use the `StudyMateiOS` scheme and iOS destinations only. Do not run the `StudyMate` macOS scheme or macOS test target as a substitute for iOS verification.
- After completing feature work, always create a git commit that includes the completed implementation and verification updates unless the user explicitly says not to commit.
- On iOS 26 toolbars, avoid unintended shared capsule/glass backgrounds around custom toolbar controls. For custom search/profile toolbar items that already draw their own shape, apply `ToolbarItem.sharedBackgroundVisibility(.hidden)` with an iOS 26 availability guard instead of changing the inner view's `Capsule().stroke(...)`.
- Do not connect to production servers directly with SSH. Backend deployment must go through GitHub Actions and the personal-deploy repository workflow unless the user explicitly re-allows direct SSH for a specific incident.
- Backend and Redis Stream Coordinator Docker images must be built on GitHub-hosted runners and pushed to GHCR. The EC2 self-hosted runner is deploy-only: it may pull GHCR images and run containers, but it must not compile backend code or build Docker images.
- GitHub Actions must not perform runtime health checks or smoke checks against backend, Grafana, local containers, or public health endpoints. This includes indirect container health gates such as `docker compose up --wait` and `docker compose wait`. Runtime monitoring belongs to the Cloudflare Health Monitor Worker and its Cron trigger.
- Production monitoring on the backend host is PLG only: Promtail, Loki, and Grafana. Do not reintroduce Prometheus or Redis exporter containers on the small EC2 host unless explicitly requested.

## Backend Architecture Rules

- Every application `*Service` must implement one or more inbound `*UseCase` contracts.
- Only composition services may depend on lower-level `*UseCase` contracts.
- Lower-level domain services must depend on outbound `*Port` contracts, not other services.
- Adapters must implement outbound or controller-facing `*Port` contracts and may depend on `*UseCase` contracts.
- Controllers must depend on controller-facing `*Port` contracts, not direct `*UseCase` contracts.
- Non-use-case helpers must not be named `*Service`; use names such as `*Provider`, `*Manager`, `*Adapter`, `*Publisher`, or `*Resolver`.

## Storage

- Use `SettingsStore` for app settings, API keys, logs, draft state, and CloudKit metadata.
- Use the existing study record store path through `SettingsStore`; do not add parallel record persistence.
- Records can scale toward 10,000, so UI must paginate or lazily render lists.

## Push

- iOS receives APNs push via `StudyRemoteNotificationBridge`.
- Push arrival should sync quietly. Only explicit notification taps/replies should navigate to the pushed question.

## Verification

Run iOS generic build after shared UI, CloudKit, notification, or model changes:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
```

Run real-device iPhone verification after user-visible iOS feature work and for push, background refresh, and entitlement changes.
