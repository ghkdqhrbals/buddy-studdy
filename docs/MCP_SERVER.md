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

The MCP transport uses Jackson 3 to match Spring AI’s schema-validator dependency; existing application persistence/response mapping remains on Jackson 2. A non-empty tool-list startup test covers this runtime compatibility boundary.

The implementation targets MCP `2025-11-25` through Spring AI `2.0.1` and the
MCP Java SDK `2.0.1` with its Jackson 3 JSON/schema implementation. It uses stateless Streamable HTTP so requests can be
served by any backend task without in-memory session affinity. The current
Java SDK does not claim full support for the newer MCP `2026-07-28` protocol,
so each intended host must be compatibility-tested before production rollout.

The server is disabled by default in every profile with
`MCP_SERVER_ENABLED=false`. Do not enable production until the privacy notice
and the client authorization model described under Rollout are approved.

## End-user ChatGPT OAuth mode

Set both `MCP_SERVER_ENABLED=true` and `MCP_OAUTH_ENABLED=true` to use the
end-user connection. The portable plugin and exact ChatGPT registration settings
are in [plugins/buddystudy](../plugins/buddystudy/README.md). This is an ordinary
user account connection, not an administrator account integration.

- OAuth protected-resource discovery: `/.well-known/oauth-protected-resource`
  and `/.well-known/oauth-protected-resource/api/v1/mcp`.
- Authorization-server discovery: `/.well-known/oauth-authorization-server`.
- `GET /oauth/authorize`: validate the predefined public client, exact redirect,
  response type, resource, scopes, state and S256 PKCE challenge; issue a
  per-request HttpOnly Secure SameSite=Lax browser cookie.
- `/oauth/connect.html`: KO/EN email-code login for an existing ACTIVE account,
  followed by explicit consent to share learning data with the connected ChatGPT.
  No signup, account linking, raw app token input or administrator login occurs.
  Unknown and ambiguous emails cannot sign in. Apple relay addresses require
  configured relay delivery. Request lifetime is 10 minutes, codes have 8 digits
  and at most five attempts; Redis limits email and network request volume.
- `POST /oauth/token`: form-encoded authorization-code + S256 exchange or rotating
  refresh. A provided public client uses `token_endpoint_auth_methods_supported:
  ["none"]`; no client secret, DCR or CIMD is used. `resource` is checked on
  authorization and token requests. Every valid callback includes `iss` and state.
- Access tokens last at most 15 minutes. Refresh authorization has an absolute
  30-day lifetime from the initial consent and does not slide on renewal.
  Without `offline_access`, the connection expires with its access token.
- `POST /oauth/revoke` revokes a connection using its current access/refresh
  credential. `GET /oauth/manage` provides fresh email-code login and a bounded
  active-connection list with immediate disconnection. Management-only browser
  requests cannot authorize a client. Existing app sessions can also use
  `GET /api/v1/me/mcp-connections` and `DELETE /api/v1/me/mcp-connections/{id}`.

Only eight tools are listed: `list_studies`, `get_study`,
`list_pending_questions`, `get_question_process`, `list_records`, `get_record`,
`get_topic_stats`, and `get_study_growth`. Only study and recent-record resources
are listed. Handlers reject OAuth access to all writes, private profile, resume
and interest context even if the caller knows an unlisted tool/resource name.
Existing owner-scoped use cases and permission checks still apply. OAuth tokens
are rejected by ordinary app APIs and app device tokens are rejected by MCP in
OAuth mode. No question quota is consumed by these reads.

Grant/code/access/refresh/browser-secret values are random; only their hashes are
persisted, together with canonical resource, client, owner, scopes, expiry, and
consent version/time. A MySQL row lock serializes redemption, rotation and
revocation; consumed refresh hashes live in a separate FK-cascading table. Reuse
of a redeemed refresh token revokes the whole connection. Expiry/withdrawal is
checked on every token use and account deletion cascades to all OAuth data.
Bounded hourly cleanup deletes expired grants and their replay evidence, including
while the connection feature is disabled. Redis contains only hashed-key rate
counters (one-hour TTL), not OAuth credentials or learning records.

