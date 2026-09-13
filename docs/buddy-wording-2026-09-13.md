# Buddy wording — 2026-09-13

The iOS conversation partner is named **버디 / Buddy / バディ**. Updated the discovery title, speaker/accessibility labels, voice settings, question/explanation and learning-record labels, connection and interruption messages, Pro description and recording consent. Korean particles were adjusted for the new name. AI-generated audio and provider usage disclosures remain factual.

The backend session prompt uses the same localized name when directly asked, while preserving the AI identity disclosure and the rule against unsolicited introductions. It does not change saved transcript content, speaker identifiers, drafts or session behavior.

App Store subtitle/keyword source files use Buddy branding in all three languages. Metadata JSON and length limits were checked locally; no App Store upload was performed. macOS-only window titles and credits are outside this iOS change.

Verification:

- `StudyMateiOS` generic iOS Debug build passed.
- 38 `VoiceTutorDiscoveryTests` passed on the connected iPhone 16 Pro. Updated the existing localized-name expectations. App installed by the device test run and launched afterward.
- Existing backend wording assertion updated for the renamed identity and sentence capitalization.
- Logs: `build/buddy-wording-ios-final-build.log`, `build/buddy-wording-device.log`, `build/buddy-wording-device.xcresult`, `build/buddy-wording-backend-final.log`.

- Backend: 75 voice tutor tests and `:tutor:bootJar` passed.

Local backend rollout: confirmed zero active sessions, replaced the existing app-volume JAR, restarted the backend container, and verified health `UP`. JAR SHA-256: `29a630653083657a5255fe243ead011074c948326e0b961671e331420fe17021`. Previous JAR retained at `/app/buddystudy-backend.pre-buddy-wording-20260913.jar`. No production deployment.
