# First-month membership discount — 2026-09-13

## Confirmed policy

- Pro (TIER2): first paid month KRW 9,900; thereafter KRW 19,900/month.
- Plus (TIER3): first paid month KRW 19,900; thereafter KRW 39,900/month.
- Existing question and voice allowances remain unchanged.
- One introductory offer per Apple subscription group, based on store eligibility.
  App registration does not itself establish eligibility; switching accounts or
  tiers does not grant another introductory offer.

## Implementation

`app-store/billing/subscriptions.json` describes the Korean regular and introductory
prices. `StudyMateDev.storekit` mirrors those amounts with one P1M paid introductory
period. The membership screen uses store-localized prices and displays both the
first-month amount and subsequent monthly renewal amount before checkout.
StoreKit and RevenueCat eligibility are refreshed after account identification,
including when product metadata comes from the shared cache. Unknown/ineligible
customers see regular pricing. Concurrent outdated loads cannot overwrite the
latest account's result, and purchase is disabled while products load.

Normal StoreKit/RevenueCat purchase processing applies the offer. No synthetic
backend discount, client-supplied payment amount, or manual entitlement is added.

## App Store Connect verification

On 2026-09-13 the authorized Korean pricing changes were submitted through the
App Store Connect API and independently read back:

| Product ID suffix | Regular price | Introductory price | Duration / mode |
| --- | ---: | ---: | --- |
| tier2.monthly | KRW 19,900 | KRW 9,900 | ONE_MONTH × 1 / PAY_AS_YOU_GO |
| tier3.monthly | KRW 39,900 | KRW 19,900 | ONE_MONTH × 1 / PAY_AS_YOU_GO |

Both price schedules and introductory offers begin 2026-09-13. Offers have no
campaign end date: each eligible subscriber receives their own first billing
month discount. Existing subscriber prices are preserved; other territories
were not changed. Both subscriptions remain `READY_TO_SUBMIT`. This configuration
is not an App Store release or review submission, and production purchase
availability remains subject to Apple's normal approval process.

Sources: [Apple introductory subscriptions](https://developer.apple.com/app-store/subscriptions/),
[subscription price management](https://developer.apple.com/documentation/appstoreconnectapi/managing-auto-renewable-subscriptions),
[introductory offer attributes](https://developer.apple.com/documentation/appstoreconnectapi/subscriptionintroductoryoffercreaterequest/data-data.dictionary/attributes-data.dictionary).

## Verification

- Generic iOS Debug build passed (`build/first-month-discount-build.log`).
- Real iPhone 16 Pro: three scoped tests passed in
  `build/first-month-discount-eligibility-device.xcresult`:
  - eligible one-month paid-discount policy; excludes unknown/ineligible, other
    durations, free trials, zero prices, and offers at/above regular price;
  - current regular-price screenshot fixtures;
  - real StoreKitTest products: localized introductory prices, regular prices,
    one-month payment mode, initial eligibility, and group-wide ineligibility
    after a simulated first purchase. Eligibility updates are asynchronous;
    the test waits up to five seconds for StoreKit propagation.
- Host catalog checks confirm exact regular/introductory amounts and duration
  match between the canonical catalog and bundled StoreKit configuration.
- App Store Connect read-back confirms actual current non-preserved prices and
  both offers, including duration, period count, start date, and no end date.
- No real paid test purchase was made.

The first broad device test attempt included existing source-inspection tests
that require Mac filesystem paths; five failed because those files are not on
the iPhone, and the repository-root architecture test skipped. These were not
app behavior failures. The final run targets device-compatible policy and
StoreKit integration tests; the file contract was checked on the host.

StoreKitTest's localized JSON currently sometimes emits introductory priceString
values with grouping commas (e.g. "19,900"); its Decimal accessor may return 19.
The integration test verifies the actual localized customer-facing price and
catalog amount independently, rather than relying on that faulty test-runtime
accessor. The app displays the store's localized price, and verified Apple
transaction amounts remain authoritative for billing.
