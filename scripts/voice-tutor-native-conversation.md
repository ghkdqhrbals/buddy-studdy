# Manual native iPhone conversation probe

`voice-tutor-native-conversation.py` supplies signaling and sanitized lifecycle
events for this explicitly selected iOS test:

`StudyMateiOSTests/VoiceTutorNativeConversationTests/testOptInNativeReceiveOnlyConversationContinuesAcrossThreeProviderTurns`

**This is a paid, opt-in integration probe, never an automatic CI check.** One
host invocation may create at most one standalone OpenAI Realtime call and three
teacher responses. Two fixed synthetic learner **text** messages are submitted
while the teacher is speaking. The physical iPhone is receive-only: no local
audio track, microphone permission/capture, recording, BuddyStudy session or
app-quota API is used. The provider credential stays on the host.

The test uses the production native renderer and response-state logic. It checks
three response boundaries and native RTP continuity; it does not test learner
ASR/VAD, intent classification, backend quota accounting or exact acoustic EOS.
R1→R2 has a one-second observation gap; R2→R3 advances immediately after matching
provider `response.done` + `output_audio_buffer.stopped` and accepted input.
Neither path waits for PCM silence or sends a fabricated client drain ACK.

## Prerequisites

- Coordinate with the device owner: no active real call, and permission for one
  paid synthetic call. Keep the physical iPhone unlocked and the test host app
  foreground-active. Do not substitute a simulator or a macOS scheme.
- Xcode, working iOS signing for `StudyMateiOS`, and a connected physical iPhone.
- Python 3.11+ and `aiohttp` 3.x, for example in an ignored local environment:

  ```sh
  python3 -m venv build/voice-tutor-native-venv
  build/voice-tutor-native-venv/bin/python -m pip install 'aiohttp>=3.10,<4'
  ```

- The Mac and iPhone must be reachable on the same trusted LAN. Allow local
  network access for the app/test host on iPhone and the relevant host process on
  macOS, and permit the selected test port through the Mac firewall. The phone
  cannot reach the Mac using `127.0.0.1`. VPN/client isolation may also block it.
- Supply `OPENAI_API_KEY` through the host's process environment using your
  approved credential source. Never put its value in a command, source file or
  shared log. This fixture does **not** discover Docker/AWS credentials, read
  phone preferences or depend on ignored helper scripts.
- Set `BUDDYSTUDY_NATIVE_CONVERSATION_BIND` to the Mac's explicit private LAN IPv4
  address and `VOICE_TUTOR_TEST_IPHONE_ID` to the selected iPhone UDID. Use a
  test-only port (8097 below); existing backend port 8080 is forbidden. No
  wildcard/public bind, Docker container or routing change is needed.

## Manual run from the repository root

First build the selected iOS test for the same device and DerivedData path.
Do not use `CODE_SIGNING_ALLOWED=NO` for this physical-device build.

```sh
set +x
: "${OPENAI_API_KEY:?Supply the provider key through the process environment}"
: "${BUDDYSTUDY_NATIVE_CONVERSATION_BIND:?Set the Mac private LAN IPv4 address}"
: "${VOICE_TUTOR_TEST_IPHONE_ID:?Set the selected physical iPhone UDID}"

env -u OPENAI_API_KEY xcodebuild \
  -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug \
  -destination "platform=iOS,id=${VOICE_TUTOR_TEST_IPHONE_ID}" \
  -derivedDataPath build/iOSDeviceDerivedData -parallel-testing-enabled NO \
  -only-testing:StudyMateiOSTests/VoiceTutorNativeConversationTests/testOptInNativeReceiveOnlyConversationContinuesAcrossThreeProviderTurns \
  build-for-testing
```

Only after that build succeeds, generate one fresh capability in the same shell
without printing it. `TEST_RUNNER_` is stripped by `xcodebuild` when forwarding
these three variables to the iPhone test process; the API key is explicitly
removed from both Xcode commands' environments.

```sh
export BUDDYSTUDY_NATIVE_CONVERSATION_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
export TEST_RUNNER_BUDDYSTUDY_NATIVE_CONVERSATION_TEST=1
export TEST_RUNNER_BUDDYSTUDY_NATIVE_CONVERSATION_TOKEN="$BUDDYSTUDY_NATIVE_CONVERSATION_TOKEN"
export TEST_RUNNER_BUDDYSTUDY_NATIVE_CONVERSATION_URL="http://${BUDDYSTUDY_NATIVE_CONVERSATION_BIND}:8097"

build/voice-tutor-native-venv/bin/python scripts/voice-tutor-native-conversation.py \
  --run --bind "$BUDDYSTUDY_NATIVE_CONVERSATION_BIND" --port 8097 &
voice_tutor_fixture_pid=$!
```

