# Question Generation Saga
## Purpose

Question generation is an asynchronous Choreography Saga. The API accepts a
request, reserves quota, and returns a correlation ID without waiting for
OpenAI, translation, notification, or push delivery.

The design provides:

- request idempotency per user;
- one active generation per user and topic;
- at-least-once stream delivery with effectively-once database effects;
- independent deduplication for each consumer group;
- lease-based crash recovery;
- one-time quota compensation on terminal failure;
- restart-safe iOS polling without replacing an active answer draft.

It does not claim distributed, mathematical exactly-once delivery. Redis and
the database cannot commit atomically with each other. Instead, durable outbox,
Inbox fencing, unique constraints, and Saga compare-and-set transitions make
replayed messages harmless.

## API Contract

### Submit

```http
POST /api/v1/studies/{topicId}/questions
Idempotency-Key: <stable client request UUID>
```

Successful acceptance returns `202 Accepted`:

```json
{
  "correlationId": "0832f51d-30dd-4a44-b093-2ea56ef4162c",
  "studyId": "10",
  "topicId": "12",
  "status": "QUEUED",
  "pollAfterMs": 250,
  "submittedAt": "2026-07-27T12:00:00Z"
}
```

The backend scopes a manual idempotency key to the authenticated user. A retry
with the same key returns the existing Saga instead of reserving quota or
creating another question.

### Poll

```http
GET /api/v1/question-processes/{correlationId}
```

Only the owner can read the process. A non-terminal response includes
`pollAfterMs`; a terminal response sets it to `null`.

The public states are:

| Status | Current step | Meaning |
| --- | --- | --- |
| `QUEUED` | `QUEUED` | Request, quota reservation, Saga, and outbox are committed. |
| `GENERATING` | `GENERATING` | The generation group owns the current lease. |
| `TRANSLATING` | `TRANSLATING` | The question is stored and the translation event is committed. |
| `COMPLETED` | `COMPLETED` | Localized question and delivery outboxes are committed. |
| `FAILED` | failed step | Retry budget ended; quota compensation is committed. |

## Persistent State

### `question_generation_sagas`

One row is the canonical process state.

- Primary key: `correlation_id`
- Unique request: `(user_id, idempotency_key)`
- Unique active topic: `(user_id, active_topic_id)`
- Unique result: `question_id`
- Generated `active_topic_id` is non-null only for `QUEUED`, `GENERATING`, and
  `TRANSLATING`, so a terminal Saga releases the topic automatically.
- State updates use expected-state conditions. A replay cannot repeat a
  successful transition.

### `stream_consumer_inbox`

One row represents one event as observed by one consumer group.

- Primary key: `(event_id, consumer_group)`
- `claim_token` fences an expired worker from completing a lease now owned by
  another worker.
- `attempts` increments only when a new lease is acquired.
- `SUCCEEDED` and `FAILED` are terminal for that group.
- Two different groups may claim the same event ID independently.

This is why group state is not stored as one column on the Saga row. Inbox owns
delivery attempts; Saga owns the business process.

### `stream_consumer_inbox_attempts`

One durable audit row represents one acquired Inbox lease.

- Unique key: `(event_id, consumer_group, attempt)`
- `PROCESSING` becomes `SUCCEEDED`, `RETRY_SCHEDULED`, or `FAILED`.
- A stale unfinished attempt becomes `LEASE_EXPIRED` when the next worker
  acquires the lease.
- Failure rows keep the exception classification and a bounded message for
  operator diagnosis.

The current Inbox row prevents duplicate business processing. Attempt history
explains how the event reached that current state and is exposed under
Monitoring `Manage > Redis Streams > Consumer group > Inbox processing
history`.

Claiming a lease and recording its success, retry, or terminal failure are
independent `REQUIRES_NEW` transactions. They are intentionally not atomic with
the handler's business transaction: if business work rolls back, the Inbox
still preserves both receipt and failure history for recovery and diagnosis.

## Event And Transaction Sequence

```text
iOS
  |
  | POST question + Idempotency-Key
  v
Request transaction
  - validate user, topic, terms, and pending-question limit
  - reserve monthly quota
  - insert Saga(QUEUED)
  - insert QUESTION_GENERATION_REQUESTED outbox
  |
  | commit
  +--> 202 + correlationId
  |
  +--> immediate outbox publish (recovery scheduler retries failures)
         |
         v
Redis question-generation stream
         |
         v
Generation consumer group
  transaction A:
    - claim Inbox lease
    - Saga QUEUED -> GENERATING
  outside transaction:
    - load prompt context
    - call OpenAI
    - calculate embedding and coverage
  transaction B:
    - insert question and related projections
    - Saga GENERATING -> TRANSLATING with questionId
    - insert QUESTION_GENERATED outbox
    - mark generation Inbox SUCCEEDED
         |
         v
Redis question-generated stream
         |
         v
Translation consumer group
  transaction C:
    - claim its own Inbox lease
  outside transaction:
    - translate when required
  transaction D:
    - persist localized content
    - insert notification and push outboxes
    - Saga TRANSLATING -> COMPLETED
    - mark translation Inbox SUCCEEDED
```

Immediate publication occurs only after each transaction commits. Publication
failure does not roll back committed business state; the durable outbox
recovery scheduler publishes it later.

## Multiple Consumer Groups

The current pipeline is sequential, not parallel:

1. `bs-backend-question-generation` consumes
   `QUESTION_GENERATION_REQUESTED`.
2. Its committed result publishes a new `QUESTION_GENERATED` event.
3. `bs-backend-question-translation` consumes that new event.

For the same event ID, different groups have separate Inbox rows and can
therefore finish independently. The current workflow does not mark the Saga
complete by counting arbitrary groups.

If a future event fans out to multiple parallel consumers, add a
`question_generation_step_executions` table keyed by
`(correlation_id, step_name)`. Each branch should own its own status, attempts,
lease, and output. A finalizer may transition the Saga only when every required
step is terminal. Do not add parallel branch states to a single Saga status
column.

## Failure And Recovery

- Each group owns a three-minute database lease.
- Redis pending recovery starts only after 210 seconds. This is intentionally
  longer than the database lease, preventing Redis from transferring a message
  while the original database claim is still valid.
- A retryable failure records `RETRY_SCHEDULED`, releases the Inbox lease
  immediately, and leaves quota reserved.
- After the retry limit, the worker marks the Saga `FAILED`, records the failed
  step, compensates quota once, and closes the Inbox entry as `FAILED`.
- Translation terminal failure also soft-deletes the unusable question.
- A duplicate terminal-failure call sees the terminal Saga and cannot refund
  quota again.
- An old worker cannot complete after lease transfer because every Inbox update
  requires its fencing token.

## iOS Recovery

Before submitting, iOS stores:

- the idempotency key;
- target study and local category;
- submission time;
- the correlation ID once acceptance succeeds.

The app performs one serial polling loop. A transport failure retries with
backoff; a structured server error terminates the process. App launch and
foreground entry resume a persisted process using the same idempotency key or
correlation ID.

On completion, the returned record is cached and assigned to its topic. It
becomes the visible current question only when doing so will not replace an
active ungraded draft.

## Verification

Automated coverage includes:

- real MySQL uniqueness and compare-and-set transitions;
- same-group deduplication and lease-expiry recovery;
- independent claims by different consumer groups;
- one-time quota compensation;
- retry without compensation;
- post-commit publication failure recovery;
- iOS idempotency header and process endpoint routing;
- iOS pending-process persistence across store recreation.
