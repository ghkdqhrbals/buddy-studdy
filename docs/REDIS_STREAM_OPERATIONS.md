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
Consumer-specific behavior remains a `RedisStreamSubscription`, which owns the
group, consumer prefix, concurrency, batch count, and blocking timeout.

## Retention

The domain event stream uses an exact `MAXLEN` of `1000`.

```yaml
buddystudy:
  streams:
    max-len: ${REACTION_STREAM_XADD_MAX_LEN:1000}
```

Publishing uses atomic `XADD ... MAXLEN = 1000`; trimming is not a separate
command and approximate trimming is disabled. This gives a deterministic
operational bound.

This stream is a delivery buffer, not the source of truth. Durable events first
exist in `redis_event_outbox`, and push requests also have
`question_push_outbox`. Operators must treat consumer lag approaching 1000
records as urgent: Redis can trim a record that is still pending when the
bounded stream advances beyond it. The database outboxes remain available for
diagnosis and recovery.

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
   count, pending count, and entries.
2. Event outbox: durable `redis_event_outbox` rows.
3. Push outbox: durable `question_push_outbox` rows.

All lists use cursor pagination with a bounded `limit` of 1 to 100.

Registered streams can be searched by logical topic or physical Redis key.
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
GET /api/v1/admin/event-streams/outboxes/events
GET /api/v1/admin/event-streams/outboxes/pushes
```

Every endpoint requires the existing administrator bearer token. Stream fields
and outbox payloads are recursively redacted before leaving the backend.
Authorization, password, secret, token, private key, and API key fields are
never returned in plaintext.

## React Frontend

The administrator frontend is already React 19 with Vite. It is the
source-of-truth implementation for new operator views, so a separate migration
is unnecessary. Operational UI work belongs in `admin-frontend`; the legacy
static monitoring pages must not gain parallel event-stream behavior.

## Operational Checks

Investigate when any of these conditions hold:

- Stream length remains near 1000.
- A consumer group's pending count grows across refreshes.
- Event or push outbox rows remain `PENDING`/`PROCESSING`.
- Attempts or `lastError` increase.
- Published database rows do not appear in the Redis stream.

Start from the durable outbox row, correlate its event or record ID with the
Redis entry, then inspect the relevant consumer group's pending count.