Wait for the metadata-only `host_ready` line before running the next command in
that same shell. Do not enable shell tracing or print either credential variable.

```sh
env -u OPENAI_API_KEY xcodebuild \
  -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug \
  -destination "platform=iOS,id=${VOICE_TUTOR_TEST_IPHONE_ID}" \
  -derivedDataPath build/iOSDeviceDerivedData -parallel-testing-enabled NO \
  -only-testing:StudyMateiOSTests/VoiceTutorNativeConversationTests/testOptInNativeReceiveOnlyConversationContinuesAcrossThreeProviderTurns \
  test-without-building
```

After success, failure or cancellation, stop only this fixture process and wait
for its cleanup before another run:

```sh
kill -TERM "$voice_tutor_fixture_pid" 2>/dev/null || true
voice_tutor_fixture_status=0
wait "$voice_tutor_fixture_pid" || voice_tutor_fixture_status=$?
printf 'Fixture exit status: %s\n' "$voice_tutor_fixture_status"
unset BUDDYSTUDY_NATIVE_CONVERSATION_TOKEN TEST_RUNNER_BUDDYSTUDY_NATIVE_CONVERSATION_TOKEN
unset TEST_RUNNER_BUDDYSTUDY_NATIVE_CONVERSATION_TEST TEST_RUNNER_BUDDYSTUDY_NATIVE_CONVERSATION_URL
```

## Bounds and interpretation

- `--run`, a nonempty `OPENAI_API_KEY`, and a fresh random 32–128-character URL-safe
  capability are mandatory. Starting the host alone does not allocate a call:
  only the authenticated receive-only SDP offer does. No allocation retry or
  second offer is allowed after the first claim, including concurrent requests.
- A watchdog requests shutdown 55 seconds after the offer claim, reserving the
  remaining budget (at most five seconds) for the provider hangup request. An
  unused host expires after 300 seconds; that idle deadline no longer applies
  once a call is claimed. The host retains at most 96 sanitized lifecycle events.
- SIGINT, SIGTERM and authenticated `/close` immediately reject new offers and
  starts. A claimed allocation remains owned by the host even if the phone's
  HTTP request is canceled: cleanup recovers its call identity within the
  original 20-second negotiation budget, then hangs it up before closing HTTP.
  If the identity is already known, cleanup need not wait for a slow SDP body.
- A provider may accept an allocation before a transport timeout loses its
  response. Without its call identity, this fixture **cannot prove or complete
  provider-side cleanup**. `provider_cleanup_unconfirmed` and
  `host_stopped.providerCleanup=unconfirmed` report this case or a failed/non-2xx
  hangup, and the host exits nonzero. Investigate before retrying; do not infer
  that a timed-out negotiation created no paid call.
- Host exit zero requires all three turns completed, acknowledged provider
  hangup, and no fixture/local cleanup failure. A stopped incomplete or unused
  host exits nonzero too. Preserve the exit status as above and require both a
  passing selected XCTest and `host_stopped` with `exitCode=0`; one does not
  substitute for the other.
- Allowed logs contain fixed reason codes, route names, counters, HTTP status
  and boolean/enum outcomes only. `fixture_request_received`,
  `provider_negotiation_started` and
  `provider_call_created` distinguish LAN arrival from actual allocation without
  printing a key, capability, provider identity, SDP, audio or transcript.
- `Local network prohibited` / `NSURLErrorDomain -1009`, signaling timeout, an
  opt-in skip, or foreground/permission failure is **not a passing voice test**.
  No `fixture_request_received` means the authenticated offer never arrived;
  `provider_call_created` means a paid provider allocation was observed.
- Inspect the selected XCTest result and its
  `native-three-turn-conversation-metadata-only` attachment. Do not infer success
  from `host_ready`, HTTP 200 alone, or a build without the test execution.
- This fixture does not print secrets, but tooling can retain environment data.
  Keep test bundles/logs private and review them before sharing. Never add this
  paid test to unattended CI or broaden the selector to unrelated/destructive
  recording tests.

## No-network host regression checks

These checks need only the Python/aiohttp environment above. They replace HTTP
with in-memory fakes and forbid real client construction and listener startup;
they do not run the paid fixture, contact a device or read app data.

```sh
env -u OPENAI_API_KEY -u BUDDYSTUDY_NATIVE_CONVERSATION_TOKEN \
  build/voice-tutor-native-venv/bin/python -B \
  scripts/test/voice_tutor_native_conversation_test.py -v
```

They cover canceled/in-flight negotiation recovery, stopping/concurrent request
guards, original deadline preservation, cleanup idempotence, unknown allocation
and failed-hangup outcomes, bounded SDP reads, idle/call deadline separation,
preflight checks and metadata redaction. They are not a substitute for the
explicitly approved physical three-turn test.
