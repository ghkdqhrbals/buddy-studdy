# Native simulator capture recipe

Run from the release worktree using the `StudyMateiOS` scheme. Captures use existing synthetic DEBUG fixture entry points; no production session is required. The script selects app language with `SIMCTL_CHILD_BUDDYSTUDY_SCREENSHOT_LANGUAGE`, and each screen with `SIMCTL_CHILD_BUDDYSTUDY_SCREENSHOT_FIXTURE`. It sets a 9:41 charged status bar and light appearance, waits four seconds for fixture layout, then writes raw PNGs with `simctl io screenshot`.

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath build/iOSSimulatorDerivedData CODE_SIGNING_ALLOWED=NO build
xcrun simctl boot "$CAPTURE_UDID"
xcrun simctl bootstatus "$CAPTURE_UDID" -b
xcrun simctl install "$CAPTURE_UDID" build/iOSSimulatorDerivedData/Build/Products/Debug-iphonesimulator/StudyMate.app
python3 app-store/releases/1.3.0/design/capture-native.py "$CAPTURE_UDID" iphone-6.5 app-store/releases/1.3.0/native --locales ko en ja
xcrun simctl status_bar "$CAPTURE_UDID" clear
```

Device directory and model mapping:

| Directory | Simulator model | Native portrait pixels | App locales |
| --- | --- | --- | --- |
| iphone-6.5 | iPhone 11 Pro Max | 1242×2688 | ko, en, ja |
| iphone-6.9 | iPhone 16 Pro Max | 1320×2868 | ko, en, ja |
| ipad-13 | iPad Pro 13-inch (M4) | 2064×2752 | ko, en, ja |
| iphone-6.3 | iPhone 16 Pro | 1206×2622 | en |

`en` is stored under `en-US`. All simulator runtimes here were iOS26.0. Six default fixtures are `learning-result`, `follow-up`, `feed`, `study-tree`, `statistics`, and `custom-question`.

For the final deployed-source preservation recapture, the command used `--fixtures follow-up custom-question` for each model; English1206 also used `--locales en`. The other forty native files remain byte-identical to the previous capture. Per-image source commit and SHA-256 in `native-capture-manifest.json` are authoritative. A signed archive supplies its own build number; these native screenshots use Debug app1.3.0 build16.

The script is portable across these booted simulators once the matching Debug app is installed. Simulator UUIDs are machine-local and must be obtained with `xcrun simctl list devices available`. Restore pre-existing device states after capture, and remove only temporary devices created specifically for the task.
