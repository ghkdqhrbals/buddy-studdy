# Billing entitlement expiry comparison

## Production evidence

The reported `BillingLifecycleMetricsReporter` event at
`2026-09-22T13:18:50.950Z` contained `entitlementMismatches=1`. Webhook lag,
exhausted reconciliations, stale reservations, negative quota counters,
duplicate active subscriptions, and ownership conflicts were all zero.

The preceding snapshot at `13:13:50.933Z` was healthy. At `13:23:51.172Z`,
the mismatch count returned to zero and the reporter explicitly recorded
`billing_lifecycle_anomaly_recovered`. This recovery happened before this
change. Six later production snapshots from `19:03:59.640Z` through
`19:29:00.729Z` also had all counters zero.

The aggregate log does not identify the affected account or retain its
subscription/projection rows at the time of the incident. Therefore the
historical account-level cause cannot be conclusively attributed to the
query defect below from that log alone.

## Reproduced comparison defect

The reporter compared the stored projection tier directly with an unexpired
subscription's tier. Application access already treats an `APP_STORE`
projection with `ACTIVE` access and `expires_at <= now` as expired. Until a
later projection rebuild, its stored tier can still be paid even though its
effective tier is `TIER1`.

This caused two incorrect results:

- Both the active subscription and projection have expired: effective access
  is free, but the reporter counted a mismatch against the stored paid tier.
- The subscription has renewed but the matching paid projection is still
  expired: effective access is free, but the same stored tier hid the real
  mismatch.

The query now normalizes only expired `APP_STORE`/`ACTIVE` projections to
`TIER1` before comparing them. Valid subscription versus missing/free/wrong
tier projection remains alertable. This change does not mutate billing
records, grant access, or change alert severity/episode handling.

The existing expected-subscription expiry rule is retained. In particular,
`rebuildEntitlementProjection` currently excludes expired `GRACE_PERIOD`
subscriptions. Removing expiry only in the reporter would introduce a
permanent mismatch after that existing writer legitimately writes a free
projection. The difference between manually persisted grace projections and
the rebuild policy needs a separate end-to-end entitlement-policy change;
this monitoring correction does not redefine grace access.

## Independent RevenueCat delivery failure

Production nginx also recorded RevenueCat webhook HTTP 401 responses at
`13:22:15`, `13:24:31`, `13:28:55`, `13:34:46`, and `14:21:29` UTC.
The first two corresponding verifier logs recorded `reason=hmac_mismatch`,
`bodyBytes=1257`, and `signatureAgeSeconds=1`.

The authenticated RevenueCat dashboard confirmed the production integration
is active, receives both Production and Sandbox events, and uses HMAC. The
corresponding Sandbox renewal exhausted six delivery attempts; its last
response was the same `14:21:29 GMT` HTTP 401. A separate active Sandbox-only
development integration also failed, with HTTP 502 on its different host.

The verification adapter matches RevenueCat's documented
[`timestamp.rawBody` HMAC-SHA256 contract](https://www.revenuecat.com/docs/integrations/webhooks#webhook-signature-verification-hmac).
The controller passes the original byte array, and the request log capture
does not consume or reserialize it. A signature age of one second rules out
the five-minute replay limit for these failures. The dashboard does not allow
an existing signing secret to be retrieved; its Authorization header value
is a separate credential and must not be substituted.

The comparison fix does not resolve or prove the cause of these signature
failures. Recovery of the mismatch metric is not evidence of successful
webhook delivery. No signature fallback, key rotation, subscription rewrite,
or webhook retry was performed during the read-only investigation.

## Verification and rollout

The original query failed two of the seven new MySQL scenarios: expired access
was a false positive, and renewal with an expired projection was a false
negative. After the correction, all 103 selected tests passed with no failures,
errors, or skips:

- 44 real MySQL tests: seven lifecycle comparisons and 37 existing billing
  ledger persistence tests, using MySQL 8.0.32 and Flyway schema V120.
- 11 lifecycle reporter tests, including alert episode and collection-failure
  handling.
- 48 application tests: billing status, RevenueCat ingestion, and admin billing.

The grace regression invokes the real ledger reconciliation method and confirms
the resulting free projection remains healthy; it does not merely insert a
synthetic grace projection. The loopback-only MySQL/Redis test instances and
temporary data were stopped and removed after verification.

```sh
cd backend
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-jdk-25.0.2+10.1/Contents/Home \
BUDDYSTUDY_TEST_MYSQL_PORT=33079 REDIS_HOST=127.0.0.1 REDIS_PORT=36379 \
./gradlew :tutor:test --tests '*BillingLifecycleMetricsIntegrationTest' \
  --tests '*BillingLedgerPersistenceAdapterTest' \
  :infra:test --tests '*BillingLifecycleMetricsReporterTest' \
  :application:test --tests '*BillingServiceTest' \
  -x :tutor:processTestAot --console=plain --max-workers=2 \
  -Pkotlin.daemon.jvmargs=-Xmx2g
```

The rollout is scoped to the backend image and the personal-deploy backend
workflow. Image compilation runs on GitHub-hosted runners; no production SSH,
monitoring rollout, or GitHub Actions runtime health probes are used.