Browser POSTs require the configured exact Origin, a custom intent header and
the per-request cookie. Pages and responses are no-store, frame-protected, and
use a self-only CSP. OAuth bodies, query values, cookies and redirect Locations
are excluded from application logs; backend Nginx uses a path-only access format.
MCP bodies remain suppressed and credential headers are redacted.

The immutable optional disclosure uses consent version `learning-read-2026-10-20`
and has KO/EN/JA copies at `docs/**/chatgpt-connection-2026-10-20.html`. Publish
these with the existing privacy policy before production activation. This
optional disclosure does not replace existing required app terms or silently
agree to them. Disconnecting does not delete already-shared ChatGPT content.
Production rollout, actual SMTP/Apple relay delivery and ChatGPT registration,
callback/refresh compatibility and public directory review are separate steps;
a local plugin package is not a published directory listing.

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
- dynamic client registration (DCR), CIMD and public/anonymous tool access;
- automatic production activation or deployment.

## Architecture

```text
MCP host
  -> POST /api/v1/mcp
  -> RequestLoggingFilter (metadata only; bodies suppressed)
  -> BearerTokenFilter (JWT + active session + device ownership)
  -> WebFluxStatelessServerTransport
  -> McpLoggingServerTransport (one redacted exchange for the final SDK result)
  -> BuddyStudyMcpPort / BuddyStudyMcpAdapter
  -> BuddyStudyMcpUseCase / BuddyStudyMcpService
  -> existing Profile, Study, Record, Question, Grading, Stats, and Voice Tutor read UseCases
  -> existing outbound ports and MySQL/Redis adapters

Private learning context
  -> LearningContextUseCase / LearningContextService
  -> LearningContextPort
  -> user_learning_contexts (one row per users.id, ON DELETE CASCADE)
```

The transport copies the already-verified `Principal` and the server-generated
parent HTTP request ID into
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

The active iOS runtime is `realtime-native-v1`. An enabled MCP HTTP endpoint
alone does not register tools with the voice model. The local
`McpVoiceTutorToolAdapter` reuses owner-scoped MCP handlers for saved studies,
records, statistics, canonical questions and grading, including the read-only
`suggest_study_topics` function. Voice focus uses `select_voice_study` and
`advance_voice_study`. Ordinary writes use `prepare_voice_study_mutation` and
`confirm_voice_study_mutation`; raw create/update/delete tools and the batch
`create_study_topics` tool are not advertised or executable by the native model.
The model receives no app bearer or independent MCP credential. This local
bridge does not enable the external HTTP MCP endpoint.

- Calls are registered from one matching completed provider response before
  execution, then run serially away from provider receive. An exact
  `function_call_output` acknowledgement gates continuation. Ordinary arguments
  and results are bounded to 16 KiB; unknown writes are never retried blindly.
- `list_studies(parent_study_id: ...)` reads direct saved children of one owned
  parent. `get_study` reads one node. `suggest_study_topics` recommends a bounded
  set for an exact saved parent; it neither creates user studies nor spends
  question quota. A root is depth 0, and a saved tree permits four descendant
  levels. Expand chosen branches lazily instead of generating every branch.
- Existing history scope, canonical saved-question/answer grading, active
  draft preservation and current lesson revision checks remain authoritative.
  Recognition and model prose never substitute for a reviewed answer or a
  saved grade. Topic setup never becomes a graded learning record.
- `prepare_voice_study_mutation` stores an immutable current-turn proposal after
  reading the owned target. `confirm_voice_study_mutation` requires the learner's
  reply after the exact prepared confirmation question has played. Changes to
  the proposed target or patch require a new proposal. Discovery, topic creation
  and question generation remain separate operations.

### Structured user input

A client advertises `X-Voice-User-Input-Protocol: user-input-v1` on SDP and control
requests. SDP initially excludes `request_user_input`; only a supported,
authenticated control handshake adds and verifies its definition. Older native
clients keep the ordinary voice catalog and cannot be left waiting for a form
they do not render.

