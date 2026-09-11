# Study-first conversation and necessary choices

## Cause and changes

The session prompt opened with a general conversation-topic question and did
not explicitly establish curriculum study as the default purpose. Both the
session instructions and `request_user_input` description required a blocking
form for every preference or next-step question. The reported saved/new/free
mode menu was model-authored under that policy, not a hardcoded app menu.

The provider now starts with “오늘은 어떤 주제를 공부할까요?” (and matching
English/Japanese copy). Native and legacy response overrides use the shared
language policy, and the iOS placeholder/accessibility copy matches it.
The default flow is selected study topic, its actual curriculum, then saved
questions and feedback. A vague recommendation request reads saved topics
and recommends one concrete direction conversationally. Clear agreement starts
the established selection flow without another readiness check.

Blocking forms are reserved for substantive unresolved study/action choices,
multi-selection, or an explicit request for selectable/written input. Greetings,
microphone checks, one simple missing fact, advice, clear agreements and routine
steps do not create forms. Related necessary decisions are bundled. Existing
server-owned curriculum and topic-creation cards, exact mutation confirmation,
answer submission, cancellation and pause rules remain intact. Main/selected
identity, terminal-leaf handling, maximum depth four and inherited root level
continue through the existing curriculum coordinator.

This changes the instructions used by the realtime model; it does not introduce
a second intent model, text classifier or a blanket ban on user-input tools.

## Verification

- Backend: 418 tests passed, zero failures/skips across service policy (74),
  user-input contract (9), native controller (226), realtime bridge (78), MCP
  relay (24), and curriculum coordinator (7). The tests cover instruction/tool
  contracts, language-specific bootstrap events, preserved curriculum choices,
  and existing state-machine behavior; assertions on prompts alone do not prove
  live-model compliance.
- Backend boot JAR, generic iOS Debug build and signed iPhone test build passed.
- Physical iPhone: all 38 `VoiceTutorDiscoveryTests` passed, including the
  Korean/English/Japanese opening labels and existing topic/draft boundaries.
  These are XCTest checks, not a new microphone/audio conversation.
- The updated app was installed by the device test run and launched normally.
  The backend rollout and device test start waited until no live conversation
  or processing result remained. No active user conversation was interrupted.
- Only the existing local development backend container was restarted with the
  verified JAR; no Docker image was built and no production host was accessed.
  Running SHA-256:
  `ac3d0fd330eac8e6ffb4c5652cae80a419db7e42162ff20535e5b394f099d9e3`.
  Local and public development health checks returned HTTP 200 / UP.

Local evidence: `build/voice-study-first-backend.log`,
`build/voice-study-first-backend-results.json`,
`build/voice-study-first-ios-generic.log`,
`build/voice-study-first-device-build.log`,
`build/voice-study-first-device.xcresult`,
`build/voice-study-first-ios-results.json` and
`build/voice-study-first-dev-runtime.json`.

## Live-model verification limit

A small standalone synthetic Realtime probe was prepared for a greeting,
a vague study recommendation, and agreement to a known saved topic. It uses
no application session or real study mutation. Its preflight stopped before
any provider call because the known development environment did not supply a
usable API key. No further credential discovery was attempted. Consequently,
there is no live-model or new spoken-conversation result for this patch; the
418 backend and 38 device checks above are contract/regression evidence only.
The optional local probe is `build/voice-study-first-model-probe.py`.
