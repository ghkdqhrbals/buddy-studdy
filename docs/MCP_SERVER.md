# BuddyStudy MCP Server

## Executive summary

BuddyStudy exposes a private, stateless Model Context Protocol endpoint at
`POST /api/v1/mcp`. An authenticated LLM client can read the current user's
profile, resume, interests, studies, questions, grading feedback, scores, and
topic-level statistics, plus private Voice Tutor quota, session history, and
Tutor Learning Results. A saved study node's learning history reads the same
canonical QUESTION/VOICE_TUTOR records as the Records tab. Publicity and comments
use that same record identity; full voice session evidence stays owner-only. The client can also
update the private learning context, create root studies or child topics, request
questions, submit answers, and delete a confirmed study subtree.

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
- cursor-paged, node-scoped ordinary and voice learning history, with optional
  descendant inclusion and original/localized text views;
- asynchronous question request and process polling;
- asynchronous answer submission and grading polling;
- grading score, feedback, explanation, and rubric details;
- read-only Voice Tutor quota, bounded session history, transcript/result detail,
  and safe optional-recording status metadata;
- topic-first statistics and study-tree growth;
- MCP resources for compact, stable context reads.

Out of scope for this release:

- public or anonymous MCP data access;
- MCP sampling, elicitation, prompts, subscriptions, or server-side sessions;
- direct synchronous OpenAI generation from an MCP request;
- starting, streaming, reconnecting, ending, extending, recording, uploading,
  downloading, or deleting a Voice Tutor session or recording;
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
  -> existing Profile, Study, Record, Question, Grading, Stats, and Voice Tutor read UseCases
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

The HTTP server and local voice bridge explicitly select the SDK's Jackson 3
JSON Schema validator through `McpJsonSchemaValidatorProvider`. Spring AI 2.x
uses NetworkNT's Jackson 3 ABI; selecting the SDK's Jackson 2 validator by
classpath order would fail at runtime. Serialization remains on the existing
application mapper and crosses the validator boundary as JSON-compatible maps.
Input, output and schema validation stay enabled. Invalid-result diagnostics
are replaced with fixed text before the SDK logging wrapper can quote private
arguments or tool results.

## Voice LLM integration

An enabled MCP HTTP endpoint does not itself teach the voice model its tools.
For an authenticated WebRTC call, the backend registers the existing
`list_studies`, `get_study`, `create_study_topic`, `list_records`, `get_record`,
`list_study_learning_records`, `get_voice_learning_record`, `get_topic_stats`,
and `get_study_growth` definitions as nine Realtime function tools.
The server's local `McpVoiceTutorToolAdapter` executes those same MCP handlers
with the captured, revalidated principal and returns a bounded
`function_call_output`; the model receives neither an app bearer nor a new MCP
access token. This local bridge does not enable the external HTTP endpoint or
change its production rollout gate.

- `list_studies` optionally accepts `parent_study_id` to page only the direct
  children of an owned parent. Missing/foreign parents are indistinguishable
  404s, the count and page share the owner/parent/query filter, and omitting the
  argument preserves the existing all-studies behavior. `get_study` returns a
  single node, not a child-topic list.
- The two learning-history tools require the requested node and the call's
  selected node to resolve to the same owned root through their actual current
  parent chains. Each chain is bounded to 32 nodes; missing nodes, unresolved
  parents, cycles, malformed identities, or another root fail closed. A shared
  topic name or a cached lesson snapshot is not proof of ancestry. Single voice
  detail is checked using its returned study ID, not a model-supplied node ID.
  Active call/device authorization is rechecked across suspended reads before
  returning private history. This additional call-tree restriction applies to
  these two tools; general MCP reads remain owner-scoped.
- Child creation requires an explicit learner request and an unambiguous parent
  inside the call's selected study subtree. Schema validation, active identity,
  parent scope and the existing use-case permissions are all enforced before
  the write. Root creation, deletion, question requests, answer submission,
  profile writes and call/recording control are not voice tools.
- Function calls are correlated to a completed response and executed serially
  off the provider receive loop. IDs are registered before execution, output
  acknowledgement gates the spoken continuation, and timeouts never blindly
  retry an uncertain mutation. JSON arguments and results are capped at 16 KiB;
  large reads ask for a smaller page, while large successful creation results
  retain compact verified study-tree metadata.
- Live audio frames and binary recordings are not passed through MCP. Persisted
  transcript-derived questions, answers and feedback are private history tool
  results, not new learner answers or consent to start a lesson. Successful child
  metadata refreshes never replace the active question or answer draft. Logs
  expose counts, fixed error codes and types only, not arguments or result bodies.

