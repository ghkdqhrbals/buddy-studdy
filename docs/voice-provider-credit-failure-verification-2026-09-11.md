# Voice connection rejection from provider credits

## Incident evidence

The development backend received two successful session-creation requests at
12:45:43 and 12:45:51 UTC on 2026-09-11. Both subsequent WebRTC negotiations
received HTTP 429 from OpenAI with the structured fields
`type=insufficient_quota` and `code=credit_balance_exhausted`. Each request
stopped after one attempt with no provider call ID. The application returned
generic HTTP 503 `VOICE_TUTOR_PROVIDER_UNAVAILABLE`, so the iPhone displayed a
connection failure and offered immediate reconnection without the actual cause.

The two owner-private session rows were read without mutation. Both were
`FAILED` with finalized `COMPLETED` processing, no provider session ID, no
connected timestamp, zero accepted audio bytes and **zero charged seconds**.
Neither failure consumed the user's conversation time. The current adapter
uses the server's `properties.openai.userContentApiKey`; this provider budget is
separate from the app user's membership and monthly Voice Tutor allowance.

The previous orb/result patch did not alter the SDP handshake or audio startup.
These requests were rejected by the provider before media could connect.

## Change

The existing bounded negotiation diagnostic remains intact. After recording the
failed attempt, only an HTTP 429 with the existing exact structured permanent
quota fields becomes `VOICE_TUTOR_PROVIDER_QUOTA_EXHAUSTED` (numeric 516,
HTTP 503). No provider body or account-specific billing detail reaches the
client. Transient rate limits keep the existing bounded retry policy; ambiguous,
malformed and other-status errors retain their existing classification. Session
finalization and any observed provider-call cleanup preserve the typed API error.

iOS recognizes this code before the general 503 fallback. The orb says
“AI 서비스 이용 불가” and explains that the service's AI provider usage limit
prevents the conversation from starting. This failure offers dismissal; general
temporary connection errors retain reconnect. Korean, English and Japanese
messages are localized. The condition cannot become the user's monthly quota
exhaustion or direct them to buy a membership. Existing app logs add only bounded
HTTP status and an allow-listed API code, without SDP, URLs or raw error text.

## Verification

- Generic iOS `StudyMateiOS` Debug build with signing disabled: passed.
- Signed iPhone build-for-testing: passed.
- Simulator contract and hosted failure-screen tests: 232 passed, 4 opt-in
  audio/touch tests skipped, 0 failed
  (`build/voice-provider-quota-simulator.xcresult`).

- Physical iPhone 16 Pro (iOS 26.6.1): 3 focused tests passed, 0 skipped or
  failed (`build/voice-provider-quota-device.xcresult`). The light and dark
  native error screens were captured and visually inspected. These used an
  injected provider-quota error, not a successful live provider connection.
- Backend: 56 tests passed, 0 failures or skips (20 WebRTC service, 35 OpenAI
  adapter, 1 localized error-envelope test). `:tutor:bootJar` passed.
- The verified JAR was copied atomically into the existing local development
  Docker app volume and only `backend-backend-1` was restarted. No image was
  built and no production host was accessed. Active-session checks returned
  zero before replacement, restart and the subsequent normal iPhone launch.
- Local and public development `/actuator/health` returned `UP` after restart.
  The updated app was launched normally on the iPhone after testing. These
  checks verify deployment and startup, not provider credit availability.

Deployed JAR SHA-256:
`d1ac3516a37c35ac1a112e6e120aaee712b64a4fbd265e15f5965f94f14a0cb6`.
The previous JAR remains in the same volume as
`buddystudy-backend.pre-provider-quota-20260911.jar`, SHA-256
`ac3d0fd330eac8e6ffb4c5652cae80a419db7e42162ff20535e5b394f099d9e3`.

Provider credits are an external dependency: changing error handling does not
replenish the account or demonstrate a successful paid Realtime connection.
No payment or API-key change was performed.
