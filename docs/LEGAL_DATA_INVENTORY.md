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
| AI processing | Server-managed OpenAI account for question generation, grading, feedback, recommendations, and fallback translation | Provider processing applies when the function is used |
| User-authorized MCP access | Stateless HTTPS tools/resources expose the authenticated user's private profile, learning context, studies, questions, grading, and topic statistics to the MCP client selected by that user | No server-side MCP session; stored source data follows its normal retention |
| Translation | Self-hosted LibreTranslate first; OpenAI fallback | Translation results are stored with content localizations |
| Notifications | APNs device token, notification preferences, notification and read state | Until device unregister, invalidation, or account deletion |
| Terms agreements | Immutable MySQL action history with version, source, time, app version, IP and user agent | Until account deletion unless required for a legal dispute |
| Subscriptions and purchases | MySQL and RevenueCat: membership, product ID, App Account Token, App Store transaction/original transaction IDs, environment, amount/currency, renewal, expiration, cancellation, refund, and fulfillment state | Until account deletion or membership termination; longer where payment, refund, dispute, or legal retention requires it |
| App control | Firebase Remote Config: app, device, and configuration request metadata | Google project retention settings |
| Product analytics | Google Analytics for Firebase in release builds; coarse screen and feature events | Firebase project retention settings |
| Error diagnostics | Sentry error and fatal events; error-session replay with all text and images masked | Sentry project retention settings |
| API and operation logs | Loki; captured request/response headers and bodies may include unmasked credentials and tokens in the administrator-only API Logs view | 7 days |
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
| OpenAI | AI question, grading, feedback, recommendation and fallback translation | Provider operating countries |
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

1. Review actual data fields, SDK configuration, providers, retention, account
   deletion, public-content behavior, and notification behavior.
2. Publish immutable Korean, English, and Japanese copies with the same version
   date and equivalent meaning.
3. Compute SHA-256 for each Korean fixed copy and register the values in a new
   Flyway migration.
4. Keep required and mutable flags aligned with product behavior.
5. Update `AppLegalLinks` and the current-document redirects.
6. Do not overwrite or delete previous documents or agreement history.
7. Run the Flyway integration test, iOS build, and local link validation.
8. Deploy the documentation and backend migration through their GitHub Actions
   workflows.

## Legal Reference Points

- Personal Information Protection Act, Article 30: privacy policy disclosure
- Act on Promotion of Information and Communications Network Utilization and
  Information Protection, Article 50: prior consent for commercial information

This inventory records implemented behavior; it is not a substitute for
professional legal advice.
