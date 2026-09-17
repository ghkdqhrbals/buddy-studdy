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
