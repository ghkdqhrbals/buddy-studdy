# Voice default language — 2026-09-10

## Finding and behavior

The user reported an unexpected Spanish response during a Korean conversation.
The previous iOS client used its interface language for call creation and had no
separate default-call-language preference. It also read that value after
registration could suspend, unlike the already frozen voice selection.

The backend already validated and stored the session language and sent it to
input-transcription configuration. That is separate from spoken output. Its
global output instruction was a brief language reminder, while special
response-level instructions, including saved-question readback, omitted that
policy. The provider treats those instructions as a replacement for the session
instructions. The opening question was also hardcoded in Korean even for other
session languages. These are verified configuration gaps; the exact model event
that produced the reported Spanish response has not been established. A
read-only check of the latest two session rows confirmed both were stored as
`ko`, including the call created at 18:26:19 KST. No original transcript content
was needed for this check.

Provider references: [response instruction override semantics](https://developers.openai.com/api/reference/resources/realtime/client-events)
and [Realtime language constraints](https://developers.openai.com/api/docs/guides/realtime-models-prompting).

Profile settings now contain one **Default call language** menu next to the AI
voice setting. It offers app language, Korean, English and Japanese. Selection
uses the existing Save/Cancel flow and `SettingsStore` persistence. Missing or
malformed old values preserve all other settings and follow the app language.
Explicit language choices survive app-language changes, settings reconstruction
and backend refresh. Call creation freezes voice and language before its first
await, retaining them through token recovery and idempotent retries.

Voice previews use the draft call language and reset cached samples when that
language changes. Language changes take effect on the next call. They do not
modify an existing call, answer draft, question allowance or recording consent.

The backend shares one output-language policy between the complete session
prompt and special native/legacy response overrides. Normal turns still inherit
the complete session instructions. The opening question is localized. The
policy keeps ordinary explanations and confirmations in the selected language
while allowing original quotations, code, names and technical terms. Input
transcription continues using the existing language field; no unsupported output
language parameter or post-generation text rewriting was added.

## Verification

- Generic iOS Debug build, `StudyMateiOS` / `generic/platform=iOS`, signing
  disabled: passed (`build/voice-language/ios-generic.log`).
- iOS simulator: 57 tests passed, no failures (`ios-simulator.xcresult`).
- iPhone 16 Pro: the same 57 tests passed, no failures (`ios-device.xcresult`).
  Both result bundles and logs are under `build/voice-language/`.
- iOS suites: `VoiceTutorVoiceSettingsTests` 39 (14 new) and
  `VoiceTutorMcpContractTests` 18. Tests cover migration, explicit/follow-app
  choices, settings copies, local persistence, Save/Cancel, backend refresh/sync
  failure, unsaved changes, call creation failure, registration suspension,
  authentication retry and the actual request language for all 12 combinations
  of app language and call-language preference. Synthetic fixtures preserve
  answer drafts and never start a real voice call.
- Backend: 312 tests passed, no failures: `VoiceTutorServiceTest` 64,
  `OpenAIVoiceTutorRealtimeAdapterTest` 78, `OpenAIVoiceTutorWebRtcAdapterTest` 33,
  `VoiceTutorMcpRelayTest` 24, `VoiceTutorNativeConversationControllerTest` 73,
  and `VoiceTutorWebRtcSessionHandshakeTest` 40. Language checks cover special
  response overrides while ordinary turns retain the complete session prompt.
  Log: `/tmp/study-mate-voice-language-backend-tests.log`.
- `:tutor:bootJar`, independent implementation review and `git diff --check`
  passed. The initial backend run exposed a test that assumed the Korean-only
  opening; it was updated to verify the selected language and the final run
  passed all suites.

## Development rollout

The ongoing call was allowed to end before rollout. Zero active sessions were
verified immediately before restarting `backend-backend-1` at approximately
18:34 KST. The JAR was copied into the existing development runtime volume; no
Docker image was built and no production deployment was performed. The previous
artifact is retained at
`/app/buddystudy-backend.pre-voice-language-20260910.jar`.

Installed JAR SHA-256 matches the built artifact:
`7ae2228e1c89b812bdf1a937b23f2099c01a2af6910f4f9203e18c47bb39f6af`.
Local and public development `/health` returned HTTP 200 with `{"ok":true}`.

The signed final app was installed through real-device tests, then launched on
the user's iPhone at 18:37:47 KST with the `buddystudy://settings` payload and
the development backend URL. Launch output is in
`build/voice-language/iphone-launch.log`. The user's saved language choice was
not silently changed. No post-change live audio conversation has been verified;
model instructions reduce drift but do not guarantee every spoken response.
