# Voice response startup and call UI

## Incident evidence

The local Docker development backend completed the WebRTC sideband handshake at
01:52:15 UTC on 2026-09-10, then ended the call with
`VoiceTutorProviderResponseTimeoutException` at 01:53:15 UTC. The iPhone showed
two learner captions and no tutor caption before disconnection.

The native controller sent a Boolean quota-notice marker inside provider
`response.create.metadata`. The [OpenAI Realtime client-event contract](https://developers.openai.com/api/reference/resources/realtime/client-events)
requires metadata values to be strings. An error rejecting an unstarted response
also left the pending request latched until the response watchdog expired.
The incident logs establish the timeout sequence; they did not retain the
original provider rejection payload. A separate bounded live provider probe
reproduced `invalid_request_error` / `invalid_type` for
`response.metadata.buddystudy_quota_notice`, correlated to the exact rejected
request event. In the same provider session, string `"false"` was accepted and
audio plus transcript events arrived. The deliberately small 32-token output
limit produced `response.done.status=incomplete`; this establishes schema and
audio-generation behavior, not completion of a full spoken sentence. The probe
used no user audio, app session, study data, or app quota and retained no key or
audio/transcript payload.

## Scope

Fix the native response request and rejection handling, distinguish waiting for
tutor audio from listening on iOS, and redesign the call/transcript presentation.
Existing native audio transport, account ownership, recording consent, draft
preservation, and server-authoritative quota settlement remain in place.

Only the existing local Docker API and the connected iPhone are rollout targets.
There is no production deployment or macOS application work.

## Final call interaction

The requested final UI centers one tutor orb on a minimal surface. A short tap
pauses or continues through the existing server-acknowledged pause flow. An
upward drag on the orb continuously reveals the same mounted conversation;
a downward drag returns to the call. Transcript history keeps independent
scrolling and its position across disclosure.

An uninterrupted hold has three stages: preparation at 0.35 seconds, a clear
keep-holding-to-end instruction at 0.9 seconds, and actual call termination
once at 2.2 seconds. After preparation begins, release, movement, or
cancellation before the threshold cancels termination and cannot fall through
to a pause/resume tap. A shorter stationary tap retains ordinary pause/resume
behavior. Movement of 12 points or more permanently cancels the hold for that
touch, even if the finger moves back. Screen exit, inactive scene state, and a
new call attempt also invalidate an outstanding hold. The first two
stages change presentation only; completed termination reuses the existing
call-end, summary, and quota-settlement operation. The completed touch hold
needs no further dialog. The VoiceOver End action instead presents an explicit
confirmation dialog. Reduce Motion, Dynamic Type, and actionable error
recovery remain part of the design.

The orb revision and accessibility-title correction are implemented. Direct
coordinate taps and upward/downward drags passed on the synthetic screen.
Hold thresholds and cancellation were verified with state tests; this run did
not inject a sustained physical long press or measure its haptic feel.

## Native completion contract

Provider response metadata uses strings. The native controller correlates a
rejected `response.create` with its exact request event ID, releases an
unaccepted request immediately, and permits one retry. An opening retry keeps
the original question and disables tools. Stale or unrelated errors cannot
abandon an accepted response. Exhausted ordinary retries emit a compact input
retry hint; an already requested spoken hangup still completes when its
farewell cannot be generated.

A successful empty response without audio or pending tools emits the
server-owned `buddystudy.voice.input.settled` event with only `type` and
`sequence`. Sequence `0` settles the initial opening expectation only. A
positive Int64 identifies the client acoustic sequence frozen into that
response, not the server transcript sequence or the latest learner input at
completion. Merged rapid speech tails retain their latest acoustic sequence.
The iPhone clears only the matching pending input; a late settlement cannot
clear a newer utterance or active speech. Tool-only responses continue waiting
for their acknowledged continuation, and failed, quota, goodbye, or draining
responses do not emit this event. The native controller drops provider-forged
settlements, client controls cannot send them, and the control policy rejects
invalid sequences and removes extra fields.

The native response announcement separately attaches the server-owned
`buddystudyQuotaExhaustionNotice` Boolean at the event root. The final WebRTC
sanitizer preserves this typed value while discarding raw provider metadata;
the provider's string flag does not become the app's quota authority. Legacy
metadata handling remains compatible.

## Verification

- Backend native controller: 32 tests passed. Native relay: 15 tests passed.
  Event policy: 25 tests passed. Total: 72 passed, no failures or skips.
  These cover typed metadata, the exact
  rejected opening request, bounded retries, stale/accepted request isolation,
  quota signaling, completing requested hangup when the farewell fails, silent
  opening and exact learner settlement, overlapping/merged speech, tool
  continuations, event forgery, and final control-event sanitization.
- `:tutor:bootJar` passed with Java 25 and the offline Gradle cache. The initial
  default Kotlin compiler heap exhausted memory before tests; the passing run
  used a 2 GiB Gradle heap, a 3 GiB Kotlin heap, and two workers.
- The final orb UI, accessibility-title correction, and release-location
  swipe handling passed the unsigned
  generic iOS build with `StudyMateiOS` and `generic/platform=iOS`
  (`build/voice-reliability-ui/ios-release-swipe-generic.log`).
- The final real-iPhone run executed 180 tests: 168 passed, 12 skipped, and no
  failures (`build/voice-reliability-ui/ios-release-swipe-device.xcresult`). Skips
  were ten repository-source inspections unavailable on the device, one native
  audio opt-in test, and one computer-use interactive opt-in test.
- The final orb simulator suite executed 180 tests: 179 passed, one native
  opt-in test skipped, and no failures
  (`build/voice-reliability-ui/ios-orb-final-simulator.xcresult`). This includes
  the computer-use interactive fixture, all twelve render fixtures, and the
  release-location regression.
- Seven added pure orb-interaction tests cover the hold stages and one-shot end,
  short-tap versus hold release, sticky movement cancellation, cancellation
  and loss of end eligibility, and swipe/hold ownership. They establish state
  behavior, not delivery of physical drag or long-press events by automation.
- Twelve iOS render fixtures under
  `build/voice-reliability-ui/screenshots-orb/` were reviewed. The corrected
  accessibility layout was additionally reviewed in
  `build/voice-reliability-ui/screenshots-orb/09-accessibility-verified.png`.
- Two coordinate taps on the interactive orb fixture exercised pause and
  resume. An upward coordinate drag revealed the conversation and a downward
  drag on the same, smaller circle returned to the call. The fixture observed
  exactly two pause operations, both disclosure boundaries, and zero hangups.
  An earlier run also displayed and cancelled the accessibility End dialog.
- The first drag attempts failed because the automation supplied the start and
  release locations without an intermediate moved callback. Temporary tracing
  confirmed a valid `onEnded` translation of `(0, -297)` with no preceding move.
  The final recognizer also settles from a valid release location, while
  preserving continuous interpolation when move events arrive. Both directions
  then passed. Earlier incomplete interactive runs are superseded by the final
  passing suite; temporary tracing was removed.
- The final app was installed by the passing physical-device test run and
  launched on the connected iPhone at 11:54:25 KST on 2026-09-10.
  Installation, launch, and synthetic provider audio do not establish a
  completed microphone-to-tutor conversation on that iPhone.

## Verification fixture corrections

The interactive screen is hosted in a `UIHostingController` outside the app's
SwiftUI scene. Its fixture now explicitly injects `scenePhase = .active`, which
the real call receives from `WindowGroup`; this lets the fixture reach the
same existing active-scene gesture gate without changing production lifecycle
behavior.

The stale-authentication-bootstrap fixture now enters the shared
`prepareVoiceTutorRegistration` path through voice preview. It still suspends
token bootstrap, replaces the account, and verifies that the old result cannot
replace the new registration or proceed to an authenticated voice request.
This isolates the intended identity boundary from the process-wide recording
startup lifecycle. It does not clean up or alter real user recording files.

## Local rollout

Before restart, the existing MySQL store had zero READY/ACTIVE/ENDING voice
sessions. Only the API artifact in its existing read-only-mounted application
volume was replaced, using a network-disabled temporary copy helper. Only
`backend-backend-1` was restarted. Database, Redis, translation, backup, AWS dev
configuration, and existing migrations were retained.

- New JAR SHA-256:
  `ccdac1ffa392b7a9f677dc753451edab89861b17e19d352d6e7ad02af5bc54f4`.
- Previous JAR retained at
  `/app/buddystudy-backend.pre-voice-fix-20260910.jar`, SHA-256
  `f8dc5d8c0221dcfeebe389021c6fcdd34b69716a968c030562f0ac8f67e67854`.
- The final API started at 02:18:55 UTC on 2026-09-10. Both local
  `http://127.0.0.1:8080/api/v1/health/dependencies` and the existing iPhone route
  `https://lowfidev.cloud/api/v1/health/dependencies` returned `ok=true`,
  `environment=dev`, with successful database and Redis checks.
