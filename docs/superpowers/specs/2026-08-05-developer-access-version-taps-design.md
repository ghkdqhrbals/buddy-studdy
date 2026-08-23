# Developer access from the version row

## Goal

Replace promotion-code entry with a hidden, build-scoped developer-access gesture and restore reliable movement of the floating debug log panel.

## Scope

- The Profile version row accepts five rapid taps within two seconds.
- The gesture is enabled only for Debug and TestFlight distributions.
- App Store production builds ignore the gesture and never expose developer options through it.
- A successful gesture reveals developer options but does not automatically open the debug panel.
- TestFlight access remains scoped to the current version/build. Installing a new TestFlight build requires five taps again.
- Debug access may persist normally in a Debug build.
- Promotion-code entry UI, formatter, verifier, state, and redemption actions are removed.
- The maintenance screen no longer presents a promotion-code sheet. Its hidden five-tap bypass works only when developer access is already available for the current build.

## Interaction design

The version row keeps its existing visual appearance. No hint, badge, toast, or visible developer affordance is added. Four taps have no visible effect. The fifth tap within the two-second window grants access, and the Developer Options card appears when the user opens Settings. The debug panel remains an explicit action after access has been granted.

The floating debug panel has a dedicated draggable header surface. Header buttons remain tappable, while dragging the non-button header area moves the panel. Log lists and detail scroll views do not initiate panel movement. The panel remains clamped within the current window bounds after dragging, expansion, rotation, and size changes.

## State and data flow

`AppDistributionContext` decides whether hidden unlocking is allowed. The Profile view owns only the five-tap timing state and delegates a successful sequence to `AppState`. `AppState` persists developer access through `DeveloperSettingsUseCase`, using the current build identifier for TestFlight and no build identifier for Debug. The existing feature-access projection then reveals developer settings without opening the overlay.

Maintenance bypass checks existing developer access before bypassing. It does not grant access itself, so App Store users and locked TestFlight builds cannot bypass maintenance through the maintenance copy.

## Error handling

- A sequence slower than two seconds resets to the first tap.
- App Store distributions ignore taps without mutating persisted settings.
- A build change invalidates persisted TestFlight developer access and disables debugging as it does today.
- Moving the debug panel cannot place its header outside the usable window bounds.

## Verification

- Unit tests cover four taps, the fifth tap, expired tap windows, Debug/TestFlight allowance, App Store rejection, and TestFlight build invalidation.
- Architecture tests verify that promotion-code UI and verifier paths are absent.
- Drag-layout tests verify offset accumulation and boundary clamping independently from SwiftUI gestures.
- Run the `StudyMateiOS` test target, generic iOS build, and real-device build/install/launch.
- After local verification, upload a new TestFlight build through the existing iOS deployment workflow and wait for the workflow result.
