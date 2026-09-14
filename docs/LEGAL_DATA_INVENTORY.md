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
processing or optional call recording. This inventory records the engineering
behavior for legal review; it does not itself publish a new policy version or
authorize production activation. Live Voice Tutor and new recording capture
therefore have separate default-off production flags. No Voice Tutor privacy
HTML or `terms` Flyway migration may be created until legal approves realtime
processing, direct WebRTC media transport, explicit recording-consent wording,
private object storage, retention/deletion, provider disclosure, localized
wording, effective date, and any re-agreement requirement.

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
| Pro Voice Tutor realtime audio | During an authenticated foreground lesson, the backend creates and controls an OpenAI Realtime call with the existing server-only regular user-content API key. iOS sends audio on the negotiated audio-only WebRTC peer connection; the backend owns the authenticated sideband, instructions, turns, transcript, and lifecycle. Ordinary realtime frames are not persisted | Realtime frames are not written to MySQL, object storage, logs, analytics, Sentry, MCP, or backups. Provider processing follows the approved provider terms and configuration |
| Voice Tutor provider-call cleanup | FK-free MySQL `voice_tutor_webrtc_cleanup_outbox`: provider call ID, user/session IDs, attachment state, retry schedule, lease token/time, attempt count, and bounded error classification. It exists only to durably end provider calls across attach failures, process restarts, and relational account deletion; it contains no raw audio, SDP, transcript, recording, or provider credential | Removed after the provider confirms hangup or reports the call already ended. It may temporarily survive account deletion so bounded recovery can finish the external call, then is deleted |
| Optional Voice Tutor call recording | Only after explicit per-session `voice-recording-v1` consent and while the separately default-off feature is enabled, iOS captures both processing taps, aligns and mixes them into one AAC/M4A file, protects it from device backup, and uploads it directly through a short signed PUT. Private S3 stores the binary; MySQL stores consent version/time, object status, checksum, size, duration, and retention metadata | 30 days from the server-recorded call end by default, operator-configurable from 1–365 days; a same-contract pending upload renewal cannot extend that deadline, and a signed GET is not issued across it. The owner can delete immediately. Deletion removes all object versions and is physically retried minutely through the post-expiry upload safety deadline, then daily forever while metadata exists. Account withdrawal independently commits a permanent FK-free prefix-cleanup tombstone before relational deletion; it contains only the withdrawn numeric storage-namespace discriminator and retry metadata, tolerates initial S3 failure, and drives minutely-then-daily prefix deletion forever. The bucket must enforce lifecycle expiry for current/noncurrent versions and delete markers no later than configured recording retention. No-consent sessions have no upload grant or object |
| Voice Tutor lesson history | MySQL: owned session/accounting and recording metadata, bounded ordered transcript text, and a separate private result containing summary, strengths, improvements, and next steps | Stored until account deletion except recording binaries, which follow the shorter configured retention. Account deletion removes voice quota, sessions, transcript turns, metadata, and results after independently committing the recording-prefix cleanup marker; private object deletion may finish asynchronously under that durable marker if S3 was unavailable |
| User-authorized MCP access | Stateless HTTPS tools/resources expose the authenticated user's private profile, learning context, studies, questions, grading, topic statistics, and read-only Voice Tutor quota/session/result data to the MCP client selected by that user | No server-side MCP session; stored source data follows its normal retention. MCP may expose safe recording status metadata in owner-scoped session detail but never audio, object keys, or signed URLs; it cannot start, extend, explicitly end, stream, record, download, or delete a Voice Tutor session |
| Translation | Self-hosted LibreTranslate first; OpenAI fallback | Translation results are stored with content localizations |
| Notifications | APNs device token, notification preferences, notification and read state | Until device unregister, invalidation, or account deletion |
| Terms agreements | Immutable MySQL action history with version, source, time, app version, IP and user agent | Until account deletion unless required for a legal dispute |
| Subscriptions and purchases | MySQL and RevenueCat: membership, product ID, App Account Token, App Store transaction/original transaction IDs, environment, amount/currency, renewal, expiration, cancellation, refund, and fulfillment state | Until account deletion or membership termination; longer where payment, refund, dispute, or legal retention requires it |
| App control | Firebase Remote Config: app, device, and configuration request metadata | Google project retention settings |
| Product analytics | Google Analytics for Firebase in release builds; coarse screen and feature events | Firebase project retention settings |
| Error diagnostics | Sentry error and fatal events; error-session replay with all text and images masked | Sentry project retention settings |
| API and operation logs | Loki; administrator-only REST exchange headers/bodies may contain raw credentials. MCP and Voice Tutor REST bodies and WebSocket frames are excluded; MCP logical logs remain redacted. Slack/Codex exports redact credentials, and API exchange events are excluded from Sentry | 7 days |
| Database backups | Encrypted operational backup | Up to 14 days |
| Local app data | Settings, drafts, logs and cache. A consented Voice Tutor call temporarily uses owner-bound, protected, backup-excluded track/mixed files and a protected retry manifest until verified upload succeeds | App reset/deletion or normal cache lifecycle. Logout, authentication invalidation, account replacement, and withdrawal persist a protected purge-pending marker and advance the recording generation fence before purging files, so an older in-flight recorder cannot recreate media or a manifest afterward. A failed purge is retried on app startup and foreground entry regardless of file age. Verified uploads remove their files; the same startup/foreground cleanup removes invalid/unreferenced artifacts older than two hours and pending retries older than 30 days |

