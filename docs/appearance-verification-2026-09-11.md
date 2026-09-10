# iOS appearance selection

Settings starts with a compact System / Light / Dark segmented control, using
localized AppStrings labels. The choice applies and saves immediately; System
is the default and releases the override to follow the device setting.

`AppState` loads and publishes the installation-level preference through
`LocalStudySettingsUseCase` and its `SettingsStore` repository. The separate
`appAppearance` key falls back to System for absent or invalid values. Backend
settings refresh and the existing settings Save/Cancel flow do not overwrite it.
Theme selection remains enabled while account preferences are loading.

The observed iOS root applies `preferredColorScheme` across normal content,
maintenance/update views and presented content. The separate debug overlay
window also updates its interface style, including `.unspecified` for System.
No view identity reset, navigation change, session restart or draft mutation
is performed when the theme changes.

## Verification

- Generic iOS Debug build passed: `build/appearance-generic.log`.
- Signed iPhone Debug build passed: `build/appearance-device-build.log`.
- Simulator Debug build passed: `build/appearance-simulator-build.log`.
- Independent source review found no missing repository conformances or
  conflicting production appearance overrides. `git diff --check` passed.
- Installed and launched `io.github.ghkdqhrbals.StudyMate` on the physical
  iPhone 16 Pro at 05:39:32 KST on 2026-09-11, using the existing development
  backend. Fresh session checks returned zero before installation and launch.
  This was an app update, preserving the existing app data.
- Manual theme switching, relaunch persistence and open-sheet appearance were
  not visually verified: iPhone Mirroring reported that the phone was in use
  and required locking, then timed out. Simulator GUI access also timed out.
  The user was notified of the device state. Build and device launch success
  do not substitute for a completed visual walkthrough.

No backend or deployment change was needed. No new automated test was added for
this reversible appearance preference.
