# API v2 record time display

Record time wording belongs to the backend. iOS uses v2 reads and renders the
returned `relativeText` verbatim in public-question cards, history rows, and
study learning-record rows. Record detail keeps its existing full date display.

## Response contract

Every canonical `StudyRecordResponse` or `CommunityQuestionResponse` returned by
the routes below includes:

```json
{
  "timeDisplay": {
    "timestamp": "2026-09-28T11:55:00Z",
    "relativeText": "5분 전",
    "language": "ko",
    "generatedAt": "2026-09-28T12:00:00Z"
  }
}
```

`timestamp` is `answeredAt` when present, otherwise question `createdAt`.
`generatedAt` is one server-clock snapshot shared by all records in the HTTP
response, including duplicated feed arrays and nested study questions. Raw
timestamps, page counts/cursors, ordering, advertisement placement, ownership,
visibility, and content localization keep their existing meanings.

The display locale uses the existing `tl`, then `language`, then Korean default
precedence. Supported locales are Korean (`ko`), English (`en`), and Japanese
(`ja`); locale variants normalize through `SupportedLanguage`. Display language
is independent of `view=original`, which controls question/answer translation.

The server floors elapsed time into seconds under one minute ("just now"),
minutes under one hour, hours under one day, days under one week, weeks under
30 days, 30-day months under 365 days, and 365-day years thereafter. Future
timestamps also display "just now". English units use singular/plural forms.
These are elapsed durations, not calendar-month or timezone arithmetic.

## Versioned reads

| GET route | Enriched fields |
| --- | --- |
| `/api/v2/records` | `records[]` (including `query` and `studyId` filters) |
| `/api/v2/records/{id}` | returned record |
| `/api/v2/records/{id}/thread` | `records[]` |
| `/api/v2/studies` | each study's `pendingQuestion` and `latestQuestion` |
| `/api/v2/studies/{id}` | `pendingQuestion` and `latestQuestion` |
| `/api/v2/studies/{id}/learning-records` | each item's `record` and legacy `questionRecord` |
| `/api/v2/public/questions` | `questions[]` and question-bearing `items[]` |
| `/api/v2/public/questions/search` | `questions[]` and question-bearing `items[]` |
| `/api/v2/public/questions/liked` | `questions[]` and question-bearing `items[]` |
| `/api/v2/public/questions/{id}` | returned public question |

The v2 study adapter delegates to the existing controller-facing read ports,
retaining owner checks, bounded pagination, and validation. Public detail/feed
retain anonymous read access and public visibility rules. Liked questions remain
authenticated and require the existing like permission before the public-route
wildcard can apply. Private study/record bodies are suppressed from API logs.

## Compatibility and rollout

- Existing v1 reads and all existing writes/comments retain their contracts.
  Nullable display metadata is omitted from v1 JSON. It is never persisted in
  backend records or written into cached use-case responses.
- iOS accepts missing metadata and displays an absolute localized date. It also
  falls back when the metadata's timestamp differs from the current record or
  its locale differs from the current app language, and when text is blank.
- Relative text is a server response snapshot. It changes on the next normal
  read/refresh; iOS does not recompute it from its clock or poll just to age it.
- Deploy the backend v2 routes before submitting/distributing the new iOS build.
  There is no database migration or change to answer submission, drafts, quota,
  grading, or list ordering.