`request_user_input` accepts a title and 1–5 questions. Each question contains an
ASCII ID, prompt, `single`, `multiple` or `text` selection mode, up to eight
ID/label options, and `allowFreeText`. Text mode has no options and allows text.
Mixed selected options and typed text are supported. No defaults are selected,
and all questions require an explicit answer or cancellation. Titles and labels
are at most 200 UTF-16 units, prompts 500, IDs 80, and each answer's typed text
2,000. This tool alone permits 64 KiB arguments/results so five long Korean
answers fit; the provider event envelope remains bounded to 64 KiB.

The server sends `buddystudy.voice.user_input.request` with UUID `requestId`,
`sessionId`, `attemptId`, monotonically increasing `sequence`, title and
questions. The app echoes the three IDs in `.submit` with
`answers: [{questionId, selectedOptionIds, text}]`, or in `.cancel`. Neither
speech nor model output submits the form. Pending input holds microphone turns
and tutor responses independently of explicit call pause. Muted input buffers
are cleared periodically; submission/cancellation waits for its input-clear
acknowledgement before the server sends `.state` with `submitted` or `cancelled`
and completes the exact provider tool. Existing pause/resume clear boundaries
remain separate. A terminal call clears the server form silently, while actual
session ending/ended owns client microphone teardown.

Malformed answers retain the form with `.state` phase `pending` and
`INVALID_ANSWERS`; a recoverable batch failure uses `ACTION_FAILED`. Matching
repeated submissions resend only their terminal receipt. Stale request,
account, session and attempt identities cannot act on another form. Only an
acknowledged explicit human result renews the tool-round budget; the total
session call-ID cap is unchanged. Form text is not written to diagnostic or MCP
exchange logs. It is supplied to the provider as the learner's tool result.

A submitted form also appends one new common USER transcript row through a
private structured-input event. Its reserved `buddystudy-user-input-` item ID
exposes `STRUCTURED_INPUT` provenance, distinct from audio transcription. The
row preserves selected labels and exact typed text, including surrounding
whitespace, using bounded question IDs instead of full prompts so every valid
form fits the 20,000-character transcript limit. Provider input/tutor items
cannot claim the reserved prefix. This source cannot become a graded answer or
learning-evidence answer. Only successful persistence and the exact tool-output
ACK advance the native human boundary, so successive GUI choices can select
different saved topics without another spoken turn. Its absent preceding
spoken tutor prevents a preference form from confirming an ordinary write.
Cancellation creates no row and clears the old learner authority after ACK.
A failed or timed-out evidence write closes the form without focus authority;
any already-completed topic batch is reported and must not be repeated.

For explicit multi-topic creation, the model supplies an optional
`studyTopicProposal: {parentStudyId, topics, difficultyLevel}` instead of an
ordinary preference form. The adapter re-reads the exact owned parent and
freezes 1–8 candidate topics and one level in a private proposal. The server
constructs all displayed confirmation text and option IDs; model-authored form
labels cannot change the action. Submitting that immutable form creates only
selected topics through the internal `create_study_topics` handler. The batch
holds the owner lock, verifies expected parent metadata and depth, and saves
atomically. Existing normalized sibling topics are returned with their actual
unchanged levels. Only verified created IDs emit tree refresh hints, and no
question is generated or charged.

Write proposals bind owner, authentication session, provider call and lesson
revision, expire after at most ten minutes, and are bounded to 256 entries.
Repeating the same selection returns its verified result without another write;
uncertain results can retry only the same frozen selection. Stale/expired
proposals, changed selections, and final tree/depth validation failures close
the form and return an error so the model can prepare a new proposal.
Typed alternative topics are first collected as
preferences, then shown in a new immutable write proposal. Ordinary forms never
implicitly approve a mutation.

## Transport and connection

