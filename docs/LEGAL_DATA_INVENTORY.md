# BuddyStudy Legal Data Inventory

This document is the engineering source of truth used when updating BuddyStudy's
Terms of Service, Privacy Policy, and Marketing Information Consent.

Last reviewed: 2026-08-30

## Published Documents

| Document | Required | User can withdraw | Effective before AdMob activation | AdMob release target |
| --- | --- | --- | --- | --- |
| Terms of Service | Yes | No, while using the service | `terms-2026-07-30.html` | unchanged |
| Privacy Policy | Yes | No, while using the service | `privacy-2026-08-14.html` | `privacy-2026-08-25.html` |
| Marketing Information Consent | No | Yes | `marketing-consent-2026-07-30.html` | unchanged |

The Korean fixed copies are the documents registered in the `terms` table. The
iOS app selects the Korean, English, or Japanese published copy based on
`AppLanguage`. Agreement history still refers to one document version per code,
so localized copies must remain equivalent translations of that version.
The 2026-08-25 privacy row is staged with a future sentinel effective time in
this release. A separate post-approval Flyway migration activates it only after
the AdMob-capable build is public and older builds are force-updated. Agreement
requests bind the exact server-provided version and content hash, preventing an
older client from recording agreement to a document it did not display.

The published privacy copies above do not yet approve Pro Voice Tutor data
processing. This inventory records the engineering behavior for legal review;
it does not itself publish a new policy version or authorize production
activation. No Voice Tutor privacy HTML or `terms` Flyway migration may be
created until legal approves the processing, retention, provider disclosure,
localized wording, effective date, and any re-agreement requirement.

## Data and Systems

| Data or processing | Current implementation | Retention or deletion |
| --- | --- | --- |
| Account and profile | MySQL: provider, provider ID, email, display name, profile settings, app language | Until account deletion |
| Optional learning context | MySQL: private resume Markdown and normalized interest list, supplied through an authenticated MCP connection | Until explicit clearing or account deletion |
| Authentication | MySQL and signed access tokens; device credential and session state | Revoked on logout, device reset, or account deletion |
| Email verification | Google SMTP; destination email and short-lived verification code | Code expires after 3 minutes |
| Studies and questions | MySQL: study tree, scheduled/generated questions, answers, grading, feedback, statistics | Until item or account deletion |
| Public community data | MySQL: public questions, public profile fields, likes, comments, views, reports, and user-to-user block relationships | Until item deletion, moderation, unblock, or account deletion |
| Advertising delivery | MySQL: server slot/campaign selection, placement and position, provider, delivery/impression/click/open time, user/device ownership, and campaign suppression | Until account deletion; aggregate campaign and placement reporting uses a 30-day window |
| AI processing | Server-managed OpenAI account for question generation, grading, feedback, recommendations, fallback translation, and Voice Tutor result summarization | Provider processing applies when the function is used |
| Pro Voice Tutor realtime audio | During an authenticated foreground lesson, the backend relays microphone audio to OpenAI Realtime and relays generated model audio back to the app. OpenAI performs realtime speech processing; the existing server-only regular user-content API key is used. BuddyStudy does not persist original microphone or model audio | Original audio exists in BuddyStudy only for the live relay and is not written to MySQL, object storage, logs, analytics, Sentry, MCP, or backups. Provider processing follows the approved provider terms and configuration |
| Voice Tutor lesson history | MySQL: owned session/accounting metadata, bounded ordered transcript text, and a separate private result containing summary, strengths, improvements, and next steps | Stored until account deletion. Deleting the account removes voice quota, sessions, transcript turns, and results through the owned relational cascade |
| User-authorized MCP access | Stateless HTTPS tools/resources expose the authenticated user's private profile, learning context, studies, questions, grading, topic statistics, and read-only Voice Tutor quota/session/result data to the MCP client selected by that user | No server-side MCP session; stored source data follows its normal retention. MCP cannot start, extend, explicitly end, or stream a Voice Tutor session |
| Translation | Self-hosted LibreTranslate first; OpenAI fallback | Translation results are stored with content localizations |
| Notifications | APNs device token, notification preferences, notification and read state | Until device unregister, invalidation, or account deletion |
| Terms agreements | Immutable MySQL action history with version, source, time, app version, IP and user agent | Until account deletion unless required for a legal dispute |
| Subscriptions and purchases | MySQL and RevenueCat: membership, product ID, App Account Token, App Store transaction/original transaction IDs, environment, amount/currency, renewal, expiration, cancellation, refund, and fulfillment state | Until account deletion or membership termination; longer where payment, refund, dispute, or legal retention requires it |
| App control | Firebase Remote Config: app, device, and configuration request metadata | Google project retention settings |
| Product analytics | Google Analytics for Firebase in release builds; coarse screen and feature events | Firebase project retention settings |
| Error diagnostics | Sentry error and fatal events; error-session replay with all text and images masked | Sentry project retention settings |
| API and operation logs | Loki; credentials and tokens are redacted. MCP and Voice Tutor REST bodies plus Voice Tutor WebSocket frames are excluded; only safe request/session metadata and redacted failure classifications may be logged | 7 days |
| Database backups | Encrypted operational backup | Up to 14 days |
| Local app data | Settings, drafts, logs and cache on the device | App reset, deletion, or cache lifecycle |

BuddyStudy does not currently support user-uploaded profile photos. The profile
uses bundled pixel-character assets. It does not intentionally collect resident
registration numbers, health data, biometrics, or other sensitive information.

## External Providers and Locations

