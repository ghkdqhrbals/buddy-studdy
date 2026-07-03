# BuddyStudy Cloudflare Health Monitor

This Worker checks the public backend health endpoint from outside the
MacBook Air/Kubernetes host and sends Slack alerts when the backend is down.

It replaces GitHub Actions health checks. GitHub Actions is not used for
runtime monitoring.

## Behavior

- Runs every minute with exactly one Cloudflare Cron Trigger, preventing
  duplicate checks and duplicate Slack alerts.
- Exposes the Worker on the workers.dev host so operators can inspect `GET /`
  and, when needed, manually trigger `POST /check`.
- Checks `HEALTHCHECK_URL`.
- Requires `HEALTHCHECK_URL` to use `/api/v1/health/readiness` at runtime, so
  scheduler freshness cannot be bypassed by accidentally using lightweight
  `/health`.
- Requires HTTPS and `api.ghkdqhrbals.org` in production, so the monitor cannot
  silently watch an insecure or development endpoint.
- Treats a JSON readiness body with `ok:false` as unhealthy even if an
  upstream layer incorrectly returns HTTP 200.
- Treats HTTP 200 non-JSON readiness responses, empty bodies, and JSON bodies
  without `ok:true` as unhealthy, so an Nginx or routing mistake cannot look
  like a healthy backend.
- Stores state in Workers KV to avoid repeated Slack spam.
- Sends Slack when:
  - the failure threshold is reached,
  - the backend is still down after `ALERT_REPEAT_SECONDS`,
  - the backend recovers after a down state.
- Bounds each health request with `HEALTHCHECK_TIMEOUT_MS`, so a hanging
  connection is treated as a failure instead of delaying the monitor.
- Bounds each Slack webhook request with `SLACK_TIMEOUT_MS`, so a slow Slack
  endpoint does not hold the monitor run indefinitely.
- Falls back to safe runtime defaults if numeric alert vars are malformed, so a
  bad var cannot disable outage alerts.
- Includes readiness failure details in Slack when the backend returns a JSON
  body with component checks, for example stale scheduler jobs or Redis
  failures.
- Slack alerts include `Down since`, `Last up`, and outage `Duration` so
  recovery messages show how long the service was unavailable.
- Slack alerts include `OBSERVABILITY_URL` when configured, so operators can
  open Grafana/Loki from the alert.
- Slack alerts include action buttons for the readiness endpoint and, when
  configured, observability.
- `GET /` validates required runtime configuration and returns missing bindings
  such as `HEALTH_MONITOR_STATE` or `SLACK_WEBHOOK_URL` before a silent monitor
  failure can happen.

## Setup

Create KV:

```sh
cd deploy/cloudflare-health-monitor
npm ci
npx wrangler kv namespace create HEALTH_MONITOR_STATE
```

Write the returned KV namespace id into `wrangler.jsonc`:

```sh
npm run configure:kv -- <namespace_id>
```

Set Slack secret:

```sh
npx wrangler secret put SLACK_WEBHOOK_URL
```

Set a manual check token for authenticated operator checks:

```sh
npx wrangler secret put MANUAL_CHECK_TOKEN
```

Run local tests:

```sh
npm test
```

Validate config and Cloudflare Worker bundle before deploying:

```sh
npm run check
```

Deploy:

```sh
npm run deploy
```

Or deploy from GitHub Actions with **Deploy Health Monitor Worker**. That
workflow only tests and deploys this Worker. It does not perform runtime health
checks. Runtime checks still run from the Cloudflare Cron Trigger.

Required GitHub Actions secrets for that deployment workflow:

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`
- `HEALTH_MONITOR_KV_NAMESPACE_ID`
- `HEALTH_MONITOR_SLACK_WEBHOOK_URL`
- `HEALTH_MONITOR_MANUAL_CHECK_TOKEN`

The workflow syncs `HEALTH_MONITOR_SLACK_WEBHOOK_URL` and
`HEALTH_MONITOR_MANUAL_CHECK_TOKEN` into Cloudflare Worker secrets as
`SLACK_WEBHOOK_URL` and `MANUAL_CHECK_TOKEN`.
It does not call the deployed Worker for health checks. Runtime checks are
owned by Cloudflare Cron.

## Post-deploy Verification

After deploying, verify the monitor without using GitHub Actions as a runtime
health checker:

1. Open `GET https://<worker-host>/` and confirm it returns JSON with
   `ok:true` or a clear stored monitor state. Missing configuration such as
   `SLACK_WEBHOOK_URL`, `HEALTHCHECK_URL`, or `HEALTH_MONITOR_STATE` must be
   fixed before relying on alerts.
2. Run one explicit operator check:

   ```sh
   HEALTH_MONITOR_URL=https://<worker-host> \
   MANUAL_CHECK_TOKEN=<MANUAL_CHECK_TOKEN> \
   npm run manual:check
   ```

3. If the backend is intentionally down while validating the alert path, run
   the same command with `ALLOW_DOWN=true`. The command should still reach the
   Worker and print the monitor state, while Slack delivery follows the same
   transition path as the Cron Trigger.
4. Wait for the Cloudflare Cron Trigger to run at least once, then open
   `GET https://<worker-host>/` again and confirm `checkedAt` has advanced.

## Manual Operator Check

After deployment, an operator can trigger one immediate check without waiting
for the cron:

```sh
HEALTH_MONITOR_URL=https://<worker-host> \
MANUAL_CHECK_TOKEN=<MANUAL_CHECK_TOKEN> \
npm run manual:check
```

`POST /check` uses the same state transition and Slack alert path as the cron.
If `MANUAL_CHECK_TOKEN` is not configured, the endpoint returns `401`.
If the backend is intentionally down during the manual check, set
`ALLOW_DOWN=true` to verify the Worker path without failing the command.

Do not run this from GitHub Actions. GitHub Actions deploys the Worker only;
runtime health checks and Slack alerts are owned by Cloudflare Cron.

## Configuration

Default vars in `wrangler.jsonc`:

- `HEALTHCHECK_URL`: `https://api.ghkdqhrbals.org/api/v1/health/readiness`
- `SERVICE_NAME`: `BuddyStudy backend`
- `ENVIRONMENT_NAME`: `production`
- `FAILURE_THRESHOLD`: `2`
- `ALERT_REPEAT_SECONDS`: `3600` (`300` to `86400`)
- `STATUS_STALE_AFTER_SECONDS`: `180` (`60` to `3600`)
- `HEALTHCHECK_TIMEOUT_MS`: `8000` (`1000` to `25000`)
- `SLACK_TIMEOUT_MS`: `5000` (`1000` to `15000`)
- `OBSERVABILITY_URL`: optional HTTPS Grafana/Loki entrypoint linked from
  Slack alerts

With the required single 1-minute cron and threshold `2`, a real outage usually alerts
after about 1-2 minutes while still filtering out a single transient failure.

If the Worker Cron itself stops running, the Worker cannot send a new Slack
alert because the monitor execution path is no longer being invoked. In that
case `GET /` reports the stored state as `stale` after
`STATUS_STALE_AFTER_SECONDS`; use that status page and Cloudflare Worker
observability to diagnose monitor execution issues.

Use Cloudflare Worker vars/secrets for environment-specific overrides.
