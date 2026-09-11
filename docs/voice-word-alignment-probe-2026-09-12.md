# Word-boundary interruption feasibility — 2026-09-12

The learner wants the current word to finish before interruption, while keeping
the existing AI voice, and allows approximately one second of additional playback
latency. The shipped acoustic-gap policy is not a word-alignment implementation.
These measurements must not be presented as a completed playback fix.

## Method and limits

The opt-in `VoiceTutorWordAlignmentCapabilityTests` and
`VoiceTutorWordAlignmentLatencyTests` inspect installed speech assets and analyze
a fixed, locally synthesized Korean fixture. The fixture is 11.61 seconds long,
fed in real-time 10 ms PCM chunks, with two seconds of trailing silence. No user
audio, provider request, microphone input, or model download is involved.

The real iPhone has the Korean iOS 26 SpeechTranscriber model installed. Its
measurement used a separate temporary diagnostic app and bundled PCM file, with
no audio session or microphone. The existing BuddyStudy conversation remained
active in its original process throughout these probes. This measures the
installed recognizer's timing behavior on synthetic speech; it is not acoustic
validation against the provider's voice or every Korean word.

Fixture SHA-256:
`a908c5ed2232e7eb88a4d3fbb3d212c9cf9ccc32c5b1a270f9754595f4b7fa97`.

## Continuous-stream measurements

| Device / engine | Final word runs | Word-end-to-confirmation delay | Confirmed within 1 s |
| --- | ---: | --- | ---: |
| Simulator DictationTranscriber, default | 18 | 2.02–13.30 s | 0 |
| Simulator DictationTranscriber, frequent finalization | 18 | 1.92–11.12 s | 0 |
| iPhone SpeechTranscriber, progressive | 22 | 1.244–12.344 s | 0 |
| iPhone SpeechTranscriber, fast results | 22 | 1.243–12.343 s | 0 |

iPhone model preparation took 0.099 seconds for progressive and 0.114 seconds
for fast results. Preparation is measured separately from the table's
post-word delay and cannot make an over-budget result compliant.

The device probes account for `resultsFinalizationTime`, which can confirm an
earlier volatile result without reissuing it with `isFinal == true`. Both device
runs still confirmed all 22 word ranges only in the final callback at about
12.70 seconds. None was confirmed earlier by the watermark. Each of the 81
provisional timing runs started at zero and covered growing utterance text;
their earlier arrival therefore did not provide individual word ends.

Forcing simulator finalization every 500 ms produced 17 final runs within
0.873 seconds, but finalized “중요합니다” as “중요” at a forced cutoff, and
“있는” as “있어”. A timer can force the recognizer to end a word halfway
through. Such forced final ranges are unsuitable as stop targets.

The public Speech API provides transcription with time ranges, not a forced
alignment operation against a supplied exact transcript. A vocabulary hint or
matching recognized text alone does not establish an accurate acoustic end.
See Apple's [results finalization contract](https://developer.apple.com/documentation/speech/speechmoduleresult/resultsfinalizationtime).

## Independent overlapping snapshots

A final iPhone experiment analyzed independent PCM copies every 500 ms, with
up to three seconds of preceding context, one worker and a 28-second watchdog.
This avoids cutting the continuous source stream when each copy is finalized.
All 24 windows completed, with no skipped jobs. Initial warmup was 0.051 seconds;
individual worker times were approximately 0.061–0.127 seconds. The whole probe
finished in 11.813 seconds, including setup and real-time input pacing.

Speed did not establish valid word boundaries. The window ending at source
time 3.0 seconds already transcribed the full “중요합니다”, although the
continuous reference located its end at 3.3 seconds. The 2.0–5.0 second window
lost internal content and produced only “브레이는”. Edge tokens and all raw
results remain in the artifact; counting them as valid fast words would conceal
the premature completion and missing coverage. The continuous transcription is
itself an estimated comparison, not manually annotated acoustic ground truth.

No word-aligned playback queue was added to the product. A queue built on these
unvalidated timestamps would replace one approximation with another. Keeping the
voice, preserving complete words and meeting the one-second target together
remain unresolved. Any future implementation must validate timing against real
provider audio and keep actual local playout completion, response ownership,
answer-ready transitions and echo protection synchronized.

## Artifacts

- `build/voice-word-alignment-iphone-metadata.json`
- `build/voice-word-alignment-iphone-normal-watermark.json`
- `build/voice-word-alignment-iphone-fast-watermark.json`
- `build/voice-word-alignment-iphone-rolling.json`
- `build/voice-word-alignment-periodic-latency.json`
- `build/voice-word-alignment-options-tests.xcresult`

The iPhone schema records every result, replacement range, finalization watermark,
timed run and actual receipt time, including empty/retracted results. Cached
confirmed runs are recorded separately, rather than assuming `isFinal` is the
only confirmation signal. The diagnostic helper is under ignored
`build/WordAlignmentMetadataProbe`; it is not part of the shipped app.
The temporary diagnostic app was removed from the iPhone after collecting its
reports. The original BuddyStudy process remained running with the same PID.