| Provider | Purpose | Typical location |
| --- | --- | --- |
| Amazon Web Services | API, MySQL, Redis, secrets and backups | Seoul region |
| Cloudflare | DNS, TLS proxy and network security | Global edge network |
| OpenAI | AI question, grading, feedback, recommendation, fallback translation, Voice Tutor realtime speech exchange/transcription, and private lesson-result summarization | Provider operating countries |
| Apple | Sign in with Apple, App Store subscriptions, StoreKit transactions, purchase management, and APNs push delivery | Provider operating countries |
| RevenueCat, Inc. | Product lookup, purchase/restore, Customer Center, subscription state, and webhook delivery | Provider operating countries |
| Google | Login, SMTP email verification, Firebase Analytics, Remote Config, UMP privacy choices, and non-personalized AdMob native advertising | Provider operating countries |
| Functional Software, Inc. (Sentry) | Error and crash diagnostics | Provider operating countries |

Data transmitted to external providers uses encrypted transport. For eligible
free-feed ad requests, Google may process IP-derived coarse location,
app/developer-bounded device identifiers, advertising data, product interaction,
and performance or diagnostic data. BuddyStudy does not send account IDs, study
content, answers, or search terms to AdMob, does not request IDFA or ATT, disables
publisher first-party ID, and requests teen-treated non-personalized ads only.
BuddyStudy does not sell personal information or provide behaviorally personalized
advertising. TIER2 and TIER3 do not receive an ad slot.

## Collection Boundaries

- Firebase events must not include BuddyStudy user IDs, email, study topics,
  questions, answers, comments, tokens, or request IDs.
- AdMob requests must not include BuddyStudy user/device IDs, email, study
  topics, questions, answers, comments, search terms, content URLs, keywords,
  tokens, or request IDs. ATT, IDFA access, publisher first-party ID, personalized
  ads, and mediation partner SDKs remain disabled.
- Sentry keeps `sendDefaultPii` disabled. Network bodies and headers,
  screenshots, and view hierarchy attachments stay disabled. Replay text and
  images remain masked.
- Request logging must redact passwords, verification codes, access tokens,
  Google ID tokens, APNs credentials, client secrets, and API keys.
- Request and response bodies on `/api/v1/mcp` must never be captured in API
  logs because they can contain resume text, interests, answers, feedback, and
  scores. The authenticated principal may be copied into tool context, but the
  raw bearer token must not be copied or forwarded.
- Request and response bodies under `/api/v1/voice-tutor`, WebSocket handshake
  payloads, and every frame on `/api/v1/voice-tutor/sessions/{id}/stream` must
  never be captured in API exchange logs or external-provider history bodies.
  Transcript text, derived summaries, audio payloads, provider credentials, and
  realtime request bodies must not appear in analytics, Sentry attachments, or
  operational error messages.
- Original Voice Tutor microphone and model audio must remain ephemeral relay
  data. BuddyStudy must not write it to MySQL, files, object storage, caches,
  backups, MCP resources, or diagnostic tooling. Persisted lesson evidence is
  limited to bounded text transcript turns, safe session/accounting metadata,
  and the private derived result.
- Voice Tutor sessions, transcripts, and results are private owner-scoped data.
  Account withdrawal must remove them along with `user_voice_quota`; they must
  never enter public-question payloads, graded-question statistics, advertising
  requests, or another user's MCP/REST response.
- Resume and interests remain private and must not appear in community profile
  responses, public questions, Firebase Analytics, Sentry attachments, or
  prompts sent to a provider unless the user explicitly invokes a function
  whose disclosed purpose requires that content.
- Public-question responses must not expose email, authentication data, device
  identifiers, push tokens, private answers, or drafts.
- Authenticated public-question and comment responses must omit content authored
  by users the requester has blocked. Direct access to a blocked author's public
  question must be rejected, and both sides of every block relationship must be
  removed when either account is deleted.
- Marketing messages require an active `MARKETING_NOTIFICATION` agreement and
  remain separate from operational question, comment, security, and maintenance
  notifications.

## Update Checklist

1. Obtain legal approval for realtime microphone/model audio processing by
   OpenAI, provider/cross-border disclosure, bounded transcript/result storage,
   retention through account deletion, MCP disclosure, and the no-raw-audio
   storage boundary. Record whether existing users must re-agree.
2. Until that approval is recorded, keep production
   `VOICE_TUTOR_ENABLED=false`; the backend deployment template uses this safe
   default, and the application itself also fails closed when the setting is
   omitted. After approval, set the deployment repository variable explicitly
   to `true`.
   Do not create or stage a Voice Tutor privacy HTML file or `terms` migration
   as a substitute for approval.
3. After approval, publish immutable Korean, English, and Japanese privacy
   copies with the same version date and equivalent meaning. Preserve all prior
   documents and agreement history.
4. Compute SHA-256 for each approved Korean fixed copy and register the exact
   version, effective time, required/mutable flags, and hash in a new additive
   Flyway migration. Update `AppLegalLinks` and current-document redirects only
   to that approved version.
5. Verify with automated tests that Voice Tutor REST bodies and WebSocket frames
   are excluded from logs, original audio is never persisted, MCP remains
   owner-scoped/read-only, and account deletion removes quota, session,
   transcript, and result rows.
6. Review the remaining data fields, SDK configuration, providers, retention,
   account deletion, public-content behavior, and notification behavior.
7. Run the Flyway integration test, iOS build, and local legal-link validation.
8. Deploy the approved documentation and backend migration through their
   GitHub Actions workflows before enabling the feature flag.

## Legal Reference Points

- Personal Information Protection Act, Article 30: privacy policy disclosure
- Act on Promotion of Information and Communications Network Utilization and
  Information Protection, Article 50: prior consent for commercial information

This inventory records implemented behavior; it is not a substitute for
professional legal advice.
