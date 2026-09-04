# Voice study actions, confirmation and call UI verification

## Behavior

- Owned study metadata remains available independently of lesson selection, including after an exact root read without a child-page traversal. Pronoun references still require semantic resolution to one owned target.
- Root/child creation, renaming, level changes and deletion first produce one concise spoken confirmation question. A fresh natural agreement then executes the exact frozen proposal once. The question identifies the parent for a new child and includes descendants for deletion. No command restatement or regex intent detection is used.
- A confirmation cannot authorize a different proposal, a failed persistence, an earlier pre-audio utterance, or a superseded lesson revision. A near-tail reply after question playback begins waits for actual completion before assessment. Unrelated dialogue retires the pending confirmation.
- Topic changes retain owner checks, optimistic parent/title/level checks, deletion subtree membership checks and one-shot execution. Configuration dialogue is not a study answer or learning-summary entitlement.
- The iOS transcript is initially hidden. Dragging the orb continuously reveals/hides the persistent transcript layout. Reading older messages suspends follow-to-bottom; hiding/revealing preserves position. Tapping the orb pauses/resumes.
- Silero v6 acoustic quiet release changes from 700 ms (704 ms in 32 ms windows) to 480 ms. This saves 224 ms at the local speech-stop boundary, not at every end-to-end response. Speech onset, noise thresholds and resumed-speech protection remain unchanged.

## iOS verification

- Thirteen focused motion/contract tests passed; rendered layout states were inspected during implementation.
- Thirty-four focused acoustic tests passed; the opt-in Korean synthesized-speech test separately passed all five samples, including short replies and a hesitation. These are offline acoustic checks, not semantic labels.
- Generic iOS unsigned and signed `StudyMateiOS` builds passed. Latest app installed and launched on the connected Min iPhone at 05:59:29 KST.
- Simulator Core ML inference p95 was 2.23–2.57 ms per synthetic case; these are not physical-device or network latency measurements. Physical drag feel and a live microphone conversation still require on-device user verification.

## Backend verification

- All application and infrastructure voice tests passed with the `com.buddystudy.backend.voice.*` selector; opt-in paid live tests stayed skipped in the offline run. `:tutor:bootJar` succeeded with Java 25 and the existing offline Gradle cache.
- The relay suite passed all 88 cases, including 44 mutation paths that explicitly play a confirmation question, assess a separate reply and await its persistence before dispatch.
- All nine owned-context/confirmation regressions passed, including pre-audio assent rejection, near-tail assent after playback starts, persistence failure, unrelated dialogue, refreshed target metadata and one-shot deletion.
- Model-setting, local speech-boundary metrics and control-handler focused tests passed. Manual client VAD now feeds stop-to-response timing; duplicate/stale stops and resumed speech cannot reuse an earlier timer.
- Local artifacts: `build/voice-confirmation-final-regression.log`, application/infra JUnit XML reports, and the iOS motion/acoustic `.xcresult` bundles under `build/`.

## Local runtime

- No READY/ACTIVE/ENDING voice sessions existed before the update. Only the existing `backend-backend-1` was restarted at 06:23:55 KST; its dev profile, AWS-secret configuration, port 8080, existing MySQL/Redis containers and volumes were retained. No infrastructure stack or Docker image was built.
- The built and mounted JAR SHA-256 both equal `390e974b67e2157c2a6534ad9fa3b532b058125be35264f32bd6670f0f621943`. The previous running JAR remains available for rollback.
- Both local `127.0.0.1:8080/actuator/health` and the existing public dev route returned `UP` after restart. No production deployment or SSH was performed.

## Bounded live semantic comparison

Synthetic data only; no app records, topic writes, microphone input or voice-minute consumption. The existing user-content API credential was used without logging it. Expected labels were frozen before execution and no failed result was retried or reinterpreted as consent.

| Single run | Short/confirmation cases | Median assessment latency |
| --- | --- | --- |
| `gpt-5.4` | 8/8 exact expected decisions | 3,417 ms |
| `gpt-5.4-mini` | 8/8 exact expected decisions | 2,180 ms |

The additional four-case owned-mutation extraction check failed for `gpt-5.4-mini`: an extracted update did not satisfy the existing target/evidence validator before independent attestation. The validator was preserved. **No faster-model runtime override was enabled.** Default semantic and summary models remain unchanged; the new optional `VOICE_TUTOR_INPUT_ASSESSMENT_MODEL` setting permits future independently verified comparisons without changing summary generation.

The same four-case extraction check passed with `gpt-5.4` against the final schema (7,890 ms): spoken level change, rename, direct deletion and a non-authorizing third-party deletion report. This distinguishes the candidate model failure from an incompatible new output contract.

The measurements cover semantic requests only, including independent confirmation attestation where applicable. They exclude ASR, realtime audio generation and device playback and are not an SLA.
