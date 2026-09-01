# Pro Voice Tutor verification

Verification date: 2026-09-01. This is implementation verification, not a
production rollout or a measured ChatGPT-equivalent latency guarantee.

Latest implementation: [provider-turn recovery and natural transcript drawer](#provider-turn-recovery-and-natural-transcript-drawer).
It extends the existing guided saved-tree descent, single-orb,
Silero/contextual-input, MCP and source-backed summary contracts; it does not
regrade past answers.
Earlier checks below are historical and do not all describe the current
implementation.
Source/fixture tests, iPhone tests and actual dev runtime observations are
recorded separately; none implies a new human microphone-to-tutor conversation
unless that specific check is explicitly recorded.

## Provider-turn recovery and natural transcript drawer

Implementation verified to date on 2026-09-01, branch `feature/2.0`.

- The captured mid-sentence disconnect was not initiated by Routingflare. Its
  control WebSocket completed the 101 upgrade and remained available until the
  backend treated a provider response failure as terminal; the client stop and
  control-socket close followed that backend provider-turn decision. The fix
  therefore scopes recoverable OpenAI Realtime failures to the affected response
  instead of ending the lesson.
- A clean failed response receives at most one internal retry. If that exact turn
  fails again, the server abandons only its response ID, asks the learner to repeat
  the input, and keeps the call, RTP media and control channel alive. Stale or
  mismatched failures cannot abandon a newer response. Unknown session-, input-
  or tool-level failures remain terminal rather than being guessed recoverable.
- An accepted MCP tool call permanently makes its response non-retryable. This
  prevents study-tree mutations, level changes and other acknowledged side effects
  from being executed twice, including when a late provider clear or failure
  arrives.
- A WebRTC tutor transcript becomes durable only after the same response has both
  `response.done` and `output_audio_buffer.stopped`, its sanitized transcript has
  been persisted successfully, and that post-relay work has been acknowledged.
  Learner-boundary settlement, the next tutor response and spoken lesson-end
  lifecycle all wait behind that exact acknowledgement. A failed or cleared
  attempt cannot publish its partial caption or make the learner turn appear
  answered.
- iOS applies an exact abandoned-response instruction only to that response's
  staged caption and playout attribution. It does not stop the whole voice session.
  Tutor captions are likewise committed only after the exact completed-and-stopped
  boundary, in either provider event order.
- The call keeps one fixed status orb for microphone, listening, speaking, paused
  and failure state. Conversation history now opens as an independent bottom
  transcript drawer with stable mute/end controls, conventional learner-right and
  tutor-left bubbles, direct open/close affordances and scroll gestures that do not
  unexpectedly collapse the call UI.

Verification completed so far:

- Selected `StudyMateiOS` contract tests: **129 discovered, 128 passed, one
  opt-in hardware test skipped, zero failures**. The cases include failed-partial
  replacement, authoritative empty finals, both WebRTC completion-boundary orders,
  done-then-clear discard and the server-attested `USER_ENDED` race. Result bundle:
  `build/VoiceTutorRecoveryFinal5.xcresult`.
- The exact required unsigned generic `StudyMateiOS` Debug build passed. A normal
  signed physical-device Debug build also passed; deep signature validation,
  bundle identifier and absence of XCTest injection artifacts were checked. The
  regular and accessibility transcript-drawer renders were inspected for control
  stability and clipping.
- A broad backend recovery run completed **453 tests: 451 passed, two opt-in
  live tests skipped, zero failures and zero errors**. It covered response-local
  retry/abandonment, MCP no-retry, failed-transcript replacement, both provider-
  boundary orders, and the final raw-provider-event -> durable transcript ->
  exact acknowledgement fence, including persistence failure and cancellation.
  Kotlin test compilation, Spring AOT processing and `:tutor:bootJar` passed. The
  JAR is 331,429,757 bytes with SHA-256
  `bac93447c112b6fea2a5d10ff5b2194288f09d270cc7646d47b2f464e2a9bf39`.
- With zero active voice sessions, only the existing `backend-backend-1` API JAR
  was atomically refreshed on localhost port 8080. Health returned `UP`, the
  OpenAPI document exposed 11 voice-tutor paths, and the deployed JAR hash matched
  the built artifact. MySQL, Redis, LibreTranslate and backup retained their exact
  container IDs and start times; no persistent container or duplicate stack was
  added.
- The signed Debug app was installed on the paired physical iPhone and launched as
  `io.github.ghkdqhrbals.StudyMate`. This proves installation and process launch,
  not a new human audible microphone-to-tutor end-to-end conversation; that last
  behavior still requires an intentional call by the tester.

## Voice root-study creation acceptance checklist

Implementation verified on 2026-09-01 on branch `feature/2.0`:

- Focused root/voice consent tests: **200 passed, zero failures**.
- Application and infra suites: **1,435 tests, zero failures/errors, two skipped**.
  The isolated HTTP MCP catalog suite passed **8/8**, and `:tutor:bootJar`
  succeeded. The built JAR is 331,467,317 bytes with SHA-256
  `fff71e10fba95dbdba786513b7d84feb24920d65123b4897d11c2e36df39cbdb`.
- Relevant iOS contract tests passed **48/48**. The required unsigned generic
  `StudyMateiOS` build and a signed physical-iPhone build succeeded; the app was
  installed and launched as `io.github.ghkdqhrbals.StudyMate`.
- With zero active voice sessions, only the existing `backend-backend-1` JAR was
  atomically replaced on localhost port 8080. Health returned `UP`, the deployed
  hash matched the built artifact, and MySQL, Redis, LibreTranslate and backup
  retained their container IDs and start times.
- A separate all-of-`tutor:test` integration attempt ran 241 tests and exposed
  eight existing shared-Testcontainers state failures in billing, migration,
  study API/statistics, OpenAI settings and quota persistence tests. None was in
  the root/MCP code or its focused suites; the isolated MCP server test passed.

This verifies implementation, installation and process launch, not a new human
audible microphone-to-tutor conversation.

- [x] A root write is considered only after one final, persisted, meaningful
  learner turn explicitly requests creation. A topic mention, saved-topic search,
  recommendation request, child-topic request, filler or tool text cannot
  authorize it.
- [x] `create_root_study(confirm=false)` writes nothing. It resolves the exact
  trimmed 1–255 character topic and the requested 1–10 level, using level 5 only
  when the learner omitted one, then returns a bounded two-minute confirmation
  token. The tutor speaks that exact topic and level without reading the token.
- [x] `confirm=true` accepts only the unchanged preview fields and token after a
  newer explicit affirmative learner turn. The server binds that turn to the
  exact completed tutor-audio transcript, provider item, response generation and
  playback-stop order, then atomically consumes a one-shot lease immediately
  before the write. A correction such as “not A; create B” supersedes A and
  requires a new B preview. Expired, consumed, changed, stale-call/account/device,
  pre-preview, newer-speech and uncertain-write retries fail closed.
- [x] The common `create_root_study` use case is owner-scoped and create-only. An
  exact normalized owned root duplicate returns the existing row unchanged with
  `created=false`; it never adopts the requested level or overwrites scheduling
  settings. A normalized match on a child is a conflict. A new root uses product
  defaults, creates no question and consumes no question quota.
- [x] Neither `created=true` nor `created=false` establishes lesson focus. The
  tutor reads the returned ID with `get_study`, speaks the exact saved root as a
  separate start offer, waits for another fresh learner agreement, and calls
  `select_voice_study`. Teaching starts only after that focus result succeeds.
- [x] Only a verified new row is eligible for the live
  `buddystudy.voice.study.changed` refresh hint. While the controller remains
  valid, its sanitized wire payload carries the type, positive `studyId`,
  server-owned change kind and a bounded deleted-ID list, never tool output or
  study settings. iOS uses a delivered creation hint to fetch that exact node
  through its existing study store. An unchanged duplicate requests no false
  hint, ordinary study sync remains authoritative, and no answer draft, question
  record or lesson state is replaced.

## Guided saved-tree descent

Implementation verified on 2026-09-01, branch `feature/2.0`, commit
`7c556753a1c7f3473253a37f5f1d097a3a5d9f87`.

- Every iOS voice call now starts without a preselected `studyId`. The tutor asks
  what topic to discuss, resolves the spoken answer only against the authenticated
  user's saved tree, follows a verified one-child chain to its endpoint, and asks
  once before beginning there. At a real branch it presents at most three actual
  children instead of requiring a separate app-side topic picker.
- `select_voice_study` owns the initial or explicitly named focus. The separate
  `advance_voice_study` tool may move only from the persisted current focus to one
  live direct child, after a fresh accepted meaningful learner turn. The server
  locks the active session, rechecks ownership and parentage, and rejects sibling,
  ancestor, other-root and skipped-descendant moves. A single learner turn can
  authorize at most one non-idempotent focus change.
- V107 adds the nullable positive `learner_turn_id` fence and a unique
  `(session_id, learner_turn_id)` key to `voice_tutor_lesson_focuses`. Existing
  revision-zero history remains valid. Silence cannot advance a lesson; deciding
  that a completed answer/feedback should continue remains a tutor instruction,
  while the saved-tree edge and turn fence are enforced transactionally.

Verification:

- Application focus tests: **52 passed, zero failures**. Selected infrastructure
  MCP, relay, persistence and summary tests: **136 passed, zero failures**. Kotlin
  compilation, Spring AOT processing and `:tutor:bootJar` passed. The JAR is
  331,232,685 bytes with SHA-256
  `847205a1b50054026c8034c15e93d68ffe4149ab9b581f1d88e392ce717d454d`.
- Selected `StudyMateiOS` simulator regression: **50 passed, zero failures** in
  `VoiceTutorDiscoveryTests`, `VoiceTutorVoiceSettingsTests` and the changed
  contract cases. Result bundle:
  `build/VoiceTutorGuidedDescentDerivedData/Logs/Test/Test-StudyMateiOS-2026.09.01_09-33-57-+0900.xcresult`.
  The exact required unsigned generic `StudyMateiOS` Debug build also passed.
- The selected physical-iPhone run could not start because the paired device was
  locked (`xcodebuild` exit 70). No real microphone, provider speech or audible
  multi-turn saved-tree traversal is claimed by the simulator and contract checks.
- With zero active calls, only the existing `backend-backend-1` API was refreshed
  on localhost port 8080. It retained the `dev` profile, `buddystudy/dev` AWS
  Secrets Manager selector, external app/data volumes, existing network, read-only
  root filesystem, dropped capabilities and 3 GiB/3 CPU limits. MySQL, Redis,
  LibreTranslate and backup retained their exact container IDs and start times;
  no persistent container or duplicate backend stack was added.
- Dev uses `FLYWAY_LOCATIONS=filesystem:/app/db/migration-mysql`, so the embedded
  V107 alone was intentionally not treated as proof of rollout. The same source
  migration was placed in that existing mounted path (SHA-256
  `e3509e5bfde95ac5ba158df7b9de0576e991840946614a9f89272c1560f7d54f`) and the
  API alone was restarted. Startup loaded `buddystudy/dev`, applied exactly V107,
  and reached schema 107. Read-only checks confirmed the column, unique index,
  check constraint and successful Flyway row; active calls remained zero. Local
  and public dev health both returned 200.

## Single-orb call and spoken lesson end

Implementation verified on 2026-09-01, branch `feature/2.0`, commit
`fe2f601333677f7ea2be81ea7b159360dbcb9013`.

- An ordinary live lesson now renders literally one central state orb: topic,
  discovery copy, countdown, transcript affordance, mute and end controls remain
  hidden until interaction. The orb's existing acknowledged pause/resume contract
  owns taps; a pause acknowledgement makes it small and still instead of guessing
  from a pending request. A deliberate downward vertical swipe reveals the topic,
  transcript, exact server countdown, mute and end actions. An upward overscroll
  returns to the orb only after the enlarged outer call and, when applicable, the
  nested transcript have reached their latest edges, so large-text controls and
  older dialogue remain scrollable. Failure, retry, result, actionable permission/
  quota text, VoiceOver, Dynamic Type and Reduce Motion remain reachable.
- Voice selection moved to a compact Settings destination. Selecting a voice
  fetches and plays fixed, server-owned copy from
  `GET /api/v1/voice-tutor/voices/{voice}/preview`; the caller cannot submit text.
  The preview requires the authenticated registered Pro user and the regular
  server OpenAI key, but creates no lesson and reserves or charges no voice
  seconds. Audio is bounded to 512 KiB, not logged or persisted by the backend,
  rate/concurrency limited in process, and served with no-store/nosniff headers.
  iOS bounds its own in-memory reuse and fences cancellation, stale completion,
  decoder failure and rapid same-voice replacement before touching the shared
  audio session. The UI identifies the sample as AI-generated speech.
- A final meaningful learner turn can semantically request
  `END_CURRENT_VOICE_LESSON`. Silence, noise, filler-only audio, quotations,
  hypothetical/negative/future statements, questions about whether to end,
  topic completion and long-speech checkpoints cannot end the call. The exact
  original learner transcript must first pass contextual assessment and durable
  persistence; assessment or persistence failure keeps the lesson open.
- WebRTC and legacy PCM both use the same `USER_ENDED` settlement/summary path.
  WebRTC retains its numbered app speech edges; legacy PCM correlates OpenAI
  server-VAD start, stop and commit events by the provider item ID before assigning
  an internal sequence. A newer speech start retracts an un-emitted end candidate.
  If the tutor is already speaking, the server waits for the exact response's
  completion and output-buffer-stop boundaries. iOS then keeps that exact response
  generation's native output path alive for a bounded local grace before closing:
  450 ms with renderer evidence, at most 1.25 seconds without it. If terminal
  ownership suppresses the second raw provider event, the server-verified spoken
  lifecycle seals the still-active exact response ID locally instead of skipping
  this fence. A newer response, close, cancellation, background or dismissal
  invalidates it. The spoken command sends no cancel, clear or truncate, while the
  explicit red end keeps its immediate behavior. This is defensive jitter/Core
  Audio grace, not proof that software observed the final acoustic sample leave the
  speaker.

Verification completed so far:

- Focused simulator run: **141 tests passed, one opt-in hardware capture test
  skipped, zero failures** across `VoiceTutorContractTests`,
  `VoiceTutorPauseTests` and `VoiceTutorVoiceSettingsTests`. This includes the
  literal single-orb source/render states, nested/outer latest-edge gesture routing,
  both spoken-end provider-boundary orders and the server-attested fallback race,
  pause acknowledgement, preview request bounds, identity recovery and lesson-free
  preview contract. Result bundle:
  `/tmp/buddystudy-single-orb-audit-fixes-v2-20260901.xcresult`.
- The exact required unsigned `StudyMateiOS` generic iOS Debug build passed after
  the final gesture and playout-race fixes. A separate normal signed Debug app build
  from the preceding implementation also passed;
  deep code-sign verification passed, the bundle ID matched, and no XCTest plug-in,
  framework or injection library remained in the app bundle.
- Focused application/infrastructure voice tests passed for preview admission and
  provider mapping, contextual intent parsing, exact publication/persistence,
  WebRTC and legacy spoken-end races, terminal ownership and the production legacy
  adapter factory. Spring AOT processing and `:tutor:bootJar` also passed. A wider
  backend run still contains eight isolated stale fixture/expectation failures
  outside this implementation; no full-suite-green claim is made here.
- The existing dev API container alone was recreated at the commit above with
  artifact SHA-256
  `5f6871dc0c79c97620d65c2d5001e61029bc9b732ea73ccda32c55b8b2530634`.
  It retained the `dev` profile, AWS secret selector, localhost-only port 8080,
  network, data volume, environment and resource/security limits. MySQL, Redis,
  LibreTranslate and backup retained their exact container IDs and start times;
  no persistent container was added. Zero active calls were observed at both
  drain gates, schema remained V106, the new preview path appeared in OpenAPI,
  and local dependency/readiness/health plus public dev health all passed.
- Physical iPhone verification of this final commit is not yet complete. The latest
  read-only device check still reported the paired iPhone 16 Pro as locked, with
  developer services unavailable and its tunnel disconnected; earlier safe
  `build-for-testing`/install attempts were rejected for that same reason. No test,
  signed build, install, launch or audible end-to-end result is claimed for this
  commit until the phone is unlocked and those steps succeed.

The preview provider contract follows the OpenAI
[text-to-speech guide](https://developers.openai.com/api/docs/guides/text-to-speech),
and the exact Realtime input/response sequencing follows the OpenAI
[Realtime conversations guide](https://developers.openai.com/api/docs/guides/realtime-conversations).

## Spoken topic discovery through saved study trees

Implementation verified on 2026-09-01, branch `feature/2.0`.

- A new start/retry omits `studyId` instead of opening a study picker or choosing
  the first cached room. Eligibility, monthly seconds, active-session exclusion,
  foreground lifecycle and explicit recording consent are unchanged. Legacy
  numeric study requests remain compatible; supplied nonpositive IDs fail before
  quota reservation.
- The opening asks what to discuss in the accepted session language. The tutor
  uses authenticated saved-study search and actual parent-scoped child pages,
  narrows a broad topic with at most three real choices, and respects an already
  exact spoken choice. No result means no invented ID or automatic study creation.
  Topic lookup alone is not teaching consent; an explicit request to study an
  unambiguous topic retains its consent while the tool resolves the saved ID.
- The voice-only `select_voice_study` function verifies an accepted learner turn,
  active owner, complete owned parent path and the bounded lesson cache. Its
  transaction captures the saved level, advances the shared lesson epoch, stores
  a historical focus and updates the live session focus. The original accepted
  request remains unchanged for idempotency. Read-only browsing never freezes
  candidate nodes, and suspended reads recheck authorization before returning.
- Typed focus events alone change the compact title; stale attempts/epochs,
  invalid metadata and deleted-node resurrection are rejected. Selection and
  MCP continuation retain the existing exact tool-output acknowledgement,
  meaningful-input and speech/playout gates. No microphone, VAD, RTP, playback
  cancellation, transcription model/language or provider model change is made.
- V106 adds only `voice_tutor_lesson_focuses` and backfills known legacy accepted
  IDs at epoch zero. Summary evidence and canonical `VOICE_TUTOR` projection use
  the question-time focus, not the final session topic. Pre-selection discovery
  cannot become a saved-node exchange; earlier questions keep their original
  focus/level after later selection. Existing Records, tree history, translations,
  sharing, comments, answer drafts and ordinary-question quota remain shared or
  untouched as appropriate.

Verification:

- Backend selection: **1,139 tests discovered, 1,137 passed, two opt-in live-model
  probes skipped, zero failures/errors**, across 129 suites. Modules: domain 20,
  application 343, infrastructure 760 (758 passed), tutor 16. The discovery audit
  matched all **266 ordinary `@Test` methods in 11 changed fixtures** to reported
  Jupiter tests, including the new HTTP, focus-index and migration fixtures.
  Logs: `build/voiceTopicDiscoveryBackend-tests-retry3.log` and
  `build/voiceTopicDiscoveryBackendAudit.json`.
- Real MySQL **8.4.10**: V106 applied to a schema-only isolated database in the
  existing MySQL container, with **16 assertions passed** for historical/live
  anchors, missing focus, question-time epochs, constraints, cascades, unchanged
  metering and no generated questions/notifications. No user rows were copied;
  no container was created; the disposable schema was removed.
  Report: `build/voiceTopicDiscoveryMySqlMigration.json`. V106 SHA-256:
  `9bede8ba9a6aa4405a9b3ae4b800b0c0fa03b109d851f5de79fd292669a35633`.
  This DDL/SQL check does not claim a live MySQL concurrency or full call test.
- Required `StudyMateiOS` generic iOS Debug build passed with signing disabled;
  the existing `build/iOSDeviceDerivedData` was reused. Log:
  `build/voiceTopicDiscoveryGenericBuild.log`.
- Paired iPhone 16 Pro: signed build-for-testing passed and **298 selected safe
  tests passed**, including all 28 new discovery/request/focus tests and 270
  existing call, Silero, pause, settings, summary, MCP and common-record checks.
  Test helpers use isolated defaults and intercepted requests; they never open
  media, call a provider, reserve real quota or purge account/recording data.
  Logs: `build/voiceTopicDiscoveryDeviceTestBuild-retry2.log` and
  `build/voiceTopicDiscoveryDeviceTests.log`.
- The normal signed app rebuilt successfully; signature verification passed and
  its bundle contains no injected XCTest plug-in/framework. It was installed on
  the paired iPhone 16 Pro and launched with the public dev base URL only after
  another zero-active-call check. Logs:
  `build/voiceTopicDiscoverySignedBuildFinal.log`,
  `build/voiceTopicDiscoveryDeviceInstall.log`, and
  `build/voiceTopicDiscoveryDeviceLaunch.log`.
- The existing dev API container alone was recreated at revision
  `d44df266f107e906e48496048828597a84981abf`, artifact SHA-256
  `f80808aba1b376509196d872a7dda48eee21fb6af6de39da18f340a23edf67d6`.
  It retained the `dev` profile, `buddystudy/dev` AWS secret identifier,
  localhost-only 8080 binding, network, mounts, environment and resource limits.
  The existing MySQL, Redis, LibreTranslate and backup containers retained their
  exact IDs/start times; no persistent container was added.
- The pre-V106 dev database backup is retained at
  `build/voice-topic-discovery-db-backup-lotXA2/pre-v106.sql` (317,774,791 bytes,
  mode 0600, SHA-256
  `b9bcb5bfaeaa91b2657841f24c5f4c0c478c88c93f37a2c77f82e015e9900c94`).
  Startup applied exactly V106 in 15 ms with no logged error. Post-start aggregate
  checks found schema 106, four focus columns, all known legacy anchors
  backfilled, zero active calls, no orphan canonical voice records, no fake
  ordinary-question lifecycle and no eligible unfinished projection. The actual
  context/projector focus SELECT templates parsed and returned no impossible-owner
  rows. Local and public dev health checks returned 200. Report:
  `build/voiceTopicDiscoveryDevRuntimeVerification.log`.

These are source/fixture, device-contract and isolated migration checks, not a
new human microphone-to-model conversation. Natural spoken disambiguation and
audible multi-turn traversal of a user's actual tree still need a real call.
The backend-architecture and OpenAI-docs skills informed the transactional focus
boundary and preservation of the existing [Realtime function-output
acknowledgement flow](https://developers.openai.com/api/docs/guides/realtime-conversations).

## Contract

- Direct iPhone/OpenAI WebRTC media, with authenticated backend-owned control;
  the bounded PCM WebSocket path remains a fallback.
- WebRTC verifies explicit `turn_detection: null`. Automatic local microphone
  speech edges are numbered start/stop pairs; the backend alone commits input
  and schedules replies. Both SDP/control requests declare `local-vad-v1`.
- Learner overlap never triggers playback stop, response cancellation, or
  truncation. Each tutor response is one short complete sentence. The next
  answer requires completion of the same provider response, provider audio-buffer
  drain, and a meaningful, persisted learner turn with no unresolved input work.
  A raw microphone edge, commit acknowledgement or partial transcript is not
  permission to reply. The continuous WebRTC track retains its
  locally buffered tail; PCM silence is not a response gate or an acoustic EOS
  acknowledgement. The legacy PCM fallback keeps its real playback callback.
- TIER2/TIER3 default to 60 minutes per user's monthly window. Tier defaults and
  personal overrides are independently adjustable. One call has a separate
  configurable ceiling, capped at 60 minutes. V100 preserves non-seed operator
  tier values, personal overrides, usage, reservations, and period boundaries.
- Recording is per-call opt-in. Pre-ready audio and connection setup silence are
  excluded. Only a positively metered terminal call can receive a recording
  upload grant; duration and bytes are bounded by server-metered time.
- Private recordings use fixed call-end retention, integrity-checked upload,
  owner-only playback/deletion, and durable deletion retries. Account changes
  invalidate in-flight requests, local exports, playback, and cached results.

## Backend checks

- Application: 114 focused tests passed across voice sessions, WebRTC lifecycle,
  recording, account withdrawal, administration, and billing.
- Infrastructure: 87 voice-focused tests passed, including turn control,
  provider negotiation/cleanup, recording storage, retention, and settlement.
- `:tutor:compileTestKotlin` passed for the MySQL integration test sources.
- An isolated, network-disabled native MySQL 8.0.44 instance applied V1–V100 and
  the repeatable migration. Queries confirmed the 3,600-second paid-tier defaults
  and FK-free recording/provider cleanup tables. A separate seed-upgrade check
  preserved an operator's 7,200-second tier, a 900-second personal override,
  23 used seconds, 12 reserved seconds, and quota revision 7. The generated
  temporary database was removed after shutdown; no existing database was used.
- MySQL 8.4 Testcontainers execution remains unverified because the local Docker
  socket did not respond. The native SQL check is not a replacement for the
  Spring/R2DBC integration suite on the production database version.

Focused backend command, from `backend/`:

```sh
./gradlew :application:test :infra:test --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=512m' \
  --tests '*VoiceTutor*' --tests '*AccountWithdrawalCleanupServiceTest' \
  --tests '*AdminManagementServiceTest' --tests '*BillingServiceTest'
```

## iOS checks

- Simulator: 121 tests passed (37 Voice Tutor contracts and 84 existing question
  flow tests), including delayed account-A responses after account-B replacement,
  request-scoped 401 handling, recording retry, durable purge, ready/end recording
  cutoffs, and same-response playout drain.
- The HTTP test fixture now isolates response handlers per client so delayed
  logout/registration cleanup cannot execute another test's assertions.
- Simulator log: `build/iOSFinalVoiceFocusedTestsStable.log`.
- The required generic iOS Debug build passed using `StudyMateiOS`,
  `generic/platform=iOS`, and `CODE_SIGNING_ALLOWED=NO`.
  Log: `build/iOSVoiceTutorFinalGenericBuild.log`.
- Physical iPhone 16 Pro: the signed app/test host built, installed, launched,
  and passed 12 selected non-mutating contract tests. These covered learner
  overlap, tutor intervention, response-generation matching, stale/early/late
  PCM drain, SDP request authentication/media types, event parsing, and the
  account identity fence. The existing app development team was supplied to
  the test target with `DEVELOPMENT_TEAM=4CL25TC734`.
  Log: `build/iOSVoiceTutorFinalDeviceTestsSigned.log`.
- Device verification deliberately excluded recording purge/file tests to
  preserve any existing user recordings. No live microphone/provider call was
  started by the device tests; acoustic and S3 end-to-end checks remain below.

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS \
  -configuration Debug -destination 'id=8DC0FFFA-22CD-4926-9DD9-FCE9F6B32AF7' \
  -derivedDataPath build/iOSPurgeTestsDerivedData CODE_SIGNING_ALLOWED=NO \
  -parallel-testing-enabled NO \
  -only-testing:StudyMateiOSTests/VoiceTutorContractTests \
  -only-testing:StudyMateiOSTests/QuestionGenerationFlowTests test
```

## Local dev call-recovery follow-up

- The reported immediate disconnect was reproduced at the control handshake,
  not by interpreting the learner's greeting as an end command. SDP negotiation
  succeeded, but the local Routingflare origin proxy removed `Upgrade` and
  changed `Connection` to `keep-alive`, producing HTTP 422 on `/control`.
  Identical unauthenticated probes retained both upgrade headers at port 8080
  and lost them through the local proxy and public dev route. Their expected
  HTTP 401 responses were used only to inspect header forwarding; they are not
  evidence of a successful authenticated call.
- Read-only quota inspection showed four seconds charged across the reported
  attempts, no remaining reservation, and 3,596 seconds available. No quota,
  entitlement, or user-data correction was performed. The erroneous zero was
  the app's stale reservation snapshot.
- Failure cleanup now preserves failure/retry state and server failure reasons,
  clears terminal countdowns, and reconciles settled quota. Per-attempt fences
  reject old callbacks, delayed detail results, and delayed playout successes
  or failures after retry; cleanup avoids cancelling its own settlement work.
- iOS rejects empty/non-audio SDP locally, preserves authenticated request
  headers and exact SDP bytes, and waits for combined ICE/DTLS connection before
  opening control. The teacher-first greeting and readiness-confirmation
  instructions use the existing one-sentence response/playout policy.
- Backend follow-up tests passed: 25 application voice-session tests, 34
  realtime adapter/turn-controller tests, 12 WebRTC adapter tests, and 15
  control-handshake/logging tests. Startup regressions cover a fully committed
  learner greeting before readiness, one opening per call, queued learner turns
  behind the opening's full playout, and no premature continuous-speech
  intervention. Existing mid-conversation fixtures now enter through the real
  opening/completion/drain lifecycle rather than a test-only readiness bypass.
  The final combined run passed all 86 tests with zero failures. Log:
  `build/voiceCallRecoveryBackendTests.log`.
- The updated JVM JAR build passed using the existing local JDK/cache, without
  building another Docker image or creating a new database/Redis stack. Log:
  `build/voiceCallRecoveryBackendBuild.log`.
- Generic `StudyMateiOS` Debug build passed. Log:
  `build/iOSVoiceCallRecoveryGenericBuild.log`.
- The signed app was installed and launched on the physical iPhone 16 Pro;
  26 selected non-mutating contract tests passed with zero failures. These
  include the new media-readiness, SDP, failure outcome, released reservation,
  and genuinely suspended old-attempt/cancelled-result regressions, plus the
  existing sentence-overlap and same-response drain contracts. Log:
  `build/iOSVoiceCallRecoveryDeviceTests.log`. Recording purge/file tests were
  excluded to preserve user data, and no additional DerivedData directory was
  created.
- The user repaired Routingflare's WebSocket forwarding separately. Repeating
  the public dev probe then preserved `Upgrade: websocket`,
  `Connection: Upgrade`, and `buddystudy.voice.control.v2`, matching the direct
  API probe. No bypass, tunnel change, or route change was made for this fix.
  An authenticated control upgrade and audible teacher greeting still require
  a live call; the unauthenticated probe, device contracts, and app launch are
  not an acoustic end-to-end pass.

## Mid-call disconnect and silent-audio follow-up

- After the user's forwarding repair, the supplied Routingflare logs show
  authenticated HTTP 101 upgrades for both reported calls. Database metadata
  shows those calls ended after approximately 3 and 9 seconds. The old
  `COMPLETED / PROVIDER_CLOSED` fallback did not retain the winning relay branch
  or WebSocket close code and cannot attribute those incidents to Routingflare
  or OpenAI.
- A later silent-audio call remained connected for approximately 67 seconds;
  its server log records `VoiceTutorProviderPlayoutTimeoutException`. This
  establishes an unmet post-response playout gate, not an upgrade failure.
  Whether the missing condition is provider-buffer stop, device audio render,
  or device-drain acknowledgement must be distinguished rather than inferred
  from the UI's provider-driven speaking label.
- The sideband now treats unexpected receive/send completion as failure,
  including a peer close code of 1000. Explicit user/server termination remains
  successful. Diagnostics snapshot the winning branch before loser cancellation
  can synthesize a local close status; the close-status observer never extends
  connection lifetime. The control bridge records its own first terminal source
  and numeric close status, correlated through a hashed provider call reference.
- Event counters have a fixed allowlist plus one `other` bucket. Logs exclude
  raw event bodies, close reasons, exception messages, provider IDs, SDP, audio,
  transcripts, and credentials. No Routingflare route, authentication, AWS
  secret, infrastructure, quota, or response/playout timeout has been changed.
- Backend verification passed 63 focused tests: 13 lifecycle/loopback cases,
  34 realtime turn-controller tests, 12 WebRTC adapter tests, and 4 control
  handler tests. Real loopback cases include close 1000/1011, raw TCP EOF,
  outbound-only completion, explicit local endings, cancellation, and privacy
  assertions. The JVM JAR build also passed. Log:
  `build/voiceDisconnectDiagnosticsBackend.log`.
- These diagnostics and the failure classification are not proof that the
  live disconnect or acoustic failure is fixed. The actual iPhone media and
  playout path remains an end-to-end verification requirement.
- The iOS renderer previously moved the drain deadline for every nonempty
  buffer, including digital silence continuously delivered after speech. It
  now ignores only all-zero buffers for that deadline, retaining every nonzero
  sample and the existing same-response/server-stop/device-latency fences.
  There is no loudness threshold that discards quiet speech. Nonzero comfort
  noise remains a conservative limitation to verify during live calls.
- The WebRTC speaking label now requires local audio for an active response.
  The existing bounded app-log path exposes first-frame/nonzero-frame counts,
  audio-device and route-type/zero-volume states, safe receive error metadata,
  and the first stop source. Actual PCM, close reasons, device identities,
  error descriptions, and conversation contents stay out of those diagnostics.
- The dev API was updated to `e277e9dd` using the existing `backend` Compose
  project, port 8080, `dev` profile, and AWS Secret configuration. Both local
  dependency and readiness checks returned HTTP 200. Database, Redis,
  translation, and backup container IDs were unchanged. No Routingflare change,
  new database/Redis stack, or Docker image build was performed.
- The required generic `StudyMateiOS` Debug build passed, followed by 33
  non-mutating contract tests on the physical iPhone 16 Pro with zero failures.
  Seven new cases exercise real renderer callbacks with synthetic buffers:
  continuous digital silence, late one-unit audio, initial silence, out-of-order
  media/control, stereo/float layouts, active-response speaking state, and
  diagnostic privacy. No provider call, recording purge, or quota mutation was
  triggered by these tests. Existing DerivedData was reused. Logs:
  `build/iOSVoicePlayoutGenericBuild.log` and
  `build/iOSVoicePlayoutDeviceTests.log`.
- A subsequent instrumented short call established the first terminal source
  as `PROVIDER_EVENT_ERROR`, immediately after `input_audio_buffer.speech_started`
  and `output_audio_buffer.cleared`. Its outbound relay completed before the
  receive-branch exception escaped, so the old fallback still persisted
  `PROVIDER_CLOSED / COMPLETED`. The handler now records failure before emitting
  the terminal signal; a regression models this exact send-winning race. This
  observed case is a provider-buffer integrity event, not evidence of a
  Routingflare transport close. The uninstrumented earlier calls remain
  unattributed rather than being retroactively assigned the same cause.
- The GA sideband now sends the turn policy through `session.update` and
  requires `session.updated` to confirm `server_vad` and both explicit false
  booleans before ready or the opening response. Missing/mismatched policy does
  not silently enable the microphone; acknowledgement uses the existing bounded
  connection timeout. The old beta header is not sent on this GA path. Effective
  policy diagnostics contain only a hashed call reference and allowlisted
  configuration fields, never the returned session/instructions. This validates
  the applied contract; it does not yet prove the provider never clears a
  buffer after acknowledging that contract during a real call.
- Verification of the policy handshake and failure race passed 93 focused
  backend tests: 29 session-handshake, 13 relay-lifecycle, 5 control-handler,
  12 WebRTC-adapter, and 34 realtime turn-controller cases, with zero failures
  or skipped cases. The JVM JAR build also passed. Confirmation ordering,
  missing or incorrectly typed flags, cancellation, timeout, an early queued
  learner turn, and full sentence drain remain covered without calling the
  provider. Log: `build/voiceTutorConfigurationHandshakeBackend.log`.
- The existing dev API was then refreshed to `781c9ae3`; both local dependency
  and readiness checks returned HTTP 200. All original environment values
  were preserved exactly, including the dev AWS Secret setup. The same four
  infrastructure container IDs and the Routingflare route were retained. The
  installed iPhone build includes the silence/drain fixes at `19a1ef1a`.
  A new live call is still required to confirm audible output and the actual
  provider-policy acknowledgement together.

## Compact iOS call surface

- Replaced the oversized call/status/quota cards with a small topic identity,
  one connection-state line, a minutes:seconds countdown, and mute,
  conversation, and end-call controls. Conversation and completed learning
  details expand in the same destination; the app tab bar is hidden during
  that destination. Recording remains explicit, and permission/quota errors
  retain their actionable text and full details.
- The display-only presentation distinguishes the authoritative live
  countdown from monthly quota after settlement. A zero free quota caused by
  the current reservation cannot become a zero call timer. Failure stays a
  failure even when a learning result completes, including the period before
  failure settlement finishes. Empty/unknown results do not invent a pending
  summary. The entry destination survives removal of the quota-dependent
  start button, and captures the selected study and per-call consent.
- The required generic iOS Debug build passed with `StudyMateiOS`,
  `generic/platform=iOS`, and `CODE_SIGNING_ALLOWED=NO`.
  Log: `build/iOSCompactVoiceCallGenericBuild.log`.
- The signed app/test host passed 45 selected non-mutating contract tests on
  the physical iPhone 16 Pro with zero failures. Twelve new cases cover call
  time and reservation boundaries, phase/actions/mute, actual-speaking state,
  failure settlement, actionable errors, result content/status combinations,
  Korean/English/Japanese clocks, and synthetic native UI rendering.
  Log: `build/iOSCompactVoiceCallDeviceTests.log`.
- The native rendering test was rerun successfully after its capture-helper
  adjustments and exports seven synthetic states without creating an
  `AppState`, microphone, or provider session. The normal listening and
  expanded-conversation layouts and the narrow, large Dynamic Type English
  control layout were visually inspected. Large accessibility text uses
  horizontal icon/label rows instead of cramped three-column labels. Some
  offscreen captures omit shared navigation/control text, so these artifacts
  are not a complete seven-state visual acceptance or an interactive
  accessibility pass. Result: `build/iOSCompactVoiceCallScreensVerified.xcresult`;
  log: `build/iOSCompactVoiceCallScreens.log`.
- Existing `build/iOSDeviceDerivedData` was reused. Recording purge tests,
  real provider calls, and quota mutations were excluded. Backend, Docker
  infrastructure, AWS Secret configuration, Routingflare, and the full-sentence
  audio/turn policy were not changed for this UI follow-up. This UI verification
  does not establish an acoustic end-to-end fix.
- After confirming there were no active dev call sessions, the updated signed
  app was explicitly launched on the iPhone with `devicectl`. No call was
  started automatically.

## Mid-sentence audio cut: verified provider reproduction and local turns

This follow-up supersedes the earlier WebRTC `server_vad`/false-flags policy;
the previous checks above remain a historical record, not the current policy.

- A subsequent dev call still acknowledged both false flags, then received
  provider `speech_started` and `output_audio_buffer.cleared` during its first
  response. Its first terminal source was the provider event; the control
  disconnect followed it. That is not evidence that Routingflare initiated
  this cut, nor proof that the learner personally interrupted (echo/noise can
  also be detected as speech).
- Isolated, bounded real-provider probes used the same `gpt-realtime-2.1`
  model/`marin` voice, direct WebRTC, and existing dev AWS-secret credential.
  The learner input was a fixed synthetic greeting, never a user recording or
  live microphone. No BuddyStudy session or monthly user quota was used.
  Each provider call was explicitly hung up with HTTP 200.
- With `server_vad` and both flags false, silence input allowed a complete
  greeting. Overlapping the synthetic learner greeting instead caused a
  matching provider clear/truncation, leaving about 0.7 seconds of audible RTP
  although `response.done` reported `completed` and text finished. The same
  overlap with `semantic_vad` and both false flags also cut audio. Simply
  changing VAD type, trusting completed text, or ignoring the clear is not a
  verified fix. Logs: `build/voiceProviderProbeOverlap.log` and
  `build/voiceProviderProbeSemanticOverlap.log`.
- With explicit null detection and server-owned input commit, the overlapping
  input was acknowledged/transcribed while the first tutor sentence finished.
  The next reply was requested only after the provider buffer stopped. Both
  replies completed and stopped normally, with 602 nonzero received PCM frames,
  no clear/truncation, and no provider error. The earlier one-turn probe's
  completion timer could end during reply two; the corrected two-turn probe
  waited for both drains. Log: `build/voiceProviderProbeManualTwoTurns.log`.
- Implementation retains continuous WebRTC media and the sentence completion,
  provider-buffer, and actual device-playout gates. It changes only how input
  turn boundaries reach the server: an always-installed processed-capture
  detector, 80 ms onset/700 ms release, explicit FloatS16 normalization, paired
  sequence, bounded ordered delivery, and attempt/account/socket fences. The
  server validates ready/capability/rate/sequence, emits one commit per matching
  stop, deduplicates ACKs, and keeps the oldest pending commit timeout bounded.
  Teacher overlap never mutes input. Genuine provider integrity errors still
  fail; timeouts were not extended and failures were not hidden.
- The full focused voice backend run passed 246 tests: 61 application and
  185 infrastructure cases, with no failures or skips. This includes all 24
  orderings of commit ACK/response done/provider stop/device drain, overlapping
  utterances before an older ACK, replay/overflow/timeout/terminal fences,
  long-monologue intervention, explicit-null configuration, and incompatible
  client rejection before provider allocation. The JVM JAR build also passed.
  Logs: `build/voiceManualTurnAllBackendTests.log` and
  `build/voiceManualTurnBackendTests.log`.
- The required generic iOS build passed. The signed physical iPhone 16 Pro
  passed 66 selected non-mutating contracts with zero failures, including 21
  new local-speech tests. Existing `build/iOSDeviceDerivedData` was reused;
  recording/account purge tests and real device microphone/provider sessions
  were excluded. Logs: `build/voiceManualTurnGenericBuild.log` and
  `build/voiceManualTurnDeviceTests.log`.
- These provider/contract tests do not prove acoustic end-to-end behavior of
  the new detector on the user's microphone, noisy-room/echo performance,
  route changes, or a complete 60-minute call. Those real-device release gates
  remain below. The model, monthly/per-user limits, optional-recording consent,
  provider key source, Docker infrastructure, and Routingflare were not changed.
- Local runtime rollout applied implementation `b762d163` to the existing
  `backend-backend-1` on `127.0.0.1:8080`, retaining the `dev` profile and
  `buddystudy/dev` AWS Secret. The refreshed JAR SHA-256 is
  `78bccbbc1824b8a9237aa2b6ed060b86269d874442ffacce8e9df5de7820b830`.
  Every original environment value was compared and preserved exactly; the
  four existing infrastructure container IDs and network were unchanged.
  Both local readiness and dependency health requests returned HTTP 200.
  No new persistent infrastructure container or routing change was made.
  Log: `build/voiceManualTurnDevRefresh.log`.
- With zero active dev calls confirmed, the normal signed iOS app was built,
  installed on the paired iPhone, and explicitly launched successfully. No
  microphone/provider call was started automatically. Logs:
  `build/voiceManualTurnSignedBuild.log`, `build/voiceManualTurnDeviceInstall.log`,
  and `build/voiceManualTurnDeviceLaunch.log`.

The manual-turn wire protocol follows [OpenAI's conversation guide](https://developers.openai.com/api/docs/guides/realtime-conversations);
the observed interruption is distinguished from response completion using the
[WebRTC output-buffer event contract](https://developers.openai.com/api/reference/resources/realtime/server-events#output_audio_buffer.cleared).

## Missing learner replies: native capture-delegate registration

- Three subsequent physical-iPhone calls completed the tutor opening and then
  followed the local stop/end path, but emitted no local speech start/stop events.
  The phone's allowlisted diagnostics showed ready/listening before tutor audio,
  so neither a missing ready gate nor the later control close explained the
  absent learner turns. No transcript or preference secrets were inspected.
- The pinned LiveKitWebRTC 144.7559.14 iOS arm64 binary confirms that
  `LKRTCAudioCustomProcessingAdapter.initWithDelegate:` ignores the supplied
  delegate and initializes its weak reference to nil. The default processing
  module initializer only calls that initializer. The delegate setter actually
  stores the reference and replays initialization with the native sample rate.
  This also matches the [upstream adapter implementation](https://github.com/webrtc-sdk/webrtc/blob/m144_release/sdk/objc/components/audio/RTCAudioCustomProcessingAdapter.mm).
  The previous pure energy/stream contracts did not exercise this native boundary.
- `VoiceTutorAudioProcessingModuleFactory` now installs both delegates explicitly
  and verifies their native identity before any peer connection is made. A
  missing or wrong delegate is a startup error, not a silently deaf call. The
  existing capture tap remains strongly retained, independent of recording
  consent. Provider detection stays null; speech thresholds, full-duplex input,
  sentence completion, server-owned commits, quota, and routing are unchanged.
- Restoring the optional recording callbacks also requires normalizing their
  copied FloatS16 samples to `[-1, 1]` before the existing AAC recorder. This
  conversion never writes into the native buffer or enables unconsented audio
  persistence. Initialization/first-input diagnostics are each bounded to one
  event and expose only counters, sample rate, and gate/closed flags; no audio,
  energy measurements, user speech, or credentials are retained.
- The generic `StudyMateiOS` build passed with the existing device DerivedData.
  Log: `build/voiceCaptureDelegateGenericBuild.log`.
- The signed physical iPhone passed all 74 selected non-purge contracts,
  including eight new native-boundary cases and the existing 21 local-speech
  cases. The real pinned SDK reproduced nil constructor delegates; the
  production factory installed the exact capture/render objects and rejected
  missing, substituted, or unexpected delegates. Metadata bounds, closed-tap
  fences, no-recording capture, and copied recording normalization also passed.
- A separate, explicit opt-in microphone probe requires a physical iPhone and
  already-granted permission. It keeps only initialization/frame counters and
  stops within a three-second observation window. Its first bare-ADM attempt
  correctly failed with one initialization but zero processed buffers: without
  a peer, WebRTC has not registered ADM's audio-transport callback. The fixture
  now creates and retains an unnegotiated peer solely to initialize the media
  engine, with ICE disabled and no sender, SDP, provider session, recording, or
  playout. It keeps the original frame-receipt assertions and checks that no
  negotiation or candidate gathering occurred. The failed attempt is retained
  in `build/voiceCaptureDelegateNativeInitialAttachments`; it is not counted as
  a successful microphone test.
- Another fixture attempt reported native start `-3010` with an unavailable
  0 Hz output format. The test now waits for an active foreground test host and
  preserves bounded preflight metadata even when startup fails; it does not
  retry failed starts or turn zero frames into a pass. The earlier attempt had
  no foreground snapshot, so its exact OS activation cause is not claimed.
- The final physical-iPhone native probe passed with no failures or skips in
  0.543 seconds: four native initializations, six processed/valid input buffers,
  2,880 frames at 48 kHz, and exactly one diagnostic of each kind. All seven
  preflight snapshots showed a foreground-active app. After native startup,
  recording and the engine were running, the microphone was unmuted, and output
  remained disabled/stopped. The closed session-ready gate produced no speech
  events, while native input delivery remained observable. No sample contents
  were retained. Logs: `build/voiceCaptureDelegateNativeMicrophonePreflight.log`
  and `build/voiceCaptureDelegateNativeVerifiedAttachments`.
- This verifies actual microphone-to-native-capture delivery, not recognition
  of a user's spoken reply, an acoustic end-to-end lesson, noise robustness,
  or route changes. The ordinary iOS contract log is
  `build/voiceCaptureDelegateDeviceTests.log`; the release gates below still
  apply. Backend/Routingflare, the existing 8080 dev container, and its AWS
  secret and infrastructure were not changed by this iOS-only fix.
- The normal signed `StudyMateiOS` app was rebuilt, installed, and launched
  on the paired iPhone with the existing `https://lowfidev.cloud` dev route.
  Zero active dev calls were checked before installation and again before
  replacing the test-host process. No lesson or provider call was started by
  the launch. The same API and four infrastructure container IDs remained
  running. Logs: `build/voiceCaptureDelegateSignedBuild.log`,
  `build/voiceCaptureDelegateDeviceInstall.log`, and
  `build/voiceCaptureDelegateDeviceLaunch.log`.

## Release gates

`VOICE_TUTOR_ENABLED` and `VOICE_TUTOR_RECORDING_ENABLED` remain default-off.
No production deployment, production database migration, or production S3 change
was performed. Before enabling them, complete the recording-specific privacy
approval and staging validation in [the data inventory](LEGAL_DATA_INVENTORY.md).

Real OpenAI/S3 end-to-end calls still require staging verification: listen to
learner/tutor overlap on an iPhone; measure speech-end-to-audio latency and the
sentence handoff; exercise speaker/headphone route changes, network loss,
background/lock termination, the full 60-minute boundary, recording export,
upload recovery, playback, deletion, and cross-account isolation. Automated
contracts and device launch alone do not establish those acoustic results.

## Replies blocked after the opening: response boundary is not PCM silence

This follow-up supersedes the earlier WebRTC client-playout-drain prerequisite.
The earlier test results remain historical evidence, not the current turn gate.

- A later 46-second call, explicitly ended by the user, had one tutor opening,
  one completed response, and one matching provider output-buffer stop. Four
  learner commits and four learner transcriptions were accepted, but no client
  playout-drain acknowledgement was sent. Thus learner capture/commit was
  working; the missing next reply was not explained by a missing user turn or
  by Routingflare closing this call.
- The phone reported 4,972 native render callbacks, 4,104 with nonzero PCM:
  roughly 41 seconds of nonzero callbacks despite an approximately six-second
  opening. The previous client timer moved its deadline on every nonzero
  render, so continuing output could starve its acknowledgement indefinitely.
  WebRTC's NetEq path can generate nonzero comfort/concealment noise after
  speech. The observed counters identify this completion-gate failure; they
  do not identify the speech type of each frame or prove acoustic end-of-speech.
- The backend now waits for generation completion and the same response's
  provider output-buffer stop, together with the existing committed learner
  turn conditions, without requiring a client PCM-silence/drain message.
  Explicit null provider turn detection and full-duplex learner capture remain
  in effect. Starting the next response neither clears/truncates the current
  output nor recreates the continuous native RTP stream; queued local audio
  remains in that stream's existing playback order.
- iOS uses the production `VoiceTutorWebRTCResponseState` to match the two
  server completion signals in either order and complete that response once.
  Native renders affect only the small speaking indication while that
  response's server output is active. Continuing nonzero output cannot move
  a completion deadline, withhold the next response, or restart the indication
  after the provider stop. This is server-streaming/UI state, not a claim that
  the final sample has already left the speaker. Acoustic tail integrity and
  no-gap sentence handoff still require the physical verification below.

### Verification at this checkpoint

- The required generic `StudyMateiOS` build passed using the existing
  `build/iOSDeviceDerivedData`. Log:
  `build/voiceResponseContinuationGenericBuild.log`.
- The physical iPhone passed all 74 explicitly selected non-purge contracts
  with zero failures. They cover the response-state change and existing
  native-boundary/local-speech contracts, not a three-turn provider call.
  Log: `build/voiceResponseContinuationDeviceTests.log`.
- The focused backend verification passed. Counting only the `VoiceTutor`
  test classes gives 61 application and 168 infrastructure tests, 229 total;
  unrelated or stale XML reports are not included in that count. Log:
  `build/voiceResponseContinuationBackendVerified.log`.
- The local JVM JAR build passed without building a Docker image. Log:
  `build/voiceResponseContinuationBackendBuild.log`.
- A separate opt-in native conversation test compiled. It is designed to
  receive three real provider responses on a receive-only iPhone peer after
  two fixed synthetic learner inputs, reuse the production renderer/response
  state, and inspect stream continuity and available native CNG/flush counters.
  It never creates a BuddyStudy session, consumes user voice quota, captures
  the user's microphone, or writes a recording. Synthetic text input is not
  microphone/ASR validation, and the fixture is not a sample-accurate acoustic
  end-of-sentence test.
- Its first device run failed before signaling reached the temporary host:
  `NSURLErrorDomain -1009`, with the network path reporting
  `Local network prohibited`. No provider call was created. The user was asked
  to enable local-network access, and confirmation was still pending at this
  checkpoint. This blocked attempt is not a passed native three-turn test,
  device-CNG proof, or acoustic acceptance. Log:
  `build/voiceResponseContinuationNativeConversation.log`.
- The test's native completion closure was explicitly annotated `@Sendable`
  after the compiler warning, and the signed iPhone test build passed again.
  Log: `build/voiceResponseContinuationFinalTestBuild.log`. This compile-only
  check did not repeat the permission-blocked provider test.
  Normal-app reinstallation/launch and the existing 8080 dev
  backend refresh for this response-continuation change were also still
  pending at this checkpoint; build success is not deployment confirmation.

### Separate OSS speech/turn-model investigation

- A bounded offline experiment used pinned Silero VAD and Smart Turn v3.2 CPU
  models with fixed Korean Yuna TTS plus seeded noise and digital silence.
  Silero distinguished that noise/silence from speech, including hesitation
  sounds. Speech presence is not a useful-utterance/filler classifier.
- Smart Turn predicts turn completion, not whether an utterance is meaningful.
  At the upstream example threshold, these synthetic fixtures included
  completed predictions for hesitation-only input and incomplete predictions
  for meaningful short replies/requests. Extra trailing silence changed some
  decisions; it did not establish a reliable filler filter.
- These are limited synthetic observations, not an accuracy benchmark for real
  Korean callers or iPhone latency measurements. Neither model was installed
  as a production turn-admission or response-completion gate by this research.
  Artifacts: `build/voiceTurnModelProbe.py` and
  `build/voiceTurnModelAssets/probe-results.json`; pinned hashes, revisions,
  example thresholds, timing environment, and model licenses are recorded
  with those local research artifacts. No user audio or provider call was used
  in that experiment.

### Local rollout and device-network follow-up

- Commit `c33f0fe4` was installed in the existing `backend` Compose API service
  on `127.0.0.1:8080`. The `dev` profile, AWS dev-secret configuration, original
  environment values, mounts, resource limits, and network were preserved.
  The database, Redis, translation, and backup container identities and start
  times were unchanged. Only the API was refreshed; no Docker image was built
  and no additional infrastructure stack or route was created.
- The JAR SHA-256 is
  `ee156104cfe927fcb8c2930f97f2bcbc2cad8835fad93a202e156f32dba81800`.
  The previous JAR remains available for local rollback. No active voice
  session was present before the refresh. Manual local health and readiness
  requests both returned HTTP 200; no GitHub Actions runtime check was added.
- The normal signed `StudyMateiOS` app rebuilt successfully, was installed on
  the physical iPhone, and launched with the existing public dev base URL.
  The normal build removed the test-only injected frameworks and plug-ins.
  Logs: `build/voiceResponseContinuationSignedBuild.log`,
  `build/voiceResponseContinuationDeviceInstall.log`, and
  `build/voiceResponseContinuationDeviceLaunch.log`.
- After the user enabled iPhone local-network access, the native three-turn
  test no longer reported `Local network prohibited`. Its requests to the
  temporary Mac fixture instead timed out with `NSURLErrorDomain -1001`.
  No provider call or answer was observed, and the temporary fixture exited.
  Log: `build/voiceResponseContinuationNativeConversationAllowed.log`.
  This is still not a passing three-turn/native-audio test.
- Read-only checks confirmed the Mac LAN address and the phone's CoreDevice
  local-network connection. The Mac firewall is enabled; the actual Homebrew
  Python executable is ad-hoc-signed and not listed as an approved application.
  The user was asked to approve Python's incoming connections. This is a
  candidate cause of the LAN timeout, not a proven packet-drop attribution.
  No firewall setting or routing rule was changed to bypass it.

### Additional meaningful-input model probes

- Namo Turn Detector v1 Korean was evaluated using the author's exact
  single-transcript input, quantized ONNX model, and unmodified argmax decision.
  Its documented complete/incomplete examples passed. However, it classified
  short meaningful Korean replies such as readiness confirmations, requests,
  and affirmative/negative answers as incomplete. Some fillers scored higher
  than meaningful replies, so tuning one threshold is not a justified fix.
  It was not installed as a production input gate. Fixed revision:
  `8a7c88d5daab243a0220cf7a6c70060a583ba77b` (Apache-2.0).
  Artifacts: `build/namoTurnModelProbe.py` and
  `build/voiceTurnModelAssets/namo/probe-results.json`.
- A separate contextual probe used official Qwen2.5-0.5B-Instruct Q8_0 with
  `de.kherud:llama:4.2.0`, one frozen prompt, six in-prompt examples, and 36
  held-out synthetic teacher/learner pairs. It preserved all 25 meaningful
  inputs but incorrectly admitted 10 of the 11 hesitation/noise cases.
  Overall accuracy (26/36) was only one case better than always admitting
  input. It was not adopted. CPU inference was about 455 ms median on this
  Mac, with about 943 MiB peak process RSS; these are not server/iPhone latency
  or memory measurements. The isolated probe also required explicit process
  termination after results were saved because a JNI-attached thread prevented
  natural JVM exit. No application process was terminated by that cleanup.
  Artifacts: `build/QwenTurnModelProbe.java` and
  `build/qwenTurnModelProbe/RESULTS.md`.
- Qwen3-0.6B was also checked with a pinned quantized ONNX conversion and the
  same frozen 36 held-out contextual cases. The canonical non-thinking chat
  template and first-token two-label logit selection rejected 12 of the 25
  meaningful inputs and admitted 5 of the 11 noise/hesitation inputs (19/36
  correct). It was not adopted. This is a limited probe of this model,
  conversion, prompt, and decision method, not a claim that all open models
  cannot classify Korean turns. Median inference was approximately 1.21 s,
  with 2.30 GiB peak Python process RSS on this Mac, not on the iPhone or dev
  JVM. ARM64 libraries were found in the ONNX Runtime Java artifact indexes;
  that packaging check is not a native Java load or deployment test.
  Conversion revision: `da1453100cf3ff33ef56d17983fc7a8648706db6`, linked
  upstream Qwen revision `c1899de289a04d12100db370d81485cdf75e47ca`
  (Apache-2.0). Artifacts: `build/qwen3OnnxTurnModelProbe.py` and
  `build/voiceTurnModelAssets/qwen3/summary.json`.
- One bounded follow-up reused those same Qwen3 weights with a single Korean
  zero-shot prompt and 24 new cases frozen before inference. It accepted all
  12 meaningful inputs, but also accepted all 12 hesitation/noise/empty inputs
  (12/24 correct). The original 36 cases were counted only as diagnostics, not
  fresh held-out data; 10 of their 11 negative cases were admitted, and one
  meaningful input was dropped. No further prompt/threshold tuning was done.
  This also failed admission requirements. The run finished in 46.38 seconds,
  with approximately 748 ms median per new case and 2.20 GiB peak process RSS.
  Artifacts: `build/qwen3OnnxKoreanTurnModelProbe.py` and
  `build/voiceTurnModelAssets/qwen3/koreanFollowup/summary.json`.
- No regex, filler word exclusion list, or unvalidated semantic gate has been
  added to production code. Speech detection, turn completion, and meaningful
  input are separate decisions; passing one is not evidence of the others.
- After the probes stopped, only the three downloaded, rejected Namo/Qwen
  weight files were removed (approximately 1.43 GB total). Frozen cases,
  results, scripts, model revisions, licenses, and SHA-256 metadata remain for
  reproducibility and explicit re-download. No application data, existing
  Docker volume, user recording, or unrelated Xcode artifact was removed.

### Physical native three-turn verification after network approval

- Read-only firewall state subsequently showed the actual Homebrew Python
  executable allowed to accept incoming connections. The user-approved network
  path then reached the temporary fixture. The selected physical-iPhone test
  passed in 30.65 seconds with zero failures; the fixture observed one provider
  allocation, completed all three teacher responses, accepted both fixed text
  learner messages, and received HTTP 200 for provider hangup before exiting.
  Log: `build/voiceResponseContinuationNativeConversationFirewallAllowed.log`.
- The metadata-only XCTest attachment recorded new native nonzero buffers for
  every turn (726, 845, 808), with new inbound RTP bytes for every turn
  (62,009, 69,328, 70,699). The receiver, track, SSRC, codec, and transport stayed
  the same. Across 118 inbound statistics samples, RTP timestamps moved forward
  115 times and backward zero times; byte/packet counters remained monotonic.
- The available native counters observed 13 CNG frames and 3 PLC frames, zero
  jitter-buffer flushes after the first turn, and 344 nonzero callbacks after
  response completion. Thus this run actually exercised continuing native
  non-silent output without depending on a client drain acknowledgement.
  Provider clears, truncations, and errors were all zero. No local recording,
  capture buffers, input events, microphone permission, or app-quota request was
  involved. The final state was listening, with native playout still enabled.
- This is evidence for repeated native receive/playout and stream continuity,
  not a microphone/ASR test, full app-control/backend integration call, listening
  judgement of acoustic quality, or a sample-accurate guarantee about the last
  audible syllable. The synthetic fixture uses the production renderer and
  response-state types; backend turn scheduling is verified separately above.
- Result bundle:
  `build/iOSDeviceDerivedData/Logs/Test/Test-StudyMateiOS-2026.08.31_17-34-22-+0900.xcresult`.
  Exported attachment directory:
  `build/voiceResponseContinuationNativeConversationPassedAttachments`.
  A reusable, explicitly opt-in fixture and manual instructions are provided in
  [the native conversation probe guide](../scripts/voice-tutor-native-conversation.md).
  No paid call or runtime check was added to CI.
- After the selected test, a normal signed app build passed, the iPhone install
  completed, and only then was the normal app launched with the unchanged dev
  base URL. This removed test-injected frameworks and plug-ins again. No active
  BuddyStudy voice session existed before reinstalling. Logs:
  `build/voiceResponseContinuationAfterNativeSignedBuild.log`,
  `build/voiceResponseContinuationAfterNativeDeviceInstall.log`, and
  `build/voiceResponseContinuationAfterNativeDeviceLaunch.log`.
- The reusable host was extracted from the local fixture after the passing
  native run and then hardened for canceled/in-flight allocation cleanup. Its
  17 no-network regression tests passed: admission closes immediately on stop,
  one concurrent offer owns allocation, cleanup recovers a delayed call ID
  within the original negotiation deadline, and uncertain allocation/hangup
  cannot report success. HTTP/websocket operations are in-memory fakes and real
  client/listener construction is forbidden in these tests. No additional paid
  provider call was made for this host-only hardening. Root verification log:
  `build/voiceNativeHostOfflineRegression.log`.
  Reproduce using the [offline host checks](../scripts/voice-tutor-native-conversation.md#no-network-host-regression-checks).

## Silero and contextual meaningful-input assessment

### Implementation boundaries

- iOS bundles the pinned MIT Silero v6 Core ML model (905,043 source bytes,
  approximately 0.9 MB). Model provenance, hashes, conversion revision and
  license are in [the bundled model README](../StudyMate/Resources/SileroVAD/README.md).
  Five-second local model loading/warm-up must complete before reserving a call;
  there is no model download, RMS-classifier fallback or additional runtime.
- Native FloatS16 microphone frames remain untouched for WebRTC transmission.
  A separate bounded worker copies/normalizes one channel, continuously converts
  to 16 kHz and runs 512-sample Silero windows with recurrent state and context.
  Probability hysteresis uses an 80 ms onset and 700 ms release. Readiness,
  explicit mute, close and capture-format generations fence stale callbacks;
  tutor speech never gates capture or interrupts tutor playback.
- Acoustic speech includes hesitation. Final ASR therefore goes through the
  existing GPT configuration (default `gpt-5.4`) with the latest completed
  teacher sentence. The strict-schema result must contain exactly one decision
  for each original USER item ID. No regex, filler blacklist, minimum sentence
  length, transcript rewrite or alternate-model fallback decides meaning.
  Short acknowledgements, negations, names, numbers, questions and partial
  meaningful ideas are explicitly valid; uncertainty preserves meaningful input.
- The application service implements its inbound use case and depends on an
  outbound provider port. Process-wide admission is bounded to four requests,
  with a five-second total deadline, no waiting queue and no implicit retries.
  Configurable batch/text bounds are documented in [the backend README](../backend/README.md).
  The authenticated context supplies user identity; callers cannot select a
  different key/account through a provider call ID. Chat Completions uses
  `store: false` and the body `safety_identifier`, not the Realtime-only header.
- Assessment and transcript publication run in an independent ordered worker,
  never inside the provider's ordered receive loop. Meaningful input is
  persisted/forwarded before authorizing a reply. Non-communicative USER items
  are removed by exact ID and matching provider acknowledgement before another
  reply. Late responses, stale assessment tokens, duplicate events and closed
  sessions cannot republish input or schedule work.
- ASR, assessment, publish/delete acknowledgement and response phases have
  separate deadlines. Assessment failure is not a negative semantic verdict:
  a small retry hint keeps the call alive after safe input cleanup. An unresolved
  cleanup or terminal deadline closes pending work immediately through an
  independent failure signal, even when outbound control delivery is blocked.
- A long learner monologue first gets a checkpoint commit without stopping
  capture. Only a meaningful, persisted checkpoint permits a short tutor
  intervention. Immediate stop/tail commits wait up to 250 ms; rapid mute/unmute
  stops in that interval share a commit that settles every reserved speech slot.
  An exact matching empty-commit error settles only its own outstanding commit.
  None of these input decisions clears, cancels, truncates, mutes or resets the
  teacher's audio.

### Backend and real-model checks

- The final focused application run passed 72 tests; the infrastructure report
  contains 240 tests with zero failures and one explicitly opt-in live-model
  test skipped (239 passed). `:tutor:bootJar` also passed. These counts come from
  the current Gradle reports, not unrelated old temporary XML files in the
  reused build directory. Log: `build/voiceMeaningfulInputBackendFinal.log`.
- Coverage includes strict parsing/identity correlation, bounded admission,
  cancellation/deadlines, private diagnostics, ASR-before-commit acknowledgement,
  both teacher completion/audio-stop orders, meaningful publication barriers,
  filler deletion acknowledgement, checkpoint/tail coalescing, stale worker
  callbacks and terminal failure under outbound backpressure.
- A separate, explicitly enabled real-GPT test used the production use case,
  adapter, default model, schema and five-second deadline. Four sequential
  requests classified 24 frozen synthetic cases: all 15 meaningful and all nine
  non-communicative cases matched their expected labels. Batch durations were
  4,555 ms, 1,650 ms, 1,662 ms and 1,709 ms (9,583 ms total). No retries or
  prompt/model tuning followed the results. Log:
  `build/voiceMeaningfulInputLiveAssessment.log`; reusable opt-in test:
  `VoiceTutorInputAssessmentLiveTest`.
- The live check used the existing dev regular user-content API credential
  only in process memory. It created no app session, quota reservation,
  database record or recording, and used no real conversation/audio. It is
  skipped in ordinary tests/CI. Twenty-four synthetic cases are not an accuracy
  benchmark, and the cold request's proximity to the deadline is not evidence
  of a production latency guarantee or load/concurrency performance.

### Physical iPhone checks

- The required generic `StudyMateiOS` Debug build passed using
  `generic/platform=iOS`, the existing `build/iOSDeviceDerivedData`, and
  `CODE_SIGNING_ALLOWED=NO`. Log:
  `build/voiceMeaningfulInputGenericBuildFinal.log`.
- The signed test host built successfully. A selected run on iPhone 16 Pro,
  iOS 26.6, passed 101 tests with no failures or skips: 76 non-mutating call
  contracts and 25 offline Silero/pipeline contracts. Recording/account purge
  tests and microphone/provider probes were excluded from that run. Existing
  DerivedData was reused. Logs:
  `build/voiceMeaningfulInputDeviceTestBuildFinal.log` and
  `build/voiceMeaningfulInputDeviceTestsFinal.log`; result bundle:
  `Test-StudyMateiOS-2026.08.31_19-15-28-+0900.xcresult`.
- The first physical run exposed a genuine large-buffer conversion defect:
  the once-only input provider ignored the converter's requested frame count,
  supplying only a 4,096-frame prefix of larger 44.1/48 kHz input. The fix
  retains an offset and supplies bounded slices until the complete input is
  consumed. Assertions were strengthened, not relaxed: 100 ms inputs must
  produce exactly 1,600 samples, and 250 ms inputs at 8/32/44.1/48/96/192 kHz
  must produce exactly 4,000 samples with the same waveform as 10 ms chunks.
  On-device pull metadata confirmed complete consumption even for 48,000 input
  frames. This finding is not an attribution of the user's earlier incident.
  Attachment: `build/voiceMeaningfulInputResamplerAttachments`.
- A separately selected, explicitly enabled synthetic Korean test passed with
  no skip. The installed, non-personal system voice generated five in-memory
  clips: short affirmative, short negative, voiced hesitation, readiness and
  topic question. Each produced both acoustic start and stop with the bundled
  model. Per-prediction maxima ranged from 0.56 to 1.23 ms in this selected
  device run; these are not production tail-latency or battery measurements.
  There was no microphone, audible playback, provider, recording file or
  semantic judgement. Log: `build/voiceMeaningfulInputSyntheticDeviceProbe.log`;
  attachment: `build/voiceMeaningfulInputSyntheticAttachments`.
- The opt-in native microphone test also passed, without requesting a new
  permission. The actual production WebRTC module factory installed its capture
  delegate; readiness-disabled delivery was observed before enabling the
  acoustic gate. The real 48 kHz callback then reached five bundled Silero
  predictions, with 28 native input buffers observed. No sender, SDP, ICE
  gathering, provider, audio storage or playback was used. Log:
  `build/voiceMeaningfulInputNativeCaptureProbe.log`; attachment:
  `build/voiceMeaningfulInputNativeCaptureAttachments`.
- These tests separately exercise real microphone-to-model delivery, Korean
  acoustic detection, actual GPT classification, controller integration and the
  previously verified native receive/playout path. They do **not** constitute
  a human microphone → live ASR → backend admission → audible reply end-to-end
  pass or a guarantee that all natural Korean calls remain uninterrupted.

### Existing dev API and normal app refresh

- Implementation commit `9b1d30f0` was installed in the existing `backend`
  Compose API on `127.0.0.1:8080` at 19:20 KST. The `dev` profile and AWS
  `buddystudy/dev` secret configuration, all original environment values,
  mounts, network and resource limits were preserved. The database, Redis,
  translation and backup container IDs and start times were unchanged. No
  additional service stack, Docker image build, tunnel or routing change was
  introduced. Log: `build/voiceMeaningfulInputDevRefresh.log`.
- The new JAR SHA-256 is
  `d5a1bfbcd0c61a2dbd82f4725f481a789dfd3476d29d2c0cb78a81e8dc3717bb`.
  The previous `c33f0fe4` JAR remains available for rollback. Active sessions
  were checked before staging and immediately before the API restart; both
  counts were zero. Manual local health/readiness and the existing public dev
  health route returned HTTP 200. These checks are local verification, not
  GitHub Actions runtime health gates or a live voice-call pass.
- After testing, the normal signed iOS build succeeded. Its bundle contained
  the compiled Silero model and MIT notice, with no injected XCTest plug-in or
  test frameworks. It was installed and then launched on the physical iPhone
  with the unchanged `https://lowfidev.cloud` dev base URL. No user recordings,
  settings, app data or unrelated build directory was deleted. Logs:
  `build/voiceMeaningfulInputSignedBuildFinal.log`,
  `build/voiceMeaningfulInputDeviceInstall.log`, and
  `build/voiceMeaningfulInputDeviceLaunch.log`.

### Post-call summary inspection (read-only)

- The reported summary is a server-side operation over stored final transcripts,
  not another microphone session or a reanalysis of an audio recording. Ending
  a call settles usage and queues its separate result as `PENDING`; the existing
  five-second recovery scheduler claims work as `PROCESSING`. A separate GPT
  request receives topic, difficulty, role-tagged text and summary instructions,
  then persists summary/strengths/improvements/next steps as `COMPLETED`.
- The inspected 147-second dev call had a completed `gpt-5.4` result roughly
  8.03 seconds after call end (seven learner and ten tutor transcript items).
  No transcript, recording, credential, user identity or full session ID was
  copied into this report. An earlier 131-second call completed its result in
  approximately 10.31 seconds. These are observations, not an SLA.
- The app can incorrectly leave its pending text visible: its poll exits when
  any non-nil result exists, including the server's `PROCESSING` placeholder,
  and a cached history detail is not refreshed. The backend had already
  completed the inspected result. This turn diagnosed that UI bug but did not
  change result polling/caching, stored summaries or quota data.
- No-transcript calls receive a deterministic empty-conversation result without
  GPT. Provider/parse failure becomes `FAILED`, not an automatic retry; an
  abandoned `PROCESSING` lease can be reclaimed after 300 seconds. No new MCP
  integration, worker container or infrastructure is needed for this flow.

## 2026-08-31 — voice MCP tools and learning-summary repair

This section supersedes the outstanding polling/cache defect identified in the
read-only inspection above. It does not change the continuous WebRTC media path,
the contextual meaningful-input classifier, or the user's 60-minute allowance.

### Confirmed causes and implementation

- The existing MCP catalog exposed study tools, but the voice session registered
  no tools and had no function-call/result execution path. Its instructions
  explicitly forbade app-data creation. An enabled HTTP MCP endpoint alone
  cannot make the voice LLM aware of those capabilities.
- The voice backend now registers seven existing MCP definitions, verifies
  their schemas and tool-choice setting in the provider's session-update ACK,
  and invokes those same permission-checked handlers through a local bridge.
  The model receives neither the app bearer nor an MCP access token. Writes
  are restricted to explicitly requested child topics in the current study's
  subtree; root creation, deletion, question generation and call control remain
  unavailable as voice tools.
- `get_study` is a single-node read, not a child list. The existing
  `list_studies` tool now supports an owner-checked `parent_study_id` filter,
  with matching count/page predicates and exact offsets. Omitting it preserves
  the original catalog behavior. Creation still uses the existing study use
  case and does not consume question quota.
- A bounded, serial worker executes only correlated, completed function calls.
  The exact output item/call acknowledgement releases a follow-up response.
  Proven function-only responses do not wait for an audio-stop event that will
  never arrive; mixed speech/tool responses still wait for playback completion.
  Input classification, accepted-transcript persistence and filler-deletion
  acknowledgement remain independent gates. No cancel, buffer-clear, truncate,
  microphone reset or RTP restart was added.
- Each call allows at most eight tools per response, seven tool rounds per learner
  turn and 256 tool-call IDs per session. Arguments/results are valid JSON
  bounded to 16 KiB; execution and output ACK have separate 15-second deadlines.
  Duplicate IDs cannot repeat writes, and an uncertain timed-out mutation is
  not automatically replayed or represented as confirmed success.
- Successful child creation publishes only its numeric study ID to the app.
  An identity/attempt-fenced read refreshes that study's metadata through the
  existing store, preserving pending questions and answer drafts. Private tool
  arguments/results are not forwarded to the UI or diagnostic logs.
- Read-only incident inspection found a normal `CLIENT_END` followed by a late
  provider close. The transport error was copied into a failed result about
  7.7 ms after call end, with no model recorded: this was not evidence that GPT
  summary generation failed. The first terminal reason/error now settles
  atomically, and a late close cannot overwrite a completed call or summary.
- A failed transport with usable persisted transcript can still be summarized.
  The existing scheduler also recovers only the narrow legacy signature where
  the result error is the copied call error and no model was used. Actual model
  failures remain terminal; cancellation or persistence failure retains the
  existing recoverable processing lease. Completed results and settled usage
  are preserved. No manual transcript, summary or quota update is used.
- Summary generation remains a server-side, bounded GPT request over saved
  final transcript text, not an audio upload. Response finish reason, refusal,
  size and JSON shape are validated, with fixed safe failure diagnostics.
  Detail responses project status and content from the same result-row read,
  avoiding stale top-level `FAILED` combined with newly completed content.
- The app now distinguishes pending/processing, completed, empty and failed
  results. Both call and history screens use at most eight identity-fenced
  detail reads with bounded delays. Reopening history refreshes cached data;
  the compact recheck action reads the existing result and does not start a new
  generation request. A processing placeholder is not treated as completion.

### Actual provider protocol check

- A bounded synthetic check used the existing dev regular API credential only
  in process memory and one Realtime WebSocket session per attempt. It supplied
  synthetic study IDs and mocked tool results, with no real MCP business write,
  microphone, app session, recording file or quota reservation.
- The first attempt exposed a real Realtime contract violation: the generated
  function-result item ID was 38 characters, and the provider rejected it with
  `string_above_max_length`. Production and probe IDs were corrected to the
  32-character maximum, and a regression assertion preserves that bound.
- The subsequent bounded attempt passed: one provider session, four responses,
  two actual model-selected tool calls (child lookup and child creation), two
  spoken results, 571,200 generated audio bytes, verified session schemas and
  matching `conversation.item.added` acknowledgements. No output transcript or
  audio was saved. Local diagnostic: `build/probeVoiceMcpProtocol.py`.
- This proves actual model tool selection, output acknowledgement and subsequent
  audio generation for those synthetic exchanges. It is not a physical iPhone
  microphone → live ASR → production MCP write → audible speaker end-to-end
  pass. Real ownership/persistence integration is covered separately by mock
  and in-memory repository tests, not asserted from the synthetic provider run.

### Physical iPhone verification

- The generic iOS Debug build passed using `StudyMateiOS`,
  `generic/platform=iOS`, `CODE_SIGNING_ALLOWED=NO` and the existing
  `build/iOSDeviceDerivedData`. Log: `build/voiceSummaryMcpGenericBuild.log`.
- A signed build-for-testing and explicit selection on the iPhone 16 Pro,
  iOS 26.6, passed 136 tests with zero failures or skips: 76 existing call
  contracts, four new MCP event contracts, 25 offline Silero/pipeline contracts,
  and 31 summary/metadata-refresh tests. The latter include bounded polling,
  stale identity/attempt completion, processing placeholders, final-attempt
  completion, read failure and preservation of active drafts/questions.
  Logs: `build/voiceSummaryMcpDeviceTestBuild.log`,
  `build/voiceSummaryMcpDeviceTests.log`; result bundle:
  `Test-StudyMateiOS-2026.08.31_20-12-06-+0900.xcresult`.
- No microphone, provider request, audio purge, account mutation or recording
  test ran in that selection. Afterward a normal signed build and strict
  signature verification passed. The normal app contained the Silero model and
  MIT notice, with no injected XCTest plug-in/framework. It was installed and
  launched with the unchanged `https://lowfidev.cloud` dev base URL; active call
  count was zero before install, before launch and after launch. Logs:
  `build/voiceSummaryMcpSignedBuildFinal.log`,
  `build/voiceSummaryMcpDeviceInstall.log`,
  `build/voiceSummaryMcpDeviceLaunch.log`.

### Backend verification and test-environment findings

- The first focused run exposed a runtime dependency conflict: Spring AI 2.x
  selected NetworkNT's Jackson 3 ABI, while the MCP SDK selected its Jackson 2
  validator. Nineteen schema-dependent tests failed with `NoSuchMethodError`.
  Both the HTTP server and voice bridge now explicitly use the SDK's Jackson 3
  validator with JSON-compatible map boundaries. Input, output and meta-schema
  checks remain enabled; validator errors are redacted before SDK logging.
  HTTP MCP fixtures subsequently passed all eight tests, and the existing
  OpenAI/Spring AI routing plus structured-output advisor tests passed.
- A preliminary `tutor:test` invocation revealed that Graal's `processTestAot`
  initializes all Spring test contexts before JUnit's `--tests` selection. It
  briefly started an isolated MySQL Testcontainer and its Ryuk helper and
  attempted the optional default-dev secret import. The build was stopped
  before test methods ran. Testcontainers removed both temporary containers;
  Docker metadata confirmed their destruction and unchanged existing database
  and Redis container IDs/start times. No application voice/model test or
  production deployment occurred in that invocation.
- The final focused run explicitly excludes the five test-AOT tasks, with its
  task graph checked first. Pure HTTP/permission fixtures and H2 repository
  tests require no new infrastructure. Newly added MySQL summary persistence
  integration cases compile but are not claimed as executed in this turn.
- The final 37-suite regression report contains 479 unique tests: 478 passed,
  zero failed and one explicitly opt-in paid classifier test skipped.
  Application: 105 passed; infrastructure: 363 passed plus one skipped;
  tutor: ten passed. This includes H2/loopback verification, not only unit
  tests. No provider request or real app-data write occurs in that selected
  regression run. Log: `build/voiceMcpSummaryFinalRegression.log`.
- Coverage includes 13 function-relay tests, two actual input-coordinator/tool
  ordering tests, 33 session-tool configuration cases, 23 MCP voice bridge
  tests, five direct-child repository pagination cases, explicit SDK schema
  validation and Spring AI compatibility, HTTP MCP/permission boundaries,
  first-terminal ordering, summary recovery policy and 35 voice service cases
  including the three detail-snapshot regressions.
- The normal main `processAot` and `bootJar` then passed with the existing
  explicit `aot` profile and build-only configuration; only test AOT was
  excluded. This main build created no Testcontainer and imported no dev
  secret. Log: `build/voiceMcpSummaryFinalMainAotJar.log`. The final artifact
  is 330,667,522 bytes with SHA-256
  `8c73d7b8fc80ef0a5431add049b305458fde9213b4a266c1b5175ddf18379b7a`.

### Existing dev API refresh and observed summary recovery

- Implementation commit `4cfac5ee` was installed into the existing `backend`
  Compose API on `127.0.0.1:8080` at 20:37:38 KST. The API container is
  `ab409e0e9325`; its source and artifact labels match the committed source and
  verified JAR above. The `dev` profile, AWS `buddystudy/dev` configuration and
  all original environment values, mounts, network and resource limits were
  preserved. Existing database, Redis, translation and backup container IDs
  and start times remained unchanged. Log: `build/voiceMcpSummaryDevRefresh.log`.
- Active call count was zero during the guarded preflight, before artifact
  staging and immediately before the API restart. The previous `9b1d30f0` JAR
  remains available for rollback. The refresh used only transient offline
  artifact-copy helpers and recreated the existing API service; it did not
  build a Docker image, add a service stack or change tunnels/routing.
- Manual local and existing public dev health checks both returned HTTP 200.
  Startup completed without bean-creation or schema ABI errors. These are
  manual dev observations, not GitHub Actions runtime health gates or evidence
  of a human voice call.
- The existing scheduler recovered both previously identified legacy failed
  results after restart: two ended calls now have `COMPLETED` result status,
  a recorded model and nonempty summary content. Their historical failed call
  state was not rewritten. The exact legacy-recoverable count is now zero;
  active call count remained zero, and no summary generation/recovery failure
  was observed in the new runtime log. Verification read only aggregate status,
  model-presence and content-presence metadata, not transcript/summary text or
  user/session IDs. No manual database repair, tool-created test study or quota
  adjustment was performed.
- The already verified normal iPhone app is installed with the same dev base
  URL. A new human call exercising saved-topic lookup/creation and audible
  continuation remains a separate user-device end-to-end check.

## 2026-08-31 — Level-based deep dives and selectable tutor voices

### Implemented boundaries

- Spoken lessons use saved child topics and each node's configured 1–10 level,
  with one question, an actual learner answer, brief supported 0–100 feedback,
  and learner/tutor follow-up exploration. These are private voice exchanges,
  not standard question generation, answer submission, grading records or quota.
- V102 stores owner-scoped, first-seen topic/parent/name/level snapshots, bounded
  to 64 per call and 32 additions per operation. The selected accepted topic and
  first ten direct children are prepared before provider negotiation. Session
  row locking protects the bound; terminal/expired sessions cannot write. A
  missing snapshot is explicitly unavailable for a new lesson/assessment, without
  misreporting an already successful child creation or replaying that mutation.
- Context preparation failure/cancellation now releases the unconnected session
  through bounded, non-cancellable terminal settlement; storage failure retains
  existing stale-session recovery. No media interruption/replay policy changed.
- V101 adds nullable result JSON. The v2 extraction contract retains topic/depth,
  tutor and learner questions, answers, optional spoken scores, supported feedback
  and source turn IDs. Owner/session, role/order and score provenance checks reject
  unsupported associations without discarding valid summary content. Legacy null
  results remain readable. No existing summary is automatically rewritten.
- iOS adds collapsed topic/exchange/source sections with bounded lazy expansion
  and a Settings voice selector. The saved choice is captured before the first
  session-start await, survives backend settings refresh and applies next call.
  Default still delegates to the server. The supported choices and next-session
  constraint follow the [official Realtime voice contract](https://developers.openai.com/api/docs/guides/realtime-conversations#voice-options).

### Backend verification

- The first focused run found one numeric-evidence bug: a sentence-ending period
  after `Your score is 85.` was mistaken for a decimal boundary. The parser now
  distinguishes a period from period-plus-digit, with English fractions and
  Korean/Japanese score, other-scale, percentage and quantity regressions.
- Final focused regression: 38 suites, 502 tests, **500 passed, zero failed,
  two opt-in provider tests skipped**. Application: 99 passed; infrastructure:
  385 passed/two skipped; tutor HTTP MCP/native hints: 16 passed. Log:
  `build/voiceExplorationBackendRegressionFinal.log`.
- This includes 12 isolated H2 snapshot tests with UTC `timestamp(6)` bindings,
  immutable rename/level/reparent/delete history, ownership, cascade, terminal
  writes, batch bounds and two transactional writers competing for slot 64.
  Three H2 result-read tests use the actual persistence adapter/JSON codec and
  nested response mapping. H2 is not presented as MySQL migration execution or
  proof of MySQL lock behavior.
- The task graph was checked before running `tutor:test`; the five test-AOT
  tasks were excluded. No Testcontainer or extra database/Redis stack was started.
  Normal main AOT and `bootJar` passed separately with the existing build-only
  `aot` profile. New persistence bean and nested response hints are present.
  Log: `build/voiceExplorationMainAotJar.log`; JAR: 330,760,903 bytes; SHA-256:
  `7e0c85c1759efcbdbf9c464a59fa777df0a677d79174d1cd1c85beb2ed1f61b8`.

### One real model extraction check

- Explicit opt-in `VoiceTutorSummaryLiveTest` made exactly one production-adapter
  request to the unchanged `gpt-5.4` summary model, using eight frozen synthetic
  Korean turns and the existing regular development user-content credential.
  No real transcript, account record, database, microphone, call reservation,
  question quota or recording was used; no credential entered a file or log.
- Passed in 8,617 ms: one topic, three exchanges, the saved child identity and
  level 4, exact question/answer/feedback source IDs, the explicitly spoken 85
  score with feedback, an ungraded learner follow-up, and an unanswered question
  with no invented answer or score. The readiness greeting was excluded.
  Log: `build/voiceExplorationLiveSummary.log`; the JUnit result records one test,
  zero skipped/failures and metadata-only `voice_summary_live` counters.
- This verifies one real structured-output extraction, not general pedagogical
  accuracy, a live Realtime question/assessment conversation, or physical audio.

### iOS and iPhone verification

- Generic iOS Debug build passed using `StudyMateiOS`, `generic/platform=iOS`,
  `CODE_SIGNING_ALLOWED=NO` and the existing `build/iOSDeviceDerivedData` path.
  Log: `build/voiceExplorationGenericBuild.log`. No macOS target was built/tested.
- Signed build-for-testing and **170 explicitly selected iPhone tests passed**:
  76 call contracts, 25 Silero contracts, 31 summary-state tests, four MCP tests,
  18 voice-preference tests and 16 exploration contract/presentation tests.
  Preference tests use isolated settings suites and intercepted URLs, not actual
  provider/media/session endpoints. No destructive recording or account test ran.
  Logs: `build/voiceExplorationDeviceTestBuild.log`,
  `build/voiceExplorationDeviceTests.log`; result bundle:
  `Test-StudyMateiOS-2026.08.31_21-58-58-+0900.xcresult`.
- A normal signed build and strict signature verification passed afterward, with
  no injected XCTest plug-in/framework. The normal app was installed and launched
  on the paired iPhone 16 Pro using the unchanged `https://lowfidev.cloud` dev URL.
  Active dev call count was zero before tests, install and launch. Logs:
  `build/voiceExplorationSignedBuildFinal.log`,
  `build/voiceExplorationDeviceInstall.log`, `build/voiceExplorationDeviceLaunch.log`.
- A human-device lesson exercising the new question/assessment loop and every
  selectable voice remains a separate end-to-end check; these results do not
  claim one was performed or that previous word-boundary playback work changed.

### Existing dev runtime and MySQL migration verification

- Implementation commit `341c90d1` and its verified JAR were installed into the
  existing `backend-backend-1` API (`8c8c7589bae9`) on `127.0.0.1:8080`. The
  `dev` profile, AWS `buddystudy/dev` configuration, original environment,
  network, mounts and resource limits were preserved. The previous `4cfac5ee`
  JAR is retained in the existing artifact volume for rollback. Log:
  `build/voiceExplorationDevRefresh.log`.
- The initial artifact refresh omitted the external SQL files: the preserved
  `FLYWAY_LOCATIONS=filesystem:/app/db/migration-mysql` correctly continued to
  read that directory, not the new files embedded in the JAR. Read-only metadata
  confirmed that V100 was still the last migration. This was a dev artifact
  synchronization omission, not a disabled Flyway or AWS configuration failure.
- Only the missing V101/V102 SQL artifacts were copied from the same verified
  source, with hashes checked and existing applied files left untouched. After
  a guarded restart of the same API container at 22:31:49 KST, Flyway applied
  both migrations normally at 22:31:56. No manual schema-history edit, repair,
  user record/quota adjustment or production deployment occurred.
- MySQL metadata now confirms successful versions 101 and 102, all six snapshot
  columns, and the nullable result exploration column. Startup completed with
  zero observed ERROR log entries; manual local and existing public dev health
  endpoints returned HTTP 200. These observations verify the actual MySQL DDL
  and running dev schema, not a human Realtime lesson or production runtime.
- Active call count was zero before artifact copying and restart. Database,
  Redis, translation and backup container IDs/start times remain identical to
  the pre-refresh values. No Docker image build or additional service stack was
  used. The offline artifact-copy helper was transient and automatically removed.
- The one-off copy helper initially reported a post-restart comparison failure
  because Docker returned the two unchanged mounts in a different array order.
  A separate read-only verification normalized mount ordering and confirmed
  expected mounts, source/JAR/SQL hashes, infrastructure, schema and HTTP status;
  no second restart was needed. Logs: `build/voiceExplorationDevMigrationSync.log`
  and the final passing `build/voiceExplorationDevRuntimeVerification.log`.
- The local-runtime note in `backend/README.md` now documents the external SQL
  synchronization requirement so a later JAR-only refresh does not repeat it.

<a id="study-tree-records-pacing-and-connected-breaks"></a>

## 2026-09-01 — Study-tree records, pacing and connected breaks

### Implemented boundaries

- Lesson focus follows actual saved node/parent IDs and frozen 1–10 levels,
  with explicit incomplete ancestry/child-page metadata. Deepening means a
  learner-chosen part of that tree, not automatically harder follow-up questions.
  Feedback ends with space for the next meaningful learner turn; silence is not
  consent to continue, change levels, create a topic or end the call.
- V103 adds private, node-attached voice learning records, uniquely keyed by
  session/question turn. The projector rechecks owned frozen tree membership,
  exact source role/order and explicitly spoken score evidence, then copies
  question, answer and feedback from original transcript turns. Ambiguous,
  unsupported, other-root, deleted-node or oversized associations remain in
  session history instead of being filed under a guessed node or truncated.
- Completion, bounded projection, localization requests and existing outboxes
  share the session-first transaction. The existing recovery scheduler also
  projects previously completed structured results, at most ten per batch,
  without another summary/grade model call. No ordinary/public question, quota,
  pending answer, draft or topic-stat score is fabricated from a voice exchange.
- Translation extends the existing configured content-translation stream with
  `VOICE_STUDY_RECORD`; no stream topology or service stack is added. ko/en/ja
  localizations use exact field sets, source hashes and request-token CAS;
  original text, source turns, node IDs, levels and scores are immutable.
  Private content is excluded from HTTP/client bodies and provider history,
  including structured-coroutine propagation and retry exception causes.
- My Studies and tree nodes use one shared, compact history section for ordinary
  and voice records. API keyset pages are owner/node/scope-bound; iOS keeps one
  visible 30-item page with an eight-page typed memory cache in the existing
  SettingsStore record path. Account, backend, locale and request fences prevent
  stale private data from being displayed. Detail exposes original/localized
  text and at most three delayed translation refreshes, not infinite polling.
- The MCP catalog now has 21 tools; the voice subset has nine. The two new
  history tools default to original text and exact-node scope, retain the old
  ordinary-question contracts, and use the same typed history use case. Voice
  reads require verified shared-root ancestry and reauthorization after reads.
  The tutor starts with a small recent-history page for the agreed node; earlier
  answers are reference evidence, never a current answer, consent or a regrade.
- Optional WebRTC `pause-v1` adds a compact connected break. One ordered control
  FIFO fences capture-stop and quiet input; the current tutor response completes
  before a server input clear and matching ACK establish pause. Resume waits for
  a fresh clear ACK before restoring the user's original mute setting. Work,
  clears and transition deadlines remain bounded; stale/duplicate ACKs cannot
  unmute or replay input. No tutor response cancellation, output clear,
  truncation or legacy device-playout gate is introduced. Connected time,
  heartbeat, hard session/monthly limits and foreground termination still apply;
  the UI explicitly discloses that a break continues to use call time.

### iOS and physical iPhone verification

- The required generic iOS Debug build passed using `StudyMateiOS`,
  `generic/platform=iOS`, `CODE_SIGNING_ALLOWED=NO` and the existing
  `build/iOSDeviceDerivedData` directory. Log:
  `build/voice-tree-records-iOS-generic-build.log`. No macOS target was built.
- Signed build-for-testing and **208 explicitly selected, non-destructive
  iPhone tests passed**, zero failures/skips: 26 node-history, 12 pause,
  76 call contracts, 16 explorations, four MCP, 25 Silero/pipeline, 31 summary
  and 18 voice preferences. Scope/source decoding, empty continuation pages,
  cache bounds, account/locale cancellation, stale detail reads, source scores,
  pause ACK ordering and compact presentation are covered. No real call,
  microphone, provider request, recording purge or account purge was invoked.
  Logs: `build/voiceTreeRecordsDeviceTestBuild.log` and
  `build/voiceTreeRecordsDeviceTests.log`.
- A normal signed iOS build passed afterward, without injected XCTest plug-ins
  or frameworks, followed by strict deep signature verification. Log:
  `build/voiceTreeRecordsSignedBuildFinal.log`. Installation/runtime observations
  are recorded separately from build success.
- A new human-device lesson covering audible pause/resume, tree focus and
  automatic use of previous answers has not been claimed. Contract/fixture tests
  do not measure real Realtime pedagogical behavior or end-to-end latency.

### Backend verification and artifact

- The final selected regression contains 58 suites and 669 tests: **667 passed,
  zero failures/errors and two explicit opt-in provider tests skipped**.
  Application: 168 passed; infrastructure: 483 passed/two skipped; tutor HTTP
  MCP/native hints: 16 passed. Only the selected suite names were counted;
  unrelated historical XML files in the output directory were excluded.
  Logs: `build/voiceTreeRecordsBackendVerifiedFinal.log` and the final discovery
  audit run `build/voiceTreeRecordsBackendExecutedAudit.log`.
- Coverage includes exact node/owner/scope cursor reads, deletion during page
  hydration, immutable transcript/score evidence, cross-tree rejection,
  concurrent duplicate projection, transactional translation outboxes, stale
  localization-token/hash rejection, failed translation original fallback,
  privacy across coroutine children and retry causes, bounded recovery,
  MCP history/active-identity boundaries, and pause/resume ordering/timeouts.
  H2 fixtures preserve CHECK/FK/unique constraints and independent transactional
  writers; they are not proof of actual MySQL DDL or locking behavior.
- Earlier runs exposed H2 recursive-CTE column declaration compatibility and
  test-fixture schema-connection lifetime issues; explicit CTE column lists and
  a held schema connection resolved them without weakening constraints. A
  structured-coroutine privacy fixture and Jackson numeric-node wire comparison
  were corrected. A final annotation/discovery audit found two inferred
  non-Unit test methods; explicit Unit signatures restored both tests. All 106
  declared tests in the 11 new backend test classes are now present and passing.
- The five test-AOT tasks were explicitly excluded; no Testcontainers or new
  database/Redis instance was started. Normal main AOT and `bootJar` passed
  separately using the existing build-only `aot` profile. Generated bean
  definitions include the new controller, read/projection services and adapters;
  all three nested history response DTOs have runtime reflection hints.
  Log: `build/voiceTreeRecordsMainAotJar.log`.
- Verified JAR: 330,970,838 bytes, SHA-256
  `6ba38da9c58fc3845f75d45a2d273fdf7e4267d0e50d3619f4e50e668e119b2c`.
  V103 is embedded in that JAR and its source SQL hash is
  `132ab4935187010cf704cb76c545f3b076bce06c93f7adbe6eec297eaf098c22`.
  The dev refresh must synchronize that same SQL into the existing external
  Flyway directory along with the JAR; no Flyway configuration change is needed.

### Existing dev runtime and device rollout

- Implementation `c1fbe8c7` and the verified JAR/V103 SQL were installed into
  the existing `backend-backend-1` API (`b97d78a70753`) on `127.0.0.1:8080`,
  starting at 00:25:38 KST on September 1. The `dev` profile, AWS
  `buddystudy/dev` configuration, every original environment value, mounts,
  network and resource limits were preserved. Original database, Redis,
  translation and backup container IDs/start times were unchanged. The previous
  `341c90d1` JAR remains in the same artifact volume for rollback.
- Active call count was zero during preflight, after staging and before the API
  restart. Only transient, offline artifact-copy helpers and the existing API
  were used; no new persistent service stack, Docker image build, database
  repair, quota adjustment, production connection or deployment was performed.
  Log: `build/voiceTreeRecordsDevRefresh.log`.
- Flyway applied V103 through the unchanged external filesystem location;
  MySQL reports all 23 record columns, 11 localization columns and the projection
  marker. V101/V102 artifacts and applied versions are intact. Startup and
  projection checks report zero ERROR/projection-failure entries. Manual local
  and existing public dev health checks both returned HTTP 200; these are not
  GitHub Actions health gates or proof of a live voice lesson.
- The actual adapter SQL was exercised read-only against MySQL for node/subtree
  scope, each with and without a keyset cursor, using an impossible owner and
  receiving zero rows. This verifies runtime SQL syntax without reading any
  user's question or answer; owner/ordering/content behavior is covered by the
  isolated fixtures, not asserted from empty results.
- Automatic recovery projected one previously completed structured call into
  three private learning records under one saved node; no eligible structured
  result remains unprojected. Six localizations entered the existing stream.
  The first observation had two READY and four PENDING, later five READY and
  one PENDING. Provider fallback/automatic retry handled the remaining Japanese
  request; the final aggregate is **six READY, zero PENDING and zero FAILED**.
  No manual event replay, content readout or source rewrite was used.
  Logs: `build/voiceTreeRecordsDevRuntimeVerification.log`,
  `build/voiceTreeRecordsDevRuntimeFinal.log` and
  `build/voiceTreeRecordsDevRuntimeComplete.log` record aggregate metadata only.
- The verified normal signed app was installed and launched on the paired
  iPhone 16 Pro with the unchanged `https://lowfidev.cloud` dev base URL. Both
  steps verified zero active calls and no injected test plug-ins/frameworks.
  Logs: `build/voiceTreeRecordsDeviceInstall.log` and
  `build/voiceTreeRecordsDeviceLaunch.log`. A human audible lesson and manual
  inspection of its node feed remain a separate end-to-end acceptance check.

<a id="voice-study-mutations-and-long-learner-turns"></a>

## 2026-09-01 — Voice study edits, confirmed deletion and long learner turns

### Implemented boundaries

- The old continuous-speech deadline could start a tutor response while
  `userSpeaking` was still true after 12 seconds. It now schedules only bounded
  transcription checkpoints, including repeated checkpoints for longer answers.
  There is one normal response-creation gate, and it waits for the learner's
  stop edge and meaningful-input publication, including after MCP completion.
  This supersedes the earlier continuous-speech intervention behavior described
  above. No response cancellation, output clearing, truncation, provider VAD
  switch or replacement media transport was introduced.
- The existing MCP catalog gains `update_study` (22 tools total); its voice
  subset has 11 tools. Explicit name/level edits use an owner-scoped partial
  update on the exact saved node, preserving identity, parent, schedule,
  activation, preferences, question quota and prior answers. Voice mutations
  must remain inside the call's verified owned tree; creating descendants stays
  inside the selected subtree. Ambiguous targets require clarification.
- Voice deletion first previews the named subtree and exact descendant count.
  A short-lived, call/account/device-bound token plus a new meaningful learner
  confirmation is required. The accepted utterance carries its original speech
  start and the preceding completed tutor response, so late ASR publication,
  intervening noise and an earlier mixed audio/tool response do not create
  consent. The LLM must still interpret the new speech as explicit agreement.
  Tokens are single use; a guarded delete compares the complete set of up to
  128 owned nodes inside the mutation transaction. Scope changes fail without
  deletion and require a new preview. No real user topic was deleted in tests.
- V104 separates the immutable accepted lesson node from its nullable live
  study FK and adds bounded, append-only lesson revisions. An explicit edit
  applies to the next new question, not a completed or pending question's level.
  Provider response/input bindings carry the server revision through delayed
  transcript publication; unknown bindings preserve text with revision `-1`,
  rather than guessing an old or current level or disconnecting the call.
  Summary evidence and existing node-record projection resolve at the question
  revision. Deleted/unresolved node evidence stays in private session history;
  the existing translation stream and original source records are retained.
- iOS applies verified exact-node metadata without replacing an active answer
  draft. Confirmed deletion tombstones reject late metadata, full-tree refresh
  and activation callbacks; account/environment changes reset these fences.
  Only derived history pages are invalidated. Deleting the current focus is not
  a hang-up and does not claim the lesson is complete; another question requires
  a learner-chosen surviving saved topic.

### iOS and paired iPhone verification

- Generic iOS Debug build and signed build-for-testing passed using only
  `StudyMateiOS` and the reused `build/iOSDeviceDerivedData` directory. Logs:
  `build/voiceStudyMutationsGenericBuild-retry2.log` and
  `build/voiceStudyMutationsDeviceTestBuild-retry2.log`.
- **219 explicitly selected iPhone tests passed**, zero failures/skips:
  26 node-history, 76 call contracts, 16 explorations, 15 MCP/metadata,
  12 pause, 25 Silero/pipeline, 31 summary and 18 voice preferences. New checks
  cover strict mutation-event decoding, late fetch/deletion fences, central
  tree tombstones and preservation of unrelated settings and active selection.
  Log: `build/voiceStudyMutationsDeviceTests-retry2.log`.
- The normal signed build passed afterward without injected test plug-ins or
  test frameworks, followed by strict deep signature verification. Log:
  `build/voiceStudyMutationsSignedBuildFinal.log`. These checks did not open a
  real call, use the microphone, invoke a provider, purge recordings, alter
  account data or mutate a real study. A human audible lesson remains a separate
  acceptance check, not an outcome implied by fixture tests or build success.

### Backend regression and artifact

- The final selected regression contains **798 tests in 66 suites: 796 passed,
  zero failures/errors, two explicit opt-in provider tests skipped**. Domain:
  six passed; application: 198 passed; infrastructure: 576 passed/two skipped;
  tutor MCP/native-hint contracts: 16 passed. Log:
  `build/voiceStudyMutationsBackend-tests-retry5.log`.
- A source-to-JUnit discovery audit verified all 416 ordinary `@Test` methods
  in the 25 changed Kotlin test classes are present in the results. Seven
  pre-existing inferred non-Unit methods had not been discovered; explicit Unit
  signatures restored them and all seven passed. Counts use selected XML suite
  identities, including Gradle's abbreviated macOS result filenames, and exclude
  unrelated historical XML. Report: `build/voiceStudyMutationsBackendAudit.json`.
- Regression coverage includes repeated long-speech checkpoints without any
  tutor response until the natural stop, outstanding MCP/persistence gates,
  immutable response/input revision binding, delayed ASR, exact stored input
  receipts, confirmation timing/identity/expiry/replay, owned partial updates,
  changed deletion manifests, concurrent owner locks, history/translation
  preservation and iOS deletion races. A false storage receipt (duplicate or
  capacity limit) consumes the handled input once and lets conversation continue,
  but clears mutation-confirmation authority rather than inventing saved consent.
- Early runs caught a cross-module nullable smart cast, two nullable fixture
  transaction returns and ambiguous JsonNode collection addition; these were
  corrected without relaxing assertions. The existing wrong-live-study test also
  caught the need to retain the live/accepted-node invariant: a live ID can be the
  immutable accepted ID or null after deletion, never a different node.
- Tests used isolated H2/fake providers, not Testcontainers, a real account or
  another database/Redis stack. H2 concurrency/constraint checks are not claims
  about MySQL execution; actual runtime verification is separate. Five test-AOT
  tasks remained explicitly excluded.
- Main `processAot` and `bootJar` passed with the existing build-only `aot`
  profile. Generated bean definitions include the confirmation adapter and its
  injection into the MCP bridge. Log: `build/voiceStudyMutationsBackend-jar.log`.
  The verified JAR is 331,060,009 bytes, SHA-256
  `12f29502e6b93b0155843be4f22340f9c344b686a6e351a29700fa27b62396c3`.
  V104 is embedded in the JAR and has the same hash as source SQL:
  `4e93aa9d5f6e88d7a819f1d337ea14844f5a7c31f0525422170255731aa7687d`.
  The existing dev runtime uses an external Flyway directory, so refresh must
  stage that SQL alongside the verified JAR without changing the profile or
  creating any new persistent infrastructure.

### Existing dev API and iPhone rollout

- Implementation `e2cf47d0` was committed on `feature/2.0` and installed into the
  existing `backend-backend-1` API (`ab6688fa64cb`) on `127.0.0.1:8080`, starting
  at 02:04:29 KST on September 1. The `dev` profile, AWS `buddystudy/dev` source,
  every original environment value, network, mounts and resource limits were
  preserved. The original database, Redis, LibreTranslate and backup containers
  retained their IDs and start times. No production server or GitHub deployment
  was used. Logs: `build/voiceStudyMutationsDevPreflight.log` and
  `build/voiceStudyMutationsDevRefresh.log`.
- Active calls were zero before staging, after staging and before restart.
  Only the existing API was recreated, without an image build or dependency
  restart. Offline artifact-copy helpers were transient and automatically
  removed. The previous `c1fbe8c7` JAR remains in the same artifact volume for
  rollback; earlier backups and source data were not deleted.
- V104 applied through the unchanged external Flyway directory. MySQL confirms
  the immutable accepted-node and transcript-revision columns plus the seven
  revision-table columns. Source, embedded SQL, mounted SQL and JAR hashes match;
  V101–V103 remain applied. Startup reported zero observed ERROR entries or
  projection failures, and manual local/public dev health checks returned 200.
- Ten read-only MySQL probes passed: the four existing node/subtree cursor
  variants and six source-extracted revision/history/confirmation SELECTs.
  An impossible owner/session returned zero rows (or current revision zero).
  These prove SQL syntax, not live mutation, populated-row semantics or audible
  voice behavior. Existing aggregate data remained three private node records
  and six READY translations, zero PENDING/FAILED translations and zero eligible
  unprojected results; no content was printed or manually replayed. Log:
  `build/voiceStudyMutationsDevRuntimeVerification.log`.
- The normal signed app was installed and launched on the paired iPhone 16 Pro
  with the existing `https://lowfidev.cloud` dev base URL, after checking zero
  active calls and no injected XCTest artifacts. Logs:
  `build/voiceStudyMutationsDeviceInstall.log` and
  `build/voiceStudyMutationsDeviceLaunch.log`. Human confirmation of a long spoken
  answer, an audible response afterward and intentional spoken topic changes
  remains the separate end-to-end acceptance step.

<a id="common-question-and-voice-records"></a>

## 2026-09-01 — One record identity for question and voice learning

### Implemented boundaries

- `questions.id` is the canonical record ID for `QUESTION` and `VOICE_TUTOR`.
  The Records tab, search, study-node history, public detail, comments, likes,
  reporting, visibility and deletion use that same identity. Voice extensions
  retain only typed learning metadata and private evidence; V105 moves the eight
  duplicated core fields into the canonical record instead of adding another
  independently writable store. Existing voice evidence/translation IDs remain
  stable and map to the new canonical ID, even when old numeric IDs collide.
- Voice records use the explicit `COMPLETED` lifecycle and a safe `voiceRecord`
  payload, not a fabricated `GradingResult`. Missing scores stay missing. Both
  tutor-question and learner-question exchanges can appear in owner history;
  publication requires a nonblank question and answer, a public record and the
  account's existing sharing permission. A complete session transcript, recording,
  private tree identifiers and transcript-turn evidence never enter public DTOs.
- Old private records and calls created before V105, including delayed summaries,
  stay private. New calls capture the existing default-public record policy,
  still subject to the live account-level gate. No real record was published or
  commented on by the verification fixtures.
- Node/subtree cursor identities remain compatible with the existing contract;
  each node item adds the shared `record` representation. The compatibility
  owner-only voice endpoint remains available. Deletion, owner withdrawal and
  stale translation tokens cannot revive a removed canonical record. The
  existing voice translation stream is reused and updates the common search
  projection transactionally; no ordinary grading/translation event is invented.
- All pending, generation, grading, quota, question scheduling, embedding,
  personalization and ordinary question-statistics paths explicitly distinguish
  record type. Common API bodies and public/comment bodies are suppressed in
  server and client traffic logs; request metadata remains available.

### Exact MySQL migration verification

- The complete, unchanged V105 SQL passed **17 assertions on MySQL 8.4.10**,
  using a uniquely named disposable schema in the existing database container.
  Only schema definitions and synthetic rows were copied; no real user rows or
  credentials entered test output, and no new container or infrastructure stack
  was created. The disposable schema was removed after verification.
- Checks cover numeric-ID collisions, original text/score/language preservation,
  deleted and foreign node associations, nullable assessment, removal of duplicate
  core columns, three-language search, reused READY translations, old/new call
  publicity defaults, canonical comment/like FKs, soft deletion, session cascade
  cleanup and zero question-quota/grading/push side effects.
- Report: `build/voiceCommonRecordsMySqlMigration.json`. Verified migration hash:
  `ff188021bd5cfe843640f0d4c21fd0122989b266992fdc4c29640a5c18b09b97`.
  H2 fixtures independently exercise the source INSERT-SELECT and transactional
  behavior, but do not substitute for MySQL DDL validation. The H2 R2DBC driver
  splits semicolons inside quoted column comments, so only the metadata COMMENT
  is omitted in its publicity-default fixture; actual MySQL used the whole file.
- V105 is a single-version cutover because it removes the old duplicate columns.
  The dev refresh must stop old writers and retain a protected full pre-migration
  database backup before starting the new binary. Restoring only the old JAR
  after V105 is explicitly forbidden; failure after cutover needs forward recovery
  or a coordinated database restoration, never an automatic binary-only rollback.

### Backend regression

- Final selected regression: **1,074 tests in 125 suites — 1,072 passed,
  zero failures/errors, two explicit opt-in provider tests skipped**. Domain:
  20 passed; application: 336 passed; infrastructure: 700 passed/two skipped;
  tutor MCP/native-hint contracts: 16 passed. Log:
  `build/voiceCommonRecordsBackend-tests-retry7.log`.
- The source-to-JUnit audit verified all **173 ordinary `@Test` methods in
  19 changed classes** were discovered. Counts use XML suite identities rather
  than Gradle's abbreviated macOS filenames, and include parameter-resolver
  method signatures such as `CapturedOutput`. Existing non-Unit logging tests
  were made explicit Unit tests rather than being silently omitted. Report:
  `build/voiceCommonRecordsBackendAudit-retry2.json`.
- Coverage includes mixed owner/public/liked pages, unscored exchanges, canonical
  comment/like identity, privacy gates, owner and node isolation, deletion/replay,
  original and translated payloads, translation CAS/search rollback and retry,
  ordinary-question mutation/analytics exclusion and private-body log suppression.
  Existing call/summary/MCP regression remains in the selection; no live provider,
  microphone, user content mutation or extra database/Redis container was used.
- Review found that localized common-record reads may enqueue a missing voice
  translation. Those entrypoints now allow a read-write transaction, covered by
  Spring transaction-attribute tests, rather than issuing `FOR UPDATE` from a
  MySQL read-only transaction. An actual Spring application-context test also
  verifies that the projector receives the localization port; its all-default
  Kotlin constructor no longer permits an unintended no-argument fallback bean.
- Main `processAot` and `bootJar` passed with the existing build-only `aot`
  profile; five test-AOT tasks remain excluded. Generated wiring now explicitly
  selects `VoiceStudyLearningLocalizationPort` for the projector, and native
  reflection includes the new payload and record-type enums. Log:
  `build/voiceCommonRecordsBackend-jar-retry2.log`. Final JAR: 331,110,774 bytes,
  SHA-256 `61f7c5e2f65994ee98dd9a0077ae12c906599aa2dc1a7be45daf972d9a21ce4b`.
  Embedded V105 SQL matches the exact-MySQL-tested source hash above.

### iOS and paired iPhone verification

- Generic iOS Debug build and signed build-for-testing passed using only
  `StudyMateiOS` and the reused `build/iOSDeviceDerivedData` directory. Logs:
  `build/voiceCommonRecordsGenericBuild-retry2.log` and
  `build/voiceCommonRecordsDeviceTestBuild-retry2.log`. The new callback wrapper
  explicitly retains main-actor isolation rather than transferring an unconstrained
  generic mutation result to a nonisolated completion handler.
- **270 explicitly selected iPhone tests passed**, zero failures/skips:
  51 common-record, 26 node-history, 76 call contracts, 16 explorations,
  15 MCP/metadata, 12 pause, 25 Silero/pipeline, 31 summary and 18 preferences.
  Log: `build/voiceCommonRecordsDeviceTests-retry2.log`. The common-record
  fixtures use isolated defaults and a fail-closed GET-only synthetic transport;
  actual public search and error-envelope contracts are checked without live
  provider/ad calls or user-record mutations.
- The final normal signed iOS build passed after testing, and deep, strict
  code-signature verification passed. The installable app contains no injected
  XCTest plug-ins or test frameworks. Log:
  `build/voiceCommonRecordsSignedBuildFinal.log`.
- Checks cover both record types in a paginated list, nullable and actual zero
  scores, read-only unanswered exchanges, legacy node fallback, canonical detail
  and comment IDs, publication eligibility, translations, body-log suppression,
  stale account/environment/locale callbacks and preserving active question drafts.
  Colliding numeric IDs across different types cannot overwrite, select or delete
  the wrong local record. On account/API-origin changes, old numeric question
  drafts detach into existing local draft storage; locale-only changes do not
  rekey them or consume quota. Public/liked caches are invalidated consistently.
- No macOS target, simulator, real voice call, microphone, recording purge,
  account purge or real publication/comment mutation was used for these checks.
  Human inspection of a newly completed lesson and an intentional public comment
  remains an end-to-end acceptance check, not something these fixtures claim.

### Existing dev API cutover and normal iPhone app

- Implementation commit: `e3ad31dc558cf09171debca4f18f083993482517` on
  `feature/2.0`. Only the existing `backend-backend-1` API was recreated with the
  verified JAR; it remains on port 8080, the `dev` profile, AWS secret
  `buddystudy/dev`, the existing network, mounts and unchanged environment.
  No persistent Docker stack or image build was added. DB, Redis, LibreTranslate
  and backup container IDs and start times are unchanged. Log:
  `build/voiceCommonRecordsDevRefresh.log`.
- Active calls were zero before staging and cutover. After stopping old API
  writers and before swapping V105/JAR, a full database dump was saved to
  `build/voice-common-records-db-backup-qcKoZl/pre-v105.sql` (313,516,000 bytes,
  SHA-256 `176811844ec1286e9e74828fc2c0871f321cffd53b16f62779e9b3a305fb8980`).
  Its directory is mode 0700 and file is 0600; the backup is retained locally,
  ignored by Git, and its contents were not printed. The previous JAR is retained
  in the artifact volume, but cannot be restored alone after this schema cutover.
- V105 applied successfully. Read-only aggregate checks confirm five existing
  voice extensions have exactly five owned canonical records, all still private,
  zero orphan mappings, zero fabricated question lifecycles, 15 common search
  rows and ten READY translations (zero PENDING/FAILED). The eight duplicate
  core columns are gone; original content is in the common record, not discarded.
  There were zero eligible unprojected results. Four node/subtree cursor and six
  revision/history/confirmation SQL probes passed with impossible owners/sessions;
  they do not claim live publication or comment mutation coverage.
- Runtime source/embedded/mounted migration and JAR hashes match. Startup
  observed zero ERROR entries/projection failures, and manual local/public dev
  health requests returned 200. Log:
  `build/voiceCommonRecordsDevRuntimeVerification.log`.
- The normal signed app was installed and launched on the paired iPhone 16 Pro
  with the existing `https://lowfidev.cloud` dev base URL. Both steps checked zero
  active calls and zero injected XCTest artifacts. Logs:
  `build/voiceCommonRecordsDeviceInstall.log` and
  `build/voiceCommonRecordsDeviceLaunch.log`. Actual user content was neither
  published nor commented on as part of verification.

## 2026-09-01 — Preserve the accepted input transcription language

### Confirmed gap and bounded fix

- The iOS app already sends the selected `ko`/`en`/`ja` language when creating a
  session and displays completed input transcripts without translation. The
  provider request contract dropped that stored language: both WebRTC and PCM
  setup configured only `gpt-4o-mini-transcribe`. A Korean tutor instruction is
  not a substitute for an input transcription language.
- `VoiceTutorRealtimeRequest.language` is now mandatory, with no default. Both
  application services forward the connected session snapshot's language. A
  shared configuration helper passes it to WebRTC creation, sideband setup and
  PCM setup, retaining the existing transcription model. Sideband readiness
  verifies the effective language alongside the existing turn/tool checks, and
  rejects a later removal/change instead of silently returning to auto detection.
- OpenAI Docs was used to verify that transcription runs separately from the
  realtime model's native audio input, that input transcription accepts a language,
  and that `session.updated` returns the full effective configuration. References:
  [Realtime session configuration](https://developers.openai.com/api/reference/resources/realtime/subresources/client_secrets)
  and [Realtime client events](https://developers.openai.com/api/reference/resources/realtime/client-events#session.update).
- This change does not translate, rewrite or regex-filter transcripts; change the
  model, noise reduction, Silero, meaningful-input policy or turn timing; or infer
  what the user actually said from the screenshot. A displayed ASR error does
  not prove the realtime model heard that same text or that it treated the text
  as lesson-start consent. Existing records and transcripts are not modified.
- Configuration diagnostics retain only the allowed language identifiers,
  `none`/`other`, booleans and the existing pseudonymous call reference. Provider
  payloads, instructions, audio, raw call IDs and credentials remain excluded.

### Verification

- Selected backend regression: **1,089 tests in 126 suites — 1,087 passed,
  zero failures/errors, two opt-in provider tests skipped**. Domain: 20;
  application: 338; infrastructure: 713 passed/two skipped; tutor: 16. Log:
  `build/voiceInputLanguageBackend-tests.log`. The source-to-JUnit audit verified
  all 141 ordinary `@Test` methods in seven changed classes were discovered;
  dynamic cases are included in the suite totals. Report:
  `build/voiceInputLanguageBackendAudit.json`.
- New regression covers Korean/English/Japanese session-to-provider forwarding,
  independence from the writing language of tutor instructions, all three
  provider setup boundaries, effective language acknowledgement, eight missing
  or mismatched language configurations, later removal and private-value log
  suppression. Existing audio turn, pause, MCP, summary and common-record tests
  remain selected. No real user content or live provider call was used.
- Main `processAot` and `bootJar` passed; five test-AOT tasks remain excluded.
  Log: `build/voiceInputLanguageBackend-jar.log`. JAR: 331,113,250 bytes,
  SHA-256 `f0ca48132bd3850c07c250016c3055f1cfe5c5adf1ef0acb40bbcc8b6e6078bb`.
  No SQL migration is added or changed; V105 retains SHA-256
  `ff188021bd5cfe843640f0d4c21fd0122989b266992fdc4c29640a5c18b09b97`.
- Two explicitly selected contracts passed on the paired iPhone, zero failures:
  `VoiceTutorVoiceSettingsTests/testSavedVoiceTravelsThroughAppStateUseCaseRepositoryAndPOSTBody`
  and `VoiceTutorContractTests/testRealtimeParserHandlesProviderAndBuddyStudyEvents`.
  These use isolated settings and synthetic transport/parser input; they do not
  create a real voice session, use the microphone, consume provider audio or
  delete recordings. Logs: `build/voiceInputLanguageDeviceTestBuild.log` and
  `build/voiceInputLanguageDeviceTests.log`. No iOS production source changed.
- The normal signed app was rebuilt after the device contracts; deep, strict
  code-signature verification passed, with no injected XCTest frameworks or
  plug-ins remaining. Log: `build/voiceInputLanguageSignedBuildFinal.log`.
- These are configuration and contract checks, **not a recognition-accuracy
  measurement**. Actual provider acknowledgement and short Korean speech in a
  new call remain live acceptance checks; prior misrecognized words cannot be
  reconstructed from the screenshot alone.

### Existing dev rollout

- Implementation commit: `ea795302a1fd11e3312bd4bc895edbb0550273b2` on
  `feature/2.0`. With zero active calls before staging and restart, only the
  existing 8080 `backend-backend-1` API was refreshed. Its `dev` profile, AWS
  secret selection, environment, network and mounts are unchanged; DB, Redis,
  LibreTranslate and backup container IDs/start times are unchanged. No new
  persistent infrastructure was created. Log: `build/voiceInputLanguageDevRefresh.log`.
- Every mounted migration hash is unchanged. Startup confirmed schema V105 is
  already current and applied zero migrations. The exact previous V105-compatible
  JAR is retained at `/app/buddystudy-backend.previous-e3ad31dc.jar`; this does not
  authorize restoring an older pre-V105 binary. The prior protected database
  backup remains retained; no user transcript or record was manually rewritten.
- Source/embedded/mounted V105 and JAR hashes match. Startup observed zero ERROR
  entries or projection failures; local and public dev health requests returned
  200. Read-only aggregate checks still find five canonical voice records,
  five owned extensions, ten READY translations and no orphan mappings. Log:
  `build/voiceInputLanguageDevRuntimeVerification.log`.
- At verification time there were zero live transcription acknowledgements and
  zero rejected acknowledgements. Health and JSON contract tests therefore do
  **not** claim that a new provider call or spoken recognition has been exercised.
- The normal signed app, without injected test artifacts, was reinstalled and
  launched on the paired iPhone using `https://lowfidev.cloud`; both steps checked
  zero active calls. Logs: `build/voiceInputLanguageDeviceInstall.log` and
  `build/voiceInputLanguageDeviceLaunch.log`. The server-side language fix applies
  when the next call is configured; existing transcript text remains unchanged.

## 2026-09-01 — Start voice lessons by traversing the saved study tree

### Guided discovery and exact focus authority

- A new call remains topicless and opens with one compact “what topic should we
  discuss?” question. The learner's first topic-bearing utterance is discovery
  only: it cannot immediately select a study or start a quiz.
- Every owner-checked page slice is validated against its exact query or parent,
  limit, offset and stable total. An exact offset-zero page with more than one
  result proves a real branch and can offer only its actual contiguous returned
  prefix, bounded to 16. Only an exact offset-zero zero/one result proves a leaf
  or single-child edge; later, gapped, truncated and unscoped pages cannot.
  Proven one-child edges are followed to the saved leaf or first real branch,
  without selecting intermediate broad nodes.
- One final tutor audio transcript must semantically attest which returned
  candidates were actually proposed. Only the next final meaningful learner item
  can bind one of those spoken IDs. Candidate metadata, MCP arguments/results,
  the original discovery utterance, checkpoints, filler and stale persistence
  acknowledgements cannot mint or revive focus authority.
- Initial focus and guided descent carry per-candidate single-child/leaf evidence.
  The write transaction locks the exact device authorization and active provider
  call, then rechecks revision, focus, metadata, path and every proved child range.
  Concurrent logout, rename, reparent, deletion, sibling/endpoint-child insertion,
  a deeper jump or a replayed learner turn cannot interleave into an accepted move.
- A guided child move additionally carries the exact persisted tutor-question,
  semantic learner-answer, fully spoken tutor-feedback, spoken navigation-offer
  and learner-continuation provider item IDs. Feedback and the offer may share one
  response or be two consecutive responses, but their exact rows/order and output
  stop boundary must precede the continuation. A greeting, unrelated tutor row,
  unanswered new question or answer-turn tool call cannot stand in for the exchange.

### Verification

- All selected application voice tests passed: **132 tests, zero failures or
  errors**. All selected infrastructure voice tests passed: **616 tests, zero
  failures/errors, two opt-in live-provider tests skipped**. Two additional
  MySQL 8.4 integration tests passed with zero failures/errors.
- Focused regressions cover complete root-to-single-child-to-leaf traversal,
  genuine branches, unspoken/partial/unscoped candidates, stale publication
  callbacks, exact question/answer/feedback boundary propagation, exchange-order
  rejection, rename/reparent/auth-revocation races, one-shot authorization and
  the maximum verified parent path. The MySQL tests exercise concurrent sibling
  insertion at a proved single-child edge and child insertion at a proved leaf,
  confirming both writes serialize after the focus transaction.
- Event-order regressions cover `output_audio_buffer.stopped`, immediate learner
  start/stop, then `response.done`: the final tutor item, spoken candidate offer
  and exact question/answer/feedback identities are backfilled before the input
  commit. Long-speech regressions retain only meaningful, publish-acknowledged
  checkpoint ASR as bounded same-sequence assessment context; original persisted
  items stay unchanged and deleted/non-communicative noise is excluded.
- A mixed native input buffer that contains speech from both before and after the
  tutor's output-stop boundary now settles every reserved speech slot in one
  commit while discarding all topic-selection, question/answer, feedback and
  navigation-offer authority. A stale spacing timer cannot flush that buffer while
  a newer learner segment is still active, so it cannot promote old consent or
  leave the response gate waiting on a lost slot.
- Normal Spring AOT processing and `:tutor:bootJar` passed. The verified JAR is
  331,370,615 bytes (`316 MiB` display), SHA-256
  `acd84a86eaa8301b7b14ee91efbf7c34f4244071559a55fc62c6ee02edf22db8`.
  No database migration was added or changed.
- The unsigned generic `StudyMateiOS` Debug device build passed. A normal signed
  build against the paired iPhone 16 Pro reached device selection but could not
  proceed because the phone was locked; no install, launch or live microphone/
  provider call is claimed by this verification.

## 2026-09-01 — Unified voice call and topicless saved-tree entry

### User flow

- A new iOS voice session never preselects the locally selected study, even when
  both the local and server study trees are already populated. It starts as one
  collapsed voice orb and opens with the localized question “어떤 주제로
  이야기해 볼까요?”. Retry resets transcript and summary disclosure to the
  same compact state.
- The first topic-bearing learner turn is discovery only. The tutor reads the
  actual owned study tree, follows each proved one-child edge without repeatedly
  asking the learner to choose intermediate nodes, and stops at the saved leaf or
  first real branch. A branch may offer only actual returned children; the exact
  saved endpoint is bound only after a fresh learner confirmation.
- The expanded transcript remains pinned while the learner follows the newest
  turn, but an intentional scroll toward older content suspends auto-follow until
  the latest edge is reached again. Voice previews support tap-to-play,
  tap-to-stop and immediate replacement with explicit loading state. Spoken
  lesson termination preserves a bounded measured local playback drain before
  WebRTC teardown; the red end button remains immediate.

### Verification

- The combined simulator run executed **185 selected tests**: **183 passed**, zero
  failed, and **two** intentional hardware-only opt-in tests skipped. It covers
  the unified orb, pause/disclosure gestures, transcript following, voice preview races, local
  playback-tail fencing and topicless discovery. The discovery suite was rerun
  after adding a populated local/server tree fixture: **28 tests, zero failures**.
  Result bundle: `build/VoiceTutorFinalAudit.xcresult`; rendered snapshots:
  `build/VoiceTutorUnifiedUIFinal2Attachments`.
- Focused backend voice suites passed with zero failures: input assessment
  (**15**), service (**55**), session creation (**4**), MCP voice tools (**70**),
  meaningful-input relay (**39**) and input-turn coordination (**40**). Five
  focused MySQL 8.4 result tests also passed: three recovery cases, one mixed-old/
  new-claim fence and one V107-to-V108 migration invariant. They cover bounded
  assessment admission, summary retry/cancellation, topicless creation, saved-tree
  focus authority and stale-worker result fencing.
- The required unsigned generic iOS device build and a normal signed iPhone build
  passed. The signed app passed deep/strict signature verification, contained no
  XCTest plug-ins, and was installed and launched on the paired iPhone. This is
  not a claim that an automated test placed a live microphone/provider call.
- `:tutor:bootJar` passed. The verified JAR is 331,385,739 bytes, SHA-256
  `87173a6720c1bffd1359327f352940402a535c602d4503193010a1a098899235`.
  V108 is one atomic, start-first-compatible expand ALTER: legacy `PROCESSING`
  rows may remain NULL, while a non-NULL 36-character claim token is permitted
  only on `PROCESSING`. A token-aware claim also carries the exact stored
  microsecond timestamp so a mixed-version reclaim that changes only `updated_at`
  still invalidates the older worker. The V108 source SHA-256 is
  `9d47ec74c98c4c579b05c6ec6d128d2d8017743d10be85e45611d50a07ca5e8f`.

### Existing dev API refresh

- Before refresh there were zero READY/ACTIVE/ENDING voice sessions and zero
  `PROCESSING` voice results. Only the JAR and V108 migration in the existing
  `backend-backend-1` application volume were replaced and that same 8080 API
  container restarted. Its `dev` profile, AWS-secret resolution,
  environment, mounts and network are unchanged; the existing MySQL, Redis,
  LibreTranslate and backup containers were neither recreated nor replaced.
- Flyway applied V108 successfully and the deployed JAR/migration hashes match the
  verified sources. The immediately previous JAR remains as a hard link for
  recovery, and every transient staging container was removed. During local V108
  reconciliation, the first restart correctly rejected a strict-version backup
  SQL whose filename still matched Flyway's migration pattern; no migration ran
  in that attempt. The backup was moved outside the migration location and the
  final startup then completed with zero ERROR entries. Local
  `127.0.0.1:8080` and public dev dependency-health requests both returned HTTP
  200.
- The new opening and tree traversal apply to a **new** voice session. Existing
  sessions retain the immutable instructions captured when they were created.
