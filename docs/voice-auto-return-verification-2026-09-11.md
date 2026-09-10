# Automatic return after a call

The live call destination now returns to the previous screen automatically when
the view model reaches `.ended`. The existing `.ending` phase retains ownership
until final playout, recording handling and server/result refreshes finish.
An inactive scene defers navigation until it becomes active. Failed calls keep
their error and retry/close controls. The call entry's existing consumed-start
intent prevents this return from starting another call.

- Generic iOS Debug build passed with the `StudyMateiOS` scheme and signing
  disabled: `build/voice-auto-return-generic-build.log`.
- Signed iPhone build-for-testing passed:
  `build/voice-auto-return-device-build.log`.
- Both new rendered navigation tests passed on Min iPhone (iOS 26.6.1):
  `build/voice-auto-return-device-tests.xcresult`. A presented
  `UIHostingController` with a real `NavigationStack` verifies that the production
  modifier pops its bound path on completion. `.ending` and `.failed` preserve
  the destination; injected background and inactive states defer the pop until
  active. The fixtures do not create a network call or use the microphone, so
  this verifies navigation on hardware, not live background audio.
- Four simulator checks passed: both navigation tests, quick-call intent not
  replaying on return, and existing terminal playout/dismissal ownership:
  `build/voice-auto-return-simulator-tests.xcresult`.
- Installed the signed app on Min iPhone and launched it against the existing
  `https://lowfidev.cloud` development endpoint. No backend rollout was needed.
