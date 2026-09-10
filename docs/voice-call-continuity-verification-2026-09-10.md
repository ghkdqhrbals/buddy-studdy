# Voice call continuation and operation status

## Report and observed evidence

The development call reported on September 10 completed answer submission and
asynchronous grading successfully. A follow-up tool was superseded by new
speech and the tutor described that stale follow-up as a failure. A later query
read the completed grade. This was not a lost answer or a failed grading job.

Two study mutation preparations also succeeded, but the second was followed by
41 seconds without a tutor confirmation or mutation-confirmation call before
the iOS client ended the session. The server recorded a normal client-requested
end. Provider response/ACK frames were not retained, so the precise historical
provider/gating cause of the missing continuation cannot be established from
those logs alone.

## Implementation

- A same-revision `get_grading_process` read survives fresh learner speech;
  authentication, current study, active-answer and terminal guards remain.
  Superseded follow-ups explicitly preserve prior answer acceptance/grading.
- Successful mutation preparation returns a typed, server-owned confirmation
  question. After the exact tool-output acknowledgement, the next response is
  instructed to read that question once, with tools disabled. It cannot claim
  that the prepared mutation has already been applied. An empty confirmation
  receives one bounded response retry, without replaying the tool. New speech
  still supersedes an obsolete proposal/readback.
- The authenticated `buddystudy.voice.operation` event carries `sequence`,
  `operationId`, `name`, `phase` and `elapsedMs`. Real model tool work and server
  question/grading reads emit start and completion/failure. The client uses a
  bounded per-attempt state, an uptime timer and explicit terminal cleanup.
  Compact call and transcript both show small secondary-colored text. Completed
  operations expire after five seconds; the animation clock pauses when no work
  is active and while the app is backgrounded. No arguments, result bodies,
  transcripts or arbitrary error messages enter this status contract.
- An exact empty-audio commit rejection now settles its latest acoustic sequence, so a false speech start that superseded a tool continuation cannot leave iOS indefinitely waiting for a reply. It does not restore old proposals, confirmations or tool side effects.
- App backgrounding and locking preserve the user-started voice session,
  media/control pumps and quota heartbeat. The app declares `UIBackgroundModes`
  audio and keeps its existing active play-and-record/voice-chat session.
  Foreground return refreshes the countdown without starting a new call.
  Explicit dismissal/end, actual audio interruption and identity invalidation
  retain terminal teardown and draft preservation.
- Consented recording finalization waits for protected data and retries only access errors correlated with a lock transition. Export, hashing and manifest storage retain source audio until success; missing/corrupt data and owner invalidation keep the existing cleanup path.

The background audio configuration follows Apple's
[Audio Guidelines by App Type](https://developer.apple.com/library/archive/documentation/Audio/Conceptual/AudioSessionProgrammingGuide/AudioGuidelinesByAppType/AudioGuidelinesByAppType.html).
This change does not introduce automatic background call creation or change
recording consent.

## Verification

- Backend focused suites: 303 tests passed across native controller, native
  relay, realtime event policy, MCP tool adapter and canonical question flow.
- Current-source Java 25 `:tutor:bootJar`: passed. SHA-256:
  `118539739027dde18dbba047b2ca7b9f00ad9adc482c1e8438faccd0bc3bd48e`.
- iOS generic device build: passed with the `StudyMateiOS` scheme, pinned local
  package cache and signing disabled. Log:
  `build/voice-continuity-ios-build-final.log`.

Automated provider fixtures use synthetic events; they do not prove the exact
historical provider response or live speech-recognition accuracy. Device and
runtime observations are recorded below after verification.

### iOS observations

- Simulator: 45 focused tests passed, followed by eight recording/background
  tests after the relock fix, for 49 distinct passing tests. Logs:
  `build/voice-continuity-simulator-tests.log` and
  `build/voice-continuity-recording-tests.log`.
- Both operation-status layouts were rendered and visually inspected in dark
  mode. The small gray function/status/time row is readable in the compact
  call and transcript header. Attachments are exported under
  `build/voice-continuity-screenshots`.
- Final generic iOS build and signed physical-device build-for-testing passed.
  The signed app includes background audio and the local Firebase configuration
  without changing the tracked Firebase placeholder.
- The app was installed on the paired iPhone 16 Pro at 23:29 KST. The device
  requires its passcode, so a live call/app-switch/lock-and-return verification
  and physical-device test execution are pending device unlock. Installation
  alone is not reported as a passing background audio integration test.

### Later physical-device verification attempt

The phone became available later that evening. The answer-editor follow-up
passed five native editor tests on the paired iPhone. A 23:56 KST call started
through iPhone Mirroring, then showed disconnection after another-app return.
The backend recorded provider-relay completion at 23:57:43 with a fresh
heartbeat two seconds earlier, not a client end or heartbeat expiry. This is
not a valid background microphone integration result because Apple does not
support microphone access through iPhone Mirroring. The app was left ready for
a direct-device call and Mirroring was closed; physical call/return verification
remains pending. See the [answer editor verification](voice-answer-editor-verification-2026-09-10.md)
for the observations and Apple documentation.

### Development runtime

The final JAR above was installed into the existing local development Docker
volume and only `backend-backend-1` was restarted at 23:34:38 KST. Two checks
found zero active calls and pending session results. The mounted JAR hash
matches the tested artifact. Its 104 external migration files are unchanged
through V117, and the existing environment, port routing, networks and other
containers were preserved. Startup took 7.35 seconds with no logged errors;
local health, database/Redis dependencies and `https://lowfidev.cloud/health`
all returned HTTP 200. Both prior JARs remain backed up in the existing volume.
No production server or GitHub deployment was changed.
