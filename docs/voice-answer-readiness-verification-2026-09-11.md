# Question availability before answer controls

## Confirmed implementation gap

After the saved-question readback correction, the learner pointed out that
Finish answer had appeared before the question itself. The latest persisted
development conversation was still the previously investigated session; there
was no fresh conversation after the preceding rollout to attribute to the new
build. Code inspection nevertheless found a separate reproducible ordering gap.

When provider audio-stop arrived before `response.done`, processing the latter
could publish a listening answer state before the relay forwarded
`response.done`. iOS finalized the tutor caption on `response.done`, but opened
an answer draft directly from the earlier listening state. That state carried
identities and answer text, not the saved question. The asynchronous record
refresh did not gate the live answer controls.

## Corrected boundary

- Validated question completion enters the same ordered client queue before
  readiness and answer state, for either provider event order.
- A new server-only `buddystudy.voice.answer.ready` event is emitted once per
  capture, immediately before its first legacy listening state. It carries the
  exact canonical question, answer/record/study/revision identities, listening
  phase and empty answer text. The question is nonblank and at most 8,000 UTF-16
  code units. The initial payload stays below the 64 KiB frame budget even with
  JSON escaping; subsequent answer drafts use the separate existing state.
- The server sanitizer preserves only these fields. Client/provider attempts
  to forge the readiness event cannot start an exercise. Empty or oversized
  saved-question results settle as question failures rather than indefinite
  ready states.
- iOS requires canonical question content to initialize a new draft. A bare
  legacy listening state cannot create an answer or a Finish action. A later
  valid ready receipt can still initialize after an out-of-order legacy state.
  Established drafts accept normal legacy updates while retaining their
  immutable question, edited text and stored draft.
- The question is displayed before the answer actions in both conversation
  layouts. Initial listening expands the transcript and follows the start of
  the question while the learner has not begun speaking or editing, rather
  than scrolling past it to the bottom action. Manual history scrolling and
  the learner's choice to scroll to the bottom retain their existing behavior. Superseded lesson
  identities, loading/generation states and cancelled readiness replays cannot
  present a stale Finish action or replace an edited draft.

The legacy `answer.state` shape is unchanged: older apps ignore the new event
and keep receiving their existing state. Deploy the backend first, then the new
iOS build. A new app connected to a backend that does not emit readiness cannot
initialize canonical capture; it does not guess a question from unrelated tutor
speech or a prior record.

## Verification

- Backend targeted regressions passed: 226 native controller, 34 native relay
  and 40 public event-policy tests (300 total, zero failures or skips). Both
  provider event orders, one-time readiness, missing/invalid question recovery,
  legacy payload compatibility and bounded public serialization are covered.
- Backend boot JAR, final generic iOS Debug build and signed iPhone
  build-for-testing passed.
- Final unique iOS simulator selection: 314 passed, three opt-in skips, zero
  outstanding failures (317 total). The full run's one failed pause test was
  an obsolete assumption that the expanded screen had only one Finish action;
  it confused the card action with the header circle. The corrected test keeps
  the single pause-button, ordering and disabled-action checks, and its two-test
  suite passed on rerun.
- The readiness UI test passed with normal and accessibility-sized text in
  compact and expanded layouts. It verifies no Finish before readiness, the
  saved question's visible frame, automatic expansion, and retained existing
  draft. State tests also cover out-of-order legacy listening, malformed and
  oversized questions, same-revision foreign identities, immutable questions,
  cancellation and delayed readiness.
- Physical iPhone rendering passed (two tests) on the final product code. The
  captured loading screen has no Finish action; the answer screen shows the
  saved question above it, including large text. Rendering uses synthetic
  state without opening a microphone or submitting a real answer. The final
  app was installed by the physical test run and launched normally afterward,
  with a fresh zero-active-session gate.
- The development backend was updated first with no active voice session or
  processing conversation result. Only the existing backend container was
  restarted; no image was built and no production host was accessed. The
  running artifact SHA-256 is
  `c3d4abc5f8b7ac8357137afe1e1e1f8554f070f685bb5943bc34ec4fee803cc6`.
  Manual local and public development health checks both returned `UP`.

Local evidence: `build/voice-answer-readiness-backend.log`,
`build/voice-answer-readiness-backend-results.json`,
`build/voice-answer-readiness-generic-final.log`,
`build/voice-answer-readiness-dev-runtime.json`,
`build/voice-answer-readiness-ios-results.json`,
`build/voice-answer-readiness-simulator-final.xcresult`,
`build/voice-answer-readiness-pause-verified.xcresult` and
`build/voice-answer-readiness-device-final.xcresult`.

Mirroring still reported that the iPhone was in use. No fresh end-to-end spoken
conversation or audible playback was verified; the physical screen tests above
are not a live microphone/provider test. No running user conversation was
stopped for installation, testing or the development server restart.
