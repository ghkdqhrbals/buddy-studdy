# Redis Stream Operations

## Ownership

`RedisStreamTopicManager` is the single runtime boundary for Redis Stream topics.
Publishers and listeners must not construct stream keys, execute `XADD`, create
consumer groups, poll records, acknowledge records, or inspect stream metadata
directly.

Topics are registered once through:

- `RedisStreamTopic`
- `RedisStreamTopicDefinition`
- `RedisStreamTopicManager`

Adding a topic therefore requires one enum value and one manager definition.
Annotated consumers own their group, consumer name, concurrency, batch count,
blocking timeout, and completion policy.

`StreamOptions` controls what happens only after a handler succeeds:

- `NONE`: leave the message pending for explicit handling.
- `ACK`: execute `XACK` and retain the stream entry.
- `ACK_DEL`: execute `XACK` and `XDEL` atomically in one Redis Lua script request.

Handler or Jackson conversion failures never ACK or delete the message. The
push listener and its idle-message recovery scheduler use `ACK` so successful
push entries remain available in the bounded stream for operational
inspection. The durable `question_push_outbox` remains the recovery source.

Each active physical stream contains exactly one event contract. Active
consumers therefore do not encounter unrelated event types. `eventType`
remains in the envelope as a schema guard, while the physical stream is
selected from the central event catalog. During migration only, consumers
attached to the former mixed stream acknowledge event types owned by another
consumer group so every legacy group can advance independently.

## Naming And Catalog

Every active stream key follows:

```text
<business-domain>.<data-type>.<event-type>.<version>
```

The catalog is:

| Business domain | Data type | Event type | Stream key | Envelope eventType |
| --- | --- | --- | --- | --- |
| notification | message | requested | `notification.message.requested.v1` | `NOTIFICATION_REQUESTED` |
| identity | account | withdrawn | `identity.account.withdrawn.v1` | `ACCOUNT_WITHDRAWN` |
| study | answer-grading | requested | `study.answer-grading.requested.v1` | `ANSWER_GRADING_REQUESTED` |
| study | question-generation | requested | `study.question-generation.requested.v1` | `QUESTION_GENERATION_REQUESTED` |
| study | question | generated | `study.question.generated.v1` | `QUESTION_GENERATED` |
| localization | content-translation | requested | `localization.content-translation.requested.v1` | `CONTENT_TRANSLATION_REQUESTED` |
| notification | question-push | requested | `notification.question-push.requested.v1` | `QUESTION_PUSH_REQUESTED` |
| community | question | viewed | `community.question.viewed.v1` | `CONTENT_VIEWED` |

`RedisDomainEventPublisher` has an exhaustive `RedisOutboxEventType` mapping.
Adding an outbox event without assigning a dedicated stream is therefore a
compile-time error.

## Retention

Every managed stream uses an independently configurable exact `MAXLEN`. The
eight active defaults are `1000`:

- `notification.message.requested.v1`
- `identity.account.withdrawn.v1`
- `study.answer-grading.requested.v1`
- `study.question-generation.requested.v1`
- `study.question.generated.v1`
- `localization.content-translation.requested.v1`
- `notification.question-push.requested.v1`
- `community.question.viewed.v1`

