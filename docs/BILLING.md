# Apple billing

BuddyStudy sells server-owned membership tiers through StoreKit 2. The iOS app
never grants a tier from an unverified client result. It sends Apple's signed
transaction JWS to the backend with the stable per-user `appAccountToken`; the
backend verifies the signature, bundle ID, App Store app ID, environment,
product mapping, account token, timestamps, amount fields, and transaction
identity before writing the ledger.

## Product catalog

`membership_tier_products` maps enabled App Store product IDs to
`user_membership_tiers`. The initial monthly products are:

- `io.github.ghkdqhrbals.StudyMate.tier2.monthly` → `TIER2`
- `io.github.ghkdqhrbals.StudyMate.tier3.monthly` → `TIER3`

The mapping is server-owned. A client-supplied product that is absent, disabled,
or has a different product type is rejected before an invoice is written.

## Ledger and state

- `invoices` is the current invoice projection.
- `invoice_events` is the append-only source of truth, ordered by an aggregate
  sequence and protected by unique event IDs.
- `payments` stores one verified Apple transaction identity and its current
  settlement projection.
- `payments_history` is the append-only verification, settlement, revocation,
  and refund history.
- `billing_actions` stores idempotent user, admin, and compensation requests.
- `billing_jobs` durably records fulfillment or compensation work with a
  maximum of three attempts.
- `apple_billing_notifications` deduplicates signed App Store Server
  Notifications V2 by notification UUID.

Invoice changes must pass `InvoiceStateMachine`. Apple notification events are
idempotent and are the only authority for final refund, refund-decline,
revocation, renewal-status, and expiration states.

## Transaction boundaries and compensation

Payment evidence is committed before membership fulfillment. Fulfillment runs
in a separate transaction. If membership work rolls back, a `REQUIRES_NEW`
boundary records `COMPENSATION_REQUIRED`, fails the fulfillment job, creates a
compensation job, and creates a required compensation action. This preserves
proof of the charge even when the entitlement transaction fails.

Apple does not provide a server API that lets BuddyStudy unilaterally issue an
App Store refund or cancel a user's subscription. iOS starts the system refund
sheet with `Transaction.beginRefundRequest`; subscription cancellation opens
Apple's subscription management. Admin actions create a durable audited request
and operational state. The final result always comes back through a verified
Apple server notification, and no admin endpoint can forge a completed refund.

## API

User endpoints:

- `GET /api/v1/billing/catalog`
- `POST /api/v1/billing/apple/transactions`
- `GET /api/v1/billing/invoices`
- `GET /api/v1/billing/invoices/{invoiceId}`
- `POST /api/v1/billing/payments/{paymentId}/refund-requests`
- `POST /api/v1/billing/subscriptions/{originalTransactionId}/cancellation-requests`

Apple public webhook:

- `POST /api/v1/billing/apple/notifications`

Admin endpoints:

- `GET /api/v1/admin/billing/invoices`
- `GET /api/v1/admin/billing/invoices/{invoiceId}`
- `POST /api/v1/admin/billing/invoices/{invoiceId}/refund-requests`
- `POST /api/v1/admin/billing/invoices/{invoiceId}/cancellation-requests`

All mutation endpoints require a validated idempotency key. Admin endpoints use
the existing monitoring administrator session. The monitoring UI exposes the
flow at `/orders.html`.

## Production setup

1. Create the two product IDs in App Store Connect and attach them to the same
   auto-renewable subscription group.
2. Configure the App Store Server Notifications V2 production and sandbox URL
   as `https://api.ghkdqhrbals.org/api/v1/billing/apple/notifications`.
3. Keep bundle ID `io.github.ghkdqhrbals.StudyMate` and numeric App Store app ID
   `6774108938` aligned with the release app.
4. Apple Root CA G2 and G3 public certificates are bundled from Apple PKI. They
   may be overridden with `APPLE_IAP_ROOT_CERTIFICATES_BASE64` when rotating
   trust material.
5. Production online certificate checks remain enabled. Xcode StoreKit
   transactions are accepted only in the development profile.

StoreKit products and server notifications still require App Store Connect
agreements, tax/banking setup, localized product metadata, prices, and review
approval before real purchases can complete.
