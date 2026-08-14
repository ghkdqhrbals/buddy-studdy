# BuddyStudy Legal Data Inventory

This document is the engineering source of truth used when updating BuddyStudy's
Terms of Service, Privacy Policy, and Marketing Information Consent.

Last reviewed: 2026-08-14

## Published Documents

| Document | Required | User can withdraw | Current fixed copy |
| --- | --- | --- | --- |
| Terms of Service | Yes | No, while using the service | `terms-2026-07-30.html` |
| Privacy Policy | Yes | No, while using the service | `privacy-2026-08-14.html` |
| Marketing Information Consent | No | Yes | `marketing-consent-2026-07-30.html` |

The Korean fixed copies are the documents registered in the `terms` table. The
iOS app selects the Korean, English, or Japanese published copy based on
`AppLanguage`. Agreement history still refers to one document version per code,
so localized copies must remain equivalent translations of that version.

## Data and Systems

| Data or processing | Current implementation | Retention or deletion |
| --- | --- | --- |
| Account and profile | MySQL: provider, provider ID, email, display name, profile settings, app language | Until account deletion |
| Authentication | MySQL and signed access tokens; device credential and session state | Revoked on logout, device reset, or account deletion |
| Email verification | Google SMTP; destination email and short-lived verification code | Code expires after 3 minutes |
| Studies and questions | MySQL: study tree, scheduled/generated questions, answers, grading, feedback, statistics | Until item or account deletion |
| Public community data | MySQL: public questions, public profile fields, likes, comments, views, reports, and user-to-user block relationships | Until item deletion, moderation, unblock, or account deletion |
| AI processing | Server-managed OpenAI account for question generation, grading, feedback, recommendations, and fallback translation | Provider processing applies when the function is used |
| Translation | Self-hosted LibreTranslate first; OpenAI fallback | Translation results are stored with content localizations |
| Notifications | APNs device token, notification preferences, notification and read state | Until device unregister, invalidation, or account deletion |
| Terms agreements | Immutable MySQL action history with version, source, time, app version, IP and user agent | Until account deletion unless required for a legal dispute |
| Subscriptions and purchases | MySQL and RevenueCat: membership, product ID, App Account Token, App Store transaction/original transaction IDs, environment, amount/currency, renewal, expiration, cancellation, refund, and fulfillment state | Until account deletion or membership termination; longer where payment, refund, dispute, or legal retention requires it |
| App control | Firebase Remote Config: app, device, and configuration request metadata | Google project retention settings |
| Product analytics | Google Analytics for Firebase in release builds; coarse screen and feature events | Firebase project retention settings |
| Error diagnostics | Sentry error and fatal events; error-session replay with all text and images masked | Sentry project retention settings |
| API and operation logs | Loki; credentials and tokens are redacted | 7 days |
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
| Google | Login, SMTP email verification, Firebase Analytics, and Remote Config | Provider operating countries |
| Functional Software, Inc. (Sentry) | Error and crash diagnostics | Provider operating countries |

Data transmitted to external providers uses encrypted transport. BuddyStudy does
not sell personal information and does not currently provide behaviorally
personalized advertising.

## Collection Boundaries

- Firebase events must not include BuddyStudy user IDs, email, study topics,
  questions, answers, comments, tokens, or request IDs.
- Sentry keeps `sendDefaultPii` disabled. Network bodies and headers,
  screenshots, and view hierarchy attachments stay disabled. Replay text and
  images remain masked.
- Request logging must redact passwords, verification codes, access tokens,
  Google ID tokens, APNs credentials, client secrets, and API keys.
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
