# Echo protection and cancelling a voice exercise

## Audio findings and changes

The native WebRTC path already requested the audio-engine device with voice
processing bypass disabled, in an AVAudioSession voiceChat category. The exact
bundled SDK enables communication processing by default; config:nil alone is
not evidence that native echo cancellation was disabled. The separate legacy
PCM graph, however, did not explicitly enable Voice Processing I/O. A voiceChat
session category alone does not configure that graph.

The PCM engine now enables its coupled voice-processing input/output path before
formats, taps and playback are installed. Native WebRTC explicitly requests
communication AEC/NS/AGC/high-pass options, reads actual processing state before
opening capture, and rejects an unavailable processing path rather than sending
unprotected microphone input. Audio route/graph changes add bounded diagnostics
of active processing and route types. No audio content is logged or recorded.
Normal learner barge-in stays available while the tutor speaks; no playback-wide
microphone mute or transcript text-similarity suppression was introduced.
The production capture tap is installed as the SDK's capture post-processor;
Silero receives those processed frames. A disabled sender track mutes samples
without preventing ADM startup after SDP, so the pre-capture readiness guard
does not wait on its own microphone gate.

Exact SDK sources used in the investigation:

- [VoiceEngine default processing options](https://github.com/webrtc-sdk/webrtc/blob/df1011beabae993c555f7c11a7a4b8e8fa62480d/media/engine/webrtc_voice_engine.cc#L526)
- [Audio-engine platform processing](https://github.com/webrtc-sdk/webrtc/blob/df1011beabae993c555f7c11a7a4b8e8fa62480d/modules/audio_device/audio_engine_device.mm)
- [Native ADM recording API](https://github.com/webrtc-sdk/webrtc/blob/df1011beabae993c555f7c11a7a4b8e8fa62480d/sdk/objc/api/peerconnection/RTCAudioDeviceModule.mm#L349)
- [Capture AEC and post-processor order](https://github.com/webrtc-sdk/webrtc/blob/df1011beabae993c555f7c11a7a4b8e8fa62480d/modules/audio_processing/audio_processing_impl.cc)

## Cancellation scenarios

| Learner action | Result |
| --- | --- |
| Cancel a choice form | No selection is authorized; acknowledge once and return to ordinary conversation without reopening the same form. |
| Cancel exercise while answering, finalizing, reviewing or paused | Send an exact answer-ID/record-ID control; preserve the draft; stop that exercise without a new submit, skip, delete or hang-up. |
| Cancel while a submission or Skip is already in progress | Do not offer cancellation as if it could undo an already accepted operation. Earlier submissions/jobs remain authoritative. |
| Cancellation acknowledged | Keep microphone input held until the server-owned next-step form takes over; delayed ASR cannot restart the cancelled exercise. |
| Choose another topic | Resolve a new actual topic choice before beginning another exercise. |
| Choose free conversation or continuing later | Keep the exercise stopped and return to conversation. The call remains open. |
| Cancel the next-step form | Return to free conversation; no implicit restart. |
| Next-step form cannot be delivered | A bounded delivery timeout ends the failed connection rather than leaving it permanently muted. |

The new UI uses the same compact choice card and independent input editor.
Cancel exercise appears in the answer card and compact conversation screen;
its busy state retains the draft. Existing pause ownership remains independent.
The answer-control parser accepts only the added bounded cancellation codes.
Duplicate cancellation acknowledgements cannot reacquire a released input hold.

Canonical answer-capture source is retained as private history and excluded from
post-call learning evidence, including late ASR. Explicit canonical submission
retains its existing grading path. No public transcript DTO or database migration
is required for this internal source classification.

## Verification and runtime

- Final generic iOS build passed: `build/voice-echo-cancel-generic-final.log`.
- Broad simulator suite: 291 passed, 4 opt-in tests skipped, 0 failures;
  `build/voice-echo-cancel-simulator.xcresult`. Final affected checks after the
  running-engine guard: 3 passed, 0 failures/skips;
  `build/voice-echo-cancel-simulator-final.xcresult`.
- Broad iPhone suite: 278 passed, 16 opt-in/host-accessibility tests skipped,
  0 failures; `build/voice-echo-cancel-device.xcresult`. Final answer state,
  audio policy and production screen rendering: 19 passed, 0 failures/skips;
  `build/voice-echo-cancel-device-final.xcresult`.
- The simulator actually activates Cancel exercise in compact/transcript and
  active/paused screens, checking the exact command, draft and microphone hold.
  iPhone production UI captures were inspected for answer capture and cancelling
  states; `build/voice-echo-cancel-device-final-ui/` contains the final renderings.
- Physical iPhone 16 Pro, iOS 26.6.1 native capture probe passed separately,
  with opt-in flags for microphone capture and the bundled Silero model:
  `build/voice-echo-native-device-final.xcresult`. The platform AEC was active,
  VPIO was enabled and not bypassed, and native input frames reached Silero.
  Metadata-only evidence is in `build/voice-echo-native-device-final-metadata/`.
- The opt-in legacy PCM VPIO graph configuration test passed. Initially running
  it immediately before the native probe left the latter's actual engine stopped
  despite recording/AEC flags remaining active: 2 frames, no Silero inferences.
  The native probe passed in its own fresh process, retaining its original frame
  and inference thresholds. This observation additionally led to the explicit
  engine-running/recording readiness guard. It is consistent with graph
  reconfiguration between fixtures, but does not identify an exact OS cause.

No probe stores audio, contacts the speech provider, generates questions or
submits answers. The microphone probe checks active native processing and real
capture callbacks, not a full SDP exchange or measured acoustic echo attenuation.
No live provider conversation or speaker/headset echo-removal percentage is
claimed. The reported historical native echo was not conclusively reproduced;
the confirmed missing VPIO setup, runtime protection and cancellation defects
are addressed explicitly.

- Focused backend regression passed: application 67 and infra 334 tests, with
  no failures, errors or skips; `build/voice-learning-cancellation-final-tests.log`.
  Infra totals include the eight suites run at 20:27 UTC, including the
  persistence report with a `__TEST-` filename; unrelated older reports are
  excluded. Coverage includes the real native relay, exact input controls,
  delivery acknowledgement, paused cancellation, late ASR, private history,
  unchanged explicit submission and resuming the same saved question only after
  a new learner request.
- Endpoint tests also caught the new cancellation control and acknowledgement
  codes missing from the shared event filter; both are now registered and
  verified through that path. Private source metadata is stripped before
  forwarding events to the client.

## Development rollout

- `:tutor:bootJar` passed in 22 seconds; `build/voice-echo-cancel-bootjar.log`.
  Artifact: `backend/tutor/build/libs/buddystudy-backend.jar`, 332,459,204 bytes.
  SHA-256: `dc038077df143df709b76312dd7e7f205a60807eac04d5dd67de1ee643263eb8`.
- At 05:29 KST on 2026-09-11, replaced the JAR atomically in the existing
  `buddystudy-feature20-dev-app` volume and restarted only `backend-backend-1`.
  A fresh database check returned zero active/ending/reserved/ready sessions or
  processing results immediately before replacement. The unchanged runtime
  image was reused; no Docker image was built.
- The previous JAR is retained at
  `/app/buddystudy-backend.pre-echo-cancel-20260911.jar`, SHA-256
  `c8fccde9b0f35c8ac7988928126a7ed9ce66e510374e795359c62df44a9bb147`.
  The new volume JAR checksum matches the locally verified artifact.
- After startup, both `http://localhost:8080/actuator/health` and
  `https://lowfidev.cloud/actuator/health` returned `status: UP`.
- Installed the final signed `StudyMateiOS` Debug app on the iPhone and launched
  `io.github.ghkdqhrbals.StudyMate` at 05:30:05 KST with the development base URL
  `https://lowfidev.cloud`. Fresh active-session checks returned zero before both
  installation and launch. Data and existing drafts were retained.
- A subsequent iPhone Mirroring inspection reported a connection timeout
  requiring the phone to be locked. This was not counted as a successful live
  UI walkthrough; the physical-device rendering and native microphone evidence
  above are the actual device verification.

No production deployment or schema migration was performed. `git diff --check`
passed before committing the implementation and this verification record.
