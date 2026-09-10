# Unspoken USER caption: origin investigation, 2026-09-10

## Finding

Provider input transcription can originate invented Korean sentences from
known non-speech PCM under the application's requested `near_field` noise
reduction setting. This was reproduced without the app, a microphone, a tutor
response, a transcript prompt, or an admission classifier. The same PCM with
noise reduction explicitly disabled produced empty transcripts in the bounded
comparison. This narrows the upstream fault to the noise-processing/ASR path;
it does not establish the provider's internal defect or reproduce the exact
reported “안녕하세요, 저는 ChatGPT입니다. 무엇을 도와드릴까요?” sentence.

The earlier explanation about accepting unverified transcripts described how
the symptom reached the UI, not why those words originated. Likewise, tests
which inject a fabricated candidate into an admission classifier are not
reproductions of ASR originating that candidate.

## Incident evidence

The user confirmed headphones and no nearby AI/video audio. The iPhone's 76
retained diagnostic entries for the incident confirm Bluetooth HFP, 16 kHz
mono capture and `voiceChat` mode. No capture-format reinitialization occurred
during the incident. Only the relevant diagnostic metadata was extracted;
the temporary full preferences copy was immediately deleted.

| KST | App diagnostic |
| --- | --- |
| 19:20:23 | Previous local speech STOP, sequence 8 |
| 19:20:31 | Tutor output starts |
| 19:20:35 | Local speech START, sequence 9, while tutor is speaking |
| 19:20:44 | Local speech STOP, sequence 9, while tutor is speaking |
| 19:21:10 | Tutor output stops |

The stored USER turn's 19:20:35.787 timestamp is the server's speech-START
timestamp, **not** the ASR completion time. The persistence adapter currently
sets both `occurred_at` and `created_at` from this timestamp. No raw ASR event
was logged for the incident, and no consented recording exists.

The committed `a52aa85e` source sends microphone audio continuously over WebRTC.
Local Silero sends START/STOP controls over a separate WebSocket; it does not
slice outgoing PCM to the detected interval. STOP commits everything since the
previous provider commit. The normal path does not clear the buffer at START.
Thus the affected commit likely spans approximately 21 seconds, including
about 12 seconds before the detected START. These are estimates from control
times, not measured provider audio boundaries. The detected state lasted about
nine seconds, so this should not be described as a single 96 ms VAD spike.

No production source inserts the greeting or passes tutor instructions as an
ASR prompt. Capture and render tracks are separate. The application's 16 kHz
Silero conversion uses a copy and cannot overwrite provider audio; this call
already captured at 16 kHz and did not use that sample-rate converter. These
facts weaken caption-role, software loopback and app-resampling explanations.
They do not identify what originally caused sequence 9's VAD activation.

## Controlled provider experiments

All 24 cases used synthetic signed 16-bit, mono 24 kHz PCM. The effective
provider configuration was checked in `session.updated`: a Realtime session,
Korean input transcription, null transcript prompt and null turn detection.
No `response.create` was sent. Completed ASR events were matched to their exact
committed item; items were deleted between burst cases. Paced cases used one
fresh provider session each. [The official transport guide](https://developers.openai.com/api/docs/guides/realtime-transcription)
documents explicit audio append/commit and item-ID correlation.

| Run | Speech-free cases producing invented text | Speech controls |
| --- | --- | --- |
| mini transcribe + `near_field`, burst upload | 6 / 6 | Four “음” and two question cases correct |
| mini transcribe + noise reduction `null`, burst upload | 0 / 2 | Question correct |
| full transcribe + `near_field`, burst upload | 2 / 2 | Question correct |
| mini + `near_field`, fresh sessions, real-time 250 ms chunks | 1 / 3 | Not repeated |
| mini + noise reduction `null`, fresh sessions, real-time chunks | 0 / 3 | Not repeated |

Examples of invented ASR output:

- Exact 2-second digital silence, RMS 0, burst: “미래가 아닌 현재를 살고 있어”.
- A synthetic click with no speech, burst: “상황을 설명해 주시면 도와드리겠습니다.”
- The same synthetic click streamed over five actual seconds in a fresh
  `near_field` session: “모든 사람이 전기 차를 원하진 않아.” With noise reduction
  disabled, its matched PCM produced an empty transcript.
- Both paced pure-silence trials with `near_field` were empty. The behavior is
  not deterministic; do not generalize the burst 6/6 rate to real calls.

The paced comparison rules out fast upload as the sole explanation. Changing
mini to the full transcription model did not remove the burst failure.
The small sample supports prioritizing the `near_field` interaction, but does
not prove that disabling it eliminates all hallucinations or preserves noisy
speech accuracy across devices.

Machine-readable synthetic results, PCM hashes and configuration labels are in
[the verification data](verification/voice-asr-origin-2026-09-10.json).
The [opt-in paced reproducer](../backend/scripts/diagnostics/probe-voice-asr-origin.py)
requires Python with `aiohttp` and an existing `OPENAI_API_KEY_USER` environment
variable. Run it from the repository root. It creates six synthetic ASR inputs,
uses no microphone or BuddyStudy account/session, and writes diagnostics under
`build/voice-asr-origin/paced-confirmation`. It is not run in CI.

## Status and next boundary

This investigation changes no runtime configuration and performs no rollout.
The targeted corrective experiment is to disable server-side `near_field` for
the already voice-processed iOS input and verify actual headphone speech,
silence, pauses and speech onset. Separately, input segmentation must avoid
submitting accumulated non-speech as a learner turn without deleting first
syllables or losing overlapping speech. Ordinary reply-on-hesitation is a
separate turn-completion issue. A probabilistic admission classifier would add
latency and is not evidence of fixing this upstream generation mechanism.