```yaml
buddystudy:
  streams:
    notification-requested-key: ${BUDDYSTUDY_NOTIFICATION_REQUESTED_STREAM_KEY:notification.message.requested.v1}
    account-withdrawn-key: ${BUDDYSTUDY_ACCOUNT_WITHDRAWN_STREAM_KEY:identity.account.withdrawn.v1}
    answer-grading-requested-key: ${BUDDYSTUDY_ANSWER_GRADING_REQUESTED_STREAM_KEY:study.answer-grading.requested.v1}
    question-generation-requested-key: ${BUDDYSTUDY_QUESTION_GENERATION_REQUESTED_STREAM_KEY:study.question-generation.requested.v1}
    question-generated-key: ${BUDDYSTUDY_QUESTION_GENERATED_STREAM_KEY:study.question.generated.v1}
    content-translation-requested-key: ${BUDDYSTUDY_CONTENT_TRANSLATION_REQUESTED_STREAM_KEY:localization.content-translation.requested.v1}
    question-push-requested-key: ${BUDDYSTUDY_QUESTION_PUSH_REQUESTED_STREAM_KEY:notification.question-push.requested.v1}
    question-viewed-key: ${BUDDYSTUDY_QUESTION_VIEWED_STREAM_KEY:community.question.viewed.v1}
    notification-requested-max-len: ${BUDDYSTUDY_NOTIFICATION_REQUESTED_STREAM_MAX_LEN:1000}
    account-withdrawn-max-len: ${BUDDYSTUDY_ACCOUNT_WITHDRAWN_STREAM_MAX_LEN:1000}
    answer-grading-requested-max-len: ${BUDDYSTUDY_ANSWER_GRADING_REQUESTED_STREAM_MAX_LEN:1000}
    question-generation-requested-max-len: ${BUDDYSTUDY_QUESTION_GENERATION_REQUESTED_STREAM_MAX_LEN:1000}
    question-generated-max-len: ${BUDDYSTUDY_QUESTION_GENERATED_STREAM_MAX_LEN:1000}
    content-translation-requested-max-len: ${BUDDYSTUDY_CONTENT_TRANSLATION_REQUESTED_STREAM_MAX_LEN:1000}
    question-push-requested-max-len: ${BUDDYSTUDY_QUESTION_PUSH_REQUESTED_STREAM_MAX_LEN:1000}
    question-viewed-max-len: ${BUDDYSTUDY_QUESTION_VIEWED_STREAM_MAX_LEN:1000}
```

Publishing uses each topic's configured `XADD ... MAXLEN` value; trimming is
not a separate command and approximate trimming is disabled. This gives each
stream a deterministic operational bound.

## Legacy Drain

The previous five stream keys are read-only migration inputs:

```text
buddystudy-events-v1
buddystudy-question-generation-v1
buddystudy-question-generated-v1
buddystudy-content-translation-v1
buddystudy-push-v1
```

When `BUDDYSTUDY_LEGACY_STREAM_DRAIN_ENABLED=true`, each listener also resumes
its existing consumer group on the applicable old stream. A legacy worker is
not started when that physical key does not exist. Publishers never target
these keys.

Disable legacy drain only after the admin console confirms `lag=0` and
`pending=0` for every legacy consumer group. After one retention window with
no new legacy entries, the old keys may be deleted. This two-phase rollout
prevents in-flight delivery loss without allowing new mixed-stream traffic.

These streams are delivery buffers, not sources of truth. Durable events first
exist in `redis_event_outbox`, and push requests also have
`question_push_outbox`. Operators must treat consumer lag approaching the
corresponding stream's configured maximum as urgent: Redis can trim a record
that is still pending when the bounded stream advances beyond it. The database
outboxes remain available for diagnosis and recovery.

Normal publication does not wait for the recovery poll. The business
transaction commits its `PENDING` outbox row, then immediately calls the
central publication use case. The managed recovery job only handles failed,
due, or stale rows. Both paths use claim-token fencing and the same
claim/publish/complete implementation. See
[`EVENT_OUTBOX_ARCHITECTURE.md`](EVENT_OUTBOX_ARCHITECTURE.md).

## Push Workers

The push consumer group runs ten concurrent workers by default:

```yaml
buddystudy:
  streams:
    push-consumer-concurrency: ${PUSH_CONSUMER_CONCURRENCY:10}
```

Worker names are stable:

```text
buddystudy-push
buddystudy-push-2
...
buddystudy-push-10
```

The first name intentionally preserves the existing consumer identity so a
restart can resume its pending records. Each worker performs blocking Redis
reads on `Dispatchers.IO`; the Reactor event loop is not blocked. Delivery is
at-least-once, so consumer idempotency remains required.

## Admin Observation

The React admin console exposes **Operations > Event Streams**.

Sources:

1. Redis Stream: topic length, configured maximum, first/last ID, consumer
   groups, last-delivered offset, entries read, lag, pending range, oldest
   pending age, per-consumer ownership/idle time, retry count, and entries.
2. Consumer Inbox: current state in `stream_consumer_inbox` and one durable
   lifecycle row per processing attempt in `stream_consumer_inbox_attempts`.
3. Event outbox: durable `redis_event_outbox` rows.
4. Push outbox: durable `question_push_outbox` rows.

All lists use cursor pagination with a bounded `limit` of 1 to 100.

Registered streams can be searched by logical topic, physical Redis key,
consumer group, or consumer name.
Operators can also bypass pagination and retrieve one retained message by its
exact Redis Stream ID. Missing IDs return `404`; malformed IDs return `422`.

