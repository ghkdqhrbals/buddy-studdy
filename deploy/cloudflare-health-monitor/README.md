# BuddyStudy Cloudflare Health Monitor

This Worker checks the public backend health endpoint from outside the
MacBook Air/Kubernetes host and sends Slack alerts when the backend is down.

It replaces GitHub Actions health checks. GitHub Actions is not used for
runtime monitoring.

## Behavior

- Runs every 5 minutes with a Cloudflare Cron Trigger.
- Checks `HEALTHCHECK_URL`.
- Stores state in Workers KV to avoid repeated Slack spam.
- Sends Slack when:
  - the failure threshold is reached,
  - the backend is still down after `ALERT_REPEAT_SECONDS`,
  - the backend recovers after a down state.
- Includes readiness failure details in Slack when the backend returns a JSON
  body with component checks, for example stale scheduler jobs or Redis
  failures.

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

## Manual Smoke Check

After deployment, trigger one immediate check without waiting for the cron:

```sh
curl -X POST https://<worker-host>/check \
  -H "Authorization: Bearer <MANUAL_CHECK_TOKEN>"
```

`POST /check` uses the same state transition and Slack alert path as the cron.
If `MANUAL_CHECK_TOKEN` is not configured, the endpoint returns `401`.

## Configuration

Default vars in `wrangler.jsonc`:

- `HEALTHCHECK_URL`: `https://api.lowfidev.cloud/api/v1/health/readiness`
- `SERVICE_NAME`: `BuddyStudy backend`
- `ENVIRONMENT_NAME`: `production`
- `FAILURE_THRESHOLD`: `2`
- `ALERT_REPEAT_SECONDS`: `3600`

Use Cloudflare Worker vars/secrets for environment-specific overrides.
