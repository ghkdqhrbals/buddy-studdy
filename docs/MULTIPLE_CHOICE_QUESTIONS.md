# Multiple-choice questions and feed participation

## Behavior

Root studies select `FREE_TEXT`, `MULTIPLE_CHOICE`, or `MIXED`; descendants inherit
the effective root setting. Existing and newly created roots default to `MIXED`.
Each manual or scheduled request independently resolves a mixed preference with
equal probability. The resolved format is persisted on the generation Saga, so
retries and later preference changes cannot alter accepted work. Existing
questions, follow-ups, and custom questions remain free text.

A multiple-choice question has two to four immutable options, one correct option,
and an explanation. Option IDs and order remain stable across languages. A tap
submits one final choice. The owner uses the existing answer/grading lifecycle;
grading compares the stored IDs for a score of 100 or 0 without another OpenAI
request. The owner score enters the ordinary topic/difficulty statistics.

Public visibility still requires the owner's completed answer and the existing
public-account, public-record, deletion, and block rules. Signed-in viewers can
choose directly in the ordinary feed, question detail, or liked collection. The
owner's choice is counted once. Other participants do not create personal records,
change learning statistics, or consume question allowance.

Before participation, public responses omit the owner's answer and grade, the
answer key, explanation, counts, percentages, and answer/AI localization metadata.
After participation they expose the viewer's selection and correctness, the
correct option and explanation, each option's count and percentage, and total
participants. No participant identity list or expiry is provided. Results refresh
on submission, read, and refresh rather than through a new realtime subscription.

## Contracts and storage

The new iOS 1.3.1 client explicitly negotiates question API contract **3** with
`X-BuddyStudy-API-Version: 3`. Existing `/api/v1` and `/api/v2` URLs stay stable;
an absent header or older contract retains the legacy representation. Responses
identify the negotiated contract and include the header in `Vary` so representations
are not shared by caches. No forced iOS update is required.

Legacy requests generate free-text questions regardless of the saved root
preference. Their public feed, search, liked pages and counts exclude objective
questions in SQL before pagination; direct objective public access is unavailable.
Owned objective records remain usable: the legacy question text lists numbered
options and asks for only a number, while the wire format remains `FREE_TEXT`.
The server maps an exact normalized option number to its immutable ID and performs
the same deterministic grading. It never sends that legacy response to AI grading.
This avoids hidden pending questions blocking an older device on a shared account.

Devices persist their currently declared `questionApiVersion` (default 1).
Registration omissions and authenticated legacy requests downgrade prior capability
instead of keeping a maximum-ever version. Scheduled objective generation requires
every currently active, owned delivery device to support contract 3; missing,
unknown or legacy delivery capability falls back to free text. Existing MCP tools
retain the legacy contract rather than silently upgrading a previously connected
client. They can read and answer owned objective records through the same numbered
fallback; the optional explicit `selected_option_id` submission remains supported.

- Study creation, `PUT /api/v1/studies/{id}/settings`, and room/settings reads include
  `questionFormatPreference`. Omitting it while editing preserves the existing
  server setting. Child writes cannot override the inherited root preference.
- Record and public-question responses add `questionFormat` and `multipleChoice`.
  The latter contains `options`, `selectedOptionId`, `totalParticipants`,
  `correctOptionId`, `isCorrect`, and `explanation`; result fields are absent or
  null before participation. Legacy question payloads decode as `FREE_TEXT`.
- Owner `POST /api/v1/records/{id}/answer` accepts `selectedOptionId` instead of a
  text answer for multiple-choice questions. Arbitrary text is never sent to AI
  grading for an objective question.
- Public `PUT /api/v1/public/questions/{id}/vote` accepts `{selectedOptionId}` and
  returns the authoritative public-question response. The same choice can be
  retried; another choice returns a conflict.
- MCP `submit_answer` accepts `selected_option_id` for objective questions and
  retains `answer` for free text. The caller must provide exactly one.

Questions retain the canonical format, option JSON, private key/explanation, and
owner choice. A unique `(question_id, user_id)` participation row is shared by
owner and feed responses. Owner choice persistence and grading acceptance share a
transaction. Public summaries are batch aggregates of canonical responses, with
no second counter store or new Redis stream.

Option labels are translated as question content in both generation delivery and
read-repair paths. They contribute to objective-question source hashes; legacy
free-text hashes are unchanged. Translations live in question localizations and
never contain the answer key. Explanation translations use the existing grading
localization path after completion.

The iOS app preserves typed text/choice drafts through `SettingsStore`, including
legacy text drafts. An uncertain choice response is reconciled with server state
before another selection is offered. Public participation state is account scoped
and cleared on session changes; it is not a parallel personal record store.

## Rollout

Production defaults `BUDDYSTUDY_MULTIPLE_CHOICE_ENABLED` to `false`. Prepare the
additive schema and backend first, release the capable iOS version, then enable
generation through a backend-only deployment. Existing iOS versions continue on
their legacy contract without an update requirement. Until
activation, generated manual/scheduled questions remain free text even though the
saved preference is mixed. Local/development environments enable the feature for
verification. Disabling generation does not erase existing questions or responses.

Use the existing GitHub Actions and personal-deploy backend module for deployment.
No direct production SSH, deployment health checks, or monitoring rollout is
introduced by this feature.

## Verification

Exercise format persistence and inheritance, stable mixed retries, invalid option
sets and keys, deterministic owner grading, concurrent/retried participation,
immutable choices, visibility and secrecy, locale identity, quota/stat isolation,
legacy models/drafts, and account changes. Run the iOS generic build and physical
iPhone checks for inline choice, navigation, long labels, and large text. Actual
verification results are recorded with the implementation; App Store publication
and production activation remain separate operational steps.

See [2026-11-12 verification](verification/2026-11-12-multiple-choice.md) for
executed checks and physical-device evidence.
