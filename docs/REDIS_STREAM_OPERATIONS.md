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
push listener and its idle-message recovery scheduler use `ACK_DEL` because
the dedicated push stream has one owning consumer group and the durable
`question_push_outbox` remains the recovery source.

Notification events use `ACK`: the domain stream can have multiple consumer
groups, so one successful listener must not delete a record needed by another
group. Its `@StreamScheduler` auto-claims idle pending records and invokes the
same Jackson-typed handler.

## Retention

The domain event and dedicated push streams use independently configurable
exact `MAXLEN` values. Both default to `1000`. Their default physical keys are
`buddystudy-events-v1` and `buddystudy-push-v1`.

```yaml
buddystudy:
  streams:
    key: ${BUDDYSTUDY_STREAMS_KEY:buddystudy-events-v1}
    push-key: ${BUDDYSTUDY_PUSH_STREAM_KEY:buddystudy-push-v1}
    domain-max-len: ${BUDDYSTUDY_DOMAIN_STREAM_MAX_LEN:${REACTION_STREAM_XADD_MAX_LEN:1000}}
    push-max-len: ${BUDDYSTUDY_PUSH_STREAM_MAX_LEN:${REACTION_STREAM_XADD_MAX_LEN:1000}}
```

Publishing uses each topic's configured `XADD ... MAXLEN` value; trimming is
not a separate command and approximate trimming is disabled. This gives each
stream a deterministic operational bound. `REACTION_STREAM_XADD_MAX_LEN`
remains a compatibility fallback for both values when their stream-specific
environment variable is unset.

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

## Operational Checks

Investigate when any of these conditions hold:

- Stream length remains near 1000.
- A consumer group's pending count grows across refreshes.
- Event or push outbox rows remain `PENDING`/`PROCESSING`.
- Attempts or `lastError` increase.
- Published database rows do not appear in the Redis stream.

Start from the durable outbox row, correlate its event or record ID with the
Redis entry, then inspect the relevant consumer group's pending count.
