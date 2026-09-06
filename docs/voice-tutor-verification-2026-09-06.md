# Realtime-owned voice conversation verification

## Implemented contract

- `realtime-native-v1` moves live meaning, intent, topic choice and MCP-backed function selection into the Realtime audio model. The active WebRTC relay has no dependency on a separate live input/consent/question/feedback assessment call.
- Proven local Silero acoustic boundaries remain enabled; provider turn detection stays `null` because previous provider-VAD testing cut off WebRTC output. A committed input schedules a normal response without waiting for final transcription, SQL or another GPT request. This removes application gates, not network/model generation time or the local 480 ms quiet-release interval.
- Native function calls bridge to the existing authenticated MCP catalog; this is not a newly exposed remote MCP server. Read/selection tools retain ownership, exact candidate, parent path and revision validation.
- Create, rename, level change and delete first prepare one immutable, expiring proposal. The tutor asks its natural confirmation once; the same realtime model interprets agreement/refusal. Confirmation contains only the opaque proposal ID and decision. Exact source-row, spoken-order, live-scope and revision checks precede one-shot canonical writes. Uncertain writes are not replayed.
- Short contextual responses need no special command wording, regex filter or second intent model. Silence/noise handling is a model instruction, not a deterministic guarantee of perfect semantic recognition.
- Long answers can be committed in bounded acoustic checkpoints without generating a reply while the learner is still speaking. Normal learner speech does not cancel the current tutor audio. Pausing preserves already accepted input for a response after resume.
- Quota exhaustion uses one exact localized terminal notice, including a bounded silent-notice retry, and the matching device playout fence. Explicit spoken hang-up uses a dedicated end-call function and short localized farewell. Opening speech contains no self-introduction.
- Original transcripts persist asynchronously with source order and frozen lesson epochs. Only post-call verified, complete educational Q&A enters the existing canonical voice record/tree/translation/publication path. Topic-management-only dialogue does not earn a score or learning record. A selected-topic setup conversation can still require post-call evidence classification; it does not receive a learning-summary entitlement merely for lasting several minutes.
- Missing ASR or failed source persistence invalidates the post-call evidence set instead of grading a partial answer. V117 adds raw-evidence and session-integrity columns; it does not introduce a second learning-record store.

## Verification

- Final backend voice suites: domain 14 passed; application 163 passed; infra 899 passed and 6 opt-in cases skipped. Total: **1,076 passed, 0 failed, 6 skipped**. `:tutor:bootJar` passed using the existing offline Gradle cache and Java 25. Log: `build/voice-realtime-native-backend-final.log`.
- Coverage includes ASR-before-commit, delayed/failed source writes, tool-output acknowledgement, same-learner revision changes, duplicate commands, tutor preambles, inverted database IDs versus spoken sequence, confirmation expiry/ownership, long-answer checkpoints, pause/commit/resume races, quota notices and interrupted-source cleanup. Private integrity routing was tested through the actual control handler callback, not just its event parser.

- iOS focused protocol/pause suite: 21 passed, 0 failed, 0 skipped in `build/voice-realtime-native-ios-boundary-tests.xcresult`.
- Required generic iOS unsigned build passed using `StudyMateiOS`, `generic/platform=iOS`, and `CODE_SIGNING_ALLOWED=NO`. Log: `build/voice-realtime-native-ios-final-verification.log`.
- Existing call presentation, pause/resume, local acoustic detector and record paths remain in use; no macOS target was built or tested.
- A signed app was installed on the connected Min iPhone and launched successfully at 14:08:18 KST after the backend update. A live microphone-to-speaker conversation, actual response latency and physical gesture feel have not been measured in this implementation run.

## Rollout scope

Only the existing local dev API container on port 8080 is in scope. Existing MySQL, Redis, volumes and dev AWS Secret loading are retained. No production deployment, SSH, new database/Redis stack or Docker image build is part of this change.

- No READY/ACTIVE/ENDING voice session existed before replacing the API artifact. Only `backend-backend-1` was restarted; the other infrastructure containers were left running.
- This existing container overrides `FLYWAY_LOCATIONS` to `filesystem:/app/db/migration-mysql`, so V117 was copied to that existing location without replacing older migrations. Flyway applied version 117 successfully at 14:07:48 KST; both new columns were verified in MySQL.
- The final API started with the existing `dev` profile at 14:07:48 KST. Local `127.0.0.1:8080/actuator/health` and the existing `https://lowfidev.cloud/actuator/health` route both returned `UP`.
- Built and mounted JAR SHA-256: `f8dc5d8c0221dcfeebe389021c6fcdd34b69716a968c030562f0ac8f67e67854`. The immediately previous artifact is retained as `/app/buddystudy-backend.pre-native-20260906.jar` (SHA-256 `390e974b67e2157c2a6534ad9fa3b532b058125be35264f32bd6670f0f621943`). Existing backups were not overwritten.
- The API's `/app` mount remains read-only. Network-disabled, automatically removed copy helpers updated only its existing artifact/migration volume; no additional running service or persistent helper was created.

The SDP and control capabilities must match on both app and backend. Old `local-vad-v1` clients are rejected before allocating a new Realtime call; update the pair together. Pending mutation proposals are intentionally process-local and expire on restart, so an uncertain pre-restart write must be checked through a saved-topic read, never automatically repeated.
