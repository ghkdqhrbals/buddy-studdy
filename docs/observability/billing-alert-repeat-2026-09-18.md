# Repeated billing lifecycle notifications

## Evidence

Grafana's production Loki logs for September 17, 2026 19:05–19:55 UTC show
ten `billing_lifecycle_anomaly` ERROR events, one every five minutes. Each
snapshot has `duplicateActiveSubscriptions=1`; webhook lag, entitlement
mismatches, exhausted reconciliation, stale reservations, negative counters,
and ownership conflicts are zero. The log timestamps match the reported Slack
notifications, including 19:07:54.726 and 19:52:54.834 UTC.

The existing investigation in
[the September 17 incident](billing-store-account-overlap-2026-09-17.md)
documents two valid Apple subscription chains. This change does not expire,
refund, cancel, or otherwise alter either subscription.

Two mechanisms produced the notification loop:

- The backend emitted a new ERROR for every unhealthy snapshot, even when the
  same anomaly remained active.
- Grafana's log-event rule treated a log falling out of its query window as a
  resolved alert. The Slack template rendered that as `[해결]`, although no
  successful billing recovery had been observed.

Local commit `2fa9033f` already corrected both behaviors, but was absent from
the deployed backend source `8a645a54` and the monitoring deployment. The fix
was reapplied on the production billing branch so existing deployed purchase
validation and runtime changes remain included.

## Behavior

Each anomaly kind emits ERROR when it first appears, then WARN while it
persists. A successful snapshot clears recovered kinds; a recurrence or a new
kind emits a new ERROR. New ownership conflicts remain independently
alertable. Collection failures follow the same first-error/subsequent-warning
pattern, and failed or cancelled collection never implies recovery. The
in-memory episode state resets on process restart, producing one fresh alert
for an existing condition.

Slack log-event delivery disables resolved messages and includes only firing
alerts, including mixed groups. Conditions requiring recovery notifications
must use a separate condition-based alert. Backend and monitoring rollouts
remain separate, and runtime checks are not added to GitHub Actions.

## Verification

- Billing lifecycle reporter tests: 11 passed with Java 25. A fresh-worktree
  compile required a 3 GiB Kotlin daemon heap and two Gradle workers.
- Monitoring dashboard build and tests: 147 passed.
- Incident receiver tests: 6 passed.
- Two stale dashboard test assertions also failed at the production branch
  baseline. They now enforce the existing separation between backend Swarm
  rollout and monitoring lifecycle, retaining the Loki error and Slack checks.

## Deployment

- Source: `bfd1c9a81b7e209dafdc030c8e3df1f5571a18f2`, branch
  `fix/billing-alert-repeat-20260918`.
- [Monitoring validation and dispatch](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/35336223423)
  and [monitoring rollout](https://github.com/ghkdqhrbals/personal-deploy/actions/runs/35336261359)
  succeeded. After refreshing the production Grafana contact-point view, the
  Slack receiver's `Disable resolved message` option was checked; before the
  rollout it was unchecked. No test Slack message was sent.
- [Backend image build](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/35336264572)
  succeeded on a GitHub-hosted runner and published the JVM image digest
  `sha256:b52bdc96caa59342429ff6a7176ec92cbcbd43e42dcdb5e6d8389bf86ced1df7`.
- [Backend rollout](https://github.com/ghkdqhrbals/personal-deploy/actions/runs/35414148040)
  succeeded on September 19 using that immutable image digest and the same
  backend-only workflow revision `9f49905e6ea3a1b7a788d4d3287874d51b592d26`
  used by the previous production rollout. Monitoring deployment completed
  separately. The workflow did not run runtime health probes.

On September 19, before the backend rollout, the 01:42:59, 01:47:59, and
01:52:59 UTC production snapshots were all INFO with all seven anomaly
counters zero, including `duplicateActiveSubscriptions=0`. The underlying
overlap cleared independently of this deployment. Runtime observation of these
healthy snapshots does not replace the unit test of sustained-anomaly
suppression.

After the successful backend rollout, Grafana showed the 01:58:37.414 UTC
(10:58:37 KST) billing snapshot at INFO with all seven counters still zero.
The latest 15-minute query for `BillingLifecycleMetricsReporter` contained
only INFO snapshots and no ERROR events. Sustained-anomaly deduplication is
verified by the 11 reporter tests; no production anomaly was introduced for
testing.
