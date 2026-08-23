# Developer Access Version Taps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reveal developer options after five rapid taps on the Profile version row in Debug/TestFlight builds, remove promotion-code entry, and make the floating debug panel reliably draggable.

**Architecture:** A pure rapid-tap tracker owns gesture timing, while `AppDistributionContext` and `AppState` own build eligibility and persistence. The Profile view forwards the fifth tap without opening the debug overlay. A pure overlay-position policy clamps movement, and an explicit header drag handle avoids conflicts with buttons and log scrolling.

**Tech Stack:** Swift 6, SwiftUI, UIKit overlay window, XCTest, `SettingsStore` through `DeveloperSettingsUseCase`, Xcode/TestFlight GitHub Actions.

---

### Task 1: Build-scoped hidden developer unlock

**Files:**
- Modify: `StudyMate/Core/AppRuntime/AppRuntimeDependencies.swift`
- Modify: `StudyMate/Debug/AppDebugControls.swift`
- Modify: `StudyMate/ViewModels/AppState.swift`
- Test: `StudyMateiOSTests/PageAccessPolicyTests.swift`

- [ ] **Step 1: Write failing policy tests**

Add tests that construct explicit Debug, TestFlight, and App Store `AppDistributionContext` values and verify:

```swift
XCTAssertTrue(AppDistributionContext(isTestFlight: false, buildIdentifier: "1.1.0(80)", isDebugBuild: true).allowsHiddenDeveloperUnlock)
XCTAssertTrue(AppDistributionContext(isTestFlight: true, buildIdentifier: "1.1.0(80)", isDebugBuild: false).allowsHiddenDeveloperUnlock)
XCTAssertFalse(AppDistributionContext(isTestFlight: false, buildIdentifier: "1.1.0(80)", isDebugBuild: false).allowsHiddenDeveloperUnlock)
```

Add tracker tests that register four taps inside two seconds, then a fifth tap, and separately verify an expired window restarts at one tap.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
xcodebuild test -project StudyMate.xcodeproj -scheme StudyMateiOS -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -only-testing:StudyMateiOSTests/DeveloperAccessPolicyTests
```

Expected: compile failure because `isDebugBuild`, `allowsHiddenDeveloperUnlock`, and `RapidDeveloperUnlockTapTracker` do not exist.

- [ ] **Step 3: Implement the distribution and tap policies**

Extend `AppDistributionContext` with an explicit `isDebugBuild` value and:

```swift
var allowsHiddenDeveloperUnlock: Bool {
    isDebugBuild || isTestFlight
}
```

Populate the live value with the compile configuration. Add `RapidDeveloperUnlockTapTracker` with a five-tap threshold and two-second window to `AppDebugControls.swift`.

- [ ] **Step 4: Add the AppState unlock action**

Replace promotion-code redemption with:

```swift
@discardableResult
func unlockDeveloperAccessFromVersionGesture() -> Bool {
    guard appDistributionContext.allowsHiddenDeveloperUnlock else { return false }
    developerSettingsUseCase.saveDeveloperAccessUnlocked(true)
    developerSettingsUseCase.saveDeveloperAccessBuildIdentifier(
        appDistributionContext.isTestFlight ? appDistributionContext.buildIdentifier : nil
    )
    applyDeveloperFeatureAccess(.fullyAllowed, reason: "version-five-taps")
    return true
}
```

The action must not set `isAPIDebugPanelPresented`.

- [ ] **Step 5: Verify GREEN**

Run the targeted `DeveloperAccessPolicyTests` command again. Expected: PASS.

### Task 2: Version-row entry and promotion UI removal

**Files:**
- Modify: `StudyMate/Views/MobileRootView.swift`
- Modify: `StudyMate/StudyMateiOSApp.swift`
- Modify: `StudyMate/Models/StudyModels.swift`
- Modify: `StudyMate/ViewModels/AppState.swift`
- Test: `StudyMateiOSTests/ArchitecturePolicyTests.swift`
- Modify: `docs/PRD.md`
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Write failing architecture tests**

Assert that the Profile version section contains a plain button with `registerDeveloperVersionTap()`, and that source files no longer contain:

```swift
DeveloperPromotionCodeVerifier
redeemDeveloperPromotionCode
promotionCodePlaceholder
MaintenanceDeveloperAccessSheet
```

Assert that maintenance five-tap handling calls the bypass only behind `appState.canAccessDeveloperOptions`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
xcodebuild test -project StudyMate.xcodeproj -scheme StudyMateiOS -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -only-testing:StudyMateiOSTests/ArchitecturePolicyTests
```