BuddyStudy does not currently support user-uploaded profile photos. The profile
uses bundled pixel-character assets. It does not intentionally collect resident
registration numbers, health data, biometrics, or other sensitive information.

## External Providers and Locations

| Provider | Purpose | Typical location |
| --- | --- | --- |
| Amazon Web Services | API, MySQL, Redis, secrets, backups, and private optional Voice Tutor recording objects | Seoul region |
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
- API exchange logging intentionally retains captured passwords, verification
  codes, access tokens, Google ID tokens, APNs credentials, client secrets, and
  API keys without masking for the administrator-only API Logs view. Redis
  Stream inspection, outbound API history, Slack/Codex output, and incident
  dispatch keep their separate redaction boundaries, while raw API exchange
  events and breadcrumbs are excluded from Sentry.
- Request and response bodies on `/api/v1/mcp` must never be captured in API
  logs because they can contain resume text, interests, answers, feedback, and
  scores. The authenticated principal may be copied into tool context, but the
  raw bearer token must not be copied or forwarded.
- Request and response bodies under `/api/v1/voice-tutor`, SDP, WebSocket
  handshake payloads, and every frame on `/stream` or `/control` must never be
  captured in API exchange logs or external-provider history bodies. Transcript
  text, derived summaries, realtime audio/control payloads, recording object
  keys or presigned URLs, provider credentials, and provider request bodies must
  not appear in analytics, Sentry attachments, or operational error messages.
- Voice Tutor realtime microphone and model frames remain ephemeral unless the
  owner has accepted the exact current recording-consent version and the
  recording feature is enabled. Unconsented frames must never be written to
  MySQL, files, object storage, caches, backups, MCP, or diagnostics. Consented
  iOS temporary files must use data protection and backup exclusion; only the
  final mixed AAC/M4A may enter the private recording bucket, and MySQL keeps
  metadata rather than a binary BLOB.
- Voice Tutor sessions, transcripts, results, consent audit fields, and recording
  metadata/objects are private owner-scoped data. Account withdrawal must commit
  an FK-free recording-prefix cleanup marker before removing `user_voice_quota`
  and relational rows; none may enter public-question payloads, graded-question statistics,
  advertising requests, or another user's MCP/REST response. MCP session detail
  may carry safe recording status metadata but never raw audio or a presigned URL.
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

1. Obtain legal approval for realtime microphone/model processing by OpenAI,
   direct WebRTC transport, provider/cross-border disclosure, bounded
   transcript/result storage, and MCP disclosure. Separately approve the exact
   optional recording-consent text/version, local temporary processing, private
   S3 storage, 30-day default retention, owner deletion, account withdrawal,
   and backup exclusion. Record whether existing users must re-agree.
2. Until those approvals are recorded, keep production
   `VOICE_TUTOR_ENABLED=false` and `VOICE_TUTOR_RECORDING_ENABLED=false`; the
   backend deployment template uses both safe defaults and the application
   fails closed when either applicable setting is omitted. Enable each only
   after its own approval, with the private bucket and least-privilege S3/KMS
   policy configured before recording is enabled.
   Do not create or stage a Voice Tutor privacy HTML file or `terms` migration
   as a substitute for approval.
3. After approval, publish immutable Korean, English, and Japanese privacy
   copies with the same version date and equivalent meaning. Preserve all prior
   documents and agreement history.
4. Compute SHA-256 for each approved Korean fixed copy and register the exact
   version, effective time, required/mutable flags, and hash in a new additive
   Flyway migration. Update `AppLegalLinks` and current-document redirects only
   to that approved version.
5. Verify with automated tests that Voice Tutor REST bodies, SDP, control frames,
   object keys, and signed URLs are excluded from logs; unconsented audio is
   never persisted; recording integrity/retention/deletion is owner-scoped; MCP
   has no binary or signed-URL path; and account deletion independently commits
   the FK-free cleanup tombstone before its best-effort initial prefix delete
   and relational cleanup. Verify that the managed job removes every version
   and delete marker minutely through grant expiry plus the safety deadline,
   then daily forever without removing the tombstone, and that bucket lifecycle
   independently bounds current/noncurrent versions and delete markers.
6. Review the remaining data fields, SDK configuration, providers, retention,
   account deletion, public-content behavior, and notification behavior.
7. Run the Flyway integration test, iOS build, and local legal-link validation.
8. Deploy the approved documentation and backend migrations through their
   GitHub Actions workflows before enabling either feature flag. Runtime WebRTC
   negotiation and recording upload/play/delete checks belong to an approved
   staging/device verification, not a GitHub Actions runtime health gate.

## Legal Reference Points

- Personal Information Protection Act, Article 30: privacy policy disclosure
- Act on Promotion of Information and Communications Network Utilization and
  Information Protection, Article 50: prior consent for commercial information

This inventory records implemented behavior; it is not a substitute for
professional legal advice.
