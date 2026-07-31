# Codex Production Incident Auto-Fix

## Context

BuddyStudy backend exceptions are collected as one multiline Loki event. Grafana is the only production component that turns `level=ERROR` logs into operational notifications. The backend application does not call Slack or GitHub.

The incident auto-fix path extends that Grafana contact point with an internal webhook integration. Slack remains the human notification surface; the webhook starts a bounded GitHub-hosted diagnosis and can open a Draft PR only after deterministic backend verification succeeds.

## Goals

- Start diagnosis immediately for a new firing backend ERROR alert.
- Give Codex the redacted full-stack Loki context and most recent successful backend deployment SHA.
- Prevent repeat Grafana notifications for one alert instance from creating multiple PRs.
- Keep the OpenAI credential out of the job that writes branches and pull requests.
- Require the complete backend test suite before a Draft PR is opened.
- Never merge or deploy automatically.

## Non-Goals

- Reading Slack history as the automation source.
- Automatically repairing iOS, infrastructure, monitoring, or deployment workflows.
- Mutating production services or data during diagnosis.
- Treating an AI-produced patch as approved for merge.

## Proposed Architecture

```text
backend ERROR
  -> Promtail multiline event
  -> Loki
  -> Grafana alert contact point
       -> Slack integration
       -> HMAC-signed internal webhook
            -> buddystudy-incident-receiver
                 -> Loki context lookup
                 -> latest successful backend deployment lookup
                 -> repository_dispatch(codex-incident-autofix)
                      -> validate/deduplicate
                      -> Codex creates local backend patch
                      -> separate job runs backend tests
                      -> separate write-capable job opens Draft PR
                      -> optional Slack PR notification
```

## Component Responsibilities

### Grafana

- Evaluates the existing backend ERROR alert once per minute.
- Sends the same firing/resolved notification group to Slack and the internal receiver.
- Signs the exact webhook body with HMAC-SHA256 using `timestamp + ":" + body`.
- Includes `X-Grafana-Alerting-Timestamp` so requests older than five minutes are rejected.

### Incident receiver

- Runs as `buddystudy-incident-receiver` on the private `buddystudy-monitoring` Docker network with no host port.
- Accepts only `POST /internal/incidents/grafana` and validates the Grafana signature before parsing JSON.
- Uses `SHA-256(alert fingerprint + startsAt)` as the stable incident ID.
- Atomically creates `/data/<incident-id>.json`; a second delivery of the same alert instance is acknowledged without another dispatch.
- A `DISPATCHING` reservation older than 15 minutes can be reclaimed after a receiver crash; completed incident reservations are retained for 90 days and then pruned on startup.
- Releases the reservation when context collection or GitHub dispatch fails so Grafana retry can recover.
- Queries at most 20 ERROR events beginning five minutes before the alert and caps the combined context at 30 KB.
- Removes bearer tokens, API keys, passwords, cookies, secrets, and token-shaped values before dispatch.
- Reads the latest successful backend deployment from the internal deployment-history API when available.
- Sends one bounded `repository_dispatch` payload to `ghkdqhrbals/buddy-studdy`.

### GitHub Actions

- The workflow must exist on the repository default branch before `repository_dispatch` can trigger it.
- `validate` rejects malformed, non-backend, non-error, empty, or oversized payloads and skips an incident branch that already has a PR.
- `generate_fix` has repository read permission and the dedicated OpenAI secret. Codex may modify only `backend/` and relevant `docs/` files.
- `verify_patch` receives only a patch artifact, applies it to a clean checkout, and runs `backend/gradlew test` on Java 25.
- `open_draft_pr` receives no OpenAI secret. It has the GitHub write permissions needed to push `codex/incident-<id>` and open a labeled Draft PR.
- No job merges, releases, tags, deploys, or calls production services.

## Delivery, Idempotency, and Failure Handling

- Grafana webhook delivery is at-least-once.
- Receiver processing is effectively once per `(Grafana fingerprint, startsAt)` while its persistent reservation exists.
- A crash after GitHub accepts the dispatch can cause another dispatch only if the reservation is lost. GitHub concurrency uses the same incident ID, and the deterministic branch/PR check prevents a second PR.
- Missing Loki context does not start Codex; the reservation is released so a later Grafana notification can retry.
- A failed Loki query or GitHub dispatch returns `502`, releases the reservation, and leaves the Slack alert unaffected.
- A failed Codex run or test run creates no branch or PR. Its GitHub Actions run remains the audit record.

## Required Secrets

Private deploy repository:

- `GRAFANA_INCIDENT_HMAC_SECRET`: random secret shared only by Grafana and the private receiver.
- `CODEX_AUTOFIX_GITHUB_TOKEN`: fine-grained token scoped to `ghkdqhrbals/buddy-studdy` with permission to create repository dispatch events.

BuddyStudy source repository:

- `OPENAI_API_KEY_CODEX_AUTOFIX`: dedicated Codex Action API key; do not reuse backend system or user OpenAI keys.
- `CODEX_AUTOFIX_SLACK_WEBHOOK_URL`: optional webhook used only to announce a successfully created Draft PR.

## Rollout

1. Merge `.github/workflows/codex-incident-autofix.yml` to the default branch first.
2. Add the source-repository OpenAI secret and optional Slack webhook.
3. Add both deploy-repository receiver secrets.
4. Deploy only the Monitoring module.
5. Send a signed synthetic firing payload directly to the private receiver from the monitoring network and use a harmless fixture ERROR log.
6. Confirm one Draft PR is created, the second identical delivery is deduplicated, and no deployment starts.
7. Remove the fixture PR and incident reservation after verification.

Rollback is a Monitoring-only change: remove the webhook receiver from the Grafana contact point and redeploy Monitoring. Slack alerting continues independently.

## Test Plan

- HMAC success, invalid signature, and timestamp replay rejection.
- Credential redaction before GitHub dispatch.
- Atomic duplicate suppression for the same alert instance.
- Resolved-alert and missing-context suppression.
- Monitoring configuration tests for private networking, HMAC, and container hardening.
- Workflow policy tests for read/write permission separation, Draft-only PR creation, allowed paths, and absence of merge/deploy commands.