Expected: FAIL because the version row is not interactive and promotion-code paths still exist.

- [ ] **Step 3: Implement version-row taps**

Store `RapidDeveloperUnlockTapTracker` in `MobileProfilePage`. Render the existing version row inside a plain `Button`; each tap calls the tracker with `Date()`, and only a completed sequence calls `appState.unlockDeveloperAccessFromVersionGesture()`.

- [ ] **Step 4: Remove promotion-code entry**

Delete the promotion card and its local state from `MobileSettingsView`. Delete the verifier, redemption state, actions, and unused localized strings. Remove `MaintenanceDeveloperAccessSheet`; maintenance taps call `bypassMaintenanceForDeveloper()` only if developer access was already restored for the current build.

- [ ] **Step 5: Update product and architecture contracts**

Document that hidden developer access is a five-tap Profile version gesture limited to Debug/TestFlight, build-scoped on TestFlight, and unavailable in App Store production.

- [ ] **Step 6: Verify GREEN**

Run the targeted architecture tests again. Expected: PASS.

### Task 3: Reliable debug-overlay movement

**Files:**
- Modify: `StudyMate/Debug/AppDebugControls.swift`
- Modify: `StudyMate/StudyMateiOSApp.swift`
- Test: `StudyMateiOSTests/PageAccessPolicyTests.swift`

- [ ] **Step 1: Write failing offset-policy tests**

Test that a proposed drag accumulates from the committed offset and clamps both axes using the container and current panel size:

```swift
XCTAssertEqual(
    DebugOverlayPositionPolicy.offsetAfterDrag(
        committed: CGSize(width: 12, height: 74),
        translation: CGSize(width: 500, height: -500),
        containerSize: CGSize(width: 390, height: 844),
        panelSize: CGSize(width: 300, height: 64),
        margin: 12
    ),
    CGSize(width: 78, height: 12)
)
```

- [ ] **Step 2: Run tests and verify RED**

Run the targeted `DeveloperAccessPolicyTests` test command. Expected: compile failure because `DebugOverlayPositionPolicy` does not exist.

- [ ] **Step 3: Implement the pure position policy**

Add `DebugOverlayPositionPolicy.boundedOffset` and `offsetAfterDrag` to `AppDebugControls.swift`, then replace the view-local offset arithmetic with that policy.

- [ ] **Step 4: Add a dedicated header drag surface**

Add a non-button `line.3.horizontal` header handle with a minimum 32-point hit target and attach `dragGesture(in:)` only to that handle. Remove the ambiguous simultaneous drag gesture from the button-filled header. Keep button taps and log scrolling independent.

- [ ] **Step 5: Verify GREEN**

Run targeted developer policy tests and architecture tests. Expected: PASS.

### Task 4: Full verification, commit, and TestFlight deployment

**Files:**
- Verify: `StudyMate.xcodeproj`
- Deploy: `.github/workflows/ios-testflight.yml` or the repository's current TestFlight workflow

- [ ] **Step 1: Run diff validation and focused tests**

```bash
git diff --check
xcodebuild test -project StudyMate.xcodeproj -scheme StudyMateiOS -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -only-testing:StudyMateiOSTests/DeveloperAccessPolicyTests -only-testing:StudyMateiOSTests/ArchitecturePolicyTests
```

Expected: all selected tests pass.

- [ ] **Step 2: Run the generic iOS build**

```bash
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
```

Expected: `BUILD SUCCEEDED`.

- [ ] **Step 3: Build, install, and launch on the connected iPhone**

```bash
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'platform=iOS,id=00008140-00120C8E2E60801C' -derivedDataPath build/iPhoneRunDerivedData -allowProvisioningUpdates build
xcrun devicectl device install app --device 00008140-00120C8E2E60801C build/iPhoneRunDerivedData/Build/Products/Debug-iphoneos/StudyMate.app
xcrun devicectl device process launch --device 00008140-00120C8E2E60801C --terminate-existing io.github.ghkdqhrbals.StudyMate
```

Expected: signed build succeeds, installation succeeds, and the app launches.

- [ ] **Step 4: Commit implementation**

Stage only the implementation, tests, and contract documents, then commit with:

```bash
git commit -m "feat(ios): unlock developer options from version taps"
```

- [ ] **Step 5: Upload TestFlight build**

Use the repository's existing iOS TestFlight GitHub Actions workflow. Wait for archive, upload, and final Slack deployment notification to succeed. Do not deploy backend, admin, monitoring, or routing modules.
