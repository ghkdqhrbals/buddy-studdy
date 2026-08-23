# External API Call History

## Context

BuddyStudy depends on external providers for AI, translation, identity verification, billing verification, push delivery, email, and Firebase Remote Config. Provider incidents must be diagnosable from the exact logical request and response that the backend observed, even after provider logs expire.

## Goals

- Persist an audit row before every implemented external provider call.
- Complete the same row with the full logical response or terminal error.
- Make history searchable and cursor-paginated in the administrator dashboard.
- Preserve full request and response bodies while removing reusable credentials before they reach storage.

## Non-Goals

- MySQL, Redis, Sentry telemetry, and local filesystem operations are not external API calls.
- The history is operational evidence, not a replay queue and not a billing ledger.
- Provider SDK internals are not replaced solely to expose private transport objects. SDK-backed providers store the complete request and response visible at the BuddyStudy adapter boundary.

## Proposed Architecture

`ExternalApiHistoryRecorder` is the only recording entry point for outbound adapters. It creates a `STARTED` row in `external_api_call_history` before invoking a provider. A successful response becomes `SUCCEEDED`; non-2xx responses become `HTTP_ERROR`; transport/runtime failures become `FAILED`; coroutine cancellation becomes `CANCELLED`.

The current provider coverage is:

- OpenAI chat and embedding calls
- LibreTranslate translation calls
- RevenueCat subscription and transaction reads
- Google and Apple identity-token verification
- Apple StoreKit signed-data verification, including online certificate checks exposed through the SDK boundary
- APNs delivery
- Firebase Remote Config publication
- SMTP verification email delivery
- Administrator-triggered LibreTranslate and OpenAI health probes

## Data Model

`external_api_call_history` owns the immutable request plus terminal response state:

- identity: numeric `id`, UUID `call_id`, optional inbound `correlation_id`
- routing: `provider`, `operation`, HTTP/logical method, request URL
- request: redacted header JSON and full body
- response: status, redacted header JSON, full body, error type/message
- lifecycle: status, start/finish timestamps, duration

Bodies use `LONGTEXT` and are not truncated. Rows are retained indefinitely unless an explicit retention policy is introduced later. Provider/status/id and start-time indexes support operator query patterns without rendering the entire table.

## Security

The recorder removes authentication headers, cookies, passwords, secrets, API keys, identity/access tokens, verification codes, and APNs device-token URL segments before the initial insert or terminal update. Business payloads and model prompts remain intact because they are the evidence the feature is intended to preserve.

## Consistency and Failure Handling

- The request row is written in a new transaction before the network side effect. If that insert fails, the provider is not called.
- Completion is retried three times. If the response cannot be persisted, the operation fails loudly; the existing row remains `STARTED` for incident review.
- A process crash after the provider responds but before the completion update can also leave `STARTED`. Cross-system atomicity is not possible, so `STARTED` is an explicit incomplete-observation state rather than a false success.
- Provider retry libraries may make more than one physical transport attempt inside one logical adapter call. The row records the complete logical request and the final response observed by BuddyStudy.

## Administrator API

- `GET /api/v1/admin/external-api-history`: cursor page with provider, status, and free-text filters. Bodies are deliberately omitted from list rows.
- `GET /api/v1/admin/external-api-history/{id}`: complete redacted request/response record.

Both routes require the existing administrator bearer session. The monitoring `External APIs` page fetches details only when an operator opens a row.

## Rollout and Rollback

Flyway migration `V80__external_api_call_history.sql` creates the additive table. The backend must deploy before the monitoring page. Rolling back the application leaves the table intact, so captured evidence is not destroyed.

## Test Plan

- Recorder tests verify start-before-call, success/failure completion, and credential redaction.
- A source coverage test prevents implemented external provider adapters from bypassing the recorder.
- Backend compilation and provider adapter tests verify constructor and behavior compatibility.
- Monitoring tests verify cursor pagination and on-demand detail loading.
- A MySQL Flyway integration check verifies the production schema.
