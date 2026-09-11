# Post-AEC microphone hold

The learner reported a USER caption they did not speak after a cancelled
selection. The screenshot's interrupted tutor sentence followed by a learner
caption is consistent with an acoustic input edge; it does not alone prove
which audio reached the recognizer.

Inspection found a concrete input-hold defect in the pinned WebRTC SDK. The
app disables platform Voice Processing I/O and uses software AEC. The SDK's
default mute mode is VoiceProcessing: `setMicrophoneMuted(true)` stores the
requested flag, but that mode does not silence PCM when VPIO is disabled.
Checking `isMicrophoneMuted` therefore verified intent rather than the actual
microphone stream. The local speech detector was held while audio could still
reach RTP. Optional recording also ran before the local hold check.

The production capture-post-processing callback now zeroes all outgoing
channels whenever input is not ready, muted, closed or failed. This happens
under the same lock as VAD and before the callback returns audio for encoding.
Held audio is excluded from optional recording and local speech inference.
`setMuted` checks that the real sample gate applied, not the ineffective native
flag. A terminal close also disables the sender track. Native capture, render
and software AEC remain running during holds so the canceller keeps its input
and render reference. Normal open-microphone interruption remains available.
No transcript rewriting or similarity filter was introduced.

The form cancel/result protocol still clears the provider input buffer and
requires exact acknowledgments. This patch fixes sample leakage while input is
held; a fully open microphone still relies on acoustic echo cancellation.

Pinned SDK implementation references:

- [Default mute mode](https://github.com/webrtc-sdk/webrtc/blob/df1011beabae993c555f7c11a7a4b8e8fa62480d/modules/audio_device/audio_engine_device.h)
- [Mute implementation and input mixer](https://github.com/webrtc-sdk/webrtc/blob/df1011beabae993c555f7c11a7a4b8e8fa62480d/modules/audio_device/audio_engine_device.mm)
- [Capture/render processing order](https://github.com/webrtc-sdk/webrtc/blob/df1011beabae993c555f7c11a7a4b8e8fa62480d/modules/audio_processing/audio_processing_impl.cc)

## Verification

- Generic iOS Debug build passed (`voice-echo-input-gate-generic.log`).
- Signed iPhone build-for-testing passed, and the updated app was launched
  normally on the connected iPhone after testing. There were no active backend
  voice sessions before device tests or normal relaunch.
- Physical iPhone 16 Pro / iOS 26.6.1:
  `testOptInNativeEchoDoesNotCreateLearnerTurnsAfterInputHold` passed, with no
  skipped assertions. Result: `build/voice-echo-hold-resume-controlled.xcresult`.
  This opt-in test requires `BUDDYSTUDY_NATIVE_ECHO_TEST=1`.

The physical test initializes the production software-AEC graph without a
provider connection, SDP or enabled sender. An in-memory Korean system voice
is injected before render-reference analysis and reaches the real speaker.
A separate capture injection of the same voice is a positive control for the
production callback and bundled Silero model. It is not a human double-talk
test. No microphone samples, synthesized samples or transcript are saved;
the result attachment contains aggregate metadata only.

| Check | Observed result |
| --- | --- |
| Known nonzero native PCM while held | 9,600 samples checked; every outgoing sample zero |
| Known native PCM while open | 8,640 samples checked; every sample preserved |
| Same synthesized speech injected at capture | One speech start and its paired stop |
| Speaker playback while input held | 117,600 captured frames; outgoing peak zero; no speech starts |
| Speaker playback after input reopened | 192,000 captured frames; no speech starts |
| Two-second playback tail | 96,000 captured frames; no speech starts |
| Speaker output during reopen phase | 128,000 rendered speech frames and 192,000 mixer frames |
| Final output path | Built-in speaker, nonzero volume, capture/playout/engine running |

The initial physical run also passed at system volume 0.45. The subsequent
positive-control run above used the device's existing volume 0.15; the test
does not change volume. These are bounded acoustic observations, not proof
that every room, route, volume, real tutor voice or simultaneous human speech
is echo-free. The per-buffer peak/RMS measurements are maxima, not a measured
echo-return-loss value. The production fix guarantees the held PCM boundary;
open-input echo cancellation still needs observation in actual conversations.

Simulator regression covered contract, Silero, pause, interruption and user
input behavior. The first run had 300 passes, six opt-in skips and one stale
card assertion expecting the previously removed auxiliary "제출한 답변" label.
That assertion now checks the visible submitted status and absence of the old
helper, retaining all selection and custom-draft checks.
The card rerun passed five tests and skipped one opt-in fixture, with no
failures (`build/voice-echo-gate-regression-cards.xcresult`). Across both runs,
the 307 unique selected tests have 301 passes, six opt-in skips and no
outstanding failure. The original failing result is retained rather than
reported as a clean initial run.
