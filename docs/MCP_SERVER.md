# BuddyStudy MCP Server

## Executive summary

BuddyStudy exposes a private, stateless Model Context Protocol endpoint at
`POST /api/v1/mcp`. An authenticated LLM client can read the current user's
profile, resume, interests, studies, questions, grading feedback, scores, and
topic-level statistics. It can also update the private learning context, create
root studies or child topics, request questions, submit answers, and delete a
confirmed study subtree.

The implementation targets MCP `2025-11-25` through Spring AI `2.0.1` and the
MCP Java SDK `2.0.1`. It uses stateless Streamable HTTP so requests can be
served by any backend task without in-memory session affinity. The current
Java SDK does not claim full support for the newer MCP `2026-07-28` protocol,
so each intended host must be compatibility-tested before production rollout.

The server is disabled by default in every profile with
`MCP_SERVER_ENABLED=false`. Do not enable production until the privacy notice
and the client authorization model described under Rollout are approved.

## Scope and non-goals

In scope:

- private profile, resume Markdown, and interests;
- owned study-tree reads, root creation, child-topic creation, and confirmed
  subtree deletion;
- bounded pending-question and record reads;
- asynchronous question request and process polling;
- asynchronous answer submission and grading polling;
- grading score, feedback, explanation, and rubric details;
- topic-first statistics and study-tree growth;
- MCP resources for compact, stable context reads.

Out of scope for this release:

- public or anonymous MCP data access;
- MCP sampling, elicitation, prompts, subscriptions, or server-side sessions;
- direct synchronous OpenAI generation from an MCP request;
- a second record store or direct `UserDefaults`/client persistence path;
- MCP OAuth 2.1 discovery, dynamic client registration, or scoped MCP tokens;
- automatic production activation or deployment.

## Architecture

```text
MCP host
  -> POST /api/v1/mcp
  -> RequestLoggingFilter (metadata only; bodies suppressed)
  -> BearerTokenFilter (JWT + active session + device ownership)
  -> WebFluxStatelessServerTransport
  -> BuddyStudyMcpPort / BuddyStudyMcpAdapter
  -> BuddyStudyMcpUseCase / BuddyStudyMcpService
  -> existing Profile, Study, Record, Question, Grading, and Stats UseCases
  -> existing outbound ports and MySQL/Redis adapters

Private learning context
  -> LearningContextUseCase / LearningContextService
  -> LearningContextPort
  -> user_learning_contexts (one row per users.id, ON DELETE CASCADE)
```

The transport copies only the already-verified `Principal` into
`McpTransportContext`. It never copies the Authorization header or raw bearer
token. Tool arguments never include `userId`; every domain read and write is
scoped by the authenticated principal. The MCP composition service has an
explicit `@RequirePermission` boundary on every public operation, including
operations whose legacy controller was the only previous permission boundary.

## Transport and connection

- Endpoint: `https://api.ghkdqhrbals.org/api/v1/mcp`
- Methods: `POST`; stateless `GET` returns `405`
- Content type: `application/json`
- Required Accept values: `application/json, text/event-stream`
- Authentication: `Authorization: Bearer <BuddyStudy access token>`
- Protocol compatibility: `2025-11-25`
- Sessions: none

Example initialization:

```http
POST /api/v1/mcp HTTP/1.1
Host: api.ghkdqhrbals.org
Authorization: Bearer <access-token>
Content-Type: application/json
Accept: application/json, text/event-stream

{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"example-host","version":"1.0.0"}}}
```

The normal BuddyStudy token is device/session-bound and currently valid for up
to 90 days. It is suitable for controlled development connections, but it is
not an OAuth 2.1 MCP access token and is broader than a third-party LLM should
receive. Never put it in prompts, logs, repository files, or browser code.

## Tools

| Tool | Effect | Permission | Important contract |
| --- | --- | --- | --- |
| `get_my_context` | Read | `profile:read` | Private profile, resume, interests |
| `update_my_learning_context` | Write | `profile:update` | Omitted fields are preserved; empty values clear |
| `list_studies` | Read | `study:read` | Bounded `limit`/`offset` page |
| `get_study` | Read | `study:read` | Owned node plus pending/latest question |
| `create_study` | Write | `study:create` | Root only; consumes no question quota |
| `create_study_topic` | Write | `study:create` | Descendant only; consumes no question quota |
| `delete_study` | Destructive | `study:delete` | Requires `confirm=true`; deletes descendants |
| `list_pending_questions` | Read | `record:read` | Bounded active-question page |
| `request_question` | Write | `question:create` | Requires stable `idempotency_key`; returns correlation ID |
| `get_question_process` | Read | `record:read` | Poll until `terminal=true`; no remaining question quota required |
| `submit_answer` | Write | `record:update` | Preserves the authored answer; queues grading |
| `get_grading_process` | Read | `record:update` | Cursor uses `after_event_id`; poll until terminal |
| `list_records` | Read | `record:read` | Bounded page with score/feedback when ready |
| `get_record` | Read | `record:read` | Full score, feedback, explanation, and rubric |
| `get_topic_stats` | Read | `stats:read` | Topic-first, bounded statistics |
| `get_study_growth` | Read | `stats:read` | Optional UTC interval |

Tool errors use MCP `isError=true` with structured `code`, HTTP-style `status`,
and a safe message. Business and validation failures are exposed without stack
traces. Unexpected exceptions produce a generic internal error and logs contain
only the operation name and exception type, never tool arguments.

## Resources

| URI | Content |
| --- | --- |
| `buddystudy://me/context` | Private profile, resume, and interests |
| `buddystudy://studies` | First 200 owned study nodes |
| `buddystudy://records/recent` | 30 recent completed records with grading results |

Resources reuse the same authenticated use cases and permission checks as
tools. They are snapshots, not subscriptions.

