# Push campaigns

Monitoring **Manage → Push Admin** (`/push.html`) lets an authenticated operator
compose an occasional highlight, choose the app destination and audience, preview
it, save a draft, and explicitly queue it. No message is sent by previewing or
saving. Campaigns are manual; creating one does not schedule weekly repeats. They use
the existing `MARKETING` notification category and respect marketing notification
consent/preferences. Individual administrative replies keep their existing
`ADMIN_MESSAGE` behavior.

## Operator flow

1. Enter a title (1–160 characters) and message (1–2,000 characters).
2. Choose a public feed, a particular public question, My Studies, Records,
   Statistics, Profile, Settings, or a Home message popup. Public-question IDs
   are checked for current public/completed visibility on preview, creation and
   send. Arbitrary websites and other users' private record/study IDs are not
   campaign destinations.
3. Select all active registered members or up to 500 selected members. Preview
   shows the current eligible count; the send operation snapshots its audience.
4. Save the reviewed content as an immutable draft. A UUID idempotency key makes
   a lost-response retry return the same campaign; a changed payload with the
   same key is rejected.
5. Open the draft and choose **Queue push**. Repeating this action cannot create
   another recipient snapshot. Inspect the campaign's counts afterward.

The campaign stays `DRAFT` until send and becomes `QUEUED` once dispatch is
requested. `QUEUED` is not evidence of device delivery. Recipient counts describe
inbox recipients. A notification can remain in the inbox even if push is
unavailable or marketing push permission is denied. Private destinations use the
receiving user's normal authentication and permissions.

## Delivery and click definitions

| Field | Meaning |
| --- | --- |
| Recipients | Unique members in the campaign snapshot; draft counts are a live preview |
| Queued | Recipients with neither provider acceptance nor a recorded push error |
| Provider accepted | Unique recipients whose push has a persisted provider success timestamp |
| Push errors | Recipients without acceptance and with the latest push error, including skipped targets; a later successful retry can change this |
| Unique push opens | Accepted recipients with at least one explicit `PUSH` open |
| Inbox opens | Explicit `INBOX` opens, stored separately |
| CTR | Unique push opens / provider-accepted recipients; the UI shows `—` when the denominator is zero |

APNs acceptance does not prove notification display or device delivery. Each
source's first open timestamp is written only once, so duplicate callbacks or
multiple taps do not inflate counts. Inbox reads, read-all, list fetches,
background sync and passive notification arrival never count as a push click.
Older app versions do not report this new event, and offline/failed reporting is
best effort, so observed CTR is not a complete count of every human interaction.

## API and persistence

Administrator endpoints use the existing Monitoring administrator session:

- `POST /api/v1/admin/push-campaigns/preview`
- `POST /api/v1/admin/push-campaigns`
- `GET /api/v1/admin/push-campaigns?limit=20&offset=0`
- `GET /api/v1/admin/push-campaigns/{id}`
- `POST /api/v1/admin/push-campaigns/{id}/send`

Preview/create accept `title`, `body`, `deepLink`, `audience` (`ALL_REGISTERED`
or `SELECTED_USERS`) and `userIds`. Create also requires `idempotencyKey`.
Listings are bounded and paginated.

The authenticated app calls `POST /api/v1/notifications/{id}/open` with
`{"source":"PUSH"}` or `{"source":"INBOX"}`. The server checks notification
ownership before persisting a timestamp. This endpoint is independent of read
state. iOS reports taps through its notification repository/use case, and a
reporting failure does not prevent navigation or alter an answer draft.

Flyway V123 adds `push_campaigns`, `push_campaign_recipients`, and separate open
timestamps on `app_notifications`, plus anonymous archived outcome counters.
Account cleanup archives the recipient outcomes transactionally before deleting
account-owned recipients and inbox rows, preserving historical CTR. The managed
`push-campaign-dispatch` job
claims at most 100 recipients per transaction and appends deterministic
recipient events to the existing notification outbox. Row locks, a unique
recipient/event identity, and the existing outbox/stream deduplication make
retries safe at the campaign and outbox boundaries. Provider delivery remains
at-least-once; a crash after acceptance can still repeat an APNs request. It uses
the existing notification and APNs pipelines; no new
Redis Stream or browser-held push credential is introduced.

Deploy backend and Monitoring through their existing separate GitHub Actions
modules, then release the matching iOS app for click reporting. This source
change does not deploy, submit an App Store version, or send a production
campaign. A staging end-to-end APNs campaign remains a release verification step.
