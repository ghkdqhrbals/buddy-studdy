# Saved-question delivery and conversation cleanup

## Observed failure

The reported conversation at 16:13–16:16 KST had two separate failures.

- The initial pending-question read hit the MySQL driver's request-queue limit.
  The metadata identified `RequestQueue.submit:113` in the installed driver;
  the native tool eventually reported failure after 15 seconds.
- The following question generation completed and saved its question in about
  23.5 seconds. The next tutor transcript nevertheless said the question was
  still pending. The controller treated any audio from a response scheduled as
  a question readback as delivery and opened answer capture for that notice.

Generation itself was complete. The app's answer card followed a typed server
answer-state event; it did not independently infer that the question was ready.
The fix therefore belongs at the server's saved-question delivery boundary.

A later grading attempt in the same conversation separately failed with a
`question_search` projection deadlock. That failure is not evidence that the
question generation failed.

## Server behavior

Saved-question readback uses an empty custom input context and instructions
containing the saved question, excluding the earlier pending-generation
conversation. The response stays in the normal conversation history (the
[Realtime custom-context contract](https://developers.openai.com/api/docs/guides/realtime-conversations#handling-responses-outside-the-default-conversation)
supports an empty `input` for exact readback). Actual
transcript content and audio must satisfy the readback gate before answer
capture opens. A status notice or different question is retried through the
existing bounded readback recovery, rather than opening an empty answer card.
The guard normalizes formatting, case, whitespace and bounded source-token
pronunciations (choice letters, numbers, acronyms and common technical names).
It does not use semantic similarity to accept paraphrases. An unsupported
phonetic spelling of a proper name can still take the retry/failure path; the
empty-context verbatim request is the primary correction, not a guarantee of
arbitrary pronunciation equivalence.

A speech edge pauses question delivery while the latest utterance is handled;
it does not itself cancel accepted learning. Explicit cancellation uses
`cancel_voice_learning`, and topic/revision changes, GUI cancellation and
termination keep their invalidation boundaries. Cancellation preserves saved
topics, questions and drafts and does not abort an accepted generation job.

Pending-question pagination consumes the page before requesting its count on
the transaction's shared MySQL connection. MCP reads, including their existing
single transient retry, have a total ten-second deadline so a blocked read
returns a failure before the native fifteen-second tool timeout. The initial
queue-full event is confirmed; its complete preceding driver history was not
available, so the paging change is recovery hardening, not proof of that event's
sole cause.

Canonical grading retries transient database failures around the separate
transactional writer. The full transition/completion/failure transaction is
retried at most twice after rollback; the provider grading request itself is
not repeated, and only retry exhaustion follows the existing failure path.

## Conversation UI

The iOS conversation no longer renders MCP operation rows, repeated speaker
labels, answer placeholders, or supplementary pause/answer instructions.
Completed selection cards keep the actual selected options or text and the
submitted/cancelled state without repeating the original prompt and topic
metadata. Existing correlation, draft storage and operation event state remain
intact. Essential phase, time, errors, actions and recording/draft indicators
remain visible; accessibility retains speaker and input guidance.

## iOS verification

- Generic iOS Debug build passed.
- Signed physical-iPhone build-for-testing passed; the app installed successfully
  after the live-session/result-processing gate returned zero.
- iOS simulator regression selection: 265 passed, three opt-in tests skipped,
  zero failures (268 total). This covers the voice contract, lesson state,
  structured inputs and answer drafts.
- Rendered light answer-capture and dark expanded-conversation images were
  inspected. The secondary helper copy and speaker labels are absent while
  actual answer content, pause and cancellation controls remain available.
- Physical iPhone screen-render tests passed (two tests, zero failures). These
  rendered the production question-loading/generating/reading, answer-capture,
  pause, review, submission and failure screens with synthetic state, without
  opening the microphone or submitting a real answer. The captured answer and
  review screens were inspected: the real question is followed by the draft
  card and its finish/submit action, without helper-copy clutter.
- No running user conversation was stopped. The incident session ended by the
  user before the installation. Mirroring was still unavailable because the
  iPhone was in use when real-device screen verification was first attempted.

Local ignored evidence: `build/voice-study-stall-backend-metadata.json`,
`build/voice-study-readback-generic.log`,
`build/voice-study-readback-device-build.log`, and
`build/voice-study-readback-simulator.xcresult`. Physical render evidence is in
`build/voice-study-readback-device-render.xcresult` and
`build/voice-study-readback-device-attachments/`.

## Backend verification and development rollout

- Backend regression selection: 1,416 passed, six opt-in tests skipped, zero
  failures. The selection covers the native voice controller/relay, MCP
  contracts, saved-question content validation, service orchestration and
  database recovery. Failed fixtures were corrected and their suites rerun;
  these counts represent the final unique test results.
- All three paging and six transactional grading retry tests were explicitly
  verified as discovered and executed. They cover sequential queries,
  cancellation, rollback and bounded retries without repeating AI grading or
  result publication.
- The backend boot JAR build passed. With zero active sessions or processing
  conversation results, the existing local development volume's JAR was
  backed up and replaced, then only `backend-backend-1` was restarted. No
  Docker image was built and no production host was accessed.
- The running JAR checksum matches the verified artifact:
  `1a5db61d1c583a31d003ba9fd5e0533278259f753e0237989a6e3efa2564b860`.
- Manual post-restart checks returned HTTP 200 and `UP` from
  `http://localhost:8080/actuator/health` and, using curl,
  `https://lowfidev.cloud/actuator/health`. An earlier Python HTTP client
  received 403 from the external route; the successful curl result is the
  external availability check. No automated deployment health gate was added.

Final aggregate evidence is in
`build/voice-study-readback-regression-final.json`, with database execution in
`build/voice-study-readback-db-final.log` and the local runtime replacement
record in `build/voice-study-readback-dev-runtime.json`.

A fresh end-to-end spoken conversation and audible playback were not verified
after this change. The physical-device verification above exercises production
screens with synthetic state; it must not be interpreted as a live microphone,
provider readback or speaker test.
