# OpenAI cost controls

This change implements cost-plan steps 1–4: usage visibility, avoiding repeated
paid work, server-owned lesson continuation, and compact MCP payloads. It does
not change configured models, grading stages, context retention, output budgets,
question allowances, voice time, or iOS conversation/draft storage.

## Usage evidence

`OpenAIUsageRecorder` emits one bounded, content-free `openai_usage` JSON event to
the existing application logs/PLG pipeline. It records operation, stage, model,
outcome, duration, HTTP status and explicit provider counts. Available input,
output, cached, modality and reasoning counts remain separate. Missing usage is
null, including a response whose connection ended without a final usage event;
it is not a zero-cost response.

- Question generation, rubric, coverage, topic recommendation, embedding,
  translation and grading evidence/critic/judge/adjudication have separate labels.
- SDK `physical_attempt` events count actual transport attempts and retries.
  A separate `logical` event carries the final provider usage. Do not add those
  attempts to the logical call count or sum their retry count cumulatively.
- Native Realtime observes `response.done` before UI filtering, retaining usage
  from cancelled/failed/incomplete responses. Transcription usage is separate and
  uses its actual transcription model. In-flight responses at disconnect have
  unknown usage. Stable hashed event references allow reattachment/log replay
  deduplication; they are not metric labels or raw provider/user identities.
- Summary stages, legacy input assessment and voice previews are observed too.
  Binary preview responses do not report token usage; they remain unknown.
- Logs contain no prompts, transcripts, answers, credentials or provider bodies.
  A telemetry sink failure cannot fail the request or initiate another API call.

Aggregate an exported log without network access:

```sh
python3 backend/scripts/diagnostics/openai-usage-report.py backend.log
python3 backend/scripts/diagnostics/openai-usage-report.py backend.log --json > usage.json
```

The report accepts plain application logs, JSON logging envelopes and individual
usage JSON events. It deduplicates event references and prefers final known
usage over an earlier unknown disconnect receipt. Totals carry per-field sample
counts. Cached/audio/text counts are subsets, not extra tokens to add to totals.
Duration-based audio usage is reported in seconds separately from token totals.
`retryAttempts` counts reported SDK transport retries; `callsWithRetries` counts
non-transport rows with attempt greater than one (a retried SDK logical call or a
later direct summary attempt). These are different views and must not be added.
Reattachment reconciliation retains a known purpose and observed elapsed time
when the final receipt contains usage but lacks its earlier start event.
No fixed rate card or currency-saving percentage is assumed. The report is
measured usage evidence, not a provider invoice; unknown samples and provider
charges without returned usage need reconciliation against the billing export.

For a before/after comparison, use the same model, language, topic difficulty,
lesson length and question/answer fixtures. Compare completed lesson/question/
grading work, retries, unknown-usage rate, latency and grading quality as well as
tokens. Do not confuse shorter source strings with measured billing savings.

## Avoiding repeat paid work

Question generation separates provider preparation from its transactional write.
Known transient write failures retry the same prepared question with bounded
backoff. After an ambiguous write error, a bounded read first recovers an already
committed question without duplicating its quota or outboxes. Transient recovery
read failures have their own bounded retry. Generation failure updates atomically
permit only QUEUED/GENERATING, so a stale worker cannot roll back a question that
has reached TRANSLATING/COMPLETED. Actual translation failures retain their own
rollback. Exhausted uncommitted post-generation failures use the existing durable
rollback path instead of starting generation again.

The normal Inbox retry path has a total budget of three logical candidate
generation calls. It may retry a transient failure before a candidate exists;
after a candidate is available, embedding/write retries reuse it locally. Exact
structured permanent provider errors, including quota exhaustion, stop outer
generation retries. SDK transport retries retain their configured bounds and are
visible separately. Root/topic creation remains distinct from question creation.

A summary retry scope retains a successful, source-fingerprinted post-call
classification while retrying result extraction. The fingerprint includes the
immutable source and effective prompt/model context; changed evidence is not
reused. It is private coroutine-scoped state, not a global or cross-user cache.

External API history completion still retries its own write. If that fails,
successful provider output remains successful; an original provider error remains
the original error. An unresolved history row and bounded diagnostic identify the
recording gap. See [external history](EXTERNAL_API_HISTORY.md).

These guarantees cover a running attempt and its bounded retries. They do not
claim exactly-once provider billing across process crashes, cancellation or a new
recovery job before a durable result checkpoint exists.

## Lesson continuation and MCP

After exact accepted selection, the adapter returns its committed focus/revision
immediately. It does not await question work inside that transaction's response.
Only after the provider acknowledges this result does the native controller
schedule `request_question` itself. The canonical operation already reuses an
existing pending question; otherwise it requests generation and observes the
existing completion signal. The model need not perform a separate
`list_pending_questions` round or ask the learner to repeat the start command.

The continuation is bound to the accepted learner/GUI evidence, selected node,
lesson revision and cancellation epoch. New speech, a newer selection, cancel or
session end invalidates stale work. A non-leaf with missing children still enters
the existing lazy curriculum path; an actual leaf proceeds without child creation.
The original main topic and its level are distinct from the selected topic. An
answer becomes submitted only at the existing explicit review/submit boundary.

Native tool descriptions share concise policies. Ordinary choice questions use
one structural schema rather than three duplicate mode schemas; server validation
still enforces single/multiple/text option and free-text rules before presentation.
Native study reads and successful mutations project only study metadata, excluding
embedded prompts/questions/answers before the payload size limit is applied.

For a repeated read in the same human turn, fresh owner authorization and the
backend read still occur. Only an identical successful result whose earlier full
output was exactly acknowledged can become a shorter receipt referring to that
call. Writes, errors, pending questions, grading/status reads and unacknowledged
results are excluded. No provider-history deletion or UI transcript deletion is
part of this change. Existing event-driven learning completion remains in place;
this is not a new polling-to-SSE migration.

Provider field references: [Realtime usage and cost](https://developers.openai.com/api/docs/guides/voice-latency-cost),
[prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching).
