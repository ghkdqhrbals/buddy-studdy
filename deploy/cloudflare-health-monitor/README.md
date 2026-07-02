# BuddyStudy Cloudflare Health Monitor

This Worker checks the public backend health endpoint from outside the
MacBook Air/Kubernetes host and sends Slack alerts when the backend is down.

It replaces GitHub Actions health checks. GitHub Actions is not used for
runtime monitoring.

## Behavior

- Runs every minute with a Cloudflare Cron Trigger.
- Checks `HEALTHCHECK_URL`.
- Stores state in Workers KV to avoid repeated Slack spam.
- Sends Slack when:
  - the failure threshold is reached,
  - the backend is still down after `ALERT_REPEAT_SECONDS`,
  - the backend recovers after a down state.
- Bounds each health request with `HEALTHCHECK_TIMEOUT_MS`, so a hanging
  connection is treated as a failure instead of delaying the monitor.
- Bounds each Slack webhook request with `SLACK_TIMEOUT_MS`, so a slow Slack
  endpoint does not hold the monitor run indefinitely.
- Includes readiness failure details in Slack when the backend returns a JSON
  body with component checks, for example stale scheduler jobs or Redis
  failures.
- Slack alerts include `Down since`, `Last up`, and outage `Duration` so
  recovery messages show how long the service was unavailable.
- `GET /` validates required runtime configuration and returns missing bindings
  such as `HEALTH_MONITOR_STATE` or `SLACK_WEBHOOK_URL` before a silent monitor
  failure can happen.

## Setup

Create KV:

```sh
cd deploy/cloudflare-health-monitor
npm install
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

Set a manual check token for authenticated smoke tests:

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

Optional GitHub Actions secrets for a post-deploy smoke check:

- `HEALTH_MONITOR_URL`
- `HEALTH_MONITOR_MANUAL_CHECK_TOKEN`

The smoke check calls `POST /check` once after deployment. It is not a
recurring health check; recurring checks are still Cloudflare Cron.

## Manual Smoke Check

After deployment, trigger one immediate check without waiting for the cron:

```sh
HEALTH_MONITOR_URL=https://<worker-host> \
MANUAL_CHECK_TOKEN=<MANUAL_CHECK_TOKEN> \
npm run smoke
```

`POST /check` uses the same state transition and Slack alert path as the cron.
If `MANUAL_CHECK_TOKEN` is not configured, the endpoint returns `401`.
If the backend is intentionally down during the smoke test, set
`ALLOW_DOWN=true` to verify the Worker path without failing the command.

## Configuration

Default vars in `wrangler.jsonc`:

- `HEALTHCHECK_URL`: `https://api.lowfidev.cloud/api/v1/health/readiness`
- `SERVICE_NAME`: `BuddyStudy backend`
- `ENVIRONMENT_NAME`: `production`
- `FAILURE_THRESHOLD`: `2`
- `ALERT_REPEAT_SECONDS`: `3600`
- `HEALTHCHECK_TIMEOUT_MS`: `8000`
- `SLACK_TIMEOUT_MS`: `5000`

With the default 1-minute cron and threshold `2`, a real outage usually alerts
after about 1-2 minutes while still filtering out a single transient failure.

Use Cloudflare Worker vars/secrets for environment-specific overrides.