- Redis cursor: stream record ID such as `1785000998000-0`.
- Database cursor: descending primary key.
- `nextCursor`: last returned item only when another item exists.

The UI keeps a cursor stack to provide familiar Previous/Next controls without
using unstable database offsets. Filters reset that stack to the newest page.

Admin endpoints:

```text
GET /api/v1/admin/event-streams/topics?query={topic-or-key}
GET /api/v1/admin/event-streams/topics/{topic}/entries
GET /api/v1/admin/event-streams/topics/{topic}/entries/{entryId}
GET /api/v1/admin/event-streams/topics/{topic}/groups/{group}/pending
GET /api/v1/admin/event-streams/inbox/attempts
GET /api/v1/admin/event-streams/outboxes/events
GET /api/v1/admin/event-streams/outboxes/pushes
```

Every endpoint requires the existing administrator bearer token. Stream fields
and outbox payloads are recursively redacted before leaving the backend.
Authorization, password, secret, token, private key, and API key fields are
never returned in plaintext.

## Monitoring UI

Redis Stream inspection belongs to the unified monitoring workspace, not the
standalone analytics administrator frontend.

```text
https://monitoring.lowfidev.cloud/streams.html
Navigation: Manage > Redis Streams
```

The page shares the backend administrator session with `Users & Quotas`, keeps
credentials in memory during login, and stores only the short-lived bearer
token in browser session storage. Stream entries use cursor pagination and the
exact-ID lookup bypasses list pagination for incident investigation.

`Delivery status` refreshes every five seconds. Its overview reports one row
per managed stream before reporting one row per Redis consumer group:

- **Entries**: current Redis `XLEN`.
- **MAXLEN**: the exact configured bound used by `XADD`.
- **Retention**: `XLEN / MAXLEN`, shown as a percentage.
- **Inspection**: whether all stream metadata commands completed.

- **Offset**: the group's last delivered Redis Stream ID.
- **Lag**: entries not yet delivered to that group.
- **Pending**: delivered entries that have not been acknowledged.
- **Retries**: `deliveryCount - 1`; the first delivery is not a retry.

Selecting a group shows its active consumers and a cursor-paginated pending
entry list. It also shows the durable **Inbox processing history**, searchable
by event ID, correlation ID, exception type, or error message. The history can
be filtered by:

- `PROCESSING`: the current lease is active.
- `RETRY_SCHEDULED`: this attempt failed and Redis may redeliver it.
- `LEASE_EXPIRED`: the worker did not complete before another worker reclaimed
  the database lease.
- `SUCCEEDED`: the attempt completed and may be acknowledged.
- `FAILED`: the retry budget was exhausted; the Inbox row is terminal and the
  event cannot be claimed again by that consumer group.

Each attempt stores its start and finish time, duration, failure classification,
and bounded failure message. The current Inbox row remains the idempotency and
lease-fencing record; the attempt table is the audit trail and is not used as a
message broker.

The overview samples at most the first 100 pending entries to avoid an
unbounded Redis read. When a group has more pending entries, the maximum retry
value is marked with `+` and is a sampled lower bound; the paginated pending
list remains exact for each displayed entry.

Stream, group, pending-summary, pending-range, and consumer inspection are
isolated operations. A failure in one command is returned as `Partial data`
with its operation instead of replacing the whole stream with false zeroes.

The application-ready cleanup removes only known legacy topology:

- old domain-stream groups superseded by dedicated push and translation
  streams;
- the dated native push test stream.

A legacy group is destroyed only when its pending count is zero. The test
stream is deleted only when it is empty and all of its groups have no pending
deliveries. The five managed streams are never cleanup candidates.

## Operational Checks

Investigate when any of these conditions hold:

- Stream length remains near 1000.
- A consumer group's pending count grows across refreshes.
- Event or push outbox rows remain `PENDING`/`PROCESSING`.
- Attempts or `lastError` increase.
- Inbox attempts remain `PROCESSING`, become `LEASE_EXPIRED`, or end in
  `FAILED`.
- Published database rows do not appear in the Redis stream.

Start from the durable outbox row, correlate its event or record ID with the
Redis entry, then inspect the relevant consumer group's pending count and Inbox
processing history. For a terminal failure, search its event ID and use the
stored exception type/message to distinguish provider failure, invalid payload,
and exhausted retry budget.
