# RevenueCat local StoreKit event isolation

## Observed incident

Production logged `billing_processing_exhausted` for RevenueCat event
`A241BC14-7172-4BB5-848B-95B8F45CDFEB` at 2026-09-24 18:47:18 UTC
(2026-09-25 03:47:18 KST). Its third attempt failed before payment application
because no BuddyStudy account UUID was available.

The authenticated Monitoring API Logs UI located the original request
`7f84cc6b-df30-4b49-ab20-64c8337f7beb`, accepted with HTTP 200 at
03:17:16.707 KST. It contained `INITIAL_PURCHASE`, `APP_STORE`, `SANDBOX`,
anonymous-only user IDs, and both transaction IDs beginning with
`StoreKitTest_Transaction_`. No raw authentication headers, account identifiers
or full request body are committed here.

The earlier hosted simulator log
`/tmp/buddystudy130-integrated-ios-tests.log` shows
`ArchitecturePolicyTests.testFirstMonthOffersMatchStoreKitPricesAndGroupEligibility`
creating a local tier-2 purchase at 03:17:14–03:17:15, matching the webhook's
purchase timestamp. The RevenueCat observer then logged that it was finishing
transaction `0` at 03:17:16.455. The test host had initialized the real SDK with
an anonymous identity. These tests were run during the preceding 1.3 integration
verification. Offline QA already disables the SDK and removes its key.

The event arrived before TestFlight 1.3.0 (123) became available at 03:48 KST.
This was a local synthetic purchase leaking through RevenueCat's webhook,
not evidence of a failed real App Store charge or a build-123 purchase.

## Change

- Debug hosted XCTest startup suppresses RevenueCat before its observer starts;
  the StoreKit test checks SDK isolation around its synthetic purchase.
- After webhook authentication, the backend retains local StoreKit receipts but
  marks them `IGNORED` before account, payment or subscription processing.
- The classifier requires `APP_STORE` + `SANDBOX` + the exact literal transaction
  prefix; genuine Sandbox/TestFlight events and ordinary anonymous errors keep
  their previous processing behavior.
- MySQL migration V123 closes only matching unfinished receipts. It does not
  change payments, invoices, memberships, entitlements or quotas, and does not
  reset retry counters. H2 has no billing ledger schema and needs no migration.

## Verification

The generic `StudyMateiOS` Debug build passed. Six focused simulator tests passed,
including actual hosted-process checks that RevenueCat is unconfigured after
app startup, `start()`, `identify()`, and the existing introductory-offer local
purchase. This verifies SDK isolation directly; no packet-capture claim is made.
Logs: `/tmp/buddystudy130-revenuecat-isolation-build.log` and
`/tmp/buddystudy130-revenuecat-isolation-tests.log`.

The production processing-failures UI showed exactly two exhausted local
StoreKit receipts before this fix: the reported event and
`0D6F20F3-5D16-4C70-BB65-E0DDE8A22390` (last failure 03:52:38 KST). Neither row
had a linked BuddyStudy user. Both had the explicit local transaction prefix.

Backend regressions passed **85 tests, zero failures/errors/skips**:
RevenueCatBillingService 9, BillingService 37, BillingLedgerPersistenceAdapter
38 and the V122→V123 migration test 1. The latter checks 13 cases on real
disposable MySQL, including genuine numeric Sandbox transactions, unmapped real
transactions, literal underscore/case lookalikes, production, other stores and
providers, and completed receipts. Runtime persistence tests verify duplicate
delivery, exclusion from future claims, and that late failure/ignore operations
cannot revive or overwrite `IGNORED`/`COMPLETED` receipts. The late failure path
does not log a scheduled retry when no retry is due. Log:
`/tmp/buddy-rc-ignore-tests.log` (`BUILD SUCCESSFUL`).

Independent code review found no remaining actionable issues after terminal
state protection was added. `git diff --check` passed.

## Production deployment and readback

Fix commit `9bb8e8a19bb62b1449695c4c7bdd61ac703e465a` was pushed on
`codex/ios-130-testflight`. The module-scoped
[backend image workflow 36090480277](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/36090480277)
and [personal-deploy workflow 36090994321](https://github.com/ghkdqhrbals/personal-deploy/actions/runs/36090994321)
both completed successfully. The deployed image is
`ghcr.io/ghkdqhrbals/buddystudy-backend:9bb8e8a19bb62b1449695c4c7bdd61ac703e465a-jvm`,
manifest digest
`sha256:d7cd08863a38bb3400b8e3558115244ac87b10896921d2b2e7e71cd7c90704bc`.
The handoff correlation is
`36090480277-1-9bb8e8a19bb62b1449695c4c7bdd61ac703e465a-jvm`.
At **12:37:58 KST**, the rollout log reported `state=completed`, `replicas=1/1`
and `expectedTaskRunning=true`. The deploy-time pull digest matches the published
manifest. Log: `/tmp/buddystudy-backend-9bb8e8a-verification/deploy.log`.

At approximately **2026-09-25 12:39 KST**, the authenticated production
[Orders & Billing UI](https://monitoring.lowfidev.cloud/orders.html), with all
failure sources/statuses selected, reported **0 retrying or exhausted records**
and `No billing processing failures match this filter.` Both previously observed
local StoreKit events were absent from the failure list. The invoice count
remained 35. No real purchase, refund, cancellation, quota adjustment, direct
database edit or production SSH command was performed. Runtime endpoint health
checks were not added to or run by GitHub Actions.

TestFlight 1.3.0 (123) remains the distributed
Release binary; the iOS fix is Debug-only and requires no replacement upload.
