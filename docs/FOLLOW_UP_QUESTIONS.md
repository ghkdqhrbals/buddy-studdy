# Follow-up learning — iOS 1.3.0

## Product flow

The study room supports two complementary ways to extend learning:

- **Get a follow-up:** the tutor asks one short question grounded in the
  learner's original answer, feedback, topic, and earlier turns. It targets a
  misconception, asks for missing reasoning, or applies an understood concept
  to a new situation. The learner answers and receives normal grading feedback.
- **Create a custom question:** the learner writes a study question and their
  own answer, then explicitly saves both as a personal study record. The score
  position displays **사용자 생성 질문 / Custom question** instead of a number.
  There is no AI generation, grading, or synthetic zero score.

Both actions are explicit. Receiving a scheduled question or a push never
starts either action or replaces an active answer/composer draft. Existing
question and answer content remains available above the continuation. Leaving
and reopening the record restores accepted work and stored drafts.

## Boundaries

- A learning thread contains the original question and at most two generated
  follow-up questions. Only its latest graded question may generate the next
  follow-up; one generation may be active at a time.
- Skipping a follow-up ends that continuation. The skipped question remains
  visible as read-only thread context and occupies its original slot; reopening
  or deleting a question does not permit a branch or reset the two-question cap.
- Each generated follow-up consumes one question from the existing monthly
  allowance. The action explains this before submission. Quota reservation,
  completion, failure compensation, and request replay use the normal durable
  generation lifecycle; retries cannot charge twice.
- Custom questions do not consume the question generation allowance. Both
  question and answer are required to save. The resulting record is complete,
  not an unanswered question waiting for AI grading, and is accessible through
  the existing paginated record list and detail flow.
- Follow-ups and custom questions stay private. A public original question
  does not make its continuation public, and these records cannot be published.
- Original scores are immutable. Follow-up grades are visible as additional
  practice, but excluded from independent topic ability and growth estimates,
  including client-side fallback calculations. Custom questions have no score
  and are also excluded from ability/growth estimates.
- Thread and custom-record access require ownership, including when an original
  question is public. Deletion and account changes must not expose stale
  content from another user.
- Korean, English, and Japanese labels follow the existing app localization
  mechanisms. Markdown remains supported in generated content.

## Backend contract

Question generation and grading remain backend-owned. Generated follow-ups
reuse the existing asynchronous generation Saga and grading lifecycle.
Records add parent/root record identity, follow-up depth, and question source;
legacy records decode as original questions with no parent.

- `POST /api/v1/records/{recordId}/follow-ups` accepts an `Idempotency-Key` and
  returns the existing accepted generation process. Its normal polling API is
  unchanged.
- `GET /api/v1/records/{recordId}/thread` returns the original and up to two
  follow-up records in conversation order.
- `POST /api/v1/studies/{topicId}/custom-questions` accepts question, answer,
  language, and an `Idempotency-Key`, and returns the saved record. This uses
  existing record persistence without calling OpenAI or reserving question
  allowance. The custom source distinguishes completed ungraded records from
  ordinary pending questions.

The iOS client polls only while the relevant screen is visible. The backend
continues accepted work independently of screen navigation. Submission
idempotency and drafts survive an uncertain response or app restart.

## Verification acceptance

- Generate the first and second follow-up from a graded original, then reject
  a third and reject branching from an earlier turn.
- Skip a follow-up, reopen the thread as read-only, and reject continuing from
  either the skipped turn or an earlier turn.
- Confirm that parent context reaches generation and that the generated record
  is private, linked to the same topic, and excluded from ability/growth data.
- Replay and race requests, including from two devices; verify one accepted
  question, one quota reservation, and exactly-once failure compensation.
- Save a custom question and answer, reopen the record, and verify the custom
  tag replaces every numeric grade/pending state. Confirm there are no OpenAI
  calls, quota changes, or ability/growth samples.
- Preserve unsaved custom drafts and idempotency on network failure and app
  restart; a retried save must not create a second record.
- Reject other users' record/thread/custom-question reads and writes.
- Navigate away and reopen, deliver a scheduled question, and switch accounts;
  preserve the correct user's drafts without displaying another user's thread.
- Run focused backend and iOS tests, the required generic iOS build, and
  physical iPhone verification. Record unavailable verification explicitly.

## Advertising status

The integrated 1.3.0 candidate is based on latest 1.2 source `810db163` and
preserves Google Mobile Ads 13.8.0, UMP 3.1.0 and the existing native feed
placements/paid-tier ad suppression. Release builds retain production AdMob
configuration. The earlier feature branch predates AdMob's restoration and
must not be used as the distribution base. Voice Tutor and the 1.2 billing,
discovery and usability changes are also retained.
