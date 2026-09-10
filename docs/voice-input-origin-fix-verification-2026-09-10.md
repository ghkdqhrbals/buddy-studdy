# Voice input origin correction — 2026-09-10

## Scope and evidence

The [origin investigation](voice-asr-origin-2026-09-10.md) reproduced invented
ASR text from synthetic non-speech, without the application, a microphone,
tutoring response or transcript prompt. In the fresh-session, real-time paced
comparison, `near_field` produced text in one of three non-speech cases; the
same PCM with noise reduction explicitly disabled produced no text in all
three. The exact reported ChatGPT greeting and the provider's internal cause
were not reproduced. The original recording is unavailable.

This correction disables additional provider input noise reduction for the
active native iOS WebRTC path, whose microphone input already uses voice
processing. Both initial call setup and the sideband update request explicit
JSON `null`; the handshake requires that effective provider value before the
call becomes ready and rejects later drift. Legacy voice protocols retain
their existing configuration.

The independent iOS correction deduplicates learner finals by provider item ID
within a call attempt, including after old captions leave the visible list.
It preserves repeated words from distinct utterances and manual answer edits.
Missing IDs remain compatible with the legacy transport; malformed supplied
IDs and native anonymous finals cannot bypass deduplication. This prevents
duplicate delivery, not the first well-formed ASR hallucination.

A private AI input-admission candidate was tested, then excluded from this
implementation because it added 1–2.3 seconds to ordinary replies and was not
a correction of the upstream origin. Its patch and verification are preserved
locally under `build/voice-input-admission/archived-candidate/`. The adopted
change adds no extra model response or transcript rewriting. Existing manual
answer capture, explicit Finish, editable review and explicit Submit remain
in place.

## Verification

Artifacts are under `build/voice-input-admission/`.

- Generic `StudyMateiOS` Debug build passed.
- Physical iPhone 16 Pro: 198 passed, 12 skipped, zero failures across voice
  contract, answer-draft, pause and session-state tests. Ten skipped source
  checks require the repository on the device; two external fixtures are
  opt-in. These checks verify application behavior, not microphone accuracy.
- A limited provider probe with noise reduction disabled and the existing
  hesitation/completion instructions stayed silent for “음” and a trailing
  “이유는”, and began a relevant reply to a complete question. The probe used
  synthetic audio and prior tutor text; it did not exercise the full application
  tool catalog or a real headset call. The positive reply was token-capped.
- Backend: 256 tests passed, zero failures/errors/skips. This includes initial
  WebRTC configuration, adapter behavior, 49 handshake cases, native controller
  and relay regressions, ASR setup and event policy. The new cases require
  explicit null, reject missing/enabled/malformed settings, and detect drift
  after readiness. Results: `build/voice-noise-reduction-fix/test-xml/`.
  The archived admission candidate's 346-test result is separate.

Disabling this provider option is supported by a bounded comparison, not proof
of perfect recognition on every route or noise condition. Actual headset
speech and false-activation behavior still require observation in real calls.
Manual answer review remains the learner's final text authority.

## Local runtime

The local Docker API was restarted at 20:28:31 KST with zero active voice
calls. Both `http://127.0.0.1:8080/health` and the development
`https://lowfidev.cloud/health` returned HTTP 200 with `{"ok":true}`.

- Installed JAR SHA-256:
  `3711b0e5fb01d5f7858e96614cd62df2bb90e881acfd5166c1a310e4225a9b84`.
- Previous artifact retained in
  `/app/buddystudy-backend.pre-voice-input-origin-20260910-ea7ec99dd710.jar`.
- Host `:tutor:bootJar` passed. Compiled bytecode confirms both native null
  configuration paths and the effective-null handshake check; the archived
  admission classes are absent. Five source/test hashes match the frozen build.
- No Docker image was built and no production infrastructure was changed.
- The iOS change was already installed by the successful physical test run.
  After the phone reconnected, the installed app launched successfully at
  20:29:49 KST with the development backend URL and profile destination.
  An actual headset conversation after this server correction has not been
  verified.

Runtime hash, health bodies and source manifest are retained under
`build/voice-noise-reduction-fix/`.
