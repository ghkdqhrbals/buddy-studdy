# Bundled Silero acoustic VAD

This is a speech-versus-nonspeech model, not a classifier of meaningful words,
readiness, lesson intent or end-of-turn semantics. Short affirmatives, negatives
and voiced hesitation are all legitimate acoustic speech; the backend owns the
separate contextual meaningful-input decision.

## Source and license

- Artifact: `silero-vad-unified-v6.0.0.mlpackage` from
  [FluidInference/silero-vad-coreml](https://huggingface.co/FluidInference/silero-vad-coreml/tree/b419383c55c110e2c9271fa6ee0ea83d03c70d96).
- Pinned Hugging Face revision:
  `b419383c55c110e2c9271fa6ee0ea83d03c70d96`.
- Original Silero checkpoint, as documented by the converter:
  `fba061dc5559f696e62171e9a0741782b0fdc23c`.
- Converter/source documentation:
  [FluidInference/mobius](https://github.com/FluidInference/mobius/tree/4040a39f760290bb1d43a72dc82e5894f27b0f5c/models/vad/silero-vad/coreml).
- Model license: MIT, Copyright (c) 2020-present Silero Team. The complete
  license is included as `SileroVAD-LICENSE.txt` and copied into the iOS bundle.
- The app's Core ML adapter is local code. FluidAudio's general-purpose package,
  its model downloader, and any additional runtime are not dependencies.

## Exact artifact integrity

| File inside the package | Bytes | Upstream identity |
| --- | ---: | --- |
| `Data/com.apple.CoreML/model.mlmodel` | 22,122 | SHA-256 `532a63e7db357d739761635285c295b6d440a07d081951e914351efc1f6fd8d9` |
| `Data/com.apple.CoreML/weights/weight.bin` | 882,304 | SHA-256 `853cf34740d3f5061f977ebe2976f7c921b064261c9c4753b3a1196f2dba42b4` |
| `Manifest.json` | 617 | SHA-256 `277a653e3968df81c0b3a5eca8f8cf8a19cb46578f83e15d08dfb769abfdf8f9`; Git blob `c4a6501f060630e4b8a3d32da2cfee6575c05dec` |

The original three-file package totals 905,043 bytes. Both binary SHA-256 values
and the manifest's Git blob identity are checked before inclusion. Xcode
compiles this package into the iOS app's `.mlmodelc` resource; application code
loads only that local bundled resource and has no model-download path.

## Streaming contract

- Silero v6 unified standard model; Core ML specification 6, minimum iOS 15
  (the app targets iOS 17+).
- 16,000 Hz mono Float32 input. One prediction consumes 512 new samples (32 ms)
  preceded by the last 64 samples from the previous prediction.
- Inputs: `audio_input` `[1,576]`, `hidden_state` `[1,128]`, `cell_state` `[1,128]`.
- Outputs: `vad_output` `[1,1,1]`, `new_hidden_state` `[1,128]`,
  `new_cell_state` `[1,128]`; all Float32.
- Hidden/cell state and context belong to one capture generation, never a user
  profile or persistent store. Native FloatS16 samples are copied and normalized
  without altering RTP or consented recording. Silence is not skipped, because
  it must advance recurrent state and the trailing-silence boundary.
- The 32 ms variant avoids the additional 256 ms aggregation delay of
  FluidAudio's current batch-oriented default. Probability hysteresis identifies
  acoustic boundaries only; no word list, transcription rule or tutor-playing
  state participates in it.

## Local runtime and failure boundaries

The view model preflights the bundled model before creating an application
session; the transport also preflights if constructed without a prepared scorer.
Preflight includes exact tensor-schema validation, a real zero-frame warm-up,
and state reset, with a five-second timeout and cancellation. No remote model
URL, model-hub package, `URLSession`, or runtime compilation/download is used by
the scorer.

The native callback copies normalized mono input into a single bounded mailbox.
One worker keeps an `AVAudioConverter`, residual samples, context, and recurrent
state across native packets. It consumes quiet samples too. Speech starts after
at least 80 ms at probability 0.5 or greater, and ends after at least 480 ms below
0.35; 32 ms windows quantize these to 96 ms and 480 ms respectively. These are
acoustic hysteresis thresholds, not a minimum meaningful-word or sentence length.
Returning speech resets the quiet hold. An acoustic stop only opens server-side
transcription/semantic assessment; it never authorizes a tutor response by itself.

Only media readiness, explicit user mute, close, and a native format change
reset this evidence. The teacher's response/playback state is not an input gate.
Gate/format changes advance a generation, so old queued or in-flight results
cannot produce events in a new generation. Existing positive, paired utterance
sequences are retained across mute/format resets.

The mailbox allows at most 32 packets and 320 ms of pending audio. Packet age is
bounded at 750 ms; an independent, reusable watchdog bounds one prediction at
250 ms even if no more input arrives. Overrun, malformed input/output, and
resampling/inference errors have fixed metadata-only error codes and terminate
the affected call through its existing failure path. There is no quiet RMS
fallback or indefinite waiting for an unavailable model.

## Verification entry points

`StudyMateiOSTests/VoiceTutorSileroTests` contains offline model, sample-scale,
resampling/chunk continuity, probability/gate, ordered queue, reset/close and
failure contracts. Its explicit opt-in method
`testOptInBundledSileroRecognizesSyntheticKoreanSpeechIncludingHesitation` uses
an already-installed, non-personal Korean system voice to generate fixed test
speech in memory. It includes short affirmative/negative and voiced hesitation,
all of which must be acoustic speech regardless of their contextual meaning.

For that selected method, the host runner forwards
`TEST_RUNNER_BUDDYSTUDY_SILERO_SYNTHETIC_VOICE_TEST=1`. The test does not play
audio, open a microphone, save PCM, use an account/quota, or call a provider.
Synthesis is bounded at six seconds per case. Missing Korean synthesis is an
explicit skip, not a successful acoustic check. Its attachment contains case
labels, counters, flags and inference latency only. This probe is separate from
real native-capture integration and conversational end-to-end verification.

The upstream model card and converted-model metadata are evidence of provenance
and interface, not proof of this app's accuracy or device performance. The iOS
contract/device tests must verify loading, continuous capture, speech/noise,
state reset, bounded queues and explicit failure before rollout.
