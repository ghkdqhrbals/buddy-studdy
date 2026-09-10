# Arrived questions in native voice lessons

## Behavior

Selecting an owned topic now reads its arrived unanswered QUESTION records
through the existing MCP path. The native controller reads the saved question
with its original requirements and difficulty, then waits for an answer. It
does not improvise a replacement quiz or expose the expected-answer hint.
The readback uses typed server metadata and a response-scoped instruction;
provider-authored JSON cannot acquire that authority. A provider retry retains
the same question, while new learner speech, changed focus and termination
invalidate an obsolete readback.

`skip_question` is available through the general authenticated MCP server and
the native voice catalog. It skips only an owned, unsubmitted question. A
repeated skip preserves its timestamp; a late answer cannot revive a skipped
record. The exact owner record-detail GET returns SKIPPED so iOS can reconcile
it, while pending/completed browse pages continue to omit skipped questions.

`request_question` first reuses an arrived question. After an explicit skip,
the tutor checks the remaining pending questions; when none remains and a new
question is wanted, it uses the normal generation Saga, question allowance and
idempotency contract. Server-owned request keys prevent model-authored retry
keys from spending allowance again for the same learner request. Process reads
wait within a bounded tool round and never fabricate a completed question.

The native `submit_answer` schema accepts only a record ID. The backend loads
the complete original persisted learner utterances after the preceding spoken
tutor item, verifies the active call, owner, exact topic and fixed lesson epoch,
and submits those utterances to the existing answer/grading path. The tutor
must read that record's canonical grading result instead of inventing a score.
The exact source rows are excluded from post-call VOICE_TUTOR extraction before
the write, preserving the raw history without creating a second learning record.
An uncertain accepted submission can recover its exact saved answer and grading
request rather than replaying a write.

The server-only `buddystudy.voice.question.changed` event contains only an exact
study ID and record ID. iOS fetches that record immediately and again at call
completion, with account, connection-attempt and latest-request checks. It
merges into the existing record store and study room without replacing the
selected question, answer text or stored draft. Canonical terminal records no
longer reappear as a synthetic pending question or editable unanswered record.

## Verification scope

Tests use synthetic transcripts, fake MCP handlers and isolated persistence.
No test skips, answers or generates a question in the user's real account, and
no microphone session or OpenAI conversation is started by these tests.

The earlier reported phantom input remains a separate diagnostic limitation:
the source transcript is provider ASR, not proof of what the person actually
said. This feature prevents model-written replacement answers and routes scores
through canonical grading; it does not establish whether the earlier input came
from speech-recognition hallucination or acoustic echo. Raw audio was not
available for that incident. Live semantic decisions, including recognizing an
answer versus a skip/clarification, still belong to the native conversational
model; no second live classifier or transcript word filter is added.

The existing minimal orb, tap-to-pause, swipe-to-reveal and staged hold-to-end
interaction and the previous turn-taking quiet windows are unchanged.

## iOS checks

- Generic unsigned `StudyMateiOS` build passed; log:
  `build/voice-pending-questions/ios-generic.log`.
- The selected simulator suites executed 217 tests, with two opt-in tests
  skipped and zero failures:
  `build/voice-pending-questions/ios-simulator.xcresult`.
- The signed build installed on the connected iPhone 16 Pro. The physical
  suites executed 217 tests: 204 passed, 12 skipped and one failed. Discovery
  and question reconciliation passed all 36 tests; pause passed all 16. The
  skips include repository-source checks unavailable on a physical device and
  opt-in probes. Bundle:
  `build/voice-pending-questions/ios-device.xcresult`.
- The sole physical failure is the existing twelve-fixture compact-call
  screenshot test. `drawHierarchy` produced five unique PNGs instead of twelve;
  an isolated retry produced eight, so this check remains failed. Exported
  retry images show a blank expanded-conversation capture and a rendered paused
  screen, while the simulator expanded-conversation image renders normally.
  The helper already documents separately composited SwiftUI layer omissions
  on iOS 26; that is consistent with these captures, but this run does not
  certify physical rendering for every fixture. Retry bundle:
  `build/voice-pending-questions/ios-device-render-retry.xcresult`.
- New iOS cases cover pending → skipped → new question → grading → graded,
  exact server identity validation, stale account/attempt/request exclusion,
  preservation of selected question and stored draft, terminal editor state,
  and resumed grading that does not delete a keyboard draft.

## Backend checks

- Application: 114 tests passed, zero failures/errors/skips (MCP service 21,
  record mutation 10, study service 19, voice service 64). Log:
  `build/voice-pending-questions/backend-application-final.log`.
- Infrastructure: 285 tests passed, zero failures/errors/skips (general MCP
  adapter 23, voice MCP adapter 100, canonical question coordinator 24, native
  controller 59, native relay 16, event policy 27, question repository 10,
  transcript persistence 26). Their reports are under
  `backend/infra/build/test-results/test`; the final artifact/check log is
  `build/voice-pending-questions/backend-final-build.log`.
- These cover saved-question readback and retry/supersession, exact-topic
  pagination, oversized-page fallback, repeated reads preserving the current
  answer/skip turn, original transcript submission, atomic source exclusion,
  skip/submission races, lost-result recovery, persisted grading after the
  pending list changes, stale owner/focus rejection, and forged control events.
  The transcript tests use the production adapter against isolated H2; they
  do not establish MySQL lock equivalence.
- `:tutor:bootJar` completed. No Docker image was built. Native-image/AOT test
  scanning is outside this JVM integration check: the initial broad
  `processTestAot` run was stopped and the targeted HTTP test runs with that
  task excluded, the test profile enabled and AWS Secrets Manager disabled.
- The targeted actual-MySQL HTTP integration test passed with zero skips:
  `StudyApiIntegrationTest.study endpoint returns my studies while records
  endpoint returns only completed records`. It verifies owner SKIPPED detail
  readback while skipped rows stay out of the completed/pending browse flow.
  Its old fixture assumed that settings synchronization creates a study; the
  fixture now explicitly creates the root through `POST /api/v1/studies`,
  matching the existing production boundary. Log:
  `build/voice-pending-questions/backend-api-final.log`.

## Local rollout

- Final backend JAR SHA-256:
  `6fb5a9fc6c0fa93cecdb71da5462860d7d20839f9ea4b02613401df905ce35ce`.
  The existing development Docker volume was updated without building an image.
  Its previous JAR remains at
  `/app/buddystudy-backend.pre-pending-questions-20260910.jar`, SHA-256
  `4ee80546f981bab26a3a9a9cc33041c5be5014293015d899f8a8d387dae8fa4f`.
- The database reported zero READY/ACTIVE/ENDING calls immediately before the
  restart. `backend-backend-1` restarted at 04:51:59 UTC. At 04:52:25 UTC both
  localhost and the existing iPhone development route
  `https://lowfidev.cloud/api/v1/health/dependencies` reported development
  `ok: true`, with database and Redis healthy. The running JAR hash matched
  the final built artifact. Other development containers were not redeployed.
  These were manual local checks, not GitHub runtime health gates or a
  production deployment.
- The signed updated app was launched successfully on the connected iPhone
  at 13:52:42 KST (04:52:42 UTC), using
  `io.github.ghkdqhrbals.StudyMate` and the existing development backend route.
  No live call was started on the learner's behalf.