- Endpoint: `https://api.ghkdqhrbals.org/api/v1/mcp`
- Methods: `POST`; stateless `GET` returns `405`
- Content type: `application/json`
- Required Accept values: `application/json, text/event-stream`
- Authentication: `Authorization: Bearer <dedicated OAuth token>` in OAuth mode; a BuddyStudy app token only in controlled legacy mode.
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
| `update_study` | Write | `study:update` | Owner-scoped topic/level patch; omitted metadata stays unchanged |
| `create_root_study` | Write | `study:create` | Owner-scoped create-only root; default level 5; normalized existing root is returned unchanged; no question quota |
| `create_study_topic` | Write | `study:create` | One selected descendant through depth 4 (root 0); existing deeper nodes preserved; no question quota |
| `suggest_study_topics` | Catalog write | `study:create` | 1–10 candidates for one owned parent, catalog-first with lazy generation; no saved user nodes or question quota |
| `create_study_topics` | Write | `study:create` | Atomic batch of 1–10 explicitly selected direct children; all conflicts checked before saving; same-parent replay preserves settings; no question quota |
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
traces. Unexpected exceptions produce a generic internal error; exception
diagnostics contain only the operation name and exception type. The separate
administrative exchange log retains the redacted request and safe error result.

The legacy application/REST root settings upsert remains available to the iOS
sync flow, but it is intentionally not published as an MCP tool. External AI
clients have exactly one root-creation contract, `create_root_study`, so a new
root request cannot accidentally select an upsert that overwrites scheduling or
prompt settings.

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
question payloads, analytics events, or Sentry attachments. Authenticated MCP
context reads and writes can appear as bounded, redacted administrative
exchange bodies, under the same operator access boundary as REST API logs.

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

Create-only root study:

```text
create_root_study(topic, difficulty_level=5)
  -> authenticate the registered owner and take the owner mutation lock
  -> return the normalized matching owned root unchanged with created=false
     OR create one root with product defaults and created=true
  -> never create a question, consume question quota, or replace existing settings
```

The external HTTP MCP tool performs that create-only operation directly. During
a live voice call, one final persisted, semantically clear learner choice to
begin one named topic as a new saved study invokes it immediately; natural
first-person wording needs neither imperative grammar, literal create/save/root
words, restatement nor contextual agreement. Structured semantic assessment and
an independent semantic attestation bind the exact learner-spoken topic and
effective level to a one-shot server lease, reject different tool arguments and
consume the lease immediately before the write. They do not classify intent with
regex, keyword lists, utterance length, punctuation or sentence completeness.
Generic “yes”, ordinary interest, recommendation requests, tutor suggestions,
checkpoints and newer speech cannot authorize creation. Either `created=true`
or `created=false` remains only a saved-study result. The voice bridge takes the
trusted positive readback ID from that result, schedules an exact server-owned
`get_study` call, and does not schedule spoken acknowledgment until both outputs
are acknowledged and the readback proves the exact root. A missing or mismatched
readback produces only an unverified-outcome message and does not retry the
mutation. Lesson focus still requires the verified read-back root to be spoken as
a new start offer, a new learner agreement and `select_voice_study`. Only a real
new row is eligible for the existing live `buddystudy.voice.study.changed` hint.
Its sanitized wire payload
contains only the event type, positive study ID, server-owned change kind and a
bounded deleted-ID list (empty for creation); it never forwards tool output,
topic text, levels, prompts or credentials. If the call is still valid and the
hint is delivered, iOS fetches that exact node. Normal study sync remains
authoritative, and `created=false` requests no false refresh hint.

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
  HTTP request metadata remains available operationally. A separate MCP
  observer captures parsed, redacted arguments and the final SDK result after
  schema validation, without buffering the raw HTTP stream.
- Delete requires both a destructive tool annotation and server-enforced
  `confirm=true`.
- Page sizes, string lengths, arrays, timestamps, enums, and unknown arguments
  are constrained by JSON Schema and application validation.
- The server never accepts a caller-supplied user ID or token passthrough.
- Voice Tutor transcript turns, results, consent state, and recording metadata
  and node-linked voice learning exchanges remain private owner-scoped content.
  Administrative MCP exchange logs contain bounded, redacted tool arguments and
  returned content; they do not become provider-history bodies, analytics, or
  exception messages. MCP has no tool/resource
  for realtime frames, locally pending files, S3 object keys, upload grants, or
  presigned playback URLs, regardless of whether the owner explicitly recorded
  a call through the iOS app.

