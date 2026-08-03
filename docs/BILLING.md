# Apple billing

BuddyStudy sells server-owned membership tiers through StoreKit 2. The iOS app
never grants a tier from an unverified client result. It sends Apple's signed
transaction JWS to the backend with the stable per-user `appAccountToken`; the
backend verifies the signature, bundle ID, App Store app ID, environment,
product mapping, account token, timestamps, amount fields, and transaction
identity before writing the ledger.

## Product catalog

`membership_tier_products` maps enabled App Store product IDs to
`user_membership_tiers`. Monthly and annual products grant the same monthly
allowance; only the Apple renewal period and price differ.

| Tier | Monthly allowance | Product | Period | Korea price |
| --- | ---: | --- | --- | ---: |
| TIER1 | 30 | Free | — | Free |
| TIER2 | 300 | `io.github.ghkdqhrbals.StudyMate.tier2.monthly` | P1M | ₩7,900 |
| TIER2 | 300 | `io.github.ghkdqhrbals.StudyMate.tier2.yearly` | P1Y | ₩69,000 |
| TIER3 | 1,000 | `io.github.ghkdqhrbals.StudyMate.tier3.monthly` | P1M | ₩17,900 |
| TIER3 | 1,000 | `io.github.ghkdqhrbals.StudyMate.tier3.yearly` | P1Y | ₩149,000 |

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

For a user-initiated purchase, BuddyStudy creates a `NORMAL` invoice before
presenting the StoreKit sheet. `INVOICE_CREATED` projects it as `WAITING`
without a payment row. Payment verification and membership fulfillment remain
detailed events while the public invoice status stays `WAITING`. It becomes
`COMPLETED` only after the verified Apple payment has successfully applied the
membership entitlement and monthly question allowance. StoreKit user
cancellation or fulfillment failure makes it `FAILED`; StoreKit's `.pending`
result deliberately keeps it `WAITING` for later approval or server-notification
recovery.

Invoice projection status is intentionally limited to `WAITING`, `COMPLETED`,
and `FAILED`. Invoice type is `NORMAL` (일반) or `REFUND` (환불). A refund never
rewrites the completed normal invoice: it creates a separate `REFUND` invoice
linked by `original_invoice_id`. The refund invoice is `WAITING` during Apple
review, `COMPLETED` when Apple confirms the refund, and `FAILED` when Apple
declines or reverses it. Detailed provider and compensation states remain in
`invoice_events`, `payments`, `payments_history`, and `billing_actions`.

The checkout idempotency key is scoped to the authenticated user. The client
includes the returned `invoiceNumber` when synchronizing JWS. Initial-purchase
notifications that arrive before the client callback recover the newest matching
pending invoice by stable `appAccountToken`, user, and product; renewals create a
new invoice because each renewal is a separate charge.

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
- `POST /api/v1/billing/checkouts`
- `POST /api/v1/billing/checkouts/{invoiceNumber}/abandon`
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

Checkout creation and billing actions require a validated idempotency key. The
transaction-sync endpoint is idempotent by Apple's transaction ID, and checkout
abandonment is idempotent by invoice state. Admin endpoints use the existing
monitoring administrator session. The monitoring UI exposes the flow at
`/orders.html`.

## Production setup

1. Keep all four products in the single `BuddyStudy Membership` auto-renewable
   subscription group. TIER3 is group level 1 and TIER2 is group level 2;
   monthly and annual products for the same tier share a level.
2. The Sandbox App Store Server Notifications V2 URL is
   `https://api.ghkdqhrbals.org/api/v1/billing/apple/notifications`. Configure
   the production URL separately before the App Store release.
3. Keep bundle ID `io.github.ghkdqhrbals.StudyMate` and numeric App Store app ID
   `6774108938` aligned with the release app.
4. Apple Root CA G2 and G3 public certificates are bundled from Apple PKI. They
   may be overridden with `APPLE_IAP_ROOT_CERTIFICATES_BASE64` when rotating
   trust material.
5. Production online certificate checks remain enabled. Xcode StoreKit
   transactions are accepted only in the development profile.

The committed App Store Connect source of truth is
`app-store/billing/subscriptions.json`. Product metadata can take up to one hour
to propagate to Sandbox. A Sandbox Apple Account must be created in App Store
Connect's Users and Access page because Apple exposes only lookup and mutation,
not tester creation, through the public API.

StoreKit products and server notifications still require App Store Connect
agreements, tax/banking setup, review screenshots, and review approval before
real purchases can complete.

## Development and Sandbox setup

- The shared `StudyMateiOS` Debug launch action uses `StudyMateDev.storekit`.
  Simulator and Xcode-launched device purchases therefore return `XCODE`
  transactions while still creating the same backend `NORMAL/WAITING` invoice
  before the StoreKit sheet and synchronizing the transaction JWS afterward.
- The same launch action injects `BUDDYSTUDY_BACKEND_BASE_URL=https://api.lowfidev.cloud`,
  keeping the local StoreKit transaction and its pending invoice on the dev
  backend even when the installation previously selected the production API.
- The backend `dev` profile accepts `XCODE` transactions and verifies their
  bundle ID, product mapping, `appAccountToken`, invoice, and transaction data.
  Apple does not sign Xcode-local test data, so this environment is never
  enabled by the production profile.
- To exercise Apple's actual Sandbox, run a development-signed build without
  the scheme StoreKit configuration and sign in with an App Store Connect
  Sandbox Apple Account. Those transactions are marked `SANDBOX` and use the
  same dev API and invoice lifecycle.
- `StudyMateDev.storekit` and `app-store/billing/subscriptions.json` must retain
  identical product IDs and Korean base prices; the iOS policy test guards this
  contract.
