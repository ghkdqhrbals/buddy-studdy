# Subscription overlap from different Apple accounts

## Evidence

The September 17, 2026 06:58 UTC lifecycle alert reported one user with
multiple active subscriptions. Entitlement mismatch, webhook backlog, exhausted
reconciliation, stale reservations, negative quota counters, and ownership
conflicts were all zero.

The account had an existing Plus subscription and a new Pro purchase. Read-only
App Store Connect inspection confirmed both approved monthly products belong to
subscription group `22283612`, with Pro at level 1 and Plus at level 2. Family
sharing was disabled for both products.

Apple's transaction and subscription-status APIs confirmed two different
original transaction chains with different `appTransactionId` values. Each
Apple account context independently had an active, renewing subscription. This
was a second purchase under a different Apple account, rather than an upgrade
of the existing chain. RevenueCat also reported both chains as active. The
Sandbox transaction environment does not make the BuddyStudy user fictitious
and does not justify excluding the account from operational alerts.

The backend correctly selected the higher tier and did not add the two question
allowances. Neither valid subscription should be expired or refunded by an
application repair.

## Prevention

Before this fix, purchase preparation could synchronize no local StoreKit
entitlement, retain the paid backend plan, and proceed with a normal purchase
on the device's different Apple account while presenting the action as a plan
change.

Billing status now identifies the original transaction chain selected by the
backend entitlement projection. Purchase preparation requires a successful fresh
billing-status request; cached free or missing status cannot authorize checkout
after a request failure. Before an existing App Store subscriber changes plans,
iOS must find a verified, current StoreKit entitlement matching that chain,
product, and BuddyStudy account token. Missing or conflicting local evidence
stops the action before checkout creation or a Store purchase. The user can
return to the purchasing Apple account and restore or manage that subscription.
Verified current grace-period access and retired annual subscriptions remain
eligible for this ownership check; retired products remain unavailable for sale.

The response field is additive for existing clients. Prevention requires the
updated iOS app; a backend response alone cannot identify the Apple account
currently used by an old client. Existing purchases, restores, payment evidence,
entitlements, and quota counters remain recoverable.

## Separate webhook finding

Two RevenueCat deliveries in the incident window were rejected with
`hmac_mismatch` and signature ages of zero and one second. The production
integration has HMAC signing enabled. The verifier's documented algorithm and
the unchanged raw request-body forwarding were checked. This is separate from
the two Apple-confirmed active subscription chains. The integration's HMAC key
and the deployed secret must be synchronized before those deliveries can be
retried successfully; no authentication bypass is appropriate.

## Verification

- `BillingServiceTest`: 37/37 passed.
- Changed MySQL adapter scenarios: 2/2 passed, including selected-chain fallback
  and cross-user/stale-free association protection.
- Full `BillingLedgerPersistenceAdapterTest`: 36/37 passed. The existing
  `completed upgrade repairs a stale product projection without regressing newer
  lifecycle state` test fails with expected 300 versus actual 1000. A clean
  detached worktree at deployed baseline `e9082e5c` reproduced the identical
  single-test failure: its fixture lowers the subscription projection but leaves
  a fulfilled Pro membership active, which the independent quota query selects.
  This fix does not change that fixture or quota behavior.
- Backend deployment workflow: six focused rollout tests passed; YAML and shell
  syntax checks passed. The workflow is backend-only and verifies the submitted
  image specification without runtime health probes.

- Generic `StudyMateiOS` device build passed after all review corrections.
- Focused tests on the physical iPhone: 21/21 passed, covering exact chain and
  account matching, grace/annual compatibility, failed fresh status reads,
  cancellation, restoration selection, downgrade preparation, and response
  decoding. This does not claim a live charged purchase or Apple-account-switch
  test.

No production purchase, cancellation, refund, or subscription expiration is part
of verification. The backend response can be deployed independently; prevention
reaches users only after the updated iOS binary is distributed.

Apple references: [appTransactionId](https://developer.apple.com/documentation/storekit/apptransaction/apptransactionid),
[subscription group behavior](https://developer.apple.com/app-store/subscriptions/).