## Configuration

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `MCP_SERVER_ENABLED` | `false` | Registers the MCP route and server; explicit opt-in is required in every profile |
| `MCP_ALLOWED_HOSTS` | BuddyStudy production/dev hosts plus loopback | Comma-separated exact or wildcard-port Host allowlist |
| `MCP_ALLOWED_ORIGINS` | empty | Comma-separated browser Origin allowlist; empty rejects supplied origins |
| `MCP_OAUTH_ENABLED` | `false` | Enables OAuth routes/discovery and switches MCP to dedicated read-only tokens |
| `MCP_OAUTH_TRUST_PROXY` | `false` | Trust gateway-overwritten `X-Real-IP` for rate limits; enable only with no direct backend exposure |
| `MCP_OAUTH_ISSUER` | `https://api.ghkdqhrbals.org` | Canonical HTTPS origin; resource is the issuer plus `/api/v1/mcp` |
| `MCP_OAUTH_CLIENT_ID` | `buddystudy-chatgpt` | Predefined public client ID, no client secret |
| `MCP_OAUTH_REDIRECT_URIS` | `https://chatgpt.com/connector_platform_oauth_redirect` | Exact comma-separated callback allowlist |
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
- Administrative API Logs and API Performance include both REST and MCP calls;
  select method `MCP` to restrict results. MCP rows retain a unique request ID,
  trusted user ID, operation/tool/resource, HTTP or voice transport, start/end
  timestamps, duration in milliseconds, error code, and redacted request/response
  bodies. HTTP rows link their parent REST request ID; voice rows link session
  and provider call IDs when available.
- Each body is redacted before a 2,000-character preview cap. Credentials,
  tokens, audio/binary fields, signed URLs, and recording grants are masked;
  unparseable JSON-like text fails closed. Exchange-log failures cannot fail or
  replay a business operation. Internal retries are timed as one logical call;
  cancellation is recorded as status `499` and still propagates.
- HTTP MCP observation surrounds the final SDK response, covering schema
  failures, unknown tools, discovery, and resources. Voice observation surrounds
  execution, explicit answer submission/skip, and `voice/progress` polling.
  Inner shared adapters suppress duplicate exchange logs. Pre-handler HTTP
  rejections remain visible through the existing REST metadata log.
- Dedicated structured `mcp_exchange` logs are emitted for local voice calls as
  well as HTTP MCP. The existing REST `api_exchange` marker, HTTP RPS metrics,
  Grafana dashboards, and outage alert queries retain their original meaning.
- Existing Grafana/Loki alerts own runtime outage detection. GitHub Actions
  must not add MCP smoke calls or runtime health gates.

## Rollout and rollback

Before enabling production:

1. For the read-only ChatGPT connection, publish the immutable KO/EN/JA
   connected-learning disclosure and existing privacy policy. The browser records
   separate optional consent; it must not silently agree to required app terms.
2. Register the provided public client and exact ChatGPT callback, then enable
   `MCP_OAUTH_ENABLED=true` with `MCP_SERVER_ENABLED=true`. Do not distribute
   normal app tokens to third-party hosts.
3. Keep the legacy write-capable mode private. It additionally requires a full
   privacy-policy update covering optional resume/interests, required agreements,
   transaction-safe per-account write budgets and edge rate limits before any
   automated third-party write exposure.
4. Set an exact production Host allowlist and only the browser Origins actually
   required by approved clients.
5. Run host compatibility tests for initialize, tools/list, tools/call,
   resources/list, resources/read, asynchronous polling, and errors.
6. Enable the two flags through the backend-only deployment module.
7. Observe latency, authorization errors, quota errors, and unexpected MCP
   operation failures in existing dashboards without inspecting body content.

