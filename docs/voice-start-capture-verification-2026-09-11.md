# Conversation startup capture fix — 2026-09-11

## Observed failure

Three user attempts at 14:40:23, 14:40:44 and 14:41:03 KST received a successful
session and SDP response, then ended after roughly 3–5 seconds. The device log
identifies `startupFailure` and `echo_cancellation_unavailable` before the control
WebSocket connects. `aecSoftwareActive=1` coexisted with `engineRunning=0` and
zero capture buffers. The server therefore never reached MCP session setup.
The REST cleanup path recorded `USER_ENDED`; this does not establish that the
user pressed End. Diagnostic evidence is retained as metadata only in
`build/voice-start-failure-metadata.json`.

The transport disables its local track until server session readiness, but it
required an active recording engine before connecting that same server control
channel. With this SDK's software AEC path, the disabled sender did not start the
input engine. Reapplying AEC options and waiting could not satisfy the condition.

## Change

`VoiceTutorEchoCancellationPolicy.startCaptureForReadiness` explicitly starts
local recording with software processing while the sender and speech gate remain
closed. The transport requires actual processed capture frames plus the live
software AEC/recording state before opening the control connection. Session-ready
continues to own the transition to transmitted audio and speech detection.
Teardown explicitly stops recording, including failure before the sender opens.
Diagnostics now include native recording initialization and running state.

The old opt-in device probe explicitly started recording with an enabled-default
track, so it did not exercise the failing startup assumption. It now calls the
production helper with a disabled track and closed speech gate, verifies that
neither opens during preparation, and only then checks Silero inference.

## Verification

- iOS generic build: `build/voice-start-capture-generic-ios.log`, succeeded.
- Simulator contract suite: 229 passed, 3 expected skips, no failures;
  `build/VoiceStartCaptureSimulator20260911.xcresult`.
- Physical iPhone 16 Pro, iOS 26.6.1: updated opt-in native capture probe passed,
  no skips; `build/VoiceStartCaptureDeviceProbe20260911.xcresult`.
- Device evidence: 6 valid input buffers / 2,880 frames at 48 kHz with
  `engineRunning=true`, `nativeRecording=true`, `aecSoftwareActive=true`,
  platform AEC/VPIO inactive, `trackEnabled=false`, `gateEnabled=false`, and
  zero speech inferences. Opening only the local gate then produced 5 Silero
  inferences while the track stayed disabled. No provider connection, recording
  file, or speech content is part of this probe.
- Signed app was installed by the physical test runner and launched normally
  against the configured development backend afterward. Live-session gates were
  zero before device actions.

Normal conversation-screen verification remains pending: iPhone Mirroring
reported that the phone was in use and then timed out. The updated production
capture path was verified on the physical device as described above, but that
probe does not establish a successful provider-connected conversation. This fix
does not change backend MCP tools or claim a new acoustic speaker-echo
measurement.