See [OpenAI's Realtime tool guidance](https://developers.openai.com/api/docs/guides/realtime-mcp)
for the distinction between server-owned functions and a provider-hosted remote
MCP connection.

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
| `list_records` | Read | `record:read` | Common QUESTION/VOICE_TUTOR `limit`/`offset` page, with canonical IDs and type-specific feedback |
| `get_record` | Read | `record:read` | Either canonical record type; ordinary grading or nullable spoken voice assessment, never fabricated grading |
| `list_study_learning_records` | Read | `study:read`, `record:read`, and `voice-tutor:read` | Owned node's mixed question/voice cursor page; default 5, maximum 30; exact node and original text by default |
| `get_voice_learning_record` | Read | `voice-tutor:read` | One owned persisted voice exchange; positive numeric `voiceRecord.id`, original text by default; never an ordinary question ID |
| `list_voice_tutor_sessions` | Read | `voice-tutor:read` | Owner-scoped opaque-cursor page; returns `sessions` and `nextCursor` without live-session mutation |
| `get_voice_tutor_session` | Read | `voice-tutor:read` | Owned session, bounded transcript turns, private learning result, and safe recording status metadata; never binary audio, object keys, or signed URLs |
| `get_voice_tutor_quota` | Read | `voice-tutor:read` | Server-owned seconds, remaining time, and reset boundary |
| `get_topic_stats` | Read | `stats:read` | Topic-first, bounded statistics |
| `get_study_growth` | Read | `stats:read` | Optional UTC interval |

Tool errors use MCP `isError=true` with structured `code`, HTTP-style `status`,
and a safe message. Business and validation failures are exposed without stack
traces. Unexpected exceptions produce a generic internal error and logs contain
only the operation name and exception type, never tool arguments.

### Learning-history arguments and results

- `list_study_learning_records` requires a positive integer `study_id`.
  `scope` is `node` (default) or `subtree`; `limit` is 1–30 (default 5).
  Omit `cursor` on the first page; subsequent cursors must be the returned
  `nextCursor`, 1–512 characters, for the same owner, study and scope.
- `get_voice_learning_record` requires positive integer `record_id`, taken from
  a returned `voiceRecord.id` and sent as a JSON number. Do not pass the page's
  `voice:123` envelope ID or a `questionRecord.id` to this tool.
- Both accept `language=ko|en|ja` (default `ko`) and
  `view=original|localized` (default `original`). The existing `list_records`
  and `get_record` contracts now read both types with their existing `localized`
  default and canonical `record.id`, not the legacy voice evidence ID.

The mixed page returns `{items, nextCursor, hasMore, limit}`. Each item has
`source=QUESTION|VOICE_TUTOR`, its exact `studyId`, a common `record`, and the
backward-compatible `questionRecord` or `voiceRecord`; envelope IDs are `question:<id>` or
`voice:<id>`. Ordering is timestamp descending, source ascending, then numeric
record ID descending. A deletion during hydration can leave an empty page with
`hasMore=true`; use its next cursor rather than treating it as empty history.

`record.recordType` distinguishes `QUESTION` (optional `gradingResult`) from
`VOICE_TUTOR` (safe `voiceRecord` content with nullable spoken score, feedback,
strengths, improvements and depth). Common record payloads exclude private
session/turn/tree evidence. Legacy voice detail additionally returns `recordId`
to link to this canonical identity while keeping its original `id` unchanged.

Owner-only voice evidence detail preserves the saved topic/level, nullable supported score,
question/answer/feedback text, strengths and improvements, depth summary, and
session/turn evidence. `localized` can enqueue bounded read repair through the
existing translation outbox/stream; unavailable translations fall back to
original text, with `translationPending` describing localization progress.
Translation never rewrites source evidence, node IDs, levels or scores. These reads do not
submit answers, create public/ordinary questions, consume question quota or
replace an active draft.

## Resources

| URI | Content |
| --- | --- |
| `buddystudy://me/context` | Private profile, resume, and interests |
| `buddystudy://studies` | First 200 owned study nodes |
| `buddystudy://records/recent` | 30 recent completed records with grading results |
| `buddystudy://voice-tutor/sessions/recent` | Recent owned Voice Tutor session summaries; use `get_voice_tutor_session` for transcript/result detail |
| `buddystudy://voice-tutor/quota` | Current server-owned Voice Tutor seconds and reset boundary |

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

Voice Tutor history:

```text
get_voice_tutor_quota()
  -> effective TIER2/TIER3 entitlement + current user_voice_quota projection
list_voice_tutor_sessions(limit, cursor?)
  -> bounded owner-scoped {sessions, nextCursor}; no realtime allocation
get_voice_tutor_session(session_id)
  -> owned session + bounded transcript turns + private Tutor Learning Result
```

Study-node learning history:

```text
list_study_learning_records(study_id, scope="node", limit=5, view="original")
  -> {items, nextCursor, hasMore, limit}
  -> either type: get_record(record.id, view="original")
  -> optional owner-only voice evidence: get_voice_learning_record(voiceRecord.id, view="original")
```

Read `subtree` only when descendant history is wanted, and retain each item's
actual node ID. In voice calls, begin with a small exact-node page (the tutor
prompt recommends 3 records) for the agreed lesson focus. Prior answers are
reference evidence, not permission to change the topic, difficulty or learning
state. On `RESULT_TOO_LARGE`, request a smaller page; do not retry an oversized
single record indefinitely or claim that an unavailable read proves no history.

These reads never call the OpenAI Realtime provider, reserve a new session, or
read or mutate an answer draft. The session/quota read use case may settle a
stale/expired session and lazily advance an overdue quota period before returning
the authoritative snapshot. That housekeeping is owner-scoped and does not let
MCP explicitly end, extend, or stream a live session.

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
- Voice Tutor transcript turns, results, consent state, and recording metadata
  and node-linked voice learning exchanges remain private owner-scoped content.
  Tool arguments and returned content are never copied into API exchange logs,
  provider-history bodies, analytics, or error messages. MCP has no tool/resource
  for realtime frames, locally pending files, S3 object keys, upload grants, or
  presigned playback URLs, regardless of whether the owner explicitly recorded
  a call through the iOS app.

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
- Record, study, pending-question, Voice Tutor, and statistics reads are bounded.
- API logs retain request ID, user ID, method, path, status, and duration while
  suppressing MCP bodies.
- Existing Grafana/Loki alerts own runtime outage detection. GitHub Actions
  must not add MCP smoke calls or runtime health gates.

## Rollout and rollback

Before enabling production:

1. Publish and register an immutable KO/EN/JA privacy-policy version that
   explicitly covers optional resume/interests, Voice Tutor transcript/result
   storage, and user-authorized MCP/LLM disclosure; collect any required
   re-agreement.
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
