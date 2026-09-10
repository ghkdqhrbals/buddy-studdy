# Persistent answer capture cue — 2026-09-10

After a trusted saved study question finishes playing, the answer orb is now
warm amber with a persistent outer ring and microphone symbol. Both the compact
call and expanded conversation show “답변 기록 중” and explain that thinking pauses
do not finish the answer. This state exists before the first recognized word;
it follows the held answer state rather than temporary acoustic speech activity.

The cue is static, including under Reduce Motion. The orb keeps the existing
Finish answer tap, conversation swipe and staged hold-to-end behavior. Review
returns to mint with a muted microphone symbol, and pause is gray with no
continued-listening notice. Larger accessibility text gets a 96-point transcript
control; the main call remains scrollable. All copy uses AppStrings in Korean,
English and Japanese. The same listening explanation is not duplicated in the
expanded editor card.

The existing manual-answer backend was audited without production changes.
An additional controller regression advances a fake clock through three minutes
before the first word, three answer parts separated by two-minute thinking
pauses, and one minute in review. It verifies that the roughly 7,800-character
answer remains exact and ordered, no tutor response or submission occurs during
those waits, and one explicit reviewed Submit contains all three source parts.
Transcription checkpoints still collect text while ordinary AI responses remain
blocked; this is not a claim that microphone media is buffered only on the phone.

The existing bounds remain: 8,000 UTF-16 code units, 32 source parts, the session
quota/hard deadline and a live foreground connection. Oversized answers enter
editable recovery; this work does not promise unlimited recording. The tests use
synthetic transcript/provider events, not a real microphone/OpenAI call.

## Verification

- Generic iOS Debug build, StudyMateiOS, generic/platform=iOS, signing disabled:
  passed (`build/voice-answer-capture-ui/ios-generic.log`).
- Simulator: 195 tests, 2 expected skips, 0 failures.
- Physical iPhone 16 Pro / iOS 26.6.1: 195 tests, 12 expected skips, 0 failures.
  The physical-only skips are repository source checks unavailable on device and
  opt-in external scenarios. Suites: 166 call contracts, 13 answer draft tests,
  16 pause tests. Result bundles are in `build/voice-answer-capture-ui/`.
- Ten manual-answer snapshots passed on both destinations, including empty
  capture, light theme, pause, accessibility capture, editor, review and retry.
  The simulator's empty/expanded/accessibility capture and the iPhone's light,
  paused and review states were visually inspected. Attachments are under
  `simulator-snapshots/` and `device-snapshots/` in the same build directory.
- Backend native controller: 72 tests, 0 failures/errors/skips; includes the new
  long-pause multipart regression (`backend-tests.log`).
- `git diff --check` passed. No backend deployment, image build, migration or
  server restart was needed; the existing manual-answer backend remains running.
- The updated signed iPhone app was launched at 2026-09-10 14:30:40 KST against
  the existing `https://lowfidev.cloud` dev route (`iphone-launch.log`).
