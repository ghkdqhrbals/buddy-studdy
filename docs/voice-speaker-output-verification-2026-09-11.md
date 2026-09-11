# Voice speaker output — 2026-09-11

## Report and evidence boundary

After the duplex startup correction (`c293c38b`), the user reported that the
connected conversation was inaudible. The 15:35:26 KST device session reached
media-ready, received both provider responses, and stayed running. It reported
a Speaker route, nonzero system output volume, active recording/playout and
software AEC. Its remote renderer saw nonzero samples.

That renderer observes WebRTC receive PCM **before** receive-source gain and
final hardware mixing. Nonzero remote buffers are not proof of audible sound.
The earlier startup verification established connection and PCM reception, but
its description of these callbacks as playback evidence was too strong.
Metadata-only evidence: `build/voice-silent-20260911-metadata.json`.

## Configuration correction

The transport deliberately disables Apple Voice Processing I/O and uses WebRTC
software AEC. Its AVAudioSession still selected `voiceChat`. Apple's documented
behavior for chat modes without Voice I/O includes lower playback level. The
upstream LiveKit audio-session implementation similarly selects `.default` with
`.playAndRecord` for software processing to preserve speaker gain.

The shared capture policy now configures `.playAndRecord + .default`, retains
`.defaultToSpeaker` and Bluetooth HFP, and preserves the existing input-first
startup, software echo cancellation and closed sender before session readiness.
It does not force system volume or enable a second echo canceller. The physical
capture probe uses this same policy and verifies the session mode before and
after duplex engine initialization. The legacy Apple VPIO probe is unchanged.

Sources:
- [Apple voiceChat mode](https://developer.apple.com/documentation/avfaudio/avaudiosession/mode-swift.struct/voicechat)
- [LiveKit software-processing session configuration](https://github.com/livekit/client-sdk-swift/blob/11fa92a70305974dd1a29ab4814a36848f324021/Sources/LiveKit/Types/AudioSessionConfiguration.swift#L66-L79)
- [LiveKit engine observer mode selection](https://github.com/livekit/client-sdk-swift/blob/11fa92a70305974dd1a29ab4814a36848f324021/Sources/LiveKit/Audio/AudioSessionEngineObserver.swift#L250-L260)

## Output diagnostics

A read-only ADM observer measures the final main-mixer output. It records only
aggregate callback/frame counts, maximum RMS/peak, mixer volume, output format
and graph connectivity. It does not keep audio samples or alter the graph,
volume, input, output, or echo cancellation. One first-output diagnostic is
queued off the realtime thread. Tap installation and removal follow engine
reconfiguration and call teardown.

The existing remote-source counter is now explicitly labeled
`pcmStage=remote_source_before_gain` and includes receive-source volume.
Mixer evidence distinguishes missing/attenuated graph output from successful
PCM reception, but physical audibility still requires listening confirmation.

## Verification

- Signed iOS build-for-testing and generic iOS Debug build succeeded.
- Simulator VoiceTutorContractTests: 229 passed, 3 expected skips, no failures;
  `build/VoiceSpeakerOutputSimulator20260911.xcresult`.
- Physical iPhone 16 Pro / iOS 26.6.1 production capture probe: 1 passed, no
  skips/failures; `build/VoiceSpeakerOutputDeviceProbe20260911.xcresult`.
- The physical probe observed 18 valid input buffers (8,640 frames at 48 kHz),
  software AEC active, recording/playout active, and the sender/speech gate
  closed. Its final mixer tap received 4,800 frames, mixer volume was 1.0,
  the output graph was connected, and hardware output was 48 kHz / 2 channels.
  No provider signal is fed in this probe, so mixer RMS/peak were zero as
  expected; this test does not establish audibility.
- The signed app was installed by the device test runner and relaunched against
  the configured development backend. There were no live voice sessions before
  test or launch actions.
- The user was asked to start a conversation directly on the iPhone after
  disconnecting mirroring and confirm whether the first tutor voice is audible.
  That physical listening confirmation is pending.
- Independent read-only review checked default input/output graph preservation,
  observer lifetime and tap removal/locking. No further issue was found.