Rollback is both `MCP_SERVER_ENABLED=false` and `MCP_OAUTH_ENABLED=false` followed by a backend-only rollout. The
database table can remain safely unused; do not reverse the Flyway migration or
delete user data during an application rollback. If the feature is retired,
provide an explicit user-data export/deletion path before a later additive
migration removes storage.

## Verification

```sh
cd backend
./gradlew --no-daemon :application:test :infra:test :tutor:test
./gradlew --no-daemon :tutor:bootJar :tutor:processAot

# Focused OAuth checks without Docker-dependent tutor test AOT context discovery:
./gradlew --no-daemon :application:test --tests '*McpOAuthServiceTest' \
  :infra:test --tests '*McpOAuth*Test' --tests '*BuddyStudyMcpAdapterTest' --tests '*RequestLoggingFilterTest' \
  :tutor:test --tests '*McpServerConfigTest' --tests '*McpOAuthSecurityTest' -x :tutor:processTestAot
```

The OAuth suite verifies discovery, CSRF/browser-cookie checks, denied consent,
exact callback/resource/client/PKCE checks, one-time code redemption, refresh replay
revocation, expiry/withdrawal, account ownership, app-token separation, hidden and
blocked write/private-context tools, JSON persistence, concurrent R2DBC code
redemption, account FK cascade, and body/header log suppression. H2 exercises
R2DBC transactions; the MySQL Flyway migration still needs MySQL verification.
Browser QA uses a local API fixture and does not prove SMTP or live ChatGPT OAuth.

The targeted suite also covers learning-context normalization and deletion, R2DBC
insert/update/FK cascade, MCP tool contracts and destructive hints, bearer
principal propagation, body-log suppression, transport media/Origin handling,
and the `/api/**` authentication boundary. MySQL Testcontainers verification
requires a running Docker daemon.

## References

- [MCP 2025-11-25 authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- [MCP 2025-11-25 transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [Spring AI MCP server documentation](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [MCP Java SDK 2.0.1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.1)


### Local verification, 2026-10-20

- Full `:application:test`: 372 passed; full `:infra:test`: 200 passed.
- Tutor MCP transport/security selection: 8 passed, including non-empty tool
  registration with the Jackson 3 schema validator.
- `:tutor:bootJar` and `:tutor:processAot`: passed; OAuth controller/model binding
  appears in generated native reachability metadata.
- Final targeted R2DBC persistence checks passed after aligning pending-code
  retention with its actual code expiry.
- `node --check` for the browser script, plugin JSON parse, deployment-template
  YAML parse, and `git diff --check`: passed.
- CUA Playwright/local API fixture: KO email/code/consent, disconnect and empty
  state, EN language switching, default desktop and 390×844 layout verified with
  screenshots and no browser warnings/errors. Browser plugin/skill was absent;
  the available CUA in-app browser's documented Playwright surface was used.
- Docker was unavailable. MySQL Flyway/Testcontainers and tutor test AOT discovery
  were not completed; targeted tutor tests excluded `:tutor:processTestAot`.
- Not verified live: SMTP/Apple relay delivery, production deployment,
  ChatGPT registration/callback/refresh, and public directory acceptance.


### Consent screen revision, 2026-10-21

The browser flow now separates email login, code verification, consent and
connection management. Consent uses a white/black/gray palette, short Korean
labels without middle-dot separators, one account row, three disclosure rows,
and paired cancel/allow actions. Existing connections are fetched only in
management mode, never as a prerequisite for consent. A browser-cookie/Origin-
protected `/oauth/restart` creates a fresh unverified grant for account switching,
preserves the registered callback/PKCE/state, and invalidates the old pending grant.
It does not revoke a previously established connection.

Verification: OAuth application tests (12) and controller tests (8) passed;
`:tutor:bootJar`, script syntax and whitespace checks passed. CUA/IAB local-fixture
QA covered login/code/consent, account switch, KO/EN, management separation and
revoke/empty states at desktop and 390×844 mobile width. No console errors or
layout overflow were observed. Screenshots were compared with the design concept;
monochrome colors, removed mark and simpler copy follow the user's later direction.
Production deployment and live OAuth/SMTP verification remain separate.