## Data model and lifecycle

`user_learning_contexts` is separate from the public/community profile:

| Column | Contract |
| --- | --- |
| `user_id` | Primary key and FK to `users.id`; delete cascades |
| `resume_markdown` | Optional Markdown, at most 50,000 characters |
| `interests_json` | JSON array; at most 50 unique entries, 100 characters each |
| `created_at`, `updated_at` | UTC persistence timestamps |

Interest values are trimmed, internal whitespace is normalized, and duplicate
values are removed case-insensitively while preserving first-seen spelling.
Patching `null`/an omitted field preserves it. Blank resume text or an empty
interest list clears that field. When both fields are empty the row is deleted.
Account deletion removes the row through the database foreign-key cascade.

Resume and interest data is never added to `UserProfileResponse`, public
question payloads, analytics events, Sentry attachments, or API body logs.

## Core flows

Question generation:

```text
request_question(study_id, idempotency_key)
  -> existing quota reservation + Saga + transactional Outbox
  -> correlationId returned immediately
  -> get_question_process(correlationId)
  -> generated question returned when terminal=true
```

Answer grading:

```text
submit_answer(record_id, authored_answer)
  -> immutable answer persistence + grading Outbox
  -> grading correlationId returned
  -> get_grading_process(correlationId, after_event_id)
  -> get_record(record_id) for final score and feedback
```

Study deletion:

```text
explicit user confirmation
  -> delete_study(study_id, confirm=true)
  -> owned node and descendants deleted
  -> existing question records retained with study_id = NULL
```

## Security and privacy controls

- `/api/v1/mcp` is covered by the existing `/api/**` authenticated boundary.
- The bearer filter verifies signature, expiration, active `user_devices`
  session, and device match before exposing the principal.
- Anonymous device accounts are rejected by the MCP use case.
- Every operation is owner-scoped and has an explicit permission annotation.
- The transport validates `Host` against `MCP_ALLOWED_HOSTS` and rejects any
  supplied `Origin` unless it is in `MCP_ALLOWED_ORIGINS`.
- Request and response bodies for `/api/v1/mcp` and route-equivalent
  matrix-parameter variants are never captured by the API exchange logger.
  Request metadata remains available operationally.
- Delete requires both a destructive tool annotation and server-enforced
  `confirm=true`.
- Page sizes, string lengths, arrays, timestamps, enums, and unknown arguments
  are constrained by JSON Schema and application validation.
- The server never accepts a caller-supplied user ID or token passthrough.

## Configuration

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `MCP_SERVER_ENABLED` | `false` | Registers the MCP route and server; explicit opt-in is required in every profile |
| `MCP_ALLOWED_HOSTS` | BuddyStudy production/dev hosts plus loopback | Comma-separated exact or wildcard-port Host allowlist |
| `MCP_ALLOWED_ORIGINS` | empty | Comma-separated browser Origin allowlist; empty rejects supplied origins |
| `MCP_REQUEST_TIMEOUT_SECONDS` | `30` | Tool/resource timeout, clamped to 5–120 seconds |

At least one allowed host is required when the server is enabled. Reverse
proxies must preserve the public Host header. No new port, container, Redis
Stream, or Nginx location is required because the route uses the existing
backend HTTPS origin.

## Capacity, reliability, and observability

- Stateless transport has no MCP session allocation, sticky routing, or
  per-task session state.
- Existing R2DBC pool limits and domain transaction boundaries apply.
- Long-running AI work remains in the existing Redis Stream/Outbox workers;
  MCP calls enqueue and poll instead of holding an HTTP connection.
- Record, study, pending-question, and statistics reads are bounded.
- API logs retain request ID, user ID, method, path, status, and duration while
  suppressing MCP bodies.
- Existing Grafana/Loki alerts own runtime outage detection. GitHub Actions
  must not add MCP smoke calls or runtime health gates.

## Rollout and rollback

Before enabling production:

1. Publish and register an immutable KO/EN/JA privacy-policy version that
   explicitly covers optional resume/interests and user-authorized MCP/LLM
   disclosure; collect any required re-agreement.
2. Prefer a short-lived, revocable, audience-bound MCP token with read/write
   scopes and OAuth 2.1 protected-resource metadata over the normal app token.
3. Define and enforce transaction-safe per-account study-tree write budgets,
   plus edge rate limits, before exposing create/delete tools to automated hosts.
4. Set an exact production Host allowlist and only the browser Origins actually
   required by approved clients.
5. Run host compatibility tests for initialize, tools/list, tools/call,
   resources/list, resources/read, asynchronous polling, and errors.
6. Enable `MCP_SERVER_ENABLED=true` through the backend deployment module.
7. Observe latency, authorization errors, quota errors, and unexpected MCP
   operation failures in existing dashboards without inspecting body content.

Rollback is `MCP_SERVER_ENABLED=false` followed by a backend-only rollout. The
database table can remain safely unused; do not reverse the Flyway migration or
delete user data during an application rollback. If the feature is retired,
provide an explicit user-data export/deletion path before a later additive
migration removes storage.

## Verification

```sh
cd backend
./gradlew --no-daemon :application:test :infra:test :tutor:test
./gradlew --no-daemon :tutor:bootJar :tutor:processAot
```

The targeted suite covers learning-context normalization and deletion, R2DBC
insert/update/FK cascade, MCP tool contracts and destructive hints, bearer
principal propagation, body-log suppression, transport media/Origin handling,
and the `/api/**` authentication boundary. MySQL Testcontainers verification
requires a running Docker daemon.

## References

- [MCP 2025-11-25 authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- [MCP 2025-11-25 transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [Spring AI MCP server documentation](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [MCP Java SDK 2.0.1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.1)
