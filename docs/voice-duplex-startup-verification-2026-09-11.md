# Voice duplex startup — 2026-09-11

Follow-up: [speaker output verification](voice-speaker-output-verification-2026-09-11.md)
corrects the distinction between remote PCM reception and audible hardware
output after a user reported that these connected calls were inaudible.

## Incident and cause

The previous capture-start fix (`1a1032e1`) passed a standalone microphone probe
but still failed in two user attempts at 15:10:49 and 15:11:09 KST. Both reached
SDP exchange and failed before control-channel readiness. At 15:14, the same
failure was reproduced through iPhone Mirroring with stage-specific diagnostics:
platform policy and track configuration succeeded; native recording start
returned **-4010**. The failure metadata is in
`build/voice-start-late-input-failure-metadata.log`.

In the installed LiveKitWebRTC SDK, -4010 is
`kAudioEngineRecordingDeviceNotAvailableError`. Its iOS branch rejects an input
node with zero sample rate or channel count. Actual calls initialized remote
playout through SDP before starting capture. Adding input to that output-only
graph then failed; the SDK stopped the engine while its old logical playing
state remained true. This explains `playing=1`, `engineRunning=0`, and no valid
input buffers. The old probe never added a sender or started playout.

Source: [SDK audio engine implementation](https://github.com/webrtc-sdk/webrtc/blob/df1011beabae993c555f7c11a7a4b8e8fa62480d/modules/audio_device/audio_engine_device.mm).

## Corrected ordering

After connection resources are installed, prepare native input and require live
AEC plus processed input frames **before creating/installing SDP**. The SDK
prepares duplex hardware when input starts first. RTP transmission and speech
detection remain closed until the existing server session-ready boundary.
After ICE/DTLS negotiation, check recording, AEC and input frames again without
restarting recording. Existing close/cancellation cleanup remains in place.

Stage diagnostics identify platform-policy, track-options and native-start
failures separately and retain the numeric SDK status. They contain no audio,
transcript or credentials.

The physical probe now installs a disabled local sender, starts input first,
then initializes and starts output. It requires additional processed input
buffers after output starts, active software AEC/duplex I/O, closed sender and
speech gate, local Silero inference after explicitly opening that gate, and
recording/playout teardown. It uses no SDP, ICE, provider or audio storage.

## Actual app verification

The updated signed app was installed on the user's iPhone 16 Pro running
iOS 26.6.1 and launched against the configured development backend. At 15:17:31
KST, a call started from the app's quick-conversation control:

- Native start returned 0; input/AEC readiness passed after 234 ms with five
  valid 48 kHz buffers and the speech gate still closed.
- After negotiation, both recording and playout remained active. Media readiness
  passed at 1,466 ms.
- The provider's first non-silent audio rendered at 4,125 ms; native capture and
  software AEC remained active.
- Mirroring showed the listening state and the first tutor caption,
  “어떤 주제로 이야기해 볼까요?”. The session stayed ACTIVE until test cleanup
  at 15:21:23, with no startup failure.
- This test-created session was ended through the development backend's normal
  ENDING state transition (the same persistence update used by requestEnd;
  scoped by the exact session ID and creation time). The server completed its
  normal cleanup, and the app automatically returned to the conversation entry
  screen. The physical long-press End gesture was not exercised.

Evidence: `build/voice-start-duplex-connected-metadata.log`.

After the physical probe and a normal app relaunch, a second actual call at
15:25:20 KST also succeeded: recording start returned 0, media connected at
1,911 ms, and the first non-silent tutor audio rendered at 4,319 ms. Mirroring
showed the listening state. This second test-created session was also cleaned
up through the same narrowly scoped development ENDING transition. Evidence:
`build/voice-start-duplex-repeat-metadata.json`.

Mirroring displayed its standard warning that the iPhone microphone cannot be
used from the Mac. These runs establish startup and tutor playback, not human
speech recognition or measured acoustic echo suppression.

## Automated verification

- Simulator VoiceTutorContractTests: 229 passed, 3 expected skips, no failures;
  `build/VoiceDuplexStartupSimulator20260911.xcresult`.
- Signed iOS build and device test build succeeded.
- Physical duplex probe: 1 passed, no skips/failures;
  `build/VoiceDuplexStartupDeviceProbe20260911.xcresult`. It observed six valid
  48 kHz input buffers after starting output, both recording/playing active,
  software AEC active, sender/gate closed and zero speech inferences. Opening
  only the local gate produced six Silero inferences while the sender stayed
  disabled. Explicit recording/playout teardown passed.
- Generic iOS Debug build succeeded:
  `build/voice-start-duplex-generic-ios.log`.
- Final signed app was installed by the physical test runner, relaunched with
  the existing development backend URL, and used for the second actual call.
- Read-only independent review found no additional regression in startup,
  cancellation, teardown or diagnostic queue behavior.
